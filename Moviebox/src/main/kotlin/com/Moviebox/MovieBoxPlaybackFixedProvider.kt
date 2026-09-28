package com.Moviebox

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

/**
 * Playback-only compatibility layer for MovieBox.
 *
 * Every non-playback operation is delegated unchanged to MovieBoxProvider.
 * Playback recovers the signed adaptive manifest while deliberately leaving
 * MainAPI.getVideoInterceptor() untouched so CloudStream can use its native
 * Cronet media path instead of being forced onto OkHttp.
 */
class MovieBoxPlaybackFixedProvider : MainAPI() {
    private val delegate = MovieBoxProvider()

    override var mainUrl = delegate.mainUrl
    override var name = delegate.name
    override val supportedTypes = delegate.supportedTypes
    override var hasMainPage = delegate.hasMainPage
    override val mainPage = delegate.mainPage

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? = delegate.getMainPage(page, request)

    override suspend fun search(query: String): List<SearchResponse> =
        delegate.search(query)

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        delegate.quickSearch(query)

    override suspend fun load(url: String): LoadResponse? =
        delegate.load(url)

    companion object {
        private const val WEB_ORIGIN = "https://movie-box.co"
        private const val WEB_REFERER = "https://movie-box.co/"
        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
    }

    private fun decodeBase64Url(value: String): String? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return sequenceOf(Base64.DEFAULT, Base64.URL_SAFE)
            .mapNotNull { flags ->
                try {
                    String(Base64.decode(padded, flags), Charsets.UTF_8)
                        .trim()
                        .takeIf {
                            it.startsWith("https://", true) ||
                                it.startsWith("http://", true)
                        }
                } catch (_: Exception) {
                    null
                }
            }
            .firstOrNull()
    }

    private fun resolveFromUrlPrefix(signCookie: String): String? {
        val encoded = Regex(
            pattern = """urlprefix=([^:;,\s]+)""",
            option = RegexOption.IGNORE_CASE
        ).find(signCookie)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val prefix = decodeBase64Url(encoded) ?: return null
        val lower = prefix.lowercase()
        return when {
            lower.endsWith(".mpd") || lower.endsWith(".m3u8") -> prefix
            lower.contains("/hls/") -> prefix.trimEnd('/') + "/index.m3u8"
            else -> prefix.trimEnd('/') + "/index.mpd"
        }
    }

    private fun resolveDashFromCloudFrontPolicy(signCookie: String): String? {
        val policyRaw = signCookie
            .split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("CloudFront-Policy=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        var normalized = buildString(policyRaw.length) {
            policyRaw.forEach { character ->
                append(
                    when (character) {
                        '-' -> '+'
                        '_' -> '='
                        '~' -> '/'
                        else -> character
                    }
                )
            }
        }
        normalized += "=".repeat((4 - normalized.length % 4) % 4)

        val policyJson = try {
            String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }

        val resource = try {
            JSONObject(policyJson)
                .optJSONArray("Statement")
                ?.optJSONObject(0)
                ?.optString("Resource")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: return null

        val base = resource
            .trimEnd('*')
            .trimEnd('/')
            .takeIf {
                it.startsWith("https://", true) ||
                    it.startsWith("http://", true)
            }
            ?: return null

        return when {
            base.endsWith(".mpd", true) || base.endsWith(".m3u8", true) -> base
            else -> "$base/index.mpd"
        }
    }

    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/notice.mp4") ||
            lower.contains("notice") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun recoveredMediaUrl(link: ExtractorLink): String? {
        val cookie = link.headers.entries
            .firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value
            .orEmpty()

        return resolveFromUrlPrefix(cookie)
            ?: resolveDashFromCloudFrontPolicy(cookie)
    }

    /**
     * Match the request identity observed on the stable MovieBox playback path.
     * Keep signed Cookie values returned by the API, but replace the API
     * referer with MovieBox's web origin and let CloudStream/Cronet apply these
     * ExtractorLink headers natively.
     */
    private fun playbackHeaders(link: ExtractorLink): Map<String, String> {
        val preserved = link.headers.filterKeys { key ->
            !key.equals("Referer", ignoreCase = true) &&
                !key.equals("Origin", ignoreCase = true) &&
                !key.equals("User-Agent", ignoreCase = true) &&
                !key.equals("Accept", ignoreCase = true)
        }

        return preserved + mapOf(
            "Origin" to WEB_ORIGIN,
            "Referer" to WEB_REFERER,
            "User-Agent" to WEB_USER_AGENT,
            "Accept" to "*/*"
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val originals = mutableListOf<ExtractorLink>()

        delegate.loadLinks(
            data = data,
            isCasting = isCasting,
            subtitleCallback = subtitleCallback,
            callback = { originalLink -> originals.add(originalLink) }
        )

        var emitted = 0
        val emittedUrls = linkedSetOf<String>()

        for (link in originals) {
            val recoveredUrl = recoveredMediaUrl(link)
            val directIsDummy = isDeprecationNoticeUrl(link.url)
            val mediaHeaders = playbackHeaders(link)

            if (recoveredUrl != null && emittedUrls.add(recoveredUrl)) {
                Log.d(
                    "MovieBox",
                    "[PLAYBACK-FIX] adaptive manifest recovered " +
                        "directHost=${link.url.substringAfter("://").substringBefore('/')} " +
                        "adaptiveHost=${recoveredUrl.substringAfter("://").substringBefore('/')} " +
                        "transport=native"
                )

                callback(
                    newExtractorLink(
                        source = "MovieBox",
                        name = "MovieBox DASH",
                        url = recoveredUrl,
                        type = INFER_TYPE
                    ) {
                        referer = WEB_REFERER
                        quality = link.quality
                        headers = mediaHeaders
                    }
                )
                emitted += 1
            }

            if (!directIsDummy && emittedUrls.add(link.url)) {
                callback(
                    newExtractorLink(
                        source = link.source,
                        name = "MovieBox Direct",
                        url = link.url,
                        type = link.type
                    ) {
                        referer = WEB_REFERER
                        quality = link.quality
                        headers = mediaHeaders
                    }
                )
                emitted += 1
            } else if (directIsDummy && recoveredUrl == null) {
                Log.e(
                    "MovieBox",
                    "[PLAYBACK-FIX] update/deprecation dummy detected but signed manifest was not recoverable; suppressing dummy"
                )
            }
        }

        return emitted > 0
    }
}

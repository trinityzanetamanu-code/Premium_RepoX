package com.Moviebox

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.json.JSONObject

/**
 * Playback-only compatibility layer for MovieBox.
 *
 * Every non-playback operation is delegated unchanged to MovieBoxProvider.
 * Playback keeps the signed adaptive manifest as the preferred source while
 * retaining a valid direct URL as a fallback when MovieBox returns both.
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

    /**
     * Re-apply every link-specific header to every media request.
     *
     * MovieBox manifests and their DASH/HLS segments are protected by the
     * stream-specific signed cookie returned by play-info. Keeping the whole
     * header set here is important because the manifest can redirect to a
     * different CDN host and subsequent segment requests must still carry the
     * same Cookie/User-Agent/Referer tuple.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()

            extractorLink.headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    builder.header(key, value)
                }
            }

            if (
                extractorLink.referer.isNotBlank() &&
                extractorLink.headers.keys.none { it.equals("Referer", ignoreCase = true) }
            ) {
                builder.header("Referer", extractorLink.referer)
            }

            chain.proceed(builder.build())
        }
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

    /**
     * Supports the current Edge-Cache-Cookie / urlprefix form as well as the
     * older urlprefix form. The decoded prefix points at MovieBox's adaptive
     * media directory rather than the update/deprecation dummy URL.
     */
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

        // CloudFront signed-cookie Base64 uses the AWS substitutions
        // '-' -> '+', '_' -> '=', '~' -> '/'.
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // The delegate callback is non-suspend, while newExtractorLink is built
        // safely from this suspend body after delegate resolution completes.
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

            // Prefer the signed adaptive manifest. A multi-representation DASH
            // or HLS manifest lets the player adapt bitrate instead of being
            // locked to one large fixed-bitrate file.
            if (recoveredUrl != null && emittedUrls.add(recoveredUrl)) {
                Log.d(
                    "MovieBox",
                    "[PLAYBACK-FIX] adaptive manifest recovered " +
                        "directHost=${link.url.substringAfter("://").substringBefore('/')} " +
                        "adaptiveHost=${recoveredUrl.substringAfter("://").substringBefore('/')}"
                )

                callback(
                    newExtractorLink(
                        source = "MovieBox",
                        name = "MovieBox Adaptive",
                        url = recoveredUrl,
                        type = INFER_TYPE
                    ) {
                        referer = link.referer
                        quality = link.quality
                        headers = link.headers
                    }
                )
                emitted += 1
            }

            // Keep a genuine direct stream as an explicit fallback instead of
            // discarding it merely because an adaptive manifest was recovered.
            // Known update/deprecation dummy URLs are never exposed.
            if (!directIsDummy && emittedUrls.add(link.url)) {
                if (recoveredUrl != null) {
                    Log.d(
                        "MovieBox",
                        "[PLAYBACK-FIX] keeping direct fallback host=" +
                            link.url.substringAfter("://").substringBefore('/')
                    )
                }
                callback(link)
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

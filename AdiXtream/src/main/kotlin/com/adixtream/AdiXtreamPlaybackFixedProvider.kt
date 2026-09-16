package com.adixtream

import android.util.Base64
import android.util.Log
import com.Adicinemax21.Adicinemax21VidSrcShared
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

class AdiXtreamPlaybackFixedProvider : AdiXtream() {
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun decodeBase64Url(value: String): String? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return sequenceOf(Base64.DEFAULT, Base64.URL_SAFE).mapNotNull { flags ->
            try {
                String(Base64.decode(padded, flags), Charsets.UTF_8).trim()
                    .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            } catch (_: Exception) {
                null
            }
        }.firstOrNull()
    }

    private fun resolveFromUrlPrefix(signCookie: String): String? {
        val encoded = Regex("""urlprefix=([^:;,\s]+)""", RegexOption.IGNORE_CASE)
            .find(signCookie)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
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
        val policyRaw = signCookie.split(';').asSequence().map { it.trim() }
            .firstOrNull { it.startsWith("CloudFront-Policy=", true) }
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        var normalized = buildString(policyRaw.length) {
            policyRaw.forEach { c ->
                append(
                    when (c) {
                        '-' -> '+'
                        '_' -> '='
                        '~' -> '/'
                        else -> c
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
            JSONObject(policyJson).optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
                ?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: return null

        val base = resource.trimEnd('*').trimEnd('/')
            .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: return null

        return if (base.endsWith(".mpd", true) || base.endsWith(".m3u8", true)) base else "$base/index.mpd"
    }

    private fun isUpdateDummy(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/notice.mp4") ||
            lower.contains("notice") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun recoveredMediaUrl(link: ExtractorLink): String? {
        val cookie = link.headers["Cookie"].orEmpty()
        if (!cookie.contains("CloudFront-Policy=", true) && !isUpdateDummy(link.url)) return null
        return resolveFromUrlPrefix(cookie) ?: resolveDashFromCloudFrontPolicy(cookie)
    }

    private fun recoveredType(url: String, fallback: ExtractorLinkType): ExtractorLinkType {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".mpd") -> ExtractorLinkType.DASH
            clean.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            else -> fallback
        }
    }

    @Suppress("DEPRECATION")
    private fun buildRecoveredLink(original: ExtractorLink, recoveredUrl: String): ExtractorLink {
        return ExtractorLink(
            source = "MovieBox",
            name = "MovieBox",
            url = recoveredUrl,
            referer = original.referer,
            quality = original.quality,
            headers = original.headers,
            extractorData = original.extractorData,
            type = recoveredType(recoveredUrl, original.type),
            audioTracks = original.audioTracks
        )
    }

    private fun firstReadyForwarder(
        emitted: AtomicInteger,
        firstReady: CompletableDeferred<Boolean>,
        callback: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit = { original ->
        val recoveredUrl = recoveredMediaUrl(original)
        val output = when {
            recoveredUrl != null -> {
                Log.d("AdiXtream", "[MOVIEBOX-FIX] signed manifest recovered")
                buildRecoveredLink(original, recoveredUrl)
            }
            isUpdateDummy(original.url) -> {
                Log.e("AdiXtream", "[MOVIEBOX-FIX] update dummy suppressed; signed manifest unavailable")
                null
            }
            else -> original
        }

        if (output != null) {
            val position = emitted.incrementAndGet()
            callback(output)
            if (position == 1) {
                Log.i("AdiXtream", "[FIRST-READY] source=${output.source}|ACTION=release-player")
                firstReady.complete(true)
            }
        }
    }

    private suspend fun loadBaseSources(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        super.loadLinks(data, isCasting, subtitleCallback, callback)
    }

    private suspend fun loadVidSrcSource(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val payload = JSONObject(data)
            val tmdbId = payload.optString("tmdbId").toIntOrNull()
            if (tmdbId == null || tmdbId <= 0) return

            val isTvSeries = payload.optBoolean("isTvSeries", false)
            val season = if (payload.has("season") && !payload.isNull("season")) payload.optInt("season") else null
            val episode = if (payload.has("episode") && !payload.isNull("episode")) payload.optInt("episode") else null

            Adicinemax21VidSrcShared.invokeVidSrc(
                tmdbId = tmdbId,
                type = if (isTvSeries) "tv" else "movie",
                season = season,
                episode = episode,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e("AdiXtream", "[VIDSRC] gagal resolve: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private suspend fun loadAllSources(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        supervisorScope {
            val baseJob = launch {
                try {
                    loadBaseSources(data, isCasting, subtitleCallback, callback)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.e("AdiXtream", "[FIRST-READY] base sources error: ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            val vidSrcJob = launch {
                loadVidSrcSource(data, subtitleCallback, callback)
            }
            joinAll(baseJob, vidSrcJob)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = AtomicInteger(0)
        val firstReady = CompletableDeferred<Boolean>()
        val forward = firstReadyForwarder(emitted, firstReady, callback)

        playbackScope.launch {
            try {
                loadAllSources(data, isCasting, subtitleCallback, forward)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiXtream", "[FIRST-READY] background resolver error: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                if (!firstReady.isCompleted) firstReady.complete(emitted.get() > 0)
                Log.i("AdiXtream", "[FIRST-READY] background complete|TOTAL_LINKS=${emitted.get()}")
            }
        }

        return firstReady.await()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val cookie = extractorLink.headers["Cookie"]
        if (cookie.isNullOrBlank()) return super.getVideoInterceptor(extractorLink)
        val userAgent = extractorLink.headers["User-Agent"]

        return Interceptor { chain ->
            val builder = chain.request().newBuilder().header("Cookie", cookie)
            if (!userAgent.isNullOrBlank()) builder.header("User-Agent", userAgent)
            chain.proceed(builder.build())
        }
    }
}

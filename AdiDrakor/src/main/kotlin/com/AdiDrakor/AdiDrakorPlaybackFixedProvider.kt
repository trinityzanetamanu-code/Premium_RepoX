package com.AdiDrakor

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

class AdiDrakorPlaybackFixedProvider : AdiDrakor() {
    private fun decodeBase64Url(value: String): String? {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return sequenceOf(Base64.DEFAULT, Base64.URL_SAFE).mapNotNull { flags ->
            try {
                String(Base64.decode(padded, flags), Charsets.UTF_8).trim()
                    .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            } catch (_: Exception) { null }
        }.firstOrNull()
    }

    private fun resolveFromUrlPrefix(signCookie: String): String? {
        val encoded = Regex("""urlprefix=([^:;,\s]+)""", RegexOption.IGNORE_CASE)
            .find(signCookie)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: return null
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
            ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() } ?: return null
        var normalized = buildString(policyRaw.length) {
            policyRaw.forEach { c -> append(when (c) { '-' -> '+'; '_' -> '='; '~' -> '/'; else -> c }) }
        }
        normalized += "=".repeat((4 - normalized.length % 4) % 4)
        val policyJson = try { String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { return null }
        val resource = try {
            JSONObject(policyJson).optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
                ?.trim()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null } ?: return null
        val base = resource.trimEnd('*').trimEnd('/')
            .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) } ?: return null
        return if (base.endsWith(".mpd", true) || base.endsWith(".m3u8", true)) base else "$base/index.mpd"
    }

    private fun isUpdateDummy(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/notice.mp4") || lower.contains("notice") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private suspend fun patchMovieBox(link: ExtractorLink): ExtractorLink? {
        val cookie = link.headers["Cookie"].orEmpty()
        if (!cookie.contains("CloudFront-Policy=", true) && !isUpdateDummy(link.url)) return link
        val recoveredUrl = resolveFromUrlPrefix(cookie) ?: resolveDashFromCloudFrontPolicy(cookie)
        if (recoveredUrl != null) {
            Log.d("AdiDrakor", "[MOVIEBOX-FIX] signed manifest recovered")
            return newExtractorLink("MovieBox", "MovieBox", recoveredUrl, INFER_TYPE) {
                referer = link.referer
                quality = link.quality
                headers = link.headers
            }
        }
        if (isUpdateDummy(link.url)) {
            Log.e("AdiDrakor", "[MOVIEBOX-FIX] update dummy suppressed; signed manifest unavailable")
            return null
        }
        return link
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val emitted = AtomicInteger(0)
        super.loadLinks(data, isCasting, subtitleCallback) { original ->
            patchMovieBox(original)?.let { fixed -> emitted.incrementAndGet(); callback(fixed) }
        }
        return emitted.get() > 0
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

package com.Adicinemax21

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Bounded three-source collector.
 * MovieBox is resolved by MovieBoxV2Shared; Idlix and VidSrc keep their
 * existing implementations and parameters.
 */
class Adicinemax21PlaybackFixedProvider : Adicinemax21() {
    companion object {
        // Idlix includes a server countdown up to 30s plus matching/claim HTTP.
        private const val IDLIX_TIMEOUT_MS = 90_000L
        // VidSrc includes a 15s WASM phase plus page traversal and fallback.
        private const val VIDSRC_TIMEOUT_MS = 60_000L
    }

    private fun sourceKey(link: ExtractorLink): String {
        val raw = link.source.ifBlank { link.name }.trim().lowercase()
        return when {
            MovieBoxV2Shared.isMovieBoxLink(link) || raw.contains("moviebox") -> "moviebox"
            raw.contains("vidsrc") -> "vidsrc"
            raw.contains("idlix") -> "idlix"
            else -> raw
        }
    }

    private fun forwarder(
        emitted: AtomicInteger,
        sourceKeys: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit = { link ->
        val family = sourceKey(link)
        sourceKeys.add(family)
        val position = emitted.incrementAndGet()
        callback(link)
        Log.i(
            "Adicinemax21",
            "[THREE-SOURCE] callback|FAMILY=$family|LINKS=$position|FAMILIES=${sourceKeys.size}"
        )
    }

    private fun intOrNull(payload: JSONObject, key: String): Int? =
        if (payload.has(key) && !payload.isNull(key)) payload.optInt(key) else null

    private fun stringOrNull(payload: JSONObject, key: String): String? =
        payload.optString(key).takeIf { it.isNotBlank() }

    private suspend fun loadMovieBox(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val title = stringOrNull(payload, "title") ?: return
        MovieBoxV2Shared.invokeMoviebox(
            sourceTag = "Adicinemax21",
            title = title,
            orgTitle = stringOrNull(payload, "orgTitle"),
            altTitle = stringOrNull(payload, "altTitle"),
            year = intOrNull(payload, "year"),
            airedYear = intOrNull(payload, "airedYear"),
            season = intOrNull(payload, "season"),
            episode = intOrNull(payload, "episode"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadIdlix(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val title = stringOrNull(payload, "title") ?: return
        Adicinemax21Idlix.invokeIdlix(
            title = title,
            orgTitle = stringOrNull(payload, "orgTitle"),
            altTitle = stringOrNull(payload, "altTitle"),
            year = intOrNull(payload, "year"),
            season = intOrNull(payload, "season"),
            episode = intOrNull(payload, "episode"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadVidSrc(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val tmdbId = intOrNull(payload, "id") ?: return
        Adicinemax21VidSrc.invokeVidSrc(
            tmdbId = tmdbId,
            type = stringOrNull(payload, "type"),
            season = intOrNull(payload, "season"),
            episode = intOrNull(payload, "episode"),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private suspend fun loadAllSources(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = supervisorScope {
        val movieBox = launch {
            try {
                loadMovieBox(data, subtitleCallback, callback)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("Adicinemax21", "[MOVIEBOX-V2] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val idlix = launch {
            try {
                val completed = withTimeoutOrNull(IDLIX_TIMEOUT_MS) {
                    loadIdlix(data, subtitleCallback, callback)
                    true
                }
                if (completed != true) Log.w("Adicinemax21", "[IDLIX] resolver timeout=${IDLIX_TIMEOUT_MS}ms")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("Adicinemax21", "[IDLIX] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val vidSrc = launch {
            try {
                val completed = withTimeoutOrNull(VIDSRC_TIMEOUT_MS) {
                    loadVidSrc(data, subtitleCallback, callback)
                    true
                }
                if (completed != true) Log.w("Adicinemax21", "[VIDSRC] resolver timeout=${VIDSRC_TIMEOUT_MS}ms")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("Adicinemax21", "[VIDSRC] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        joinAll(movieBox, idlix, vidSrc)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = supervisorScope {
        val emitted = AtomicInteger(0)
        val sourceKeys = ConcurrentHashMap.newKeySet<String>()
        val forward = forwarder(emitted, sourceKeys, callback)

        // Forward immediately; another source's first link never ends this scope.
        // Parent cancellation still stops every resolver; slow sources own their deadlines.
        loadAllSources(data, subtitleCallback, forward)

        Log.i("Adicinemax21", "[THREE-SOURCE] complete|LINKS=${emitted.get()}|FAMILIES=${sourceKeys.joinToString(",")}")
        emitted.get() > 0
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        if (MovieBoxV2Shared.isMovieBoxLink(extractorLink)) {
            return MovieBoxV2Shared.videoInterceptor
        }
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

package com.AdiDrakor

import android.util.Log
import com.Adicinemax21.MovieBoxV2Shared
import com.Adicinemax21.Adicinemax21IdlixShared
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
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

class AdiDrakorPrefetchProvider : AdiDrakor() {
    companion object {
        // Same countdown + network budget as the delegate's Idlix resolver.
        private const val IDLIX_TIMEOUT_MS = 90_000L
    }

    private val playbackDelegate = AdiDrakorPlaybackFixedProvider()

    override suspend fun load(url: String): LoadResponse? {
        val result = super.load(url)
        if (result != null) {
            Adicinemax21IdlixShared.prefetch(
                title = result.name,
                year = result.year,
                isSeries = result is TvSeriesLoadResponse
            )
        }
        return result
    }

    private fun family(link: ExtractorLink): String {
        val raw = link.source.ifBlank { link.name }.lowercase()
        return when {
            MovieBoxV2Shared.isMovieBoxLink(link) || raw.contains("moviebox") -> "moviebox"
            raw.contains("vidsrc") -> "vidsrc"
            raw.contains("idlix") || raw.contains("majorplay") -> "idlix"
            else -> raw.trim()
        }
    }

    private fun forwarder(
        emitted: AtomicInteger,
        families: MutableSet<String>,
        idlixUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): (ExtractorLink) -> Unit = { link ->
        val key = family(link)
        val duplicateIdlix = key == "idlix" && !idlixUrls.add(link.url)
        if (!duplicateIdlix) {
            families.add(key)
            val count = emitted.incrementAndGet()
            callback(link)
            Log.i("AdiDrakor", "[IDLIX-PREFETCH] callback|FAMILY=$key|LINKS=$count|FAMILIES=${families.size}")
        }
    }

    private suspend fun loadFastIdlix(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val payload = JSONObject(data)
        val title = payload.optString("title").takeIf { it.isNotBlank() } ?: return
        val orgTitle = payload.optString("orgTitle").takeIf { it.isNotBlank() }
        val altTitle = payload.optString("altTitle").takeIf { it.isNotBlank() }
        val year = if (payload.has("year") && !payload.isNull("year")) payload.optInt("year") else null
        val season = if (payload.has("season") && !payload.isNull("season")) payload.optInt("season") else null
        val episode = if (payload.has("episode") && !payload.isNull("episode")) payload.optInt("episode") else null

        Adicinemax21IdlixShared.invokeIdlix(
            title = title,
            orgTitle = orgTitle,
            altTitle = altTitle,
            year = year,
            season = season,
            episode = episode,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = supervisorScope {
        val emitted = AtomicInteger(0)
        val families = ConcurrentHashMap.newKeySet<String>()
        val idlixUrls = ConcurrentHashMap.newKeySet<String>()
        val forward = forwarder(emitted, families, idlixUrls, callback)

        // Keep the cached Idlix path and delegate alive independently of first-link timing.
        // Both remain children of this request; returning completes all owned work.
        val proven = launch {
            playbackDelegate.loadLinks(data, isCasting, subtitleCallback, forward)
        }
        val prefetchedIdlix = launch {
            try {
                val completed = withTimeoutOrNull(IDLIX_TIMEOUT_MS) {
                    loadFastIdlix(data, subtitleCallback, forward)
                    true
                }
                if (completed != true) Log.w("AdiDrakor", "[IDLIX-PREFETCH] resolver timeout=${IDLIX_TIMEOUT_MS}ms")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.e("AdiDrakor", "[IDLIX-PREFETCH] ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        joinAll(proven, prefetchedIdlix)

        Log.i("AdiDrakor", "[IDLIX-PREFETCH] complete|FAMILIES=${families.joinToString(",")}|LINKS=${emitted.get()}")
        emitted.get() > 0
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? =
        playbackDelegate.getVideoInterceptor(extractorLink)
}

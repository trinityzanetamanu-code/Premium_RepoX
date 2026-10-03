package com.RiveStream.vanguard

import android.util.Log
import com.RiveStream.playback.RivePlaybackIssue
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

/**
 * Targeted validator for Vanguard/Cinejoy HLS.
 *
 * It never changes CloudStream/Media3 globally. When the source is a master
 * playlist it selects only video renditions whose child playlist, init object
 * (when present), first media object and one mid-file object are reachable.
 * If the variant references a separate AUDIO group, one healthy audio
 * rendition from the same group is returned alongside it so the provider can
 * attach it through ExtractorLink.audioTracks.
 */
internal object VanguardHlsValidator {
    private const val TAG = "RiveVanguard"
    private const val REQUEST_TIMEOUT_MS = 6_000L
    private const val MAX_HEALTHY_VARIANTS = 2

    sealed class Decision {
        data object KeepOriginal : Decision()
        data class UseRenditions(val renditions: List<Rendition>) : Decision()
        data class Reject(val issue: RivePlaybackIssue) : Decision()
    }

    data class Rendition(
        val videoUrl: String,
        val quality: Int,
        val audioUrl: String?,
    )

    private data class Variant(
        val url: String,
        val height: Int,
        val bandwidth: Long,
        val audioGroup: String?,
        val codecs: String?,
    )

    private data class AudioRendition(
        val groupId: String,
        val url: String?,
        val isDefault: Boolean,
    )

    private data class HttpText(val code: Int, val text: String)

    private enum class ProbeState { HEALTHY, HTTP_502, UNHEALTHY }

    suspend fun validate(
        masterUrl: String,
        headers: Map<String, String>,
    ): Decision {
        val master = fetchText(masterUrl, headers) ?: run {
            issue(RivePlaybackIssue.NETWORK, "master request timed out/failed: $masterUrl")
            return Decision.Reject(RivePlaybackIssue.NETWORK)
        }

        if (master.code == 502) {
            issue(RivePlaybackIssue.VANGUARD_CDN_502, "master HTTP 502: $masterUrl")
            return Decision.Reject(RivePlaybackIssue.VANGUARD_CDN_502)
        }
        if (master.code !in 200..299) {
            issue(RivePlaybackIssue.HTTP_SOURCE_FAILURE, "master HTTP ${master.code}: $masterUrl")
            return Decision.Reject(RivePlaybackIssue.HTTP_SOURCE_FAILURE)
        }
        if (!master.text.trimStart().startsWith("#EXTM3U")) {
            issue(RivePlaybackIssue.HLS_MASTER_INVALID, "response is not HLS: $masterUrl")
            return Decision.Reject(RivePlaybackIssue.HLS_MASTER_INVALID)
        }

        val variants = parseVariants(masterUrl, master.text)
        if (variants.isEmpty()) {
            return when (probeMediaPlaylist(masterUrl, master.text, headers)) {
                ProbeState.HEALTHY -> Decision.KeepOriginal
                ProbeState.HTTP_502 -> Decision.Reject(RivePlaybackIssue.VANGUARD_CDN_502)
                ProbeState.UNHEALTHY -> Decision.Reject(RivePlaybackIssue.HLS_RENDITION_UNHEALTHY)
            }
        }

        val audioByGroup = parseAudioRenditions(masterUrl, master.text)
            .groupBy { it.groupId }
            .mapValues { (_, list) -> list.sortedByDescending { it.isDefault } }
        val audioHealthCache = HashMap<String, ProbeState>()
        val healthy = ArrayList<Rendition>()

        // Prefer the highest healthy variant, but never trust a master entry
        // merely because it exists: its child media objects must pass probes.
        for (variant in variants.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })) {
            if (healthy.size >= MAX_HEALTHY_VARIANTS) break

            val videoProbe = probePlaylistUrl(variant.url, headers)
            if (videoProbe != ProbeState.HEALTHY) {
                logProbeFailure("video ${variant.height}p", variant.url, videoProbe)
                continue
            }

            var audioSatisfied = variant.audioGroup == null
            var audioUrl: String? = null
            variant.audioGroup?.let { group ->
                val candidates = audioByGroup[group].orEmpty()
                for (audio in candidates) {
                    // URI-less AUDIO entries are in-band according to HLS.
                    // The video media-segment probe already checks availability,
                    // so no separate external AudioFile is required.
                    val candidateUrl = audio.url
                    if (candidateUrl == null) {
                        audioSatisfied = true
                        break
                    }

                    val actual = audioHealthCache[candidateUrl]
                        ?: probePlaylistUrl(candidateUrl, headers).also { probed ->
                            audioHealthCache[candidateUrl] = probed
                        }
                    if (actual == ProbeState.HEALTHY) {
                        audioSatisfied = true
                        audioUrl = candidateUrl
                        break
                    }
                    logProbeFailure("audio group=$group", candidateUrl, actual)
                }
            }

            if (!audioSatisfied) {
                issue(
                    RivePlaybackIssue.VANGUARD_AUDIO_MAP,
                    "${variant.height}p has AUDIO=${variant.audioGroup} but no healthy audio rendition",
                )
                continue
            }

            healthy += Rendition(
                videoUrl = variant.url,
                quality = variant.height.takeIf { it > 0 } ?: 400,
                audioUrl = audioUrl,
            )
        }

        if (healthy.isEmpty()) {
            issue(RivePlaybackIssue.HLS_RENDITION_UNHEALTHY, "no healthy Vanguard rendition")
            return Decision.Reject(RivePlaybackIssue.HLS_RENDITION_UNHEALTHY)
        }

        Log.d(
            TAG,
            "validated Vanguard renditions=" + healthy.joinToString { r ->
                "${r.quality}p(audio=${if (r.audioUrl != null) "external" else "muxed/none"})"
            },
        )
        return Decision.UseRenditions(healthy)
    }

    private suspend fun probePlaylistUrl(
        url: String,
        headers: Map<String, String>,
    ): ProbeState {
        val response = fetchText(url, headers) ?: return ProbeState.UNHEALTHY
        if (response.code == 502) return ProbeState.HTTP_502
        if (response.code !in 200..299) return ProbeState.UNHEALTHY
        if (!response.text.trimStart().startsWith("#EXTM3U")) return ProbeState.UNHEALTHY
        return probeMediaPlaylist(url, response.text, headers)
    }

    private suspend fun probeMediaPlaylist(
        playlistUrl: String,
        body: String,
        headers: Map<String, String>,
    ): ProbeState {
        // A nested master is not emitted as a validated child. Treat it as
        // unhealthy rather than recursively expanding without a depth bound.
        if (body.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            return ProbeState.UNHEALTHY
        }

        val initRef = body.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-MAP:") }
            ?.let { MAP_URI.find(it)?.groupValues?.getOrNull(1) }
        if (!initRef.isNullOrBlank()) {
            val state = probeObject(resolve(playlistUrl, initRef), headers)
            if (state != ProbeState.HEALTHY) return state
        }

        val mediaRefs = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        if (mediaRefs.isEmpty()) return ProbeState.UNHEALTHY

        val indexes = linkedSetOf(0, mediaRefs.size / 2)
        for (index in indexes) {
            val ref = mediaRefs[index.coerceIn(0, mediaRefs.lastIndex)]
            val state = probeObject(resolve(playlistUrl, ref), headers)
            if (state != ProbeState.HEALTHY) return state
        }
        return ProbeState.HEALTHY
    }

    private suspend fun probeObject(
        url: String,
        headers: Map<String, String>,
    ): ProbeState {
        val response = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                app.get(url, headers = headers + ("Range" to "bytes=0-1"))
            }
        } catch (_: Exception) {
            null
        } ?: return ProbeState.UNHEALTHY

        return when {
            response.code == 502 -> ProbeState.HTTP_502
            response.code in 200..299 -> ProbeState.HEALTHY
            else -> ProbeState.UNHEALTHY
        }
    }

    private suspend fun fetchText(
        url: String,
        headers: Map<String, String>,
    ): HttpText? {
        val response = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                app.get(url, headers = headers)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return HttpText(response.code, response.text)
    }

    private fun parseVariants(masterUrl: String, body: String): List<Variant> {
        val lines = body.lines()
        val out = ArrayList<Variant>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val attrs = attributes(line)
            val ref = lines.drop(i + 1)
                .asSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: continue
            val resolution = attrs["RESOLUTION"].orEmpty()
            val height = resolution.substringAfter('x', "").toIntOrNull() ?: 0
            out += Variant(
                url = resolve(masterUrl, ref),
                height = height,
                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                audioGroup = attrs["AUDIO"],
                codecs = attrs["CODECS"],
            )
        }
        return out
    }

    private fun parseAudioRenditions(masterUrl: String, body: String): List<AudioRendition> {
        val out = ArrayList<AudioRendition>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (!line.startsWith("#EXT-X-MEDIA:")) continue
            val attrs = attributes(line)
            if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) continue
            val group = attrs["GROUP-ID"] ?: continue
            val ref = attrs["URI"]
            out += AudioRendition(
                groupId = group,
                url = ref?.let { resolve(masterUrl, it) },
                isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
            )
        }
        return out
    }

    private fun attributes(line: String): Map<String, String> {
        val raw = line.substringAfter(':', "")
        val out = LinkedHashMap<String, String>()
        for (match in ATTRIBUTE.findAll(raw)) {
            out[match.groupValues[1]] = match.groupValues[2].removeSurrounding("\"")
        }
        return out
    }

    private fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref).toString()
    } catch (_: Exception) {
        ref
    }

    private fun logProbeFailure(label: String, url: String, state: ProbeState) {
        issue(
            if (state == ProbeState.HTTP_502) RivePlaybackIssue.VANGUARD_CDN_502
            else RivePlaybackIssue.HLS_RENDITION_UNHEALTHY,
            "$label failed preflight: $url",
        )
    }

    private fun issue(code: RivePlaybackIssue, message: String) {
        Log.w(TAG, "[$code] $message")
    }

    private val MAP_URI = Regex("""URI=\"([^\"]+)\"""")
    private val ATTRIBUTE = Regex("""([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)""")
}
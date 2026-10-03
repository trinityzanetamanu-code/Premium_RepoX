package com.RiveStream.bestserver

import android.util.Log
import com.RiveStream.api.RiveApi
import com.RiveStream.playback.RivePlaybackIssue
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URLEncoder

/**
 * PrimeSrc / RiveStream "Server 2 — Best-Server" integration.
 *
 * This client intentionally does not solve or bypass Cloudflare Turnstile.
 * It uses the public server-list endpoint, direct host-key mappings that work
 * without /api/v1/l, and the normal /api/v1/l resolver only when it answers
 * without a challenge. Unsupported/challenge-gated hosts are skipped so one
 * mirror cannot break the other sources.
 */
internal object BestServerClient {
    private const val TAG = "RiveBestServer"
    private const val BASE = "https://primesrc.me"
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val EXTRACTOR_TIMEOUT_MS = 12_000L
    private const val MAX_SUCCESSFUL_HOSTS = 4

    data class Server(
        val name: String,
        val key: String,
        val quality: String?,
        val fileSize: String?,
        val fileName: String?,
    )

    suspend fun emit(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        val servers = discover(tmdbId, isTv, season, episode)
        if (servers.isEmpty()) {
            issue(RivePlaybackIssue.NO_SOURCE, "PrimeSrc returned no servers for tmdb=$tmdbId")
            return 0
        }

        var emitted = 0
        var successfulHosts = 0
        val completedHostFamilies = HashSet<String>()

        for (server in servers.sortedBy { priority(it.name) }) {
            if (successfulHosts >= MAX_SUCCESSFUL_HOSTS) break

            val hostFamily = normalize(server.name)
            if (hostFamily in completedHostFamilies) continue

            val embedUrl = resolveEmbed(server) ?: continue
            var hostEmitted = 0
            val extractedLinks = ArrayList<ExtractorLink>()

            val matched = try {
                withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                    loadExtractor(
                        url = embedUrl,
                        referer = "$BASE/",
                        subtitleCallback = subtitleCallback,
                    ) { extracted ->
                        extractedLinks += extracted
                    }
                } ?: run {
                    issue(
                        RivePlaybackIssue.EXTRACTOR_FAILURE,
                        "${server.name}: extractor timed out after ${EXTRACTOR_TIMEOUT_MS}ms",
                    )
                    false
                }
            } catch (e: Exception) {
                issue(
                    RivePlaybackIssue.EXTRACTOR_FAILURE,
                    "${server.name}: ${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                )
                false
            }

            for (extracted in extractedLinks) {
                if (!seen.add(extracted.url)) continue
                callback(
                    newExtractorLink(
                        source = "Best-Server",
                        name = "Best-Server | ${server.name}",
                        url = extracted.url,
                        type = extracted.type,
                    ) {
                        this.quality = extracted.quality
                        this.referer = extracted.referer
                        this.headers = extracted.headers
                        this.extractorData = extracted.extractorData
                        this.audioTracks = extracted.audioTracks
                    }
                )
                hostEmitted++
                emitted++
            }

            if (hostEmitted > 0) {
                successfulHosts++
                completedHostFamilies += hostFamily
            }

            when {
                !matched -> issue(
                    RivePlaybackIssue.EXTRACTOR_UNSUPPORTED,
                    "${server.name}: no matching CloudStream extractor for $embedUrl",
                )
                hostEmitted == 0 -> issue(
                    RivePlaybackIssue.EXTRACTOR_FAILURE,
                    "${server.name}: extractor matched but emitted no playable link",
                )
            }
        }

        return emitted
    }

    private suspend fun discover(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): List<Server> {
        val type = if (isTv) "tv" else "movie"
        val url = buildString {
            append("$BASE/api/v1/s?type=$type&tmdb=${enc(tmdbId)}")
            if (isTv) {
                append("&season=${season ?: 1}")
                append("&episode=${episode ?: 1}")
            }
        }

        val response = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to RiveApi.USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to "$BASE/",
                    )
                )
            }
        } catch (e: Exception) {
            issue(RivePlaybackIssue.NETWORK, "server discovery failed: ${e.message.orEmpty()}")
            null
        } ?: run {
            issue(RivePlaybackIssue.NETWORK, "server discovery timed out")
            return emptyList()
        }

        if (response.code !in 200..299) {
            issue(
                RivePlaybackIssue.HTTP_SOURCE_FAILURE,
                "server discovery HTTP ${response.code}",
            )
            return emptyList()
        }

        val root = runCatching { JSONObject(response.text) }.getOrNull() ?: run {
            issue(RivePlaybackIssue.HTTP_SOURCE_FAILURE, "invalid server-list JSON")
            return emptyList()
        }
        val arr = root.optJSONArray("servers") ?: return emptyList()
        val out = ArrayList<Server>(arr.length())

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name", "").trim()
            val key = item.optString("key", "").trim()
            if (name.isBlank() || key.isBlank()) continue
            out += Server(
                name = name,
                key = key,
                quality = item.optString("quality", "").takeIf { it.isNotBlank() },
                fileSize = item.optString("file_size", "").takeIf { it.isNotBlank() && it != "null" },
                fileName = item.optString("file_name", "").takeIf { it.isNotBlank() && it != "null" },
            )
        }
        return out
    }

    /**
     * Return a host embed URL without bypassing anti-bot protection.
     *
     * Most PrimeSrc hosts expose a stable /e/{key} URL directly. Voe and
     * Streamtape are resolved through /api/v1/l because their raw key is not
     * reliably portable across mirrors. If that endpoint is challenge-gated,
     * the host is skipped and the next mirror is tried.
     */
    private suspend fun resolveEmbed(server: Server): String? {
        if (server.key.startsWith("http://") || server.key.startsWith("https://")) {
            return server.key
        }

        val normalized = normalize(server.name)
        val direct = when {
            "filemoon" in normalized -> "https://filemoon.sx/e/${server.key}"
            normalized == "dood" || normalized.startsWith("dood") -> "https://dood.wf/e/${server.key}"
            "streamwish" in normalized -> "https://streamwish.com/e/${server.key}"
            "filelions" in normalized -> "https://filelions.sx/e/${server.key}"
            "mixdrop" in normalized -> "https://mixdrop.ag/e/${server.key}"
            "vidmoly" in normalized -> "https://vidmoly.to/e/${server.key}"
            "luluvdoo" in normalized -> "https://luluvdoo.com/e/${server.key}"
            "streamplay" in normalized -> "https://streamplay.cc/e/${server.key}"
            "vidara" in normalized -> "https://vidara.online/e/${server.key}"
            else -> null
        }
        if (direct != null) return direct

        // These providers are known to require PrimeSrc link resolution.
        if ("voe" in normalized || "streamtape" in normalized) {
            return resolveViaPrimeSrc(server)
        }

        issue(
            RivePlaybackIssue.EXTRACTOR_UNSUPPORTED,
            "${server.name}: no safe direct mapping; host skipped",
        )
        return null
    }

    private suspend fun resolveViaPrimeSrc(server: Server): String? {
        val url = "$BASE/api/v1/l?key=${enc(server.key)}"
        val response = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to RiveApi.USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                        "Referer" to "$BASE/",
                        "Origin" to BASE,
                    )
                )
            }
        } catch (e: Exception) {
            issue(
                RivePlaybackIssue.NETWORK,
                "${server.name}: resolver failed: ${e.message.orEmpty()}",
            )
            null
        } ?: return null

        if (response.code !in 200..299) {
            issue(
                RivePlaybackIssue.HTTP_SOURCE_FAILURE,
                "${server.name}: resolver HTTP ${response.code}; next mirror will be tried",
            )
            return null
        }

        val link = runCatching { JSONObject(response.text).optString("link", "") }
            .getOrNull()
            .orEmpty()
            .trim()
        if (link.isBlank()) {
            issue(RivePlaybackIssue.NO_SOURCE, "${server.name}: resolver returned no link")
            return null
        }
        return link
    }

    private fun priority(name: String): Int {
        val n = normalize(name)
        return when {
            "filemoon" in n -> 0
            "streamwish" in n -> 1
            n.startsWith("dood") -> 2
            "mixdrop" in n -> 3
            "vidmoly" in n -> 4
            "luluvdoo" in n -> 5
            "streamplay" in n -> 6
            "vidara" in n -> 7
            "filelions" in n -> 8
            "voe" in n -> 9
            "streamtape" in n -> 10
            else -> 50
        }
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun issue(code: RivePlaybackIssue, message: String) {
        Log.w(TAG, "[$code] $message")
    }
}
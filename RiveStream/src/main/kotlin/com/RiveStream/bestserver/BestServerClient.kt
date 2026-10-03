package com.RiveStream.bestserver

import android.util.Log
import com.RiveStream.api.RiveApi
import com.RiveStream.playback.RivePlaybackIssue
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
    private const val WEBVIEW_TIMEOUT_MS = 25_000L
    private const val SUBTITLE_TIMEOUT_MS = 8_000L
    private const val MAX_SUCCESSFUL_HOSTS = 2
    private const val SUBTITLE_BASE = "https://sub.wyzie.ru"

    private val voeEmbedRegex = Regex(
        """https?://[^/]*(?:voe[.]sx|tubelessceliolymph[.]com|simpulumlamerop[.]com|urochsunloath[.]com|nathanfromsubject[.]com|yip[.]su|metagnathtuggers[.]com|donaldlineelse[.]com|charlestoughrace[.]com)(?:/|\z)""",
        RegexOption.IGNORE_CASE,
    )
    private val streamTapeEmbedRegex = Regex(
        """https?://[^/]*(?:streamtape[.]com|streamtape[.]net|streamtape[.]xyz|watchadsontape[.]com|shavetape[.]cash|streamta[.]site)(?:/|\z)""",
        RegexOption.IGNORE_CASE,
    )

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
        // Subtitle Indonesia tidak bergantung pada resolver host PrimeSrc.
        emitIndonesianSubtitles(tmdbId, isTv, season, episode, subtitleCallback)

        val servers = discover(tmdbId, isTv, season, episode)
            .filter(::isRequestedBestServerHost)
        if (servers.isEmpty()) {
            issue(
                RivePlaybackIssue.NO_SOURCE,
                "PrimeSrc returned no Voe/Streamtape server for tmdb=$tmdbId",
            )
            return 0
        }

        var emitted = 0
        var successfulHosts = 0
        val completedHostFamilies = HashSet<String>()

        for (server in servers.sortedBy { priority(it.name) }) {
            if (successfulHosts >= MAX_SUCCESSFUL_HOSTS) break

            val hostFamily = normalize(server.name)
            if (hostFamily in completedHostFamilies) continue

            val embedUrl = resolveEmbed(
                server = server,
                tmdbId = tmdbId,
                isTv = isTv,
                season = season,
                episode = episode,
            ) ?: continue
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
     * Resolve hanya dua host yang diminta: Voe dan Streamtape.
     *
     * /api/v1/s hanya memberi opaque key. Pertama coba link API normal.
     * Bila link API sedang challenge-gated, gunakan halaman embed PrimeSrc
     * sendiri melalui WebView dan tangkap URL iframe upstream yang memang
     * dimuat oleh halaman tersebut. Tidak ada token yang dibuat/dipalsukan
     * atau disuntikkan oleh client ini.
     */
    private suspend fun resolveEmbed(
        server: Server,
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): String? {
        if (server.key.startsWith("http://") || server.key.startsWith("https://")) {
            return server.key
        }
        if (!isRequestedBestServerHost(server)) return null

        resolveViaPrimeSrc(server)?.let { return it }

        return resolveViaEmbedPage(
            server = server,
            tmdbId = tmdbId,
            isTv = isTv,
            season = season,
            episode = episode,
        )
    }

    private suspend fun resolveViaEmbedPage(
        server: Server,
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
    ): String? {
        val normalized = normalize(server.name)
        val targetRegex = when {
            "streamtape" in normalized || "streamta" in normalized -> streamTapeEmbedRegex
            "voe" in normalized -> voeEmbedRegex
            else -> return null
        }

        val pageUrl = buildString {
            append("$BASE/embed/")
            append(if (isTv) "tv" else "movie")
            append("?tmdb=${enc(tmdbId)}")
            if (isTv) {
                append("&season=${season ?: 1}")
                append("&episode=${episode ?: 1}")
            }
            append("&fallback=false")
            append("&server_order=${enc(server.name)}")
            if ("streamtape" in normalized || "streamta" in normalized) {
                append("&ds=streamtape")
            }
            append("&autoplay=1&muted=1")
        }

        return try {
            val request = WebViewResolver(
                interceptUrl = targetRegex,
                userAgent = null,
                useOkhttp = false,
                timeout = WEBVIEW_TIMEOUT_MS,
            ).resolveUsingWebView(
                url = pageUrl,
                referer = "$BASE/",
            ).first

            val resolved = request?.url?.toString()
            if (resolved.isNullOrBlank()) {
                issue(
                    RivePlaybackIssue.EXTRACTOR_FAILURE,
                    "${server.name}: PrimeSrc embed page did not expose a host URL",
                )
                null
            } else {
                resolved
            }
        } catch (e: Exception) {
            issue(
                RivePlaybackIssue.EXTRACTOR_FAILURE,
                "${server.name}: WebView resolve failed: ${e.javaClass.simpleName}: ${e.message.orEmpty()}",
            )
            null
        }
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
                        "X-Requested-With" to "XMLHttpRequest",
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

    private suspend fun emitIndonesianSubtitles(
        tmdbId: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = buildString {
            append("$SUBTITLE_BASE/search?id=${enc(tmdbId)}")
            if (isTv) {
                append("&season=${season ?: 1}")
                append("&episode=${episode ?: 1}")
            }
            append("&language=id&format=srt")
        }

        val response = try {
            withTimeoutOrNull(SUBTITLE_TIMEOUT_MS) {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to RiveApi.USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                    )
                )
            }
        } catch (e: Exception) {
            issue(
                RivePlaybackIssue.NETWORK,
                "Indonesian subtitle request failed: ${e.message.orEmpty()}",
            )
            null
        } ?: return

        if (response.code !in 200..299) {
            issue(
                RivePlaybackIssue.HTTP_SOURCE_FAILURE,
                "Indonesian subtitle HTTP ${response.code}",
            )
            return
        }

        val items = parseSubtitleArray(response.text) ?: return
        val seen = HashSet<String>()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val rawUrl = firstString(item, "url", "file", "src") ?: continue
            val label = firstString(item, "display", "label", "name", "language", "lang").orEmpty()
            val language = firstString(item, "lang", "language", "language_code", "iso639").orEmpty()

            if (!isIndonesianSubtitle(language, label)) continue

            val finalUrl = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                rawUrl.startsWith("/") -> "$SUBTITLE_BASE$rawUrl"
                else -> "$SUBTITLE_BASE/$rawUrl"
            }
            if (!seen.add(finalUrl)) continue

            subtitleCallback(newSubtitleFile("Indonesian", finalUrl))
        }
    }

    private fun parseSubtitleArray(text: String): JSONArray? {
        val clean = text.trim()
        if (clean.isBlank()) return null

        return runCatching { JSONArray(clean) }.getOrNull()
            ?: runCatching { JSONObject(clean).optJSONArray("subtitles") }.getOrNull()
            ?: runCatching { JSONObject(clean).optJSONArray("results") }.getOrNull()
    }

    private fun firstString(item: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            val value = item.optString(key, "").trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return null
    }

    private fun isIndonesianSubtitle(language: String, label: String): Boolean {
        val lang = language.trim().lowercase()
        val text = label.trim().lowercase()
        return lang in setOf("id", "ind", "indonesian", "id-id", "in_id") ||
            text.contains("indonesian") ||
            text.contains("bahasa indonesia") ||
            text == "id"
    }

    private fun isRequestedBestServerHost(server: Server): Boolean {
        val n = normalize(server.name)
        return "voe" in n || "streamtape" in n || "streamta" in n
    }

    private fun priority(name: String): Int {
        val n = normalize(name)
        return when {
            "voe" in n -> 0
            "streamtape" in n || "streamta" in n -> 1
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
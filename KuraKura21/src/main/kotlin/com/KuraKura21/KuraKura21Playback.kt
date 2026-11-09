package com.kurakura21

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Playback only. Existing catalog, search and detail parsing remain unchanged.
 * The site may expose sources in HTML, server tab attributes or DooPlay AJAX.
 */
internal class KuraKura21Playback(
    private val mainUrl: String,
    private val headers: Map<String, String>
) {
    private data class Player(val url: String, val label: String, val referer: String)

    suspend fun load(
        detailUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(detailUrl, headers = headers)
        val doc = response.document.apply { setBaseUri(response.url) }
        val found = linkedMapOf<String, Player>()
        fun add(raw: String?, label: String, referer: String) {
            parsePlayerValue(raw, referer).forEach { url ->
                if (url != detailUrl) found.putIfAbsent(url, Player(url, label, referer))
            }
        }

        collect(doc, detailUrl, ::add)

        // DooPlay-style server buttons do not contain an iframe URL until clicked.
        val options = doc.select(
            ".dooplay_player_option[data-post][data-nume], " +
                "#playeroptionsul [data-post][data-nume], " +
                "[data-post][data-nume][data-type]"
        ).distinctBy { listOf(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type")) }
        for (option in options.take(12)) {
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            if (post.isBlank() || nume.isBlank()) continue
            val type = option.attr("data-type").ifBlank { "movie" }
            val label = option.text().trim().ifBlank { "Server " + nume }
            try {
                val ajax = app.post(
                    mainUrl.trimEnd('/') + "/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to detailUrl
                    )
                ).text
                collectEmbedResponse(ajax, detailUrl, label, ::add)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unavailable server must not hide the other servers.
            }
        }

        val emitted = AtomicBoolean(false)
        val emittedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val emit: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                callback(link)
                emitted.set(true)
            }
        }
        coroutineScope {
            found.values.take(16).map { player ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000L) {
                        visit(player, 0, mutableSetOf(), subtitleCallback, emit)
                    }
                }
            }.awaitAll()
        }
        return emitted.get()
    }

    private fun collect(
        doc: Document,
        pageUrl: String,
        add: (String?, String, String) -> Unit
    ) {
        doc.select("iframe[src], iframe[data-src], iframe[data-lazy-src], video[src], video source[src]")
            .forEach { element ->
                add(
                    element.attr("src")
                        .ifBlank { element.attr("data-src") }
                        .ifBlank { element.attr("data-lazy-src") },
                    "Player",
                    pageUrl
                )
            }

        doc.select(
            "#playeroptionsul li, .dooplay_player_option, .server-item, " +
                ".player-option, .player-options li, .server a, " +
                "#player [data-embed], #player [data-url], " +
                ".video-player [data-url], .video-player [data-embed]"
        ).forEach { option ->
            val label = option.text().trim().take(50).ifBlank { "Server" }
            for (attribute in listOf(
                "data-url", "data-embed", "data-player", "data-video",
                "data-file", "data-iframe", "data-src", "href"
            )) {
                add(option.attr(attribute), label, pageUrl)
            }
        }

        // Inline player configuration: JWPlayer, video.js, HTML5 media sources.
        val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val mediaPattern = Regex(
            """(?i)(?:["']?(?:file|src|source|url)["']?\s*[:=]\s*["'])([^"']{1,2048})["']"""
        )
        mediaPattern.findAll(scripts).take(40).forEach { match ->
            val url = match.groupValues[1]
            if (isMediaText(url)) add(url, "Video", pageUrl)
        }
        Regex("""(?i)atob\(["']([A-Za-z0-9+/=]{24,})["']\)""")
            .findAll(scripts).take(12).forEach { match ->
                decodeBase64(match.groupValues[1])?.let { text ->
                    parsePlayerValue(text, pageUrl).forEach { add(it, "Player", pageUrl) }
                }
            }
    }

    private fun collectEmbedResponse(
        response: String,
        base: String,
        label: String,
        add: (String?, String, String) -> Unit
    ) {
        val decoded = response.trim()
        if (decoded.startsWith("{")) {
            val json = try { JSONObject(decoded) } catch (_: Exception) { null }
            if (json != null) {
                for (key in listOf("embed_url", "iframe", "url", "embed", "html", "player", "link")) {
                    val raw = json.optString(key)
                    if (raw.isNotBlank()) collectEmbedResponse(raw, base, label, add)
                }
                return
            }
        }
        val frame = Jsoup.parseBodyFragment(decoded, base)
        frame.select("iframe[src], iframe[data-src], video[src], source[src]").forEach {
            add(it.attr("src").ifBlank { it.attr("data-src") }, label, base)
        }
        if (!decoded.contains("<")) add(decoded, label, base)
    }

    private fun parsePlayerValue(raw: String?, base: String): List<String> {
        val value = raw?.trim()?.takeIf { it.isNotBlank() && it != "#" }
            ?: return emptyList()
        var input = value
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        if (input.startsWith("base64:", true)) {
            input = decodeBase64(input.substringAfter(':')) ?: return emptyList()
        } else if (input.length in 24..12000 &&
            input.matches(Regex("""[A-Za-z0-9+/=]+"""))
        ) {
            val decoded = decodeBase64(input)
            if (decoded != null && (decoded.contains("http") || decoded.contains("<iframe"))) {
                input = decoded
            }
        }
        if (input.startsWith("%68", true) || input.startsWith("https%3A", true)) {
            input = try { URLDecoder.decode(input, "UTF-8") } catch (_: Exception) { input }
        }
        if (input.startsWith("{")) {
            val json = try { JSONObject(input) } catch (_: Exception) { null }
            if (json != null) {
                return listOf("url", "file", "src", "embed", "embed_url", "link")
                    .flatMap { parsePlayerValue(json.optString(it), base) }
                    .distinct()
            }
        }
        if (input.contains("<iframe") || input.contains("<video") || input.contains("<source")) {
            val doc = Jsoup.parseBodyFragment(input, base)
            return doc.select("iframe[src], video[src], source[src]")
                .flatMap { parsePlayerValue(it.attr("src"), base) }
                .distinct()
        }
        val url = try {
            val uri = URI(base).resolve(input)
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return emptyList()
            val host = uri.host?.lowercase() ?: return emptyList()
            if (host == "localhost" || host.startsWith("127.") ||
                host.startsWith("192.168.") || host.startsWith("10.") ||
                host.contains("doubleclick.net") ||
                host.contains("googlesyndication.com") ||
                host.contains("google-analytics.com")
            ) return emptyList()
            val path = uri.path.orEmpty().lowercase()
            if (listOf(".jpg", ".jpeg", ".webp", ".png", ".gif", ".css", ".js", ".svg", ".vtt")
                    .any(path::endsWith)
            ) return emptyList()
            uri.toString()
        } catch (_: Exception) {
            return emptyList()
        }
        return listOf(url)
    }

    private suspend fun visit(
        player: Player,
        depth: Int,
        seen: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (depth > 2 || !seen.add(player.url)) return
        if (isMediaText(player.url)) {
            val isHls = URI(player.url).path.endsWith(".m3u8", ignoreCase = true)
            callback(newExtractorLink(
                source = "KURAKURA21",
                name = "KURAKURA21 - " + player.label,
                url = player.url,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = player.referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to player.referer,
                    "User-Agent" to headers["User-Agent"].orEmpty()
                )
            })
            return
        }
        try {
            loadExtractor(player.url, player.referer, subtitleCallback, callback)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unrecognized host: inspect its public embed page below.
        }
        if (depth >= 2) return
        val response = try {
            app.get(player.url, headers = headers + ("Referer" to player.referer))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (response.code !in 200..299) return
        val doc = response.document.apply { setBaseUri(response.url) }
        val next = linkedMapOf<String, Player>()
        collect(doc, response.url) { raw, label, referer ->
            parsePlayerValue(raw, referer).forEach { url ->
                if (url != response.url) next.putIfAbsent(url, Player(url, label, referer))
            }
        }
        for (nested in next.values.take(8)) {
            visit(nested, depth + 1, seen, subtitleCallback, callback)
        }
    }

    private fun isMediaText(url: String): Boolean {
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { "" }
        return listOf(".m3u8", ".mp4", ".m4v", ".webm").any(path::endsWith)
    }

    private fun decodeBase64(encoded: String): String? = try {
        String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).take(16000)
    } catch (_: Exception) {
        null
    }
}

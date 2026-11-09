package com.kurakura21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves the actual Muvipro tab AJAX protocol used by kurakura21.com.
 * The AJAX response contains an iframe, not a directly playable video.
 */
internal class KuraKura21Playback(
    private val mainUrl: String,
    private val headers: Map<String, String>
) {
    private data class Server(val name: String, val url: String)

    suspend fun load(
        detailUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(detailUrl, headers = headers).document
        val player = page.selectFirst("#muvipro_player_content_id") ?: return false
        val postId = player.attr("data-id").takeIf {
            it.isNotBlank() && it.all(Char::isDigit)
        } ?: return false

        val tabs = player.select("ul.muvipro-player-tabs a[href^='#p']")
            .mapNotNull { anchor ->
                val tab = anchor.attr("href").removePrefix("#")
                if (!tab.matches(Regex("""p\d+"""))) null
                else tab to anchor.text().trim().ifBlank { "Server $tab" }
            }.distinctBy { it.first }.take(12)
        if (tabs.isEmpty()) return false

        val emitted = AtomicBoolean(false)
        val links = Collections.synchronizedSet(mutableSetOf<String>())
        val emit: (ExtractorLink) -> Unit = { stream ->
            if (links.add(stream.url)) {
                emitted.set(true)
                callback(stream)
            }
        }

        coroutineScope {
            tabs.map { (tab, name) ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(42_000L) {
                        try {
                            val response = app.post(
                                "$mainUrl/wp-admin/admin-ajax.php",
                                data = mapOf(
                                    "action" to "muvipro_player_content",
                                    "tab" to tab,
                                    "post_id" to postId
                                ),
                                headers = headers + mapOf(
                                    "Referer" to detailUrl,
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                                )
                            )
                            if (response.code !in 200..299) return@withTimeoutOrNull
                            val fragment = Jsoup.parseBodyFragment(response.text, detailUrl)
                            val urls = fragment.select("iframe[src], video[src], source[src]")
                                .mapNotNull { node -> absoluteUrl(node.attr("src"), detailUrl) }
                                .distinct()
                            for (url in urls.take(5)) {
                                resolve(Server(name, url), detailUrl, subtitleCallback, emit)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Failure in one server does not prevent the others.
                        }
                    }
                }
            }.awaitAll()
        }
        return emitted.get()
    }

    private suspend fun resolve(
        server: Server,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isDirectMedia(server.url)) {
            emitMedia(server.name, server.url, pageUrl, emptyMap(), callback)
            return
        }
        try {
            if (loadExtractor(server.url, pageUrl, subtitleCallback, callback)) return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A host without a registered extractor may still load media in a WebView.
        }

        // JS-driven hosts require browser requests to reveal the actual media URL.
        val hostname = runCatching { URI(server.url).host.orEmpty() }.getOrDefault("")
        if (hostname !in setOf("turtle4up.top", "kr21.click", "n1mwq.org", "k21cdn.click")) return

        val media = withTimeoutOrNull(37_000L) {
            try {
                WebViewResolver(
                    Regex(
                        """^https?://(?![^/]*(?:mmcdn\.com|jads\.co|juicyads|chaseherbalpasty|fozekikikiminu))[^?#]+(?:\.(?:m3u8|mp4|m4v|webm)(?:[?#]|$)|/api/videos/stream/)""",
                        RegexOption.IGNORE_CASE
                    ),
                    // Let the WebView send original JSON POST bodies for Byse attestation/playback.
                    // The default OkHttp interception cannot replay those bodies faithfully.
                    userAgent = null,
                    useOkhttp = false,
                    timeout = 35_000L
                ).resolveUsingWebView(server.url, referer = pageUrl).first
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } ?: return

        val url = media.url.toString()
        if (!isDirectMedia(url) && !url.contains("/api/videos/stream/")) return
        // The CDN HLS requests in the browser originate from the nested Byse player.
        // Never reuse a captured signed playlist URL or copy cookies/session tokens.
        val byseCdn = runCatching {
            URI(url).host?.endsWith(".owphbf24.com", ignoreCase = true) == true
        }.getOrDefault(false)
        val mediaReferer = if (byseCdn) "https://n1mwq.org/"
            else media.header("Referer") ?: server.url
        val mediaHeaders = buildMap {
            media.header("User-Agent")?.let { put("User-Agent", it) }
            media.header("Origin")?.let { put("Origin", it) }
            if (byseCdn) put("Origin", "https://n1mwq.org")
        }
        emitMedia(server.name, url, mediaReferer, mediaHeaders, callback)
    }

    private suspend fun emitMedia(
        label: String,
        url: String,
        referer: String,
        requestHeaders: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val hls = URI(url).path.orEmpty().endsWith(".m3u8", ignoreCase = true)
        callback(newExtractorLink(
            source = "KURAKURA21",
            name = "KURAKURA21 - $label",
            url = url,
            type = if (hls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = requestHeaders + mapOf(
                "Referer" to referer,
                "User-Agent" to (requestHeaders["User-Agent"] ?: headers["User-Agent"].orEmpty())
            )
        })
    }

    private fun absoluteUrl(raw: String, base: String): String? = try {
        val url = URI(base).resolve(raw.trim().replace("&amp;", "&"))
        if (url.scheme?.lowercase() in setOf("http", "https") && !url.host.isNullOrBlank()) {
            url.toString()
        } else null
    } catch (_: Exception) {
        null
    }

    private fun isDirectMedia(url: String): Boolean = try {
        val path = URI(url).path.orEmpty().lowercase()
        listOf(".m3u8", ".mp4", ".webm", ".m4v").any(path::endsWith)
    } catch (_: Exception) {
        false
    }
}

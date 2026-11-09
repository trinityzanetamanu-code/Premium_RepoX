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
import android.util.Log
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
    private data class Server(val name: String, val url: String, val tab: String)

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
                                resolve(Server(name, url, tab), detailUrl, subtitleCallback, emit)
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
        // Advertising clips often have ordinary MP4 URLs. Do not emit them.
        if (isDirectMedia(server.url) && isApprovedMediaHost(server.url)) {
            emitMedia(server.name, server.url, pageUrl, emptyMap(), callback)
            return
        }
        val extractedLinks = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(server.url, pageUrl, subtitleCallback) { link ->
                extractedLinks.add(link)
            }
            val accepted = extractedLinks.toList().filter { isApprovedMediaHost(it.url) }
            accepted.forEach(callback)
            if (accepted.isNotEmpty()) return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Try first-party iframe playback when no verified extractor exists.
        }

        // JS-driven hosts require browser requests to reveal the actual media URL.
        val hostname = runCatching { URI(server.url).host.orEmpty() }.getOrDefault("")
        if (hostname !in setOf("turtle4up.top", "kr21.click", "n1mwq.org", "k21cdn.click")) return


        // kr21 needs the movie-page iframe context; opening it as a top-level
        // browser page does not reproduce the user's successful Chrome flow.
        val embeddedKr21 = hostname == "kr21.click"
        val browserUrl = if (embeddedKr21) pageUrl else server.url
        val browserReferer = if (embeddedKr21) "$mainUrl/" else pageUrl
        val pageScript = if (embeddedKr21) """
            (function () {
              if (window.__kura21Started) return;
              window.__kura21Started = true;
              var tries = 0;
              var selected = false;
              var timer = setInterval(function () {
                if (++tries > 45) { clearInterval(timer); return; }
                if (!selected) {
                  var tab = document.querySelector('a[href="#${server.tab}"]');
                  if (tab) {
                    tab.click();
                    selected = true;
                  }
                }
                // The original page's countdown exposes its own Play Now button.
                var play = document.getElementById('timeloading');
                if (play && play.tagName === 'BUTTON' && play.offsetParent !== null) {
                  play.click();
                }
              }, 500);
            })();
        """.trimIndent() else null


        // Ad MP4s are commonly just 22-30 seconds long. Only verified media
        // origins may stop the WebView early. Other HLS is collected for review.
        val trustedStreamPattern = Regex(
            """^https?://(?:[a-z0-9-]+\.)*owphbf24\.com/[^?#]+\.(?:m3u8|mp4)(?:[?#]|$)|^https?://n1mwq\.org/api/videos/stream(?:/|\?|$)""",
            RegexOption.IGNORE_CASE
        )
        val hlsPattern = Regex(
            """^https?://[^?#]+\.m3u8(?:[?#]|$)""",
            RegexOption.IGNORE_CASE
        )
        val requests = withTimeoutOrNull(38_000L) {
            try {
                val (primary, secondary) = WebViewResolver(
                    trustedStreamPattern,
                    additionalUrls = listOf(hlsPattern),
                    userAgent = null,
                    useOkhttp = false,
                    script = pageScript,
                    timeout = 35_000L
                ).resolveUsingWebView(browserUrl, referer = browserReferer)
                listOfNotNull(primary) + secondary
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()

        val media = requests.distinctBy { it.url.toString() }.firstOrNull { request ->
            val candidate = request.url.toString()
            when {
                isAdMedia(candidate) -> false
                isApprovedMediaHost(candidate) -> true
                isHls(candidate) -> isLongHls(candidate, request.header("Referer") ?: server.url)
                else -> false
            }
        } ?: run {
            Log.i("KuraKura21", "No verified movie stream for " + server.name)
            return
        }

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

    private fun isAdMedia(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        val adHosts = listOf(
            "mmcdn.com", "jads.co", "stripchat.com", "juicyads.com",
            "exoclick.com", "exosrv.com", "trafficjunky.net",
            "doubleclick.net", "googlesyndication.com"
        )
        adHosts.any { host == it || host.endsWith(".$it") } ||
            Regex("""(?:^|/)(?:ads?|preroll|vast|vpaid|promo|sponsor)(?:/|[-_.])""")
                .containsMatchIn(path)
    } catch (_: Exception) { true }

    private fun isApprovedMediaHost(url: String): Boolean = try {
        if (isAdMedia(url)) {
            false
        } else {
            val uri = URI(url)
            val host = uri.host.orEmpty().lowercase()
            val knownCdn = host == "owphbf24.com" || host.endsWith(".owphbf24.com")
            (knownCdn && isDirectMedia(url)) ||
                (host == "n1mwq.org" && uri.path.orEmpty().startsWith("/api/videos/stream/"))
        }
    } catch (_: Exception) { false }

    private fun isHls(url: String): Boolean = try {
        URI(url).path.orEmpty().lowercase().endsWith(".m3u8")
    } catch (_: Exception) { false }

    // Check media playlist duration; reject short ads and unknown playlists.
    private suspend fun isLongHls(url: String, referer: String, depth: Int = 0): Boolean {
        if (isAdMedia(url) || depth > 1) return false
        return try {
            val manifest = app.get(
                url,
                headers = headers + mapOf("Referer" to referer)
            ).text.take(1_000_000)
            if (!manifest.trimStart().startsWith("#EXTM3U")) return false
            val seconds = Regex("""(?m)^#EXTINF:([0-9]+(?:\.[0-9]+)?)""")
                .findAll(manifest)
                .sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            if (seconds >= 120.0) return true
            if (depth == 1 || !manifest.contains("#EXT-X-STREAM-INF")) return false
            val lines = manifest.lines().map { it.trim() }
            val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            if (index < 0) return false
            val variant = lines.drop(index + 1).firstOrNull {
                it.isNotEmpty() && !it.startsWith("#")
            } ?: return false
            val resolved = URI(url).resolve(variant).toString()
            isLongHls(resolved, url, depth + 1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
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

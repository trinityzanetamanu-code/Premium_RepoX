package com.kurakura21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.Collections
import kotlin.coroutines.coroutineContext

/** Selected detail -> Muvipro AJAX -> bounded iframe chain -> verified public HTTP extractor. */
internal class KuraKura21Playback(private val mainUrl: String, private val headers: Map<String, String>,
    private val sourceName: String = "KURAKURA21", private val limits: KuraKura21HttpLimits = KuraKura21HttpLimits()) {
    suspend fun load(detailUrl: String, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val trace = KuraKura21HttpTrace()
        val session = KuraKura21HttpSession(headers, limits)
        var delivered = false
        withTimeoutOrNull(limits.totalMs) {
            val response = try { session.request(detailUrl, "$mainUrl/") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { trace.event("DETAIL_FETCH", "HTTP_ERROR"); return@withTimeoutOrNull }
            trace.event("DETAIL_FETCH", "HTTP_STATUS", response.status)
            if (response.status !in 200..299 || response.truncated || !response.mime.contains("html") ||
                KuraKura21MediaPolicy.origin(response.url) != KuraKura21MediaPolicy.origin(mainUrl)) return@withTimeoutOrNull
            val player = KuraKura21PlayerData.parse(response.text()) ?: run {
                trace.event("PLAYER_PARSE", "PLAYER_MISSING"); return@withTimeoutOrNull
            }
            val title = Jsoup.parse(response.text()).selectFirst("h1.entry-title, h1[itemprop=name], .sheader h1, h1")?.text()
            trace.event("PLAYER_PARSE", "TABS_READY", player.tabs.size)
            val visited = mutableSetOf<String>()
            for ((tab, label) in player.tabs) {
                coroutineContext.ensureActive()
                val result = withTimeoutOrNull(limits.serverMs) server@ {
                    try {
                        val ajax = session.request("$mainUrl/wp-admin/admin-ajax.php", response.url,
                            post = KuraKura21PlayerData.ajaxData(tab, player.postId), origin = mainUrl)
                        trace.event("AJAX_FETCH", "HTTP_STATUS", ajax.status)
                        if (ajax.status !in 200..299 || ajax.truncated ||
                            !(ajax.mime.contains("html") || ajax.mime.contains("json"))) return@server null
                        val frames = KuraKura21PlayerData.frames(ajax.text(), response.url)
                        trace.event("IFRAME_PARSE", "FRAMES_READY", frames.size)
                        for (frame in frames.take(5)) {
                            try {
                                val found = resolve(frame, response.url, 0, session, visited, title, label, trace)
                                if (found != null) return@server found
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { trace.event("EXTRACTOR_HTTP", "FRAME_FAILED") }
                        }
                        null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { trace.event("EXTRACTOR_HTTP", "SERVER_FAILED"); null }
                }
                if (result != null) {
                    callback(result.first)
                    result.second.forEach(subtitleCallback)
                    delivered = true
                    break
                }
                trace.event("EXTRACTOR_HTTP", "SERVER_NO_HTTP_MEDIA")
            }
        }
        trace.event("CLEANUP", if (delivered) "HTTP_COMPLETE" else "NO_HTTP_MEDIA", session.requestCount)
        delivered
    }

    private suspend fun resolve(url: String, parent: String, depth: Int, session: KuraKura21HttpSession,
        visited: MutableSet<String>, title: String?, label: String, trace: KuraKura21HttpTrace): Pair<ExtractorLink, List<SubtitleFile>>? {
        coroutineContext.ensureActive()
        if (depth > 2 || !KuraKura21MediaPolicy.isPlayerHost(url) || KuraKura21MediaPolicy.isAd(url) || !visited.add(url)) return null
        if (KuraKura21MediaPolicy.host(url) == "turtle4up.top") {
            var link: ExtractorLink? = null
            val subtitles = mutableListOf<SubtitleFile>()
            KuraKura21TurtleExtractor(session, headers, sourceName, label, title, trace)
                .getUrl(url, parent, { subtitles.add(it) }, { if (link == null) link = it })
            return link?.let { it to subtitles.toList() }
        }
        if (depth == 2) return null
        val page = session.request(url, parent)
        if (page.status !in 200..299 || page.truncated || !page.mime.contains("html")) return null
        for (child in KuraKura21PlayerData.frames(page.text(), page.url).take(3)) {
            try {
                resolve(child, page.url, depth + 1, session, visited, title, label, trace)?.let { return it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { trace.event("EXTRACTOR_HTTP", "NESTED_FRAME_FAILED") }
        }
        // SPA/session-gated players have no verified direct contract. Never emulate their protection.
        trace.event("EXTRACTOR_HTTP", "NO_VERIFIED_HTTP_CONTRACT")
        return null
    }
}

/** Pure parsing/policy code is exercised by the module's offline regression harness. */
internal object KuraKura21PlayerData {
    data class Player(val postId: String, val tabs: List<Pair<String, String>>)

    fun parse(html: String): Player? {
        val player = Jsoup.parse(html).selectFirst("#muvipro_player_content_id") ?: return null
        val id = player.attr("data-id").takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val tabs = player.select("ul.muvipro-player-tabs a[href^='#p']").mapNotNull { link ->
            val tab = link.attr("href").removePrefix("#")
            if (!tab.matches(Regex("p[0-9]+"))) null else tab to link.text().trim().ifBlank { "Server $tab" }
        }.distinctBy { it.first }.take(12)
        return if (tabs.isEmpty()) null else Player(id, tabs)
    }

    fun ajaxData(tab: String, postId: String) = mapOf(
        "action" to "muvipro_player_content", "tab" to tab, "post_id" to postId
    )

    fun frames(raw: String, base: String): List<String> {
        // Muvipro normally returns HTML. Also accept a JSON-encoded HTML string;
        // never scan unrelated script text or every URL in an AJAX response.
        val text = if (raw.trimStart().startsWith('"') || raw.trimStart().startsWith('{')) {
            when (val value = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()) {
                is String -> value
                is JSONObject -> if (value.optBoolean("success")) value.opt("data") as? String else null
                else -> null
            } ?: return emptyList()
        } else raw
        return Jsoup.parseBodyFragment(text, base).select("iframe[src]").mapNotNull {
            KuraKura21MediaPolicy.absolute(it.attr("src"), base)
        }.filterNot(KuraKura21MediaPolicy::isAd).distinct()
    }
}

internal class KuraKura21MediaPolicy(val videoId: String) {
    private val declaredFrames = Collections.synchronizedSet(mutableSetOf<String>())
    private val videoComponent = Regex("${Regex.escape(videoId)}_(?:[a-z0-9]+|,[a-z0-9,]+\\.urlset)")
    fun authorizeFrame(url: String): Boolean {
        val uri = safeUri(url) ?: return false
        val parts = uri.path.trim('/').split('/')
        if (isAd(url) || parts.size != 2 || !parts[0].matches(Regex("pn[0-9]+")) || parts[1] != videoId) return false
        declaredFrames.add(url)
        return true
    }
    fun playerDocument(url: String): Boolean = identity(url) == videoId || synchronized(declaredFrames) {
        declaredFrames.any { sameDocument(it, url) }
    }

    fun mediaPath(url: String): Boolean {
        val uri = safeUri(url) ?: return false
        if (isAd(url) || !(isHls(url) || uri.path.endsWith(".mp4", true))) return false
        // Match path components, not query tokens, substrings, or a CDN name.
        return sameVideoPath(url)
    }

    fun accepts(url: String, referer: String, requestOrigin: String?, observedOrigins: Set<String>): Boolean {
        if (!mediaPath(url)) return false
        val parent = origin(referer) ?: return false
        if (parent !in observedOrigins || isAd(referer)) return false
        if (requestOrigin != null && origin(requestOrigin) != parent) return false
        // A full player referrer must refer to this video; origin-only referrers
        // are valid only when their matching frame was already observed.
        val path = safeUri(referer)?.path.orEmpty()
        return path.isEmpty() || path == "/" || playerDocument(referer)
    }

    fun validPlaylist(text: String, url: String): Boolean {
        if (text.length > 1_000_000 || !mediaPath(url)) return false
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") return false
        var pendingUri = false
        var master = false
        var media = false
        var count = 0
        for (line in lines.drop(1)) {
            if (!line.startsWith('#')) {
                val resolved = absolute(line, url) ?: return false
                if (!pendingUri || isAd(resolved) || !sameVideoPath(resolved)) return false
                pendingUri = false
                count++
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pendingUri || media) return false
                master = true
                pendingUri = true
            } else if (line.startsWith("#EXTINF:")) {
                val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return false
                if (pendingUri || master || !duration.isFinite() || duration <= 0) return false
                media = true
                pendingUri = true
            }
            // Attribute URIs include alternate audio and fMP4 initialization;
            // they are media dependencies, not comments to ignore. Key URLs need
            // not encode the movie ID, but must still be HTTPS and not ads.
            if (line.startsWith("#EXT")) {
                val attributes = line.substringAfter(':', "")
                val uriAttribute = Regex("(?:^|,)\\s*URI=\"([^\"]*)\"").find(attributes)
                if (attributes.contains(Regex("(?:^|,)\\s*URI=")) && uriAttribute == null) return false
                val key = line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")
                if ((line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                        (key && !attributes.split(',').contains("METHOD=NONE"))) && uriAttribute == null) return false
                if (uriAttribute != null) {
                    val dependency = absolute(uriAttribute.groupValues[1], url) ?: return false
                    if (isAd(dependency) || (!key && !sameVideoPath(dependency))) return false
                }
            }
        }
        return count > 0 && !pendingUri && (master || media)
    }

    fun firstVariant(text: String, url: String): String? {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { !it.startsWith('#') }?.let { absolute(it, url) }
            ?.takeIf(::mediaPath)
    }

    private fun sameVideoPath(url: String): Boolean {
        val path = safeUri(url)?.path ?: return false
        val parts = path.split('/')
        return parts.withIndex().any { (index, part) ->
            (index == parts.lastIndex && (part == "$videoId.mp4" || part == "$videoId.m3u8")) ||
                part.matches(videoComponent)
        }
    }

    companion object {
        private val playerHosts = setOf("kr21.click", "n1mwq.org", "turtle4up.top", "k21cdn.click")
        private val adHosts = setOf(
            "sacdnssedge.com", "tsyndicate.com", "mmcdn.com", "jads.co", "stripchat.com",
            "juicyads.com", "exoclick.com", "exosrv.com", "trafficjunky.net",
            "doubleclick.net", "googlesyndication.com"
        )

        fun safeUri(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf {
            it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null &&
                it.port in setOf(-1, 443) && it.normalize().path == it.path &&
                it.path.split('/').none { part -> part == "." || part == ".." } &&
                it.host.contains('.') && it.host.any(Char::isLetter) &&
                !it.host.endsWith(".local", true) && !it.host.endsWith(".localhost", true)
        }
        fun host(url: String): String? = safeUri(url)?.host?.lowercase()
        fun origin(url: String): String? = safeUri(url)?.let { "https://${it.host.lowercase()}" }
        fun isPlayerHost(url: String) = host(url) in playerHosts
        fun identity(url: String): String? {
            val uri = safeUri(url) ?: return null
            if (uri.host.lowercase() !in setOf("kr21.click", "n1mwq.org")) return null
            val parts = uri.path.trim('/').split('/')
            if (!((parts[0] == "e" && parts.size in 2..3) ||
                    (parts[0].matches(Regex("pn[0-9]+")) && parts.size == 2))) return null
            return parts[1].takeIf { it.matches(Regex("[a-zA-Z0-9]{6,64}")) }
        }
        fun mp4Header(bytes: ByteArray): Boolean {
            if (bytes.size < 16) return false
            val size = bytes.take(4).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
            return size >= 16 && bytes.copyOfRange(4, 8).contentEquals(byteArrayOf(102, 116, 121, 112))
        }
        fun isAd(url: String): Boolean {
            val uri = safeUri(url) ?: return true
            val host = uri.host.lowercase()
            return adHosts.any { host == it || host.endsWith(".$it") } ||
                Regex("(?:^|/)(?:ads?|preroll|vast|vpaid|promo|sponsor)(?:/|[-_.])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(uri.path)
        }
        fun absolute(raw: String, base: String): String? = runCatching {
            URI(base).resolve(raw.trim().replace("&amp;", "&")).toString()
        }.getOrNull()?.takeIf { safeUri(it) != null }
        fun isHls(url: String) = safeUri(url)?.path?.endsWith(".m3u8", true) == true
        fun sameDocument(first: String, second: String): Boolean {
            val a = safeUri(first) ?: return false
            val b = safeUri(second) ?: return false
            return origin(first) == origin(second) && a.path == b.path && a.rawQuery == b.rawQuery
        }
    }
}

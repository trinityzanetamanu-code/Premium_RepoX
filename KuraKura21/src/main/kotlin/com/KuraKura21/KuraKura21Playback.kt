package com.kurakura21

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** HTTP handles Muvipro; only the normal, session-owning player runs in a WebView. */
internal class KuraKura21Playback(
    private val mainUrl: String,
    private val headers: Map<String, String>,
    private val context: Context
) {
    private data class Media(val url: String, val headers: Map<String, String>)
    private data class PlayerIdentity(val videoId: String, val frame: String, val parent: String)

    suspend fun load(
        detailUrl: String,
        @Suppress("UNUSED_PARAMETER") subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var emitted = false
        val seen = mutableSetOf<String>()
        // Sequential fallback avoids competing hidden players and bounds the whole operation.
        withTimeoutOrNull(125_000L) {
            val response = app.get(detailUrl, headers = headers, timeout = 12)
            if (response.code !in 200..299) return@withTimeoutOrNull
            val player = KuraKura21PlayerData.parse(response.text) ?: run {
                diagnostic("detail", "player_missing")
                return@withTimeoutOrNull
            }
            for ((tab, label) in player.tabs) {
                coroutineContext.ensureActive()
                val media = withTimeoutOrNull(42_000L) {
                    try {
                        val ajax = app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = KuraKura21PlayerData.ajaxData(tab, player.postId),
                            headers = headers + mapOf(
                                "Referer" to detailUrl,
                                "Origin" to mainUrl,
                                "X-Requested-With" to "XMLHttpRequest",
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ), timeout = 12
                        )
                        if (ajax.code !in 200..299) {
                            diagnostic(tab, "ajax_http_${ajax.code}")
                            return@withTimeoutOrNull null
                        }
                        val frames = KuraKura21PlayerData.frames(ajax.text, detailUrl)
                        for (entry in frames.take(5)) {
                            try {
                                val identity = findIdentity(entry, detailUrl) ?: continue
                                val policy = KuraKura21MediaPolicy(identity.videoId)
                                discoverFrame(identity.frame, identity.parent, policy)
                                // Only the selected browser owns access/playback and attestation.
                                // It stays alive until a candidate is actually validated.
                                val captured = webViewLock.withLock {
                                    withTimeoutOrNull(35_000L) {
                                        browserMedia(detailUrl, entry, tab, policy)
                                    }
                                }
                                if (captured != null) return@withTimeoutOrNull captured
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                diagnostic(tab, "frame_error_${error.javaClass.simpleName}")
                            }
                        }
                        null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Never log exception messages: HTTP errors may include signed URLs.
                        diagnostic(tab, "server_error_${error.javaClass.simpleName}")
                        null
                    }
                }
                if (media != null && seen.add(media.url)) {
                    emit(label, media, callback)
                    emitted = true
                    break // A verified candidate is sufficient; alternatives are fallbacks.
                }
                diagnostic(tab, "no_valid_media")
            }
        }
        emitted
    }

    private suspend fun findIdentity(entry: String, parent: String, depth: Int = 0): PlayerIdentity? {
        KuraKura21MediaPolicy.identity(entry)?.let { return PlayerIdentity(it, entry, parent) }
        if (depth >= 2 || !KuraKura21MediaPolicy.isPlayerHost(entry)) return null
        val response = app.get(entry, headers = headers + ("Referer" to parent), timeout = 8)
        if (response.code !in 200..299 || !KuraKura21MediaPolicy.isPlayerHost(response.url)) return null
        KuraKura21MediaPolicy.identity(response.url)?.let { return PlayerIdentity(it, response.url, parent) }
        for (child in KuraKura21PlayerData.frames(response.text, response.url).take(3)) {
            findIdentity(child, response.url, depth + 1)?.let { return it }
        }
        return null
    }

    private suspend fun discoverFrame(entry: String, parent: String, policy: KuraKura21MediaPolicy) {
        if (KuraKura21MediaPolicy.identity(entry) != policy.videoId) return
        try {
            // Public embed metadata may describe a new player origin. Access/playback
            // endpoints and attestation are deliberately left to the browser session.
            val origin = KuraKura21MediaPolicy.origin(entry) ?: return
            val response = app.get("$origin/api/videos/${policy.videoId}/embed/details",
                headers = headers + mapOf("Referer" to entry, "x-embed-parent" to entry,
                    "x-embed-origin" to URI(parent).host, "x-embed-referer" to "$mainUrl/"), timeout = 5)
            if (response.code !in 200..299 || KuraKura21MediaPolicy.origin(response.url) != origin) return
            val metadata = JSONObject(response.text)
            if (metadata.optString("code") == policy.videoId) {
                policy.authorizeFrame(metadata.optString("embed_frame_url"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Metadata can be session-gated. The real browser may still load it.
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun browserMedia(
        detailUrl: String,
        entry: String,
        tab: String,
        policy: KuraKura21MediaPolicy
    ): Media? {
        var browser: WebView? = null
        val finished = AtomicBoolean(false)
        val entrySeen = AtomicBoolean(false)
        val nestedSeen = AtomicBoolean(false)
        val candidates = Channel<Media>(8)
        val candidateUrls = Collections.synchronizedSet(mutableSetOf<String>())
        var validator: Job? = null
        // Origins are admitted only after an actual matching player document request.
        val playerOrigins = Collections.synchronizedSet(mutableSetOf<String>())
        try {
            return withContext(Dispatchers.Main.immediate) {
                val captured = suspendCancellableCoroutine<Media?> { continuation ->
                    val view = WebView(context)
                    browser = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.mediaPlaybackRequiresUserGesture = false
                    // Keep this WebView's own normal browser cookies. No imported identity/token.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                    val userAgent = view.settings.userAgentString
                    // One bounded, sequential validator. Do not kill this session
                    // merely because its first plausible media request was invalid.
                    validator = launch(Dispatchers.IO) {
                        for (candidate in candidates) {
                            try {
                                coroutineContext.ensureActive()
                                val media = withContext(Dispatchers.Main.immediate) {
                                    val cookie = CookieManager.getInstance().getCookie(candidate.url)
                                    if (cookie.isNullOrBlank()) candidate
                                    else candidate.copy(headers = candidate.headers + ("Cookie" to cookie))
                                }
                                if (validMedia(media, policy)) {
                                    coroutineContext.ensureActive()
                                    if (finished.compareAndSet(false, true)) continuation.resume(media)
                                    break
                                }
                                diagnostic(tab, "media_validation_failed")
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                diagnostic(tab, "candidate_error_${error.javaClass.simpleName}")
                            }
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (KuraKura21MediaPolicy.isAd(url)) return true
                            return request.isForMainFrame &&
                                !KuraKura21MediaPolicy.sameDocument(url, detailUrl) && !policy.playerDocument(url)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (KuraKura21MediaPolicy.sameDocument(url, detailUrl) && !finished.get()) {
                                // Preserve the real Muvipro document/referrer/countdown context.
                                // Click only its selected server and its own Play Now button.
                                view.evaluateJavascript("""
                                    (function(){
                                      if(window.__kura21Started)return;
                                      window.__kura21Started=true;
                                      var tries=0,selected=false;
                                      var timer=setInterval(function(){
                                        if(++tries>65){clearInterval(timer);return;}
                                        if(!selected){
                                          var tab=document.querySelector('a[href="#$tab"]');
                                          if(tab){tab.click();selected=true;}
                                        }
                                        var play=document.getElementById('timeloading');
                                        if(selected&&play&&play.tagName==='BUTTON'&&play.offsetParent!==null)play.click();
                                      },500);
                                    })();
                                """.trimIndent(), null)
                            }
                        }

                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            if (finished.get() || !continuation.isActive || KuraKura21MediaPolicy.isAd(url)) return blocked()
                            val requestHeaders = request.requestHeaders
                            fun header(name: String) = requestHeaders.entries.firstOrNull {
                                it.key.equals(name, ignoreCase = true)
                            }?.value
                            val referer = header("Referer") ?: return null
                            val destination = header("Sec-Fetch-Dest")
                            val isDocument = if (destination != null) destination in setOf("iframe", "document")
                                else header("Accept")?.contains("text/html", true) == true
                            if (isDocument && KuraKura21MediaPolicy.sameDocument(url, entry) &&
                                KuraKura21MediaPolicy.origin(referer) == KuraKura21MediaPolicy.origin(detailUrl)) {
                                entrySeen.set(true)
                                KuraKura21MediaPolicy.origin(url)?.let(playerOrigins::add)
                            }
                            if (!entrySeen.get()) return null

                            if (isDocument && policy.playerDocument(url) &&
                                KuraKura21MediaPolicy.origin(referer) in playerOrigins) {
                                KuraKura21MediaPolicy.origin(url)?.let(playerOrigins::add)
                                if (!KuraKura21MediaPolicy.sameDocument(url, entry)) nestedSeen.set(true)
                            }
                            val origin = header("Origin")
                            val snapshot = synchronized(playerOrigins) { playerOrigins.toSet() }
                            // Path identity + observed frame + referrer; duration is never proof.
                            if (!policy.accepts(url, referer, origin, snapshot)) return null
                            val enqueue = synchronized(candidateUrls) {
                                candidateUrls.size < 8 && candidateUrls.add(url)
                            }
                            if (enqueue) {
                                val playbackHeaders = buildMap {
                                    put("Referer", referer)
                                    put("User-Agent", header("User-Agent") ?: userAgent)
                                    origin?.let { put("Origin", it) }
                                }
                                candidates.trySend(Media(url, playbackHeaders))
                            }
                            return blocked()
                        }
                    }
                    continuation.invokeOnCancellation { finished.set(true) }
                    view.loadUrl(detailUrl, headers.filterKeys { !it.equals("User-Agent", true) })
                }
                captured
            }
        } finally {
            finished.set(true)
            candidates.cancel()
            validator?.cancel()
            // Unlike WebViewResolver's polling loop, cancellation always destroys our view.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                browser?.let { view ->
                    view.stopLoading()
                    view.webViewClient = WebViewClient()
                    view.webChromeClient = null
                    view.removeAllViews()
                    view.destroy()
                }
                browser = null
            }
            diagnostic(tab, when {
                !entrySeen.get() -> "selected_iframe_not_observed"
                !nestedSeen.get() -> "nested_player_not_observed"
                else -> "player_session_finished"
            })
        }
    }

    private suspend fun validMedia(media: Media, policy: KuraKura21MediaPolicy): Boolean {
        if (KuraKura21MediaPolicy.isHls(media.url)) {
            val response = app.get(media.url, headers = media.headers, timeout = 8)
            if (response.code !in 200..299 || !policy.validPlaylist(response.text, response.url)) return false
            if (!response.text.contains("#EXT-X-STREAM-INF:")) return true
            val variant = policy.firstVariant(response.text, response.url) ?: return false
            val child = app.get(variant, headers = media.headers, timeout = 8)
            return child.code in 200..299 && policy.validPlaylist(child.text, child.url) &&
                child.text.contains("#EXTINF:")
        }
        // HEAD may be unsupported; MIME alone can also mislabel an error body.
        // Read only a container prefix, even when the server ignores Range.
        val response = app.get(media.url, headers = media.headers + ("Range" to "bytes=0-31"), timeout = 8)
        return response.okhttpResponse.use {
            val mime = response.headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()
            if (response.code !in setOf(200, 206) || !policy.mediaPath(response.url) ||
                mime !in setOf("video/mp4", "application/mp4")) return@use false
            if (response.code == 206 && !response.headers["Content-Range"].orEmpty().startsWith("bytes 0-")) return@use false
            val source = response.body.source()
            val valid = source.request(16) && KuraKura21MediaPolicy.mp4Header(source.readByteArray(16))
            coroutineContext.ensureActive()
            valid
        }
    }

    private suspend fun emit(label: String, media: Media, callback: (ExtractorLink) -> Unit) {
        callback(newExtractorLink(
            source = "KURAKURA21", name = "KURAKURA21 - $label", url = media.url,
            type = if (KuraKura21MediaPolicy.isHls(media.url)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            referer = media.headers["Referer"].orEmpty()
            headers = media.headers
            quality = Qualities.Unknown.value
        })
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    private fun diagnostic(tab: String, reason: String) = Log.i("KuraKura21", "$tab: $reason")

    private companion object {
        // Shared across provider instances, so concurrent loadLinks calls cannot race players.
        val webViewLock = Mutex()
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

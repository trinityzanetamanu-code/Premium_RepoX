package com.kurakura21

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public client data encoding observed in Turtle4Up's served player bundle.
 * Encodes the selected alias only. Never generates fingerprint, ad/headless status or view beacons. */
internal object KuraKura21TurtleCodec {
    private val keyChars = listOf("𝕊", "𝔼", "𝕮", "𝚁", "𝔼", "𝚃", "𝓚", "𝓔", "𝓨", "𝟙", "𝟚", "𝟛")
    private val saltChars = listOf("🔐", "🗝️", "🔑", "🚪", "🛡️", "⚡")
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun bytes(text: String): ByteArray {
        require(text.length in 32..200_000 && text.length % 32 == 0 && text.matches(Regex("[0-9a-fA-F]+")))
        return text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun cipher(mode: Int, host: String): Cipher {
        val key = (keyChars.joinToString("") { it.codePointAt(0).toString(16) } +
            (System.currentTimeMillis() / 86_400_000L).toString(16)).take(32).padEnd(32, '0')
        val iv = (saltChars.joinToString("") { it.codePointAt(0).toString(16) } +
            hex(host.lowercase().toByteArray(StandardCharsets.UTF_8))).take(32).padEnd(32, '0')
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(mode, SecretKeySpec(bytes(key), "AES"), IvParameterSpec(bytes(iv)))
        }
    }
    fun encode(alias: String, host: String): String = hex(cipher(Cipher.ENCRYPT_MODE, host)
        .doFinal(alias.toByteArray(StandardCharsets.UTF_8)))
    fun decode(value: String, host: String): String = String(cipher(Cipher.DECRYPT_MODE, host)
        .doFinal(bytes(value)), StandardCharsets.UTF_8)
}

internal data class KuraKura21HttpLimits(val totalMs: Long = 55_000L,
    val serverMs: Long = 20_000L, val requests: Int = 24) {
    init { require(totalMs > 0 && serverMs > 0 && requests > 0) }
}

/** Locally generated diagnostics only; no URL, identity, token, cookie or exception message. */
internal class KuraKura21HttpTrace {
    private var events = 0
    @Synchronized fun event(stage: String, reason: String, number: Int? = null) {
        if (events++ >= 64) return
        val s = stage.takeIf { it.matches(Regex("[A-Z_]{1,30}")) } ?: "UNKNOWN"
        val r = reason.takeIf { it.matches(Regex("[A-Z_]{1,40}")) } ?: "UNKNOWN"
        Log.i("KuraKura21", "build=HTTP_80dcfc7 stage=$s reason=$r" + (number?.let { " count_or_status=$it" } ?: ""))
    }
}

internal data class KuraKura21HttpData(val url: String, val status: Int, val mime: String,
    val body: ByteArray, val headers: Headers, val truncated: Boolean) {
    fun text(): String = String(body, StandardCharsets.UTF_8)
}

internal class KuraKura21HttpCookies : CookieJar {
    private val values = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            values.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis() && values.size < 32) values.add(cookie)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        values.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return values.filter { it.matches(url) }
    }
}

/** Fresh normal HTTP cookies are scoped to this load and their actual domain/path.
 * No browser cookies or global identities are imported, exported, or forwarded as manual headers. */
internal class KuraKura21HttpSession(private val defaults: Map<String, String>,
    private val limits: KuraKura21HttpLimits) {
    private val cookies = KuraKura21HttpCookies()
    private val client = app.baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(cookies).connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()
    private var count = 0
    val requestCount: Int get() = count

    suspend fun request(url: String, referer: String, post: Map<String, String>? = null,
        origin: String? = null, media: KuraKura21HttpScope? = null, probe: Boolean = false): KuraKura21HttpData {
        var current = url
        var form = post
        repeat(3) {
            if (count >= limits.requests) throw IOException("Request budget")
            count++
            require(KuraKura21MediaPolicy.safeUri(current) != null && !KuraKura21MediaPolicy.isAd(current))
            if (media != null) require(media.allows(current))
            else require(KuraKura21MediaPolicy.host(current) in playerHosts)
            val builder = Request.Builder().url(current)
            for ((name, value) in defaults) if (name.equals("User-Agent", true) || name.equals("Accept-Language", true))
                builder.header(name, value)
            builder.header("Referer", referer).header("Accept", "*/*")
            origin?.let { value -> builder.header("Origin", value) }
            if (form != null) {
                val body = FormBody.Builder().apply { form!!.forEach { (key, value) -> add(key, value) } }.build()
                builder.post(body).header("X-Requested-With", "XMLHttpRequest")
            }
            if (probe && URI(current).path.endsWith(".mp4", true)) builder.header("Range", "bytes=0-31")
            val result = exchange(builder.build(), probe)
            if (result.status !in setOf(301, 302, 303, 307, 308)) return result
            if (URI(url).path.startsWith("/api/")) throw IOException("API redirect unsupported")
            val next = KuraKura21MediaPolicy.absolute(result.headers["Location"].orEmpty(), current)
                ?: throw IOException("Redirect target")
            // Public player redirects stay within known hosts. Media redirects never leave this exact scope.
            if (media != null) require(media.allows(next))
            else require(KuraKura21MediaPolicy.host(next) in playerHosts)
            if (form != null) require(KuraKura21MediaPolicy.origin(next) == KuraKura21MediaPolicy.origin(url))
            current = next
            if (result.status == 303 || (result.status in setOf(301, 302) && form != null)) form = null
        }
        throw IOException("Redirect budget")
    }

    private suspend fun exchange(request: Request, probe: Boolean): KuraKura21HttpData = suspendCancellableCoroutine { continuation ->
        val timeout = when {
            request.method == "POST" -> 12L
            request.url.encodedPath.startsWith("/api/") -> 8L
            probe || request.url.encodedPath.endsWith(".m3u8") -> 8L
            else -> 12L
        }
        val call = client.newBuilder().callTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val data = response.use {
                        val source = it.body.source()
                        val buffer = Buffer()
                        var maximum = 1_000_000L
                        if (probe) {
                            while (buffer.size < 16 && source.read(buffer, 16 - buffer.size) != -1L) { }
                            if (KuraKura21MediaPolicy.mp4Header(buffer.clone().readByteArray())) maximum = 16
                        }
                        if (maximum != 16L) while (buffer.size <= maximum) {
                            if (source.read(buffer, minOf(8192L, maximum + 1 - buffer.size)) == -1L) break
                        }
                        val truncated = buffer.size > maximum
                        KuraKura21HttpData(it.request.url.toString(), it.code,
                            it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(),
                            buffer.readByteArray(), it.headers, truncated)
                    }
                    if (continuation.isActive) continuation.resume(data)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
    companion object {
        val playerHosts = setOf("kurakura21.com", "www.kurakura21.com", "kr21.click", "n1mwq.org", "turtle4up.top", "k21cdn.click")
    }
}

/** The exact main-media URL is delegated by the selected alias's HTTPS resource response.
 * Restrict that delegation to observed CDN families and its video-specific directory namespace. */
internal class KuraKura21HttpScope(val root: String) {
    private val uri = KuraKura21MediaPolicy.safeUri(root)
    private val prefix = uri?.path?.substringBeforeLast('/', "")?.plus("/").orEmpty()
    private val host = uri?.host?.lowercase()
    val valid = uri != null && !KuraKura21MediaPolicy.isAd(root) &&
        (host == "turtle4up.top" || host?.endsWith(".goldencrestdigital.shop") == true) &&
        prefix.trim('/').split('/').filter(String::isNotEmpty).size >= 2
    fun allows(url: String): Boolean {
        val other = KuraKura21MediaPolicy.safeUri(url) ?: return false
        return valid && other.host.equals(host, true) && other.path.startsWith(prefix) && !KuraKura21MediaPolicy.isAd(url)
    }
    fun dash(text: String, url: String): Boolean {
        if (!allows(url) || text.length > 1_000_000 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
                setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
                    override fun error(error: org.xml.sax.SAXParseException) { throw error }
                    override fun fatalError(error: org.xml.sax.SAXParseException) { throw error }
                })
            }
            val doc = builder.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
            val root = doc.documentElement
            if (root.localName != "MPD" || root.namespaceURI != "urn:mpeg:dash:schema:mpd:2011") return false
            if (doc.getElementsByTagNameNS("*", "ContentProtection").length > 0 ||
                doc.getElementsByTagNameNS("*", "pssh").length > 0 ||
                doc.getElementsByTagNameNS("*", "Representation").length == 0) return false
            val all = doc.getElementsByTagName("*")
            if (all.length > 10_000) return false
            for (index in 0 until all.length) {
                val item = all.item(index) as org.w3c.dom.Element
                if (item.hasAttributeNS("http://www.w3.org/XML/1998/namespace", "base")) return false
                for (attributeIndex in 0 until item.attributes.length) {
                    if (item.attributes.item(attributeIndex).namespaceURI == "http://www.w3.org/1999/xlink") return false
                }
                if (item.localName in setOf("BaseURL", "Location", "PatchLocation")) {
                    if (!allows(KuraKura21MediaPolicy.absolute(item.textContent.trim(), url) ?: return false)) return false
                }
                for (name in listOf("media", "initialization", "sourceURL", "href")) if (item.hasAttribute(name)) {
                    if (!allows(KuraKura21MediaPolicy.absolute(item.getAttribute(name), url) ?: return false)) return false
                }
            }
            true
        } catch (_: Exception) { false }
    }
    fun playlist(text: String, url: String): Boolean {
        if (!allows(url) || text.length > 1_000_000) return false
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") return false
        var pending = false; var count = 0; var master = false; var media = false
        for (line in lines.drop(1)) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pending || media) return false
                pending = true; master = true
            } else if (line.startsWith("#EXTINF:")) {
                val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return false
                if (pending || master || !duration.isFinite() || duration <= 0) return false
                pending = true; media = true
            } else if (!line.startsWith('#')) {
                if (!pending || !allows(KuraKura21MediaPolicy.absolute(line, url) ?: return false)) return false
                pending = false; count++
            }
            if (line.startsWith("#EXT")) {
                if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                    val methods = line.substringAfter(':').split(',').map(String::trim).filter { it.startsWith("METHOD=") }
                    if (methods != listOf("METHOD=NONE")) return false // No unsupported encryption/DRM flow.
                }
                val attributes = line.substringAfter(':', "")
                val uriPattern = Regex("(?:^|,)\\s*URI=\"([^\"]*)\"")
                val uris = uriPattern.findAll(attributes).toList()
                if (Regex("(?:^|,)\\s*URI=").findAll(attributes).count() != uris.size || uris.size > 1) return false
                val uri = uris.singleOrNull()
                if (attributes.contains(Regex("(?:^|,)\\s*URI=")) && uri == null) return false
                if ((line.startsWith("#EXT-X-MAP:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) && uri == null) return false
                if (uri != null && !allows(KuraKura21MediaPolicy.absolute(uri.groupValues[1], url) ?: return false)) return false
            }
        }
        return !pending && count > 0 && (master || media)
    }
}

/** Custom ExtractorApi for the verified public Turtle4Up/Turbovio contract, not StreamSB. */
internal class KuraKura21TurtleExtractor(private val session: KuraKura21HttpSession,
    private val requestHeaders: Map<String, String>, private val sourceName: String,
    private val label: String, private val titleHint: String?, private val trace: KuraKura21HttpTrace) : ExtractorApi() {
    override val name = "Turtle4Up HTTP"
    override val mainUrl = "https://turtle4up.top"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit) {
        val uri = KuraKura21MediaPolicy.safeUri(url) ?: return
        if (uri.host.lowercase() != "turtle4up.top") return
        val parts = uri.path.trim('/').split('/')
        if (parts.size != 1 || !parts[0].matches(Regex("[A-Za-z0-9_-]{4,64}")) || uri.fragment != null) return
        val alias = parts[0]
        val parsed = HttpUrl.Builder().scheme("https").host(uri.host).addPathSegment("api").addPathSegment("stream")
        parsed.addQueryParameter("s", alias)
        val query = requestQuery(url)
        if (query.keys.any { it != "pv" }) return
        val pv = query["pv"]
        if (pv != null && !pv.matches(Regex("[A-Za-z0-9._-]{1,64}"))) return
        pv?.let { parsed.addQueryParameter("pv", it) }
        val metadata = session.request(parsed.build().toString(), url)
        trace.event("EXTRACTOR_HTTP", "METADATA_STATUS", metadata.status)
        if (metadata.status !in 200..299 || metadata.truncated || !metadata.mime.contains("json")) return
        val data = JSONObject(metadata.text()).optJSONObject("data") ?: return
        val title = (data.opt("title") as? String)?.trim() ?: return
        if (title.isEmpty() || !matchesTitle(titleHint, title)) {
            trace.event("IDENTITY_CHECK", "TITLE_MISMATCH"); return
        }
        val api = HttpUrl.Builder().scheme("https").host(uri.host).addPathSegment("api").addPathSegment("stream")
            .addPathSegment("tracking").addQueryParameter("c", KuraKura21TurtleCodec.encode(alias, uri.host))
        pv?.let { api.addQueryParameter("pv", it) }
        val resource = session.request(api.build().toString(), url)
        trace.event("EXTRACTOR_HTTP", "RESOURCE_STATUS", resource.status)
        if (resource.status !in 200..299 || resource.truncated) return
        val encoded = resource.headers["x-tracking"] ?: return
        val stream = JSONObject(KuraKura21TurtleCodec.decode(encoded, uri.host)).optJSONObject("stream") ?: return
        for (field in listOf("hls", "tiktok", "direct")) {
            val candidate = stream.opt(field) as? String ?: continue
            val scope = KuraKura21HttpScope(candidate)
            if (!scope.valid) { trace.event("MEDIA_REJECT", "UNTRUSTED_SCOPE"); continue }
            val origin = KuraKura21MediaPolicy.origin(url) ?: return
            val type = try { validate(candidate, url, origin, scope) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { trace.event("MEDIA_REJECT", "CANDIDATE_HTTP_ERROR"); null } ?: continue
            val playbackHeaders = requestHeaders.filterKeys { it.equals("User-Agent", true) || it.equals("Accept-Language", true) } +
                mapOf("Referer" to url, "Origin" to origin)
            callback(newExtractorLink(source = sourceName, name = "$sourceName - $label (HTTP)", url = candidate, type = type) {
                this.referer = url
                headers = playbackHeaders
                quality = Qualities.Unknown.value
            })
            trace.event("EXTRACTOR_EMIT", "DELIVERED")
            val subtitles = data.optJSONArray("subtitles")
            if (subtitles != null) for (i in 0 until minOf(subtitles.length(), 20)) {
                val sub = subtitles.optJSONObject(i) ?: continue
                val target = sub.optString("url")
                if (scope.allows(target)) subtitleCallback(newSubtitleFile(sub.optString("lang").ifBlank { "Unknown" }, target))
            }
            return
        }
    }

    private suspend fun validate(url: String, referer: String, origin: String, scope: KuraKura21HttpScope): ExtractorLinkType? {
        val response = session.request(url, referer, origin = origin, media = scope, probe = true)
        if (response.status !in 200..299 || response.truncated) { trace.event("MEDIA_REJECT", "MEDIA_HTTP", response.status); return null }
        if (KuraKura21MediaPolicy.mp4Header(response.body)) {
            if (response.mime !in setOf("video/mp4", "application/mp4")) return null
            if (response.status == 206 && !response.headers["Content-Range"].orEmpty().startsWith("bytes 0-")) return null
            return ExtractorLinkType.VIDEO
        }
        if (response.mime in setOf("application/dash+xml", "application/xml", "text/xml") ||
            response.mime in setOf("text/plain", "application/octet-stream") &&
                response.text().trimStart().let { it.startsWith("<MPD") || it.startsWith("<?xml") }) {
            if (scope.dash(response.text(), response.url)) return ExtractorLinkType.DASH
            trace.event("MEDIA_REJECT", "DASH_SCOPE_OR_DRM"); return null
        }
        if (!hlsMime(response.mime) || !scope.playlist(response.text(), response.url)) {
            trace.event("MEDIA_REJECT", "MANIFEST_SCOPE"); return null
        }
        val lines = response.text().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        if (index >= 0) {
            val target = lines.drop(index + 1).firstOrNull { !it.startsWith('#') }
                ?.let { KuraKura21MediaPolicy.absolute(it, response.url) } ?: return null
            if (!scope.allows(target)) return null
            val child = session.request(target, referer, origin = origin, media = scope)
            if (child.status !in 200..299 || child.truncated || !hlsMime(child.mime) || !scope.playlist(child.text(), child.url) ||
                !child.text().lineSequence().any { it.startsWith("#EXTINF:") }) return null
        }
        return ExtractorLinkType.M3U8
    }

    companion object {
        private fun hlsMime(mime: String) = mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl",
            "audio/mpegurl", "audio/x-mpegurl", "text/plain", "application/octet-stream")
        fun matchesTitle(expected: String?, actual: String): Boolean {
            // A catalogue code, when present, adds an independent wrong-title check.
            val id = expected?.let { Regex("\\b([A-Z]{2,8})[- ]([0-9]{2,6})\\b").find(it) } ?: return true
            return Regex("(?i)(?<![A-Z0-9])${Regex.escape(id.groupValues[1])}[ _-]*${Regex.escape(id.groupValues[2])}(?![0-9])").containsMatchIn(actual)
        }
        private fun requestQuery(url: String): Map<String, String> {
            val uri = HttpUrl.Builder().scheme("https").host("turtle4up.top").build().resolve(url) ?: return emptyMap()
            return uri.queryParameterNames.associateWith { uri.queryParameter(it).orEmpty() }
        }
    }
}

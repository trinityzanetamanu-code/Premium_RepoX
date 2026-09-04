package com.streamzy

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

class StreamzyProvider(
    private val applicationContext: Context
) : MainAPI() {

    override var mainUrl = "https://streamzy.org"
    override var name = "Streamzy"
    override var lang = "en"

    override val hasMainPage = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        private const val POSTER_CDN =
            "https://image.tmdb.org/t/p/w500"

        private const val LOG_TAG = "Streamzy"
        private const val MAX_RESOLVER_DEPTH = 6
        private const val MAX_RESOLVER_PAGES = 12
        private const val MEDIA_PROBE_LIMIT = 131_072
        private const val WASM_TIMEOUT_MS = 15_000L
    }

    private data class GateApiResponse(
        @param:JsonProperty("src")
        val src: String? = null
    )

    private data class StreamApiResponse(
        @param:JsonProperty("data")
        val data: StreamApiData? = null,

        @param:JsonProperty("vs")
        val vs: WasmEnvelope? = null
    )

    private data class StreamApiData(
        @param:JsonProperty("stream_urls")
        val streamUrls: Any? = null
    )

    private data class WasmEnvelope(
        @param:JsonProperty("wasm_url")
        val wasmUrl: String? = null,

        @param:JsonProperty("wasm")
        val inlineWasm: String? = null
    )

    private data class PlayerRuntimeConfig(
        val playerUrl: String,
        val exactApi: String?,
        val streamBase: String?,
        val metaApi: String?,
        val season: String,
        val episode: String
    )

    private data class MediaCandidate(
        val url: String,
        val type: ExtractorLinkType,
        val referer: String,
        val headers: Map<String, String>
    )

    private data class MediaProbe(
        val code: Int,
        val contentType: String,
        val bytes: ByteArray
    )

    private data class ResolverBudget(
        var pages: Int = 0
    )

    private data class PeachifyApiResponse(
        @param:JsonProperty("sources")
        val sources: List<PeachifySource> = emptyList()
    )

    private data class PeachifySource(
        @param:JsonProperty("url")
        val url: String? = null,

        @param:JsonProperty("type")
        val type: String? = null,

        @param:JsonProperty("dub")
        val dub: String? = null,

        @param:JsonProperty("headers")
        val upstreamHeaders: Map<String, String>? = null
    )

    private data class PeachifySubtitle(
        @param:JsonProperty("url")
        val url: String? = null,

        @param:JsonProperty("display")
        val display: String? = null,

        @param:JsonProperty("language")
        val language: String? = null,

        @param:JsonProperty("format")
        val format: String? = null,

        @param:JsonProperty("isHearingImpaired")
        val isHearingImpaired: Boolean? = null
    )

    private data class PeachifyContent(
        val kind: String,
        val apiPath: String
    )

    private data class PeachifyServerLink(
        val hrefForm: String,
        val resolvedUrl: String
    )

    private class WasmBridge(
        private val successCallback: (String) -> Unit,
        private val failureCallback: (String) -> Unit
    ) {
        @JavascriptInterface
        fun success(value: String) {
            successCallback(value)
        }

        @JavascriptInterface
        fun failure(value: String) {
            failureCallback(value)
        }
    }

    // ============================================================
    // SEARCH MODELS
    // ============================================================

    data class SearchApiResponse(
        @param:JsonProperty("results")
        val results: List<SearchApiItem> = emptyList()
    )

    data class SearchApiItem(
        @param:JsonProperty("id")
        val id: Int = 0,

        @param:JsonProperty("media_type")
        val mediaType: String = "",

        @param:JsonProperty("title")
        val title: String = "",

        @param:JsonProperty("release_date")
        val releaseDate: String? = null,

        @param:JsonProperty("poster_path")
        val posterPath: String? = null,

        @param:JsonProperty("vote_average")
        val voteAverage: Double? = null
    )

    private data class StreamzySeasonInfo(
        val season: Int,
        val episodeCount: Int
    )

    // ============================================================
    // MAIN PAGE
    // ============================================================

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Popular Movies",
        "$mainUrl/tv" to "Popular TV Shows",

        "$mainUrl/trending" to "Trending",

        "$mainUrl/new-releases" to "New Movies",
        "$mainUrl/new-releases?type=tv" to "New TV Shows",

        "$mainUrl/top-rated" to "Top Rated Movies",
        "$mainUrl/top-rated?type=tv" to "Top Rated TV Shows",

        "$mainUrl/now-playing" to "Now Playing",
        "$mainUrl/upcoming" to "Upcoming Movies",

        "$mainUrl/genre/action?type=movie" to "Action Movies",
        "$mainUrl/genre/adventure?type=movie" to "Adventure Movies",
        "$mainUrl/genre/comedy?type=movie" to "Comedy Movies",
        "$mainUrl/genre/crime?type=movie" to "Crime Movies",
        "$mainUrl/genre/drama?type=movie" to "Drama Movies",
        "$mainUrl/genre/fantasy?type=movie" to "Fantasy Movies",
        "$mainUrl/genre/horror?type=movie" to "Horror Movies",
        "$mainUrl/genre/mystery?type=movie" to "Mystery Movies",
        "$mainUrl/genre/romance?type=movie" to "Romance Movies",
        "$mainUrl/genre/sci-fi?type=movie" to "Sci-Fi Movies",
        "$mainUrl/genre/thriller?type=movie" to "Thriller Movies",

        "$mainUrl/genre/action-adventure?type=tv" to
            "Action & Adventure TV",

        "$mainUrl/genre/comedy?type=tv" to
            "Comedy TV Shows",

        "$mainUrl/genre/crime?type=tv" to
            "Crime TV Shows",

        "$mainUrl/genre/mystery?type=tv" to
            "Mystery TV Shows",

        "$mainUrl/genre/reality?type=tv" to
            "Reality TV Shows",

        "$mainUrl/country/kr?type=movie" to "Korean Movies",
        "$mainUrl/country/kr?type=tv" to "Korean TV Shows",

        "$mainUrl/country/jp?type=movie" to "Japanese Movies",
        "$mainUrl/country/jp?type=tv" to "Japanese TV Shows",

        "$mainUrl/country/in?type=movie" to "Indian Movies",

        "$mainUrl/country/us?type=movie" to "American Movies"
    )

    // ============================================================
    // HELPERS
    // ============================================================

    private fun buildPageUrl(
        baseUrl: String,
        page: Int
    ): String {

        if (page <= 1) {
            return baseUrl
        }

        return if (baseUrl.contains("?")) {
            "$baseUrl&page=$page"
        } else {
            "$baseUrl?page=$page"
        }
    }

    private fun absoluteUrl(
        url: String?
    ): String? {

        if (url.isNullOrBlank()) {
            return null
        }

        val clean = url.trim()

        return when {
            clean.startsWith("https://") ->
                clean

            clean.startsWith("http://") ->
                clean

            clean.startsWith("//") ->
                "https:$clean"

            clean.startsWith("/") ->
                "$mainUrl$clean"

            else ->
                "$mainUrl/$clean"
        }
    }

    private fun absolutePosterUrl(
        url: String?
    ): String? {

        if (url.isNullOrBlank()) {
            return null
        }

        val clean = url.trim()

        return when {
            clean.startsWith("https://") ->
                clean

            clean.startsWith("http://") ->
                clean

            clean.startsWith("//") ->
                "https:$clean"

            clean.startsWith("/") ->
                "$mainUrl$clean"

            else ->
                "$mainUrl/$clean"
        }
    }

    private fun buildWatchUrl(
        data: String
    ): String? {

        val clean = data.trim()

        Regex(
            """^streamzy://movie/(\d+)$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { movieId ->
                return "$mainUrl/watch/movie/$movieId"
            }

        Regex(
            """^streamzy://episode/(\d+)/(\d+)/(\d+)$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(clean)
            ?.let { match ->

                val tvId =
                    match.groupValues[1]

                val season =
                    match.groupValues[2]

                val episode =
                    match.groupValues[3]

                return "$mainUrl/watch/tv/" +
                    "$tvId/$season/$episode"
            }

        Regex(
            """/watch/movie/(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { movieId ->
                return "$mainUrl/watch/movie/$movieId"
            }

        Regex(
            """/watch/tv/(\d+)/(\d+)/(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(clean)
            ?.let { match ->

                val tvId =
                    match.groupValues[1]

                val season =
                    match.groupValues[2]

                val episode =
                    match.groupValues[3]

                return "$mainUrl/watch/tv/" +
                    "$tvId/$season/$episode"
            }

        Regex(
            """/movie/(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { movieId ->
                return "$mainUrl/watch/movie/$movieId"
            }

        return null
    }

    private fun logMarker(message: String) {
        Log.i(LOG_TAG, message)
    }

    private fun safeHost(url: String?): String {
        if (url.isNullOrBlank()) {
            return "-"
        }

        return try {
            URI(url).host?.lowercase() ?: "invalid"
        } catch (_: Exception) {
            "invalid"
        }
    }

    private fun getPeachifyContent(
        embedUrl: String
    ): PeachifyContent? {

        val parsed =
            try {
                URI(embedUrl)
            } catch (_: Exception) {
                return null
            }

        if (!parsed.host.equals("peachify.top", true)) {
            return null
        }

        val path = parsed.path.orEmpty()

        Regex(
            """^/embed/movie/(\d+)/?$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { tmdbId ->
                return PeachifyContent(
                    kind = "movie",
                    apiPath = "movie/$tmdbId"
                )
            }

        Regex(
            """^/embed/tv/(\d+)/(\d+)/(\d+)/?$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(path)
            ?.let { match ->
                return PeachifyContent(
                    kind = "series",
                    apiPath =
                        "tv/${match.groupValues[1]}/" +
                            "${match.groupValues[2]}/" +
                            match.groupValues[3]
                )
            }

        return null
    }

    private fun isPeachifyServerPage(
        url: String
    ): Boolean {

        return try {
            URI(url)
                .rawQuery
                ?.split("&")
                ?.any { part ->
                    part
                        .substringBefore("=")
                        .equals("server", true) &&
                        part
                            .substringAfter(
                                delimiter = "=",
                                missingDelimiterValue = ""
                            )
                            .equals("peachify", true)
                } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun peachifyHrefForm(
        href: String
    ): String {

        val clean = href.trim()

        return when {
            clean.startsWith("?") ->
                "query-relative"

            clean.startsWith("https://", true) ||
                clean.startsWith("http://", true) ||
                clean.startsWith("//") ->
                "absolute"

            else ->
                "path-relative"
        }
    }

    private fun peachifyWatchPathKind(
        url: String
    ): String {

        val path =
            try {
                URI(url).path.orEmpty()
            } catch (_: Exception) {
                return "other"
            }

        return when {
            Regex(
                """^/watch/movie/\d+/?$""",
                RegexOption.IGNORE_CASE
            ).matches(path) ->
                "movie"

            Regex(
                """^/watch/tv/\d+/\d+/\d+/?$""",
                RegexOption.IGNORE_CASE
            ).matches(path) ->
                "tv"

            else ->
                "other"
        }
    }

    private fun getPeachifyFallbackEmbed(
        watchUrl: String
    ): String? {

        val parsed =
            try {
                URI(watchUrl)
            } catch (_: Exception) {
                return null
            }

        if (!parsed.host.equals(URI(mainUrl).host, true)) {
            return null
        }

        val path = parsed.path.orEmpty()

        Regex(
            """^/watch/movie/(\d+)/?$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(path)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { tmdbId ->
                return "https://peachify.top/embed/movie/$tmdbId"
            }

        Regex(
            """^/watch/tv/(\d+)/(\d+)/(\d+)/?$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(path)
            ?.let { match ->
                return "https://peachify.top/embed/tv/" +
                    "${match.groupValues[1]}/" +
                    "${match.groupValues[2]}/" +
                    match.groupValues[3]
            }

        return null
    }

    private fun peachifyLabel(
        value: String?
    ): String {

        val clean =
            value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Source"

        return clean
            .replace(Regex("\\s+"), " ")
            .replace("|", " ")
            .take(80)
            .replaceFirstChar { character ->
                if (character.isLowerCase()) {
                    character.titlecase()
                } else {
                    character.toString()
                }
            }
    }

    private fun validHttpUrl(
        value: String?
    ): String? {

        val raw = value ?: return null

        if (
            raw.isBlank() ||
            raw != raw.trim()
        ) {
            return null
        }

        return try {
            val parsed = URI(raw)
            raw.takeIf {
                parsed.host != null &&
                    (
                        parsed.scheme.equals("https", true) ||
                            parsed.scheme.equals("http", true)
                        )
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolvePeachifySubtitles(
        embedUrl: String,
        content: PeachifyContent,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {

        val subtitleUrl =
            "https://none.eat-peach.sbs/subs/" +
                content.apiPath

        val response =
            try {
                app.get(
                    url = subtitleUrl,
                    headers = mapOf(
                        "Accept" to "application/json"
                    ),
                    referer = embedUrl
                )
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                        "CONTENT=${content.kind}|" +
                        "SUBTITLE_HTTP=FAILED|" +
                        "SUBTITLE_COUNT=0|" +
                        "SUBTITLE_CALLBACKS=0|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return 0
            }

        val httpCode = response.okhttpResponse.code
        val subtitles =
            if (httpCode in 200..299) {
                response
                    .parsedSafe<List<PeachifySubtitle>>()
                    .orEmpty()
            } else {
                emptyList()
            }

        val emittedUrls = mutableSetOf<String>()
        var subtitleCallbacks = 0

        subtitles.forEachIndexed { index, subtitle ->
            val url =
                validHttpUrl(subtitle.url)
                    ?: return@forEachIndexed

            if (!emittedUrls.add(url)) {
                return@forEachIndexed
            }

            val label =
                peachifyLabel(
                    subtitle.display
                        ?.takeIf { it.isNotBlank() }
                        ?: subtitle.language
                            ?.takeIf { it.isNotBlank() }
                        ?: "Subtitle ${index + 1}"
                )

            subtitleCallback(
                SubtitleFile(
                    label,
                    url
                )
            )

            subtitleCallbacks += 1
        }

        logMarker(
            "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                "CONTENT=${content.kind}|" +
                "SUBTITLE_HTTP=$httpCode|" +
                "SUBTITLE_COUNT=${subtitles.size}|" +
                "SUBTITLE_CALLBACKS=$subtitleCallbacks"
        )

        return subtitleCallbacks
    }

    private suspend fun resolvePeachify(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val content =
            getPeachifyContent(embedUrl)
                ?: return false

        logMarker(
            "STREAMZY_PEACHIFY|STAGE=iframe|" +
                "CONTENT=${content.kind}|" +
                "HOST=${safeHost(embedUrl)}"
        )

        val outerHeaders =
            mapOf(
                "Referer" to embedUrl,
                "Origin" to "https://peachify.top"
            )

        var callbackCount = 0
        val emittedUrls = mutableSetOf<String>()

        for (route in listOf("air", "holly")) {
            val apiUrl =
                "https://none.eat-peach.sbs/" +
                    "$route/${content.apiPath}"

            val response =
                try {
                    app.get(
                        url = apiUrl,
                        headers = mapOf(
                            "Accept" to "application/json"
                        ),
                        referer = embedUrl
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        throw error
                    }

                    logMarker(
                        "STREAMZY_PEACHIFY|STAGE=api|" +
                            "CONTENT=${content.kind}|" +
                            "ROUTE=$route|" +
                            "RESULT=fail|" +
                            "ERROR=${error.javaClass.simpleName}"
                    )

                    continue
                }

            val httpCode =
                response.okhttpResponse.code

            val payload =
                if (httpCode in 200..299) {
                    response.parsedSafe<PeachifyApiResponse>()
                } else {
                    null
                }

            val sources =
                payload
                    ?.sources
                    .orEmpty()

            logMarker(
                "STREAMZY_PEACHIFY|STAGE=api|" +
                    "CONTENT=${content.kind}|" +
                    "ROUTE=$route|" +
                    "HTTP=$httpCode|" +
                    "SOURCE_COUNT=${sources.size}"
            )

            val hlsSources =
                sources
                    .filter { source ->
                        !source.url.isNullOrBlank() &&
                            source.type.equals(
                                "hls",
                                true
                            )
                    }

            logMarker(
                "STREAMZY_PEACHIFY|STAGE=parse|" +
                    "CONTENT=${content.kind}|" +
                    "ROUTE=$route|" +
                    "SOURCE_COUNT=${sources.size}|" +
                    "HLS_COUNT=${hlsSources.size}"
            )

            val uniqueHlsSources =
                hlsSources
                    .filter { source ->
                        val sourceUrl = source.url
                        sourceUrl != null &&
                            sourceUrl !in emittedUrls
                    }
                    .distinctBy { source ->
                        source.url
                    }

            val baseLabels =
                uniqueHlsSources.map { source ->
                    peachifyLabel(source.dub)
                }

            val labelCounts =
                baseLabels.groupingBy { label ->
                    label
                }.eachCount()

            val labelOccurrences = mutableMapOf<String, Int>()

            uniqueHlsSources.forEachIndexed { index, source ->
                val sourceUrl = source.url ?: return@forEachIndexed

                if (!emittedUrls.add(sourceUrl)) {
                    return@forEachIndexed
                }

                val baseLabel = baseLabels[index]
                val occurrence =
                    (labelOccurrences[baseLabel] ?: 0) + 1

                labelOccurrences[baseLabel] = occurrence

                val label =
                    if ((labelCounts[baseLabel] ?: 0) > 1) {
                        "$baseLabel $occurrence"
                    } else {
                        baseLabel
                    }

                callback(
                    newExtractorLink(
                        source = name,
                        name = "Peachify - $label",
                        url = sourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = embedUrl
                        quality = Qualities.Unknown.value
                        headers = outerHeaders
                    }
                )

                callbackCount += 1

                logMarker(
                    "STREAMZY_PEACHIFY|STAGE=callback|" +
                        "CONTENT=${content.kind}|" +
                        "ROUTE=$route|" +
                        "COUNT=$callbackCount|" +
                        "HOST=${safeHost(sourceUrl)}|" +
                        "TYPE=hls|" +
                        "LABEL=$label|" +
                        "LABEL_GROUP_COUNT=${labelCounts[baseLabel] ?: 1}|" +
                        "UPSTREAM_HEADERS_FORWARDED=false"
                )
            }

            if (callbackCount > 0) {
                break
            }
        }

        try {
            resolvePeachifySubtitles(
                embedUrl = embedUrl,
                content = content,
                subtitleCallback = subtitleCallback
            )
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                    "CONTENT=${content.kind}|" +
                    "SUBTITLE_HTTP=FAILED|" +
                    "SUBTITLE_COUNT=0|" +
                    "SUBTITLE_CALLBACKS=0|" +
                    "ERROR=${error.javaClass.simpleName}"
            )
        }

        logMarker(
            "STREAMZY_PEACHIFY|STAGE=done|" +
                "CONTENT=${content.kind}|" +
                "CALLBACKS=$callbackCount|" +
                "RESULT=${if (callbackCount > 0) "success" else "no-links"}"
        )

        return callbackCount > 0
    }

    private fun resolveHttpUrl(
        baseUrl: String,
        value: String?
    ): String? {

        val clean =
            value
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val uriSafe =
            clean.replace(
                " ",
                "%20"
            )

        return try {
            val resolved =
                when {
                    uriSafe.startsWith("//") ->
                        "https:$uriSafe"

                    uriSafe.startsWith("?") -> {
                        val base = URI(baseUrl)
                        URI(
                            base.scheme,
                            base.authority,
                            base.path,
                            null,
                            null
                        ).toString() + uriSafe
                    }

                    else ->
                        URI(baseUrl).resolve(uriSafe).toString()
                }

            val parsed = URI(resolved)

            resolved.takeIf {
                parsed.host != null &&
                    (
                        parsed.scheme.equals("https", true) ||
                            parsed.scheme.equals("http", true)
                        )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getIframeUrls(
        document: Document,
        baseUrl: String
    ): List<String> {

        return document
            .select(
                "iframe[src], iframe[data-src]"
            )
            .mapNotNull { iframe ->

                val rawUrl =
                    iframe
                        .attr("src")
                        .trim()
                        .ifBlank {
                            iframe
                                .attr("data-src")
                                .trim()
                        }

                resolveHttpUrl(
                    baseUrl,
                    rawUrl
                )
            }
            .distinct()
    }

    private fun decodeJsValue(value: String): String {
        var output =
            value
                .replace("\\/", "/")
                .replace("\\x26", "&", true)
                .replace("&amp;", "&")

        output =
            Regex(
                """\\u([0-9a-fA-F]{4})"""
            ).replace(output) { match ->
                match
                    .groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }

        return output
    }

    private fun getConfigString(
        source: String,
        key: String
    ): String? {

        val escapedKey = Regex.escape(key)

        val patterns = listOf(
            Regex(
                """(?:["']$escapedKey["']|\b$escapedKey\b)\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """(?:CONFIG|CFG)\.$escapedKey\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
        )

        return patterns
            .asSequence()
            .mapNotNull { pattern ->
                pattern
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .map(::decodeJsValue)
            .firstOrNull {
                it.isNotBlank()
            }
    }

    private fun getConfigScalar(
        source: String,
        key: String
    ): String? {

        getConfigString(
            source,
            key
        )?.let {
            return it
        }

        val escapedKey = Regex.escape(key)

        return Regex(
            """(?:["']$escapedKey["']|\b$escapedKey\b)\s*:\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun encodeComponent(value: String): String {
        return URLEncoder
            .encode(value, "UTF-8")
            .replace("+", "%20")
    }

    private fun originOf(url: String): String? {
        return try {
            val parsed = URI(url)
            val authority =
                parsed.rawAuthority
                    ?: return null

            "${parsed.scheme}://$authority"
        } catch (_: Exception) {
            null
        }
    }

    private fun isHtmlPayload(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }

        val prefix =
            bytes
                .copyOfRange(
                    0,
                    minOf(bytes.size, 4096)
                )
                .toString(Charsets.UTF_8)
                .trimStart()
                .lowercase()

        return prefix.startsWith("<!doctype") ||
            prefix.startsWith("<html") ||
            prefix.contains("<title>access denied") ||
            prefix.contains("cf-chl-") ||
            prefix.contains("cloudflare ray id")
    }

    private fun isMpegTs(bytes: ByteArray): Boolean {
        if (bytes.size < 188 * 3) {
            return false
        }

        return bytes[0] == 0x47.toByte() &&
            bytes[188] == 0x47.toByte() &&
            bytes[376] == 0x47.toByte()
    }

    private fun isMp4(bytes: ByteArray): Boolean {
        return bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    private fun isM3u8(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || isHtmlPayload(bytes)) {
            return false
        }

        val text =
            bytes
                .toString(Charsets.UTF_8)
                .removePrefix("\uFEFF")
                .trimStart()

        return text.startsWith("#EXTM3U") &&
            (
                text.contains("#EXT-X-STREAM-INF") ||
                    text.contains("#EXTINF") ||
                    text.contains("#EXT-X-PART")
                )
    }

    private fun readLimited(
        input: java.io.InputStream,
        limit: Int
    ): ByteArray {

        val buffer = ByteArray(limit)
        var total = 0

        while (total < limit) {
            val count =
                input.read(
                    buffer,
                    total,
                    limit - total
                )

            if (count <= 0) {
                break
            }

            total += count
        }

        return buffer.copyOf(total)
    }

    private suspend fun probeMedia(
        url: String,
        referer: String?,
        requestHeaders: Map<String, String>
    ): MediaProbe? {

        return try {
            val response =
                app.get(
                    url = url,
                    headers =
                        requestHeaders +
                            mapOf(
                                "Range" to
                                    "bytes=0-${MEDIA_PROBE_LIMIT - 1}"
                            ),
                    referer = referer
                )

            val body = response.body

            val bytes =
                body
                    .byteStream()
                    .use { input ->
                        readLimited(
                            input,
                            MEDIA_PROBE_LIMIT
                        )
                    }

            body.close()

            MediaProbe(
                code = response.okhttpResponse.code,
                contentType =
                    response
                        .okhttpResponse
                        .header("Content-Type")
                        .orEmpty(),
                bytes = bytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "HLS_RESOLVE|STAGE=probe|" +
                    "HOST=${safeHost(url)}|" +
                    "RESULT=fail|" +
                    "ERROR=${error.javaClass.simpleName}"
            )

            null
        }
    }

    private suspend fun validateMediaCandidate(
        url: String,
        referer: String?,
        headers: Map<String, String>
    ): MediaCandidate? {

        val response =
            probeMedia(
                url,
                referer,
                headers
            ) ?: return null

        val success =
            response.code == 200 ||
                response.code == 206

        val type =
            when {
                success &&
                    !isHtmlPayload(response.bytes) &&
                    isM3u8(response.bytes) ->
                    ExtractorLinkType.M3U8

                success &&
                    !isHtmlPayload(response.bytes) &&
                    (
                        isMp4(response.bytes) ||
                            isMpegTs(response.bytes)
                        ) ->
                    ExtractorLinkType.VIDEO

                else ->
                    null
            }

        logMarker(
            "HLS_RESOLVE|HOST=${safeHost(url)}|" +
                "HTTP=${response.code}|" +
                "CONTENT_TYPE=${response.contentType.take(80)}|" +
                "PLAYLIST=${if (type == ExtractorLinkType.M3U8) "yes" else "no"}|" +
                "RESULT=${if (type != null) "pass" else "fail"}"
        )

        return type?.let {
            MediaCandidate(
                url = url,
                type = it,
                referer = referer.orEmpty(),
                headers = headers
            )
        }
    }

    private fun parseToken(body: String): String? {
        val clean = body.trim()

        if (
            clean.isBlank() ||
            clean.startsWith("<") ||
            clean.length > 8192
        ) {
            return null
        }

        if (
            clean.startsWith("\"") &&
            clean.endsWith("\"") &&
            clean.length > 2
        ) {
            return clean
                .substring(1, clean.length - 1)
                .replace("\\\"", "\"")
                .takeIf { it.isNotBlank() }
        }

        if (clean.startsWith("{")) {
            return Regex(
                """["'](?:token|data|string|result)["']\s*:\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
                .find(clean)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
        }

        return clean
    }

    private suspend fun generateToken(
        mediaUrl: String,
        playerUrl: String
    ): String? {

        val origin =
            originOf(mediaUrl)
                ?: return null

        val endpoint = "$origin/generate.php"

        return try {
            val response =
                app.get(
                    url = endpoint,
                    referer = playerUrl
                )

            val token =
                if (
                    response.okhttpResponse.code in
                    200..299
                ) {
                    parseToken(response.text)
                } else {
                    null
                }

            logMarker(
                "TOKEN_FLOW|TOKEN_GENERATED|" +
                    "STATUS=${if (token != null) "pass" else "fail"}|" +
                    "TOKEN_HOST=${safeHost(mediaUrl)}|" +
                    "HTTP=${response.okhttpResponse.code}"
            )

            token
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "TOKEN_FLOW|TOKEN_GENERATED|" +
                    "STATUS=fail|" +
                    "TOKEN_HOST=${safeHost(mediaUrl)}|" +
                    "ERROR=${error.javaClass.simpleName}"
            )

            null
        }
    }

    private fun applyToken(
        mediaUrl: String,
        token: String
    ): Pair<String, String> {

        return if (mediaUrl.contains("__TOKEN__")) {
            mediaUrl.replace(
                "__TOKEN__",
                token
            ) to "placeholder-replace"
        } else {
            mediaUrl +
                if (mediaUrl.contains("?")) {
                    "&token=$token"
                } else {
                    "?token=$token"
                } to "query-append"
        }
    }

    private fun queryValue(
        url: String,
        key: String
    ): String? {
        return try {
            URI(url)
                .rawQuery
                ?.split("&")
                ?.firstOrNull { part ->
                    part.substringBefore("=") == key
                }
                ?.substringAfter(
                    delimiter = "=",
                    missingDelimiterValue = ""
                )
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Prefix(value: String): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            .take(12)
    }

    private fun hasWasmMagic(bytes: ByteArray): Boolean {
        return bytes.size >= 8 &&
            bytes[0] == 0x00.toByte() &&
            bytes[1] == 0x61.toByte() &&
            bytes[2] == 0x73.toByte() &&
            bytes[3] == 0x6d.toByte() &&
            bytes[4] == 0x01.toByte() &&
            bytes[5] == 0x00.toByte() &&
            bytes[6] == 0x00.toByte() &&
            bytes[7] == 0x00.toByte()
    }

    private suspend fun loadWasmBytes(
        envelope: WasmEnvelope,
        sourceApiUrl: String,
        playerUrl: String
    ): ByteArray? {

        val inline =
            envelope
                .inlineWasm
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (inline != null) {
            val decoded =
                try {
                    Base64.decode(
                        inline,
                        Base64.DEFAULT
                    )
                } catch (_: Exception) {
                    null
                }

            logMarker(
                "STREAM_DECRYPT|WASM_SOURCE=inline|" +
                    "MAGIC=${if (decoded != null && hasWasmMagic(decoded)) "pass" else "fail"}"
            )

            return decoded
                ?.takeIf(::hasWasmMagic)
        }

        val wasmUrl =
            resolveHttpUrl(
                sourceApiUrl,
                envelope.wasmUrl
            ) ?: return null

        return try {
            val response =
                app.get(
                    url = wasmUrl,
                    referer = playerUrl
                )

            val body = response.body
            val bytes = body.bytes()
            body.close()

            val valid =
                response.okhttpResponse.code in 200..299 &&
                    hasWasmMagic(bytes)

            logMarker(
                "STREAM_DECRYPT|WASM_SOURCE=url|" +
                    "HOST=${safeHost(wasmUrl)}|" +
                    "HTTP=${response.okhttpResponse.code}|" +
                    "MAGIC=${if (valid) "pass" else "fail"}"
            )

            bytes.takeIf { valid }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            logMarker(
                "STREAM_DECRYPT|WASM_SOURCE=url|" +
                    "HOST=${safeHost(wasmUrl)}|" +
                    "RESULT=fail|" +
                    "ERROR=${error.javaClass.simpleName}"
            )

            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun decryptWithActualWasm(
        encryptedBase64: String,
        wasmBytes: ByteArray
    ): String? {

        return try {
            withTimeoutOrNull(WASM_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val completed = AtomicBoolean(false)
                    val mainHandler =
                        Handler(
                            Looper.getMainLooper()
                        )

                    val webViewHolder =
                        arrayOfNulls<WebView>(1)

                    fun destroyWebView() {
                        mainHandler.post {
                            webViewHolder[0]
                                ?.removeJavascriptInterface(
                                    "StreamzyBridge"
                                )

                            webViewHolder[0]
                                ?.stopLoading()

                            webViewHolder[0]
                                ?.destroy()

                            webViewHolder[0] = null
                        }
                    }

                    fun finish(value: String?) {
                        if (!completed.compareAndSet(false, true)) {
                            return
                        }

                        destroyWebView()

                        if (continuation.isActive) {
                            continuation.resume(value)
                        }
                    }

                    continuation.invokeOnCancellation {
                        if (
                            completed.compareAndSet(
                                false,
                                true
                            )
                        ) {
                            destroyWebView()
                        }
                    }

                    mainHandler.post {
                        try {
                            val wasmBase64 =
                                Base64.encodeToString(
                                    wasmBytes,
                                    Base64.NO_WRAP
                                )

                            val bridge =
                                WasmBridge(
                                    successCallback = { encoded ->
                                        val plain =
                                            try {
                                                Base64.decode(
                                                    encoded,
                                                    Base64.DEFAULT
                                                ).toString(
                                                    Charsets.UTF_8
                                                )
                                            } catch (_: Exception) {
                                                null
                                            }

                                        finish(plain)
                                    },
                                    failureCallback = {
                                        finish(null)
                                    }
                                )

                            val webView =
                                WebView(
                                    applicationContext
                                )

                            webViewHolder[0] = webView
                            webView.settings.javaScriptEnabled = true
                            webView.settings.domStorageEnabled = false
                            webView.settings.allowFileAccess = false
                            webView.settings.allowContentAccess = false
                            webView.addJavascriptInterface(
                                bridge,
                                "StreamzyBridge"
                            )

                            val html =
                                """
                                <!doctype html><meta charset="utf-8">
                                <script>
                                (function () {
                                  'use strict';
                                  function b64(s) {
                                    var bin = atob(s);
                                    var out = new Uint8Array(bin.length);
                                    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
                                    return out;
                                  }
                                  function outB64(bytes) {
                                    var value = '';
                                    var chunk = 8192;
                                    for (var i = 0; i < bytes.length; i += chunk) {
                                      value += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
                                    }
                                    return btoa(value);
                                  }
                                  var wasm = b64('$wasmBase64');
                                  var enc = b64('$encryptedBase64');
                                  WebAssembly.instantiate(wasm.buffer, {}).then(function (result) {
                                    var ex = result.instance.exports;
                                    if (!ex.memory || !ex.alloc || !ex.decrypt) throw new Error('missing-export');
                                    var ptr = ex.alloc(enc.length);
                                    new Uint8Array(ex.memory.buffer, ptr, enc.length).set(enc);
                                    var outLen = ex.decrypt(ptr, enc.length);
                                    if (outLen < 0 || ptr + 12 + outLen > ex.memory.buffer.byteLength) {
                                      throw new Error('invalid-output-range');
                                    }
                                    var output = new Uint8Array(ex.memory.buffer, ptr + 12, outLen);
                                    StreamzyBridge.success(outB64(output));
                                  }).catch(function () {
                                    StreamzyBridge.failure('wasm-runtime-failed');
                                  });
                                })();
                                </script>
                                """.trimIndent()

                            webView.loadDataWithBaseURL(
                                "https://streamzy.local/",
                                html,
                                "text/html",
                                "UTF-8",
                                null
                            )
                        } catch (_: Exception) {
                            finish(null)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }

            null
        }
    }

    private suspend fun getStreamUrls(
        sourceApiUrl: String,
        playerUrl: String
    ): List<String> {

        val response =
            try {
                app.get(
                    url = sourceApiUrl,
                    headers = mapOf(
                        "Accept" to "application/json"
                    ),
                    referer = playerUrl
                )
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAM_API|HOST=${safeHost(sourceApiUrl)}|" +
                        "RESULT=fail|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return emptyList()
            }

        val payload =
            response.parsedSafe<StreamApiResponse>()

        val rawStreams =
            payload
                ?.data
                ?.streamUrls

        logMarker(
            "STREAM_API|HOST=${safeHost(sourceApiUrl)}|" +
                "HTTP=${response.okhttpResponse.code}|" +
                "STREAM_URLS_TYPE=" +
                when (rawStreams) {
                    is String -> "string"
                    is List<*> -> "array"
                    null -> "missing"
                    else -> "unsupported"
                }
        )

        if (rawStreams is List<*>) {
            return rawStreams
                .filterIsInstance<String>()
                .mapNotNull { stream ->
                    resolveHttpUrl(
                        sourceApiUrl,
                        stream
                    )
                }
                .distinct()
        }

        val encrypted =
            (rawStreams as? String)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return emptyList()

        val envelope =
            payload.vs
                ?: return emptyList()

        val wasmBytes =
            loadWasmBytes(
                envelope,
                sourceApiUrl,
                playerUrl
            ) ?: return emptyList()

        val decrypted =
            decryptWithActualWasm(
                encrypted,
                wasmBytes
            )

        val urls =
            decrypted
                ?.lineSequence()
                ?.map { line ->
                    line.trim()
                }
                ?.filter { line ->
                    line.isNotBlank()
                }
                ?.mapNotNull { stream ->
                    resolveHttpUrl(
                        sourceApiUrl,
                        stream
                    )
                }
                ?.distinct()
                ?.toList()
                .orEmpty()

        logMarker(
            "STREAM_DECRYPT|RESULT=" +
                if (urls.isNotEmpty()) {
                    "pass|URLS=${urls.size}"
                } else {
                    "fail|URLS=0"
                }
        )

        return urls
    }

    private fun getPlayerRuntimeConfig(
        html: String,
        playerUrl: String
    ): PlayerRuntimeConfig? {

        val exactApi =
            getConfigString(
                html,
                "api"
            )

        val streamBase =
            getConfigString(
                html,
                "streamBase"
            )

        if (
            exactApi == null &&
            streamBase == null
        ) {
            if (
                playerUrl.contains(
                    "/embed/player/",
                    true
                )
            ) {
                logMarker(
                    "PLAYER_CONFIG|RESULT=missing|" +
                        "API_PRESENT=no|STREAM_BASE_PRESENT=no|" +
                        "PLAYER_HOST=${safeHost(playerUrl)}"
                )
            }

            return null
        }

        val tvPath =
            Regex(
                """/embed/player/tv/\d+/(\d+)/(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(playerUrl)

        val season =
            getConfigScalar(
                html,
                "season"
            ) ?: tvPath
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

        val episode =
            getConfigScalar(
                html,
                "episode"
            ) ?: tvPath
                ?.groupValues
                ?.getOrNull(2)
                .orEmpty()

        return PlayerRuntimeConfig(
            playerUrl = playerUrl,
            exactApi = exactApi,
            streamBase = streamBase,
            metaApi =
                getConfigString(
                    html,
                    "metaApi"
                ),
            season = season,
            episode = episode
        )
    }

    private fun buildSourceApiUrl(
        config: PlayerRuntimeConfig
    ): String {

        config.exactApi?.let { exactApi ->
            return exactApi
        }

        val streamBase =
            config.streamBase
                ?: return ""

        // Exact apiFor() shape proven by player.js. The final
        // stream_urls parameter is deliberately bare, not =1.
        return streamBase +
            "&season=${encodeComponent(config.season)}" +
            "&episode=${encodeComponent(config.episode)}" +
            "&stream_urls"
    }

    private suspend fun selectMediaCandidate(
        streamUrls: List<String>,
        playerUrl: String
    ): MediaCandidate? {

        val playerHeaders =
            originOf(playerUrl)
                ?.let { origin ->
                    mapOf(
                        "Origin" to origin
                    )
                }
                .orEmpty()

        for (
            (zeroBasedIndex, rawUrl) in
            streamUrls.withIndex()
        ) {
            val index = zeroBasedIndex + 1

            logMarker(
                "STREAM_CANDIDATE_SOURCE|INDEX=$index|" +
                    "SOURCE=stream_urls"
            )

            logMarker(
                "STREAM_CANDIDATE|INDEX=$index|" +
                    "HOST=${safeHost(rawUrl)}|" +
                    "STAGE=raw"
            )

            validateMediaCandidate(
                url = rawUrl,
                referer = null,
                headers = emptyMap()
            )?.let { candidate ->
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=pass|TOKEN=not-required"
                )

                return candidate
            }

            val token =
                generateToken(
                    rawUrl,
                    playerUrl
                )

            if (token == null) {
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=fail|REASON=token-unavailable"
                )

                continue
            }

            val (
                tokenizedUrl,
                applyMethod
            ) = applyToken(
                rawUrl,
                token
            )

            logMarker(
                "TOKEN_FLOW|TOKEN_APPLIED|" +
                    "METHOD=$applyMethod|" +
                    "TOKEN_HOST=${safeHost(rawUrl)}"
            )

            validateMediaCandidate(
                url = tokenizedUrl,
                referer = null,
                headers = emptyMap()
            )?.let { candidate ->
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=pass|CONTEXT=no-explicit-header"
                )

                return candidate
            }

            validateMediaCandidate(
                url = tokenizedUrl,
                referer = playerUrl,
                headers = playerHeaders
            )?.let { candidate ->
                logMarker(
                    "STREAM_CANDIDATE|INDEX=$index|" +
                        "RESULT=pass|CONTEXT=player"
                )

                return candidate
            }

            logMarker(
                "STREAM_CANDIDATE|INDEX=$index|" +
                    "RESULT=fail|REASON=media-validation"
            )
        }

        return null
    }

    private suspend fun resolvePlayerStreams(
        config: PlayerRuntimeConfig,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val sourceApiMode =
            if (config.exactApi != null) {
                "config-exact"
            } else {
                "apiFor"
            }

        logMarker(
            "PLAYER_CONFIG|PLAYER_HOST=" +
                safeHost(config.playerUrl) +
                "|STREAM_API_HOST=" +
                safeHost(
                    config.exactApi
                        ?: config.streamBase
                ) +
                "|META_API_HOST=" +
                safeHost(config.metaApi) +
                "|SOURCE_API_MODE=$sourceApiMode" +
                "|SEASON_PRESENT=" +
                config.season.isNotBlank() +
                "|EPISODE_PRESENT=" +
                config.episode.isNotBlank()
        )

        val sourceApiUrl =
            buildSourceApiUrl(config)

        if (sourceApiUrl.isBlank()) {
            logMarker(
                "STREAM_API|DERIVATION=failed|" +
                    "REASON=missing-api-and-stream-base"
            )

            return false
        }

        val hasBareStreamUrls =
            Regex(
                "(?:[?&])stream_urls(?:[=&]|$)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(sourceApiUrl)

        logMarker(
            "STREAM_API|DERIVATION=$sourceApiMode|" +
                "HOST=${safeHost(sourceApiUrl)}|" +
                "BARE_STREAM_URLS=${if (hasBareStreamUrls) "yes" else "no"}"
        )

        val streamUrls =
            getStreamUrls(
                sourceApiUrl,
                config.playerUrl
            )

        if (streamUrls.isEmpty()) {
            return false
        }

        logMarker(
            "STREAM_API|CANDIDATE_SOURCE=stream_urls|" +
                "COUNT=${streamUrls.size}|" +
                "METADATA_MEDIA=excluded"
        )

        val selected =
            selectMediaCandidate(
                streamUrls,
                config.playerUrl
            ) ?: return false

        if (selected.type == ExtractorLinkType.M3U8) {
            val token =
                queryValue(
                    selected.url,
                    "token"
                )

            logMarker(
                "HLS_PLAYBACK|MASTER|" +
                    "HOST=${safeHost(selected.url)}|" +
                    "TOKEN_PRESENT=${if (token == null) "no" else "yes"}|" +
                    "TOKEN_HASH=${token?.let(::sha256Prefix) ?: "none"}"
            )

            logMarker(
                "HLS_URI_ACTUAL|TYPE=master|" +
                    "SCOPE=provider-handoff|" +
                    "QUERY_TOKEN=${if (token == null) "no" else "yes"}"
            )

            logMarker(
                "TOKEN_PROPAGATION|VARIANT=UNKNOWN|SEGMENT=UNKNOWN|" +
                    "REASON=player-requests-not-exposed-to-provider"
            )

            logMarker(
                "NETWORK_FAMILY|STATUS=UNKNOWN|" +
                    "REASON=socket-route-not-exposed-to-provider"
            )
        }

        callback(
            newExtractorLink(
                source = name,
                name = "$name ${safeHost(selected.url)}",
                url = selected.url,
                type = selected.type
            ) {
                referer = selected.referer
                quality = Qualities.Unknown.value
                headers = selected.headers
            }
        )

        logMarker(
            "STREAM_SELECTED|HOST=${safeHost(selected.url)}|" +
                "TYPE=${selected.type}|" +
                "PLAYBACK_HEADERS=${selected.headers.keys.sorted().joinToString(",").ifBlank { "none" }}|" +
                "REFERER=${if (selected.referer.isBlank()) "none" else "player"}"
        )

        return true
    }

    private suspend fun resolveValidatedPage(
        pageUrl: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        budget: ResolverBudget,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (
            depth > MAX_RESOLVER_DEPTH ||
            budget.pages >= MAX_RESOLVER_PAGES ||
            !visited.add(pageUrl)
        ) {
            return false
        }

        budget.pages += 1

        logMarker(
            "STREAMZY_RESOLVE|STAGE=page|" +
                "DEPTH=$depth|" +
                "HOST=${safeHost(pageUrl)}"
        )

        val html =
            try {
                app.get(
                    url = pageUrl,
                    referer = referer
                ).text
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=page|" +
                        "HOST=${safeHost(pageUrl)}|" +
                        "RESULT=fail|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return false
            }

        getPlayerRuntimeConfig(
            html,
            pageUrl
        )?.let { config ->
            if (
                resolvePlayerStreams(
                    config,
                    callback
                )
            ) {
                return true
            }
        }

        val document =
            Jsoup.parse(
                html,
                pageUrl
            )

        val dataApiUrls =
            document
                .select("[data-api]")
                .mapNotNull { element ->
                    resolveHttpUrl(
                        pageUrl,
                        element.attr("data-api")
                    )
                }
                .distinct()

        for (dataApiUrl in dataApiUrls) {
            val gateSrc =
                try {
                    app.get(
                        url = dataApiUrl,
                        headers = mapOf(
                            "Accept" to "application/json"
                        ),
                        referer = pageUrl
                    )
                        .parsedSafe<GateApiResponse>()
                        ?.src
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        throw error
                    }

                    null
                }

            val gateUrl =
                resolveHttpUrl(
                    dataApiUrl,
                    gateSrc
                ) ?: continue

            logMarker(
                "STREAMZY_RESOLVE|STAGE=gate|" +
                    "API_HOST=${safeHost(dataApiUrl)}|" +
                    "NEXT_HOST=${safeHost(gateUrl)}"
            )

            if (
                resolveValidatedPage(
                    pageUrl = gateUrl,
                    referer = pageUrl,
                    depth = depth + 1,
                    visited = visited,
                    budget = budget,
                    callback = callback
                )
            ) {
                return true
            }
        }

        val playerUrl =
            resolveHttpUrl(
                pageUrl,
                getConfigString(
                    html,
                    "playerUrl"
                )
            )

        if (playerUrl != null) {
            logMarker(
                "STREAMZY_RESOLVE|STAGE=player-url|" +
                    "HOST=${safeHost(playerUrl)}"
            )

            if (
                resolveValidatedPage(
                    pageUrl = playerUrl,
                    referer = pageUrl,
                    depth = depth + 1,
                    visited = visited,
                    budget = budget,
                    callback = callback
                )
            ) {
                return true
            }
        }

        for (
            iframeUrl in
            getIframeUrls(
                document,
                pageUrl
            )
        ) {
            if (
                resolveValidatedPage(
                    pageUrl = iframeUrl,
                    referer = pageUrl,
                    depth = depth + 1,
                    visited = visited,
                    budget = budget,
                    callback = callback
                )
            ) {
                return true
            }
        }

        return false
    }

    private fun exactHostMatch(
        urlHost: String,
        extractorHost: String
    ): Boolean {
        return urlHost == extractorHost ||
            urlHost.endsWith(".$extractorHost")
    }

    private suspend fun tryExactExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val urlHost = safeHost(url)

        if (
            urlHost == "-" ||
            urlHost == "invalid"
        ) {
            return false
        }

        val exactExtractors =
            mutableListOf<ExtractorApi>()

        for (
            index in
            extractorApis.lastIndex downTo 0
        ) {
            val extractor = extractorApis[index]
            val extractorHost =
                safeHost(
                    extractor.mainUrl
                )

            if (
                extractorHost != "-" &&
                extractorHost != "invalid" &&
                exactHostMatch(
                    urlHost,
                    extractorHost
                )
            ) {
                exactExtractors.add(extractor)
            }
        }

        for (extractor in exactExtractors) {
            var callbackCount = 0

            logMarker(
                "STREAMZY_RESOLVE|STAGE=extractor|" +
                    "HOST=$urlHost|" +
                    "EXTRACTOR=${extractor.name}|" +
                    "MATCH=exact-host"
            )

            try {
                extractor.getUrl(
                    url = url,
                    referer = referer,
                    subtitleCallback =
                        subtitleCallback,
                    callback = { link ->
                        callbackCount += 1
                        callback(link)
                    }
                )
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=extractor|" +
                        "HOST=$urlHost|" +
                        "EXTRACTOR=${extractor.name}|" +
                        "RESULT=exception|" +
                        "ERROR=${error.javaClass.simpleName}"
                )
            }

            logMarker(
                "STREAMZY_RESOLVE|STAGE=extractor|" +
                    "HOST=$urlHost|" +
                    "EXTRACTOR=${extractor.name}|" +
                    "CALLBACKS=$callbackCount"
            )

            if (callbackCount > 0) {
                return true
            }
        }

        return false
    }

    private fun cleanText(
        input: String?
    ): String? {

        if (input.isNullOrBlank()) {
            return null
        }

        return input
            .replace(Regex("""&#0;?"""), "")
            .replace("\u0000", "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
            .takeIf {
                it.isNotBlank()
            }
    }

    // ============================================================
    // MAIN PAGE CARD
    // ============================================================

    private fun parseCard(
        element: Element
    ): SearchResponse? {

        val href = element
            .attr("href")
            .trim()

        if (href.isBlank()) {
            return null
        }

        val title = element
            .selectFirst(".card-info h3")
            ?.text()
            ?.trim()
            .orEmpty()

        if (title.isBlank()) {
            return null
        }

        val rawPoster = element
            .selectFirst(".card-img img")
            ?.attr("src")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val poster =
            absolutePosterUrl(rawPoster)

        val year = element
            .selectFirst(
                ".card-info span.text-zinc-500"
            )
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val targetUrl =
            absoluteUrl(href)
                ?: return null

        return when {

            href.contains("/movie/") -> {

                newMovieSearchResponse(
                    title,
                    targetUrl,
                    TvType.Movie
                ) {
                    posterUrl = poster
                    this.year = year
                }
            }

            href.contains("/tv/") -> {

                newTvSeriesSearchResponse(
                    title,
                    targetUrl,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                    this.year = year
                }
            }

            else -> null
        }
    }

    // ============================================================
    // MAIN PAGE
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = buildPageUrl(
            request.data,
            page
        )

        val document =
            app.get(url).document

        val items = document
            .select(
                "a.card[href^=/movie/], " +
                    "a.card[href^=/tv/]"
            )
            .mapNotNull {
                parseCard(it)
            }

        return newHomePageResponse(
            request,
            items,
            hasNext = items.size >= 20
        )
    }

    // ============================================================
    // QUICK SEARCH
    // ============================================================

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {

        return search(query)
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) {
            return emptyList()
        }

        val response = app.get(
            "$mainUrl/api/search",
            params = mapOf(
                "q" to query
            )
        )

        val data =
            response.parsedSafe<SearchApiResponse>()
                ?: return emptyList()

        return data.results
            .mapNotNull { item ->

                if (
                    item.id <= 0 ||
                    item.title.isBlank()
                ) {
                    return@mapNotNull null
                }

                val mediaType =
                    item.mediaType
                        .trim()
                        .lowercase()

                val poster =
                    item.posterPath
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let { path ->

                            when {
                                path.startsWith(
                                    "https://"
                                ) -> path

                                path.startsWith(
                                    "http://"
                                ) -> path

                                path.startsWith("/") ->
                                    "$POSTER_CDN$path"

                                else ->
                                    "$POSTER_CDN/$path"
                            }
                        }

                val year =
                    item.releaseDate
                        ?.take(4)
                        ?.toIntOrNull()

                when (mediaType) {

                    "movie" -> {

                        newMovieSearchResponse(
                            item.title,
                            "$mainUrl/movie/${item.id}",
                            TvType.Movie
                        ) {
                            posterUrl = poster
                            this.year = year
                        }
                    }

                    "tv" -> {

                        newTvSeriesSearchResponse(
                            item.title,
                            "$mainUrl/tv/${item.id}",
                            TvType.TvSeries
                        ) {
                            posterUrl = poster
                            this.year = year
                        }
                    }

                    else -> null
                }
            }
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app.get(url).document

        val canonical =
            document
                .selectFirst(
                    "link[rel=canonical]"
                )
                ?.attr("href")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: url

        return when {

            canonical.contains("/movie/") ->
                loadMovie(
                    canonical,
                    document
                )

            canonical.contains("/tv/") ->
                loadTv(
                    canonical,
                    document
                )

            else -> null
        }
    }

    // ============================================================
    // MOVIE
    // ============================================================

    private suspend fun loadMovie(
        url: String,
        document: Document
    ): LoadResponse? {

        val movieData =
            Regex(
                """/movie/(\d+)"""
            )
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { movieId ->
                    "streamzy://movie/$movieId"
                }
                ?: url

        val title =
            document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val backdrop =
            document
                .selectFirst(
                    ".hero-bg img"
                )
                ?.attr("src")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    absolutePosterUrl(it)
                }

        val plot =
            cleanText(
                document
                    .selectFirst(
                        "meta[property=og:description]"
                    )
                    ?.attr("content")
            )

        val pageText =
            document.text()

        // YEAR

        val year =
            Regex(
                """Released:\s*(\d{4})""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        // DURATION: 2h 28m

        val hourMinuteDuration =
            Regex(
                """Duration:\s*(\d+)h\s*(\d+)m""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.let { match ->

                    val hours =
                        match.groupValues
                            .getOrNull(1)
                            ?.toIntOrNull()
                            ?: 0

                    val minutes =
                        match.groupValues
                            .getOrNull(2)
                            ?.toIntOrNull()
                            ?: 0

                    hours * 60 + minutes
                }

        // DURATION: 95 min

        val minuteDuration =
            Regex(
                """Duration:\s*(\d+)\s*min""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val duration =
            hourMinuteDuration
                ?: minuteDuration

        // RATING

        val rating =
            Regex(
                """(?:IMDB|IMDb)\s*:?\s*([0-9.]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)

        // GENRES

        val genres =
            document
                .select(
                    "a[href^=/genre/][href*=type=movie]"
                )
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        // CONTENT RATING

        val contentRating =
            Regex(
                """\b(PG-13|NC-17|PG|R|G|NR)\b"""
            )
                .find(pageText)
                ?.value

        // ACTORS

        val actors =
            parseActors(document)

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            dataUrl = movieData
        ) {

            posterUrl = poster
            backgroundPosterUrl = backdrop

            this.year = year
            this.plot = plot

            comingSoon = false

            if (duration != null) {
                this.duration = duration
            }

            if (genres.isNotEmpty()) {
                tags = genres
            }

            if (
                !contentRating.isNullOrBlank()
            ) {
                this.contentRating =
                    contentRating
            }

            if (
                !rating.isNullOrBlank()
            ) {
                score = Score.from(
                    rating,
                    10
                )
            }

            if (actors.isNotEmpty()) {
                this.actors = actors
            }
        }
    }

    // ============================================================
    // SEASON PARSER
    // ============================================================

    private fun parseSeasonInfo(
        document: Document,
        tvId: Int
    ): List<StreamzySeasonInfo> {

        val pageText =
            document.text()

        val seasonNumbers =
            document
                .select(
                    "a[href*=\"/watch/tv/$tvId/\"]"
                )
                .mapNotNull { element ->

                    val href =
                        element
                            .attr("href")
                            .trim()

                    Regex(
                        """/watch/tv/$tvId/(\d+)/(\d+)"""
                    )
                        .find(href)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }
                .distinct()
                .sorted()

        return seasonNumbers
            .map { seasonNumber ->

                val episodeCount =
                    Regex(
                        """Season\s+$seasonNumber\b.{0,150}?(\d+)\s+Episodes?""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(pageText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?: 1

                StreamzySeasonInfo(
                    season = seasonNumber,
                    episodeCount = episodeCount
                )
            }
    }

    // ============================================================
    // TV SERIES
    // ============================================================

    private suspend fun loadTv(
        url: String,
        document: Document
    ): LoadResponse? {

        val title =
            document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr("content")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val backdrop =
            document
                .selectFirst(
                    ".hero-bg img"
                )
                ?.attr("src")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    absolutePosterUrl(it)
                }

        val plot =
            cleanText(
                document
                    .selectFirst(
                        "meta[property=og:description]"
                    )
                    ?.attr("content")
            )

        val pageText =
            document.text()

        // TV ID

        val tvId =
            Regex(
                """/tv/(\d+)"""
            )
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        // YEAR

        val year =
            Regex(
                """(?:Released|First Air Date):\s*(\d{4})""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        // RATING

        val rating =
            Regex(
                """(?:IMDB|IMDb)\s*:?\s*([0-9.]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)

        // GENRES

        val genres =
            document
                .select(
                    "a[href^=/genre/][href*=type=tv]"
                )
                .map {
                    it.text().trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        // CONTENT RATING

        val contentRating =
            Regex(
                """\b(TV-MA|TV-14|TV-PG|TV-G|TV-Y7|TV-Y)\b"""
            )
                .find(pageText)
                ?.value

        // ACTORS

        val actors =
            parseActors(document)

        // SEASONS

        val seasonInfo =
            tvId
                ?.let {
                    parseSeasonInfo(
                        document,
                        it
                    )
                }
                .orEmpty()

        val seasonNames =
            seasonInfo.map {
                SeasonData(
                    season = it.season,
                    name = "Season ${it.season}"
                )
            }

        // ========================================================
        // EPISODES
        // ========================================================
        //
        // Penting:
        //
        // Signature Cloudstream:
        //
        // newEpisode(
        //     url,
        //     initializer,
        //     fix
        // )
        //
        // Jadi initializer harus ditulis di dalam argument.
        // ========================================================

        val episodes: List<Episode> =
            if (tvId != null) {

                seasonInfo.flatMap { seasonInfoItem ->

                    (1..seasonInfoItem.episodeCount)
                        .map { episodeNumber ->

                            val episodeData =
                                "streamzy://episode/" +
                                    "$tvId/" +
                                    "${seasonInfoItem.season}/" +
                                    episodeNumber

                            newEpisode(
                                url = episodeData,
                                initializer = {

                                    name =
                                        "Episode $episodeNumber"

                                    season =
                                        seasonInfoItem.season

                                    episode =
                                        episodeNumber
                                },
                                fix = false
                            )
                        }
                }

            } else {
                emptyList()
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {

            posterUrl = poster
            backgroundPosterUrl = backdrop

            this.year = year
            this.plot = plot

            comingSoon = false

            if (genres.isNotEmpty()) {
                tags = genres
            }

            if (
                !contentRating.isNullOrBlank()
            ) {
                this.contentRating =
                    contentRating
            }

            if (
                !rating.isNullOrBlank()
            ) {
                score = Score.from(
                    rating,
                    10
                )
            }

            if (actors.isNotEmpty()) {
                this.actors = actors
            }

            if (seasonNames.isNotEmpty()) {
                this.seasonNames =
                    seasonNames
            }
        }
    }

    // ============================================================
    // ACTORS
    // ============================================================

    private fun parseActors(
        document: Document
    ): List<ActorData> {

        return document
            .select(
                "a[href^=/person/]"
            )
            .mapNotNull { element ->

                val imageElement =
                    element.selectFirst("img")
                        ?: return@mapNotNull null

                val image =
                    imageElement
                        .attr("src")
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?.let {
                            absolutePosterUrl(it)
                        }

                val actorName =
                    element
                        .selectFirst(
                            "p.text-white"
                        )
                        ?.text()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: imageElement
                            .attr("alt")
                            .substringBefore(" in ")
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                        ?: return@mapNotNull null

                val role =
                    element
                        .select("p")
                        .drop(1)
                        .firstOrNull()
                        ?.text()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }

                ActorData(
                    actor = Actor(
                        name = actorName,
                        image = image
                    ),
                    roleString = role
                )
            }
            .distinctBy {
                it.actor.name
            }
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val watchUrl =
            buildWatchUrl(data)
                ?: return false

        val contentKind =
            if (
                watchUrl.contains(
                    "/watch/tv/",
                    true
                )
            ) {
                "episode"
            } else {
                "movie"
            }

        logMarker(
            "STREAMZY_RESOLVE|STAGE=start|" +
                "CONTENT=$contentKind|" +
                "WATCH_HOST=${safeHost(watchUrl)}"
        )

        val firstHtml =
            try {
                val response =
                    app.get(watchUrl)

                val html = response.text

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=watch-fetch|" +
                        "CONTENT=$contentKind|" +
                        "HTTP=${response.okhttpResponse.code}|" +
                        "EFFECTIVE_HOST=" +
                        safeHost(
                            response
                                .okhttpResponse
                                .request
                                .url
                                .toString()
                        ) +
                        "|" +
                        "CONTENT_TYPE=" +
                        response
                            .okhttpResponse
                            .header("Content-Type")
                            .orEmpty()
                            .take(80)
                )

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=watch-body|" +
                        "CONTENT=$contentKind|" +
                        "BYTES=${html.toByteArray(Charsets.UTF_8).size}|" +
                        "SHA256_PREFIX=${sha256Prefix(html)}"
                )

                html
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_RESOLVE|STAGE=watch|" +
                        "RESULT=fail|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return false
            }

        val firstDocument =
            Jsoup.parse(
                firstHtml,
                watchUrl
            )

        val primaryIframeUrls =
            getIframeUrls(
                firstDocument,
                watchUrl
            )

        val primaryVidsrcUrl =
            primaryIframeUrls
                .firstOrNull { iframeUrl ->
                    safeHost(iframeUrl) == "vidsrc.mov"
                }

        logMarker(
            "STREAMZY_RESOLVE|STAGE=watch-parse|" +
                "CONTENT=$contentKind|" +
                "IFRAME_TAGS=${firstDocument.select("iframe").size}|" +
                "PARSER_MATCHES=${primaryIframeUrls.size}|" +
                "VIDSRC_MATCH=${if (primaryVidsrcUrl == null) "no" else "yes"}"
        )

        val primaryIframeResult =
            if (primaryVidsrcUrl != null) {
                "FOUND=yes|HOST=${safeHost(primaryVidsrcUrl)}"
            } else {
                "FOUND=no|REASON=no-vidsrc-in-primary-watch"
            }

        logMarker(
            "STREAMZY_RESOLVE|STAGE=watch-iframe|" +
                "CONTENT=$contentKind|" +
                primaryIframeResult
        )

        val serverPageUrls =
            mutableListOf(watchUrl)

        serverPageUrls.addAll(
            firstDocument
                .select(
                    "a[href*='?server='], " +
                        "a[href*='&server=']"
                )
                .mapNotNull { serverLink ->
                    absoluteUrl(
                        serverLink
                            .attr("href")
                            .trim()
                    )
                }
        )

        val distinctServerPages =
            serverPageUrls.distinct()

        logMarker(
            "STREAMZY_RESOLVE|STAGE=servers|" +
                "COUNT=${distinctServerPages.size}"
        )

        val testedIframeUrls = mutableSetOf<String>()
        val emittedUrls = mutableSetOf<String>()

        var callbackCount = 0

        val forwardingCallback:
            (ExtractorLink) -> Unit = { link ->

                if (emittedUrls.add(link.url)) {
                    callbackCount += 1

                    logMarker(
                        "STREAMZY_RESOLVE|STAGE=callback|" +
                            "COUNT=$callbackCount|" +
                            "HOST=${safeHost(link.url)}|" +
                            "TYPE=${link.type}|" +
                            "QUALITY=${link.quality}"
                    )

                    callback(link)
                }
            }

        val peachifyServerLink =
            firstDocument
                .select(
                    "a[href*='?server='], " +
                        "a[href*='&server=']"
                )
                .mapNotNull { anchor ->
                    val rawHref =
                        anchor
                            .attr("href")
                            .trim()

                    val resolvedUrl =
                        resolveHttpUrl(
                            baseUrl = watchUrl,
                            value = rawHref
                        )
                            ?: return@mapNotNull null

                    if (!isPeachifyServerPage(resolvedUrl)) {
                        return@mapNotNull null
                    }

                    PeachifyServerLink(
                        hrefForm = peachifyHrefForm(rawHref),
                        resolvedUrl = resolvedUrl
                    )
                }
                .firstOrNull()

        val peachifyServerPageUrl =
            peachifyServerLink?.resolvedUrl

        logMarker(
            "STREAMZY_PEACHIFY|STAGE=server-link|" +
                "CONTENT=$contentKind|" +
                "LINK_FOUND=${peachifyServerLink != null}|" +
                "HREF_FORM=${peachifyServerLink?.hrefForm ?: "none"}|" +
                "RESOLVED_HOST=${peachifyServerPageUrl?.let(::safeHost) ?: "none"}|" +
                "RESOLVED_PATH_KIND=${peachifyServerPageUrl?.let(::peachifyWatchPathKind) ?: "other"}"
        )

        if (peachifyServerPageUrl != null) {
            var serverPageHttp = "FAILED"
            var serverPageBytes = 0

            val peachifyDocument =
                try {
                    val response =
                        app.get(
                            url = peachifyServerPageUrl,
                            referer = watchUrl
                        )

                    serverPageHttp =
                        response
                            .okhttpResponse
                            .code
                            .toString()

                    val html = response.text
                    serverPageBytes =
                        html
                            .toByteArray()
                            .size

                    Jsoup.parse(
                        html,
                        peachifyServerPageUrl
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        throw error
                    }

                    logMarker(
                        "STREAMZY_PEACHIFY|STAGE=server-page|" +
                            "CONTENT=$contentKind|" +
                            "HTTP=FAILED|" +
                            "BODY_BYTES=0|" +
                            "IFRAME_COUNT=0|" +
                            "PEACHIFY_IFRAME_MATCH=false"
                    )

                    null
                }

            val peachifyIframeUrls =
                peachifyDocument
                    ?.let { document ->
                        getIframeUrls(
                            document,
                            peachifyServerPageUrl
                        )
                    }
                    .orEmpty()

            val staticPeachifyIframeUrl =
                peachifyIframeUrls
                    .firstOrNull { iframeUrl ->
                        getPeachifyContent(iframeUrl) != null
                    }

            if (peachifyDocument != null) {
                logMarker(
                    "STREAMZY_PEACHIFY|STAGE=server-page|" +
                        "CONTENT=$contentKind|" +
                        "HTTP=$serverPageHttp|" +
                        "BODY_BYTES=$serverPageBytes|" +
                        "IFRAME_COUNT=${peachifyIframeUrls.size}|" +
                        "PEACHIFY_IFRAME_MATCH=${staticPeachifyIframeUrl != null}"
                )
            }

            val fallbackPeachifyIframeUrl =
                if (staticPeachifyIframeUrl == null) {
                    getPeachifyFallbackEmbed(watchUrl)
                } else {
                    null
                }

            logMarker(
                "STREAMZY_PEACHIFY|STAGE=fallback|" +
                    "CONTENT=$contentKind|" +
                    "FALLBACK_USED=${fallbackPeachifyIframeUrl != null}"
            )

            val peachifyIframeUrl =
                staticPeachifyIframeUrl
                    ?: fallbackPeachifyIframeUrl

            if (peachifyIframeUrl != null) {
                testedIframeUrls.add(peachifyIframeUrl)

                try {
                    resolvePeachify(
                        embedUrl = peachifyIframeUrl,
                        subtitleCallback = subtitleCallback,
                        callback = forwardingCallback
                    )
                } catch (error: Exception) {
                    if (error is CancellationException) {
                        throw error
                    }

                    logMarker(
                        "STREAMZY_PEACHIFY|STAGE=done|" +
                            "CONTENT=$contentKind|" +
                            "RESULT=exception|" +
                            "ERROR=${error.javaClass.simpleName}"
                    )
                }
            } else {
                logMarker(
                    "STREAMZY_PEACHIFY|STAGE=iframe|" +
                        "CONTENT=$contentKind|" +
                        "RESULT=not-found"
                )
            }
        } else {
            logMarker(
                "STREAMZY_PEACHIFY|STAGE=iframe|" +
                    "CONTENT=$contentKind|" +
                    "RESULT=server-page-not-found"
            )
        }

        for (
            (serverZeroIndex, serverPageUrl) in
            distinctServerPages.withIndex()
        ) {

            val serverIndex = serverZeroIndex + 1
            val serverRole =
                if (serverPageUrl == watchUrl) {
                    "primary-watch"
                } else {
                    "alternative"
                }

            val serverDocument =
                if (serverPageUrl == watchUrl) {
                    firstDocument
                } else {
                    try {
                        Jsoup.parse(
                            app.get(
                                url = serverPageUrl,
                                referer = watchUrl
                            ).text,
                            serverPageUrl
                        )
                    } catch (error: Exception) {
                        if (error is CancellationException) {
                            throw error
                        }

                        logMarker(
                            "STREAMZY_RESOLVE|STAGE=server|" +
                                "HOST=${safeHost(serverPageUrl)}|" +
                                "RESULT=fail|" +
                                "ERROR=${error.javaClass.simpleName}"
                        )

                        continue
                    }
                }

            val iframeUrls =
                getIframeUrls(
                    serverDocument,
                    serverPageUrl
                )

            logMarker(
                "STREAMZY_RESOLVE|STAGE=server|" +
                    "INDEX=$serverIndex|" +
                    "ROLE=$serverRole|" +
                    "HOST=${safeHost(serverPageUrl)}|" +
                    "IFRAMES=${iframeUrls.size}"
            )

            for (iframeUrl in iframeUrls) {

                if (
                    !testedIframeUrls.add(
                        iframeUrl
                    )
                ) {
                    continue
                }

                val resolved =
                    try {
                        resolveValidatedPage(
                            pageUrl = iframeUrl,
                            referer = serverPageUrl,
                            depth = 0,
                            visited = mutableSetOf(),
                            budget = ResolverBudget(),
                            callback = forwardingCallback
                        )
                    } catch (error: Exception) {
                        if (error is CancellationException) {
                            throw error
                        }

                        logMarker(
                            "STREAMZY_RESOLVE|STAGE=validated-chain|" +
                                "HOST=${safeHost(iframeUrl)}|" +
                                "RESULT=exception|" +
                                "ERROR=${error.javaClass.simpleName}"
                        )

                        false
                    }

                if (
                    resolved &&
                    callbackCount > 0
                ) {
                    logMarker(
                        "STREAMZY_RESOLVE|STAGE=done|" +
                            "CALLBACKS=$callbackCount"
                    )

                    return true
                }

                if (
                    tryExactExtractor(
                        url = iframeUrl,
                        referer = serverPageUrl,
                        subtitleCallback =
                            subtitleCallback,
                        callback =
                            forwardingCallback
                    ) &&
                    callbackCount > 0
                ) {
                    logMarker(
                        "STREAMZY_RESOLVE|STAGE=done|" +
                            "CALLBACKS=$callbackCount"
                    )

                    return true
                }
            }
        }

        logMarker(
            "STREAMZY_RESOLVE|STAGE=done|" +
                "CALLBACKS=$callbackCount|" +
                "RESULT=no-links"
        )

        return callbackCount > 0
    }
}

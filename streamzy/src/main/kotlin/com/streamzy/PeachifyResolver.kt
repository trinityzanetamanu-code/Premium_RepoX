package com.streamzy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

internal class PeachifyResolver(
    private val mainUrl: () -> String,
    private val logMarkerCallback: (String) -> Unit,
    private val safeHostCallback: (String?) -> String,
    private val resolveHttpUrlCallback: (String, String?) -> String?,
    private val getIframeUrlsCallback: (Document, String) -> List<String>
) {

    private fun logMarker(message: String) =
        logMarkerCallback(message)

    private fun safeHost(url: String?) =
        safeHostCallback(url)

    private fun resolveHttpUrl(
        baseUrl: String,
        value: String?
    ) = resolveHttpUrlCallback(baseUrl, value)

    private fun getIframeUrls(
        document: Document,
        baseUrl: String
    ) = getIframeUrlsCallback(document, baseUrl)

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

        if (!parsed.host.equals(URI(mainUrl()).host, true)) {
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

    private fun parsePeachifySubtitleItem(
        value: Any?
    ): PeachifySubtitle? {

        val item =
            value as? Map<*, *>
                ?: return null

        val rawUrl = item["url"]
        val rawDisplay = item["display"]
        val rawLanguage = item["language"]
        val rawFormat = item["format"]
        val rawHearingImpaired = item["isHearingImpaired"]

        if (
            rawUrl !is String ||
            (rawDisplay != null && rawDisplay !is String) ||
            (rawLanguage != null && rawLanguage !is String) ||
            (rawFormat != null && rawFormat !is String) ||
            (
                rawHearingImpaired != null &&
                    rawHearingImpaired !is Boolean
                )
        ) {
            return null
        }

        return PeachifySubtitle(
            url = rawUrl,
            display = rawDisplay as? String,
            language = rawLanguage as? String,
            format = rawFormat as? String,
            isHearingImpaired =
                rawHearingImpaired as? Boolean
        )
    }

    private fun peachifySubtitleRootType(
        value: Any?
    ): String {

        return when (value) {
            is List<*> -> "ROOT_ARRAY"
            is Map<*, *> -> "ROOT_OBJECT"
            is String -> "ROOT_STRING"
            is Number -> "ROOT_NUMBER"
            is Boolean -> "ROOT_BOOLEAN"
            null -> "ROOT_NULL"
            else -> "ROOT_OTHER"
        }
    }

    private fun isPeachifyIndonesianSubtitle(
        subtitle: PeachifySubtitle
    ): Boolean {

        val language =
            subtitle.language
                ?.trim()
                ?.lowercase()
                ?.replace("_", "-")
                .orEmpty()

        if (
            language == "id" ||
            language.startsWith("id-") ||
            language in setOf(
                "ind",
                "indonesian",
                "indonesia",
                "bahasa indonesia"
            )
        ) {
            return true
        }

        val display =
            subtitle.display
                ?.trim()
                ?.lowercase()
                ?.replace(Regex("\\s+"), " ")
                .orEmpty()

        return Regex(
            "^(?:bahasa indonesia|indonesian|indonesia)" +
                "(?:\\s*\\([^)]*\\))?$",
            RegexOption.IGNORE_CASE
        ).matches(display)
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

        if (httpCode !in 200..299) {
            logMarker(
                "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                    "CONTENT=${content.kind}|" +
                    "ENDPOINT=/subs/${content.apiPath}|" +
                    "SUBTITLE_HTTP=$httpCode|" +
                    "ROOT_TYPE=NOT_PARSED|" +
                    "RAW_ITEMS=0|" +
                    "CONVERTED_ITEMS=0|" +
                    "MALFORMED_ITEMS=0|" +
                    "VALID_URLS=0|" +
                    "DEDUPED=0|" +
                    "SUBTITLE_CALLBACKS=0|" +
                    "INDONESIAN_ITEMS=0|" +
                    "INDONESIAN_CALLBACKS=0|" +
                    "ERROR=HttpStatus"
            )

            return 0
        }

        val root: Any? =
            try {
                response.parsed<Any>()
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }

                logMarker(
                    "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                        "CONTENT=${content.kind}|" +
                        "ENDPOINT=/subs/${content.apiPath}|" +
                        "SUBTITLE_HTTP=$httpCode|" +
                        "ROOT_TYPE=PARSE_FAILED|" +
                        "RAW_ITEMS=0|" +
                        "CONVERTED_ITEMS=0|" +
                        "MALFORMED_ITEMS=0|" +
                        "VALID_URLS=0|" +
                        "DEDUPED=0|" +
                        "SUBTITLE_CALLBACKS=0|" +
                        "INDONESIAN_ITEMS=0|" +
                        "INDONESIAN_CALLBACKS=0|" +
                        "ERROR=${error.javaClass.simpleName}"
                )

                return 0
            }

        val rootType =
            peachifySubtitleRootType(root)

        val rawItems =
            root as? List<*>

        if (rawItems == null) {
            logMarker(
                "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                    "CONTENT=${content.kind}|" +
                    "ENDPOINT=/subs/${content.apiPath}|" +
                    "SUBTITLE_HTTP=$httpCode|" +
                    "ROOT_TYPE=$rootType|" +
                    "RAW_ITEMS=0|" +
                    "CONVERTED_ITEMS=0|" +
                    "MALFORMED_ITEMS=0|" +
                    "VALID_URLS=0|" +
                    "DEDUPED=0|" +
                    "SUBTITLE_CALLBACKS=0|" +
                    "INDONESIAN_ITEMS=0|" +
                    "INDONESIAN_CALLBACKS=0|" +
                    "ERROR=UnexpectedRootType"
            )

            return 0
        }

        val subtitles =
            rawItems.mapNotNull { item ->
                parsePeachifySubtitleItem(item)
            }

        val malformedItems =
            rawItems.size - subtitles.size

        val validSubtitles =
            subtitles.mapNotNull { subtitle ->
                validHttpUrl(subtitle.url)
                    ?.let { url ->
                        subtitle to url
                    }
            }

        val deduplicatedSubtitles =
            validSubtitles.distinctBy { (_, url) ->
                url
            }

        val indonesianItems =
            subtitles.count(
                ::isPeachifyIndonesianSubtitle
            )

        var subtitleCallbacks = 0
        var indonesianCallbacks = 0

        deduplicatedSubtitles.forEachIndexed {
                index,
                (subtitle, url) ->

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

            if (
                isPeachifyIndonesianSubtitle(
                    subtitle
                )
            ) {
                indonesianCallbacks += 1
            }
        }

        logMarker(
            "STREAMZY_PEACHIFY|STAGE=subtitle|" +
                "CONTENT=${content.kind}|" +
                "ENDPOINT=/subs/${content.apiPath}|" +
                "SUBTITLE_HTTP=$httpCode|" +
                "ROOT_TYPE=$rootType|" +
                "RAW_ITEMS=${rawItems.size}|" +
                "CONVERTED_ITEMS=${subtitles.size}|" +
                "MALFORMED_ITEMS=$malformedItems|" +
                "VALID_URLS=${validSubtitles.size}|" +
                "DEDUPED=${deduplicatedSubtitles.size}|" +
                "SUBTITLE_CALLBACKS=$subtitleCallbacks|" +
                "INDONESIAN_ITEMS=$indonesianItems|" +
                "INDONESIAN_CALLBACKS=$indonesianCallbacks|" +
                "ERROR=NONE"
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

    suspend fun resolveFromWatchPage(
        watchUrl: String,
        contentKind: String,
        firstDocument: Document,
        testedIframeUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        forwardingCallback: (ExtractorLink) -> Unit
    ) {
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

    }
}

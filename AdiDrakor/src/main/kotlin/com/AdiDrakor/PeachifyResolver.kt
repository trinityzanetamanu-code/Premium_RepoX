package com.AdiDrakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/**
 * Peachify playback source.
 *
 * Ported verbatim from Adicinemax21/PeachifyResolver.kt, which in turn was
 * ported from Streamzy's proven baseline. Only the package declaration
 * changed; all logic is byte-for-byte identical to the Adicinemax21
 * baseline that is already confirmed runtime-working.
 *
 * Entry point: [resolveFromTmdbId] builds the canonical embed URL from the
 * TMDB metadata already present in AdiDrakor.LinkData and delegates to the
 * unchanged [resolvePeachify] pipeline (air/holly routes, HLS filter, label
 * grouping, URL dedup, subtitle parsing, Indonesian detection, error
 * handling, CancellationException rethrow).
 *
 * Log marker prefix is kept as "STREAMZY_PEACHIFY|..." so logs can be
 * diffed 1:1 against the Adicinemax21 baseline.
 */
internal class PeachifyResolver(
    private val sourceName: String,
    private val logMarkerCallback: (String) -> Unit,
    private val safeHostCallback: (String?) -> String
) {

    private fun logMarker(message: String) =
        logMarkerCallback(message)

    private fun safeHost(url: String?) =
        safeHostCallback(url)

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

    // ============================================================
    // ENTRY POINT (AdiDrakor)
    // ============================================================

    /**
     * Builds the canonical Peachify embed URL from TMDB metadata, then
     * delegates to the unmodified [resolvePeachify] pipeline.
     *
     * - Movie : https://peachify.top/embed/movie/{tmdbId}
     * - Series: https://peachify.top/embed/tv/{tmdbId}/{season}/{episode}
     */
    suspend fun resolveFromTmdbId(
        tmdbId: Int,
        type: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = when {
            type?.equals("tv", true) == true &&
                season != null &&
                episode != null ->
                "https://peachify.top/embed/tv/$tmdbId/$season/$episode"

            type?.equals("movie", true) == true ->
                "https://peachify.top/embed/movie/$tmdbId"

            else -> {
                logMarker(
                    "STREAMZY_PEACHIFY|STAGE=embed|" +
                        "RESULT=skip|" +
                        "REASON=unsupported-type-or-missing-season"
                )
                return false
            }
        }

        logMarker(
            "STREAMZY_PEACHIFY|STAGE=embed|" +
                "HOST=${safeHost(embedUrl)}|" +
                "URL=$embedUrl"
        )

        return resolvePeachify(
            embedUrl = embedUrl,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    // ============================================================
    // CONTENT RESOLUTION
    // ============================================================

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

    // ============================================================
    // LABELS / VALIDATION
    // ============================================================

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

    // ============================================================
    // SUBTITLE PARSING
    // ============================================================

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

    // ============================================================
    // SUBTITLE RESOLUTION
    // ============================================================

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

    // ============================================================
    // STREAM RESOLUTION (unchanged from Adicinemax21 baseline)
    // ============================================================

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
                        source = sourceName,
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
}

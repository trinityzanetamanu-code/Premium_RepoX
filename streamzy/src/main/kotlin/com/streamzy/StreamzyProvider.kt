package com.streamzy

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

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


    private val peachifyResolver =
        PeachifyResolver(
            sourceName = name,
            mainUrl = { mainUrl },
            logMarkerCallback = ::logMarker,
            safeHostCallback = ::safeHost,
            resolveHttpUrlCallback = ::resolveHttpUrl,
            getIframeUrlsCallback = ::getIframeUrls
        )

    private val vidSrcResolver =
        VidSrcResolver(
            applicationContext = applicationContext,
            sourceName = name,
            logMarkerCallback = ::logMarker,
            safeHostCallback = ::safeHost,
            resolveHttpUrlCallback = ::resolveHttpUrl,
            getIframeUrlsCallback = ::getIframeUrls,
            sha256PrefixCallback = ::sha256Prefix
        )

    private fun sha256Prefix(value: String): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            .take(12)
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

        peachifyResolver.resolveFromWatchPage(
            watchUrl = watchUrl,
            contentKind = contentKind,
            firstDocument = firstDocument,
            testedIframeUrls = testedIframeUrls,
            subtitleCallback = subtitleCallback,
            forwardingCallback = forwardingCallback
        )

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
                        vidSrcResolver.resolveValidatedPage(
                            pageUrl = iframeUrl,
                            referer = serverPageUrl,
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
                    vidSrcResolver.tryExactExtractor(
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

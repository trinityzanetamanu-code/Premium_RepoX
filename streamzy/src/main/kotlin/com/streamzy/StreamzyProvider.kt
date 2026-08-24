package com.streamzy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class StreamzyProvider : MainAPI() {

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
        private const val TMDB_IMAGE_BASE =
            "https://image.tmdb.org/t/p/w500"
    }

    /*
     * ============================================================
     * MAIN PAGE
     * ============================================================
     */

    override val mainPage = mainPageOf(

        "$mainUrl/movies" to
            "Popular Movies",

        "$mainUrl/tv" to
            "Popular TV Shows",

        "$mainUrl/trending" to
            "Trending",

        "$mainUrl/new-releases" to
            "New Movies",

        "$mainUrl/new-releases?type=tv" to
            "New TV Shows",

        "$mainUrl/top-rated" to
            "Top Rated Movies",

        "$mainUrl/top-rated?type=tv" to
            "Top Rated TV Shows",

        "$mainUrl/now-playing" to
            "Now Playing",

        "$mainUrl/upcoming" to
            "Upcoming Movies",

        /*
         * MOVIE GENRES
         */

        "$mainUrl/genre/action?type=movie" to
            "Action Movies",

        "$mainUrl/genre/adventure?type=movie" to
            "Adventure Movies",

        "$mainUrl/genre/comedy?type=movie" to
            "Comedy Movies",

        "$mainUrl/genre/crime?type=movie" to
            "Crime Movies",

        "$mainUrl/genre/drama?type=movie" to
            "Drama Movies",

        "$mainUrl/genre/fantasy?type=movie" to
            "Fantasy Movies",

        "$mainUrl/genre/horror?type=movie" to
            "Horror Movies",

        "$mainUrl/genre/mystery?type=movie" to
            "Mystery Movies",

        "$mainUrl/genre/romance?type=movie" to
            "Romance Movies",

        "$mainUrl/genre/sci-fi?type=movie" to
            "Sci-Fi Movies",

        "$mainUrl/genre/thriller?type=movie" to
            "Thriller Movies",

        /*
         * TV GENRES
         */

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

        /*
         * COUNTRIES
         */

        "$mainUrl/country/kr?type=movie" to
            "Korean Movies",

        "$mainUrl/country/kr?type=tv" to
            "Korean TV Shows",

        "$mainUrl/country/jp?type=movie" to
            "Japanese Movies",

        "$mainUrl/country/jp?type=tv" to
            "Japanese TV Shows",

        "$mainUrl/country/in?type=movie" to
            "Indian Movies",

        "$mainUrl/country/us?type=movie" to
            "American Movies"
    )

    /*
     * ============================================================
     * URL HELPERS
     * ============================================================
     */

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

        val clean =
            url.trim()

        return when {

            clean.startsWith(
                "https://"
            ) -> clean

            clean.startsWith(
                "http://"
            ) -> clean

            clean.startsWith(
                "/"
            ) -> "$mainUrl$clean"

            else ->
                "$mainUrl/$clean"
        }
    }

    /*
     * ============================================================
     * CLEAN DESCRIPTION
     * ============================================================
     */

    private fun cleanText(
        input: String?
    ): String? {

        if (input.isNullOrBlank()) {
            return null
        }

        return input
            /*
             * Streamzy kadang menghasilkan:
             *
             * boss&#0
             */
            .replace(
                Regex("""&#0;?"""),
                ""
            )
            .replace(
                "\u0000",
                ""
            )
            .replace(
                "&amp;",
                "&"
            )
            .replace(
                "&quot;",
                "\""
            )
            .replace(
                "&#39;",
                "'"
            )
            .replace(
                "&apos;",
                "'"
            )
            .replace(
                "&nbsp;",
                " "
            )
            .replace(
                "&lt;",
                "<"
            )
            .replace(
                "&gt;",
                ">"
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()
            .takeIf {
                it.isNotBlank()
            }
    }

    /*
     * ============================================================
     * YOUTUBE TRAILER
     * ============================================================
     */

    private fun normalizeYoutubeUrl(
        url: String?
    ): String? {

        if (url.isNullOrBlank()) {
            return null
        }

        val source =
            url.trim()

        /*
         * Streamzy:
         *
         * https://www.youtube.com/embed/VIDEO_ID
         */
        val embedId = Regex(
            """(?:youtube\.com|youtube-nocookie\.com)/embed/([^?&/"']+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)

        if (!embedId.isNullOrBlank()) {

            return "https://www.youtube.com/watch?v=$embedId"
        }

        /*
         * youtube.com/watch?v=...
         */
        val watchId = Regex(
            """[?&]v=([^?&/"']+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)

        if (!watchId.isNullOrBlank()) {

            return "https://www.youtube.com/watch?v=$watchId"
        }

        /*
         * youtu.be/...
         */
        val shortId = Regex(
            """youtu\.be/([^?&/"']+)""",
            RegexOption.IGNORE_CASE
        )
            .find(source)
            ?.groupValues
            ?.getOrNull(1)

        if (!shortId.isNullOrBlank()) {

            return "https://www.youtube.com/watch?v=$shortId"
        }

        return null
    }

    /*
     * ============================================================
     * GET STREAMZY TRAILER
     * ============================================================
     */

    private fun getTrailer(
        document: Document
    ): String? {

        /*
         * Confirmed HTML Streamzy:
         *
         * <a
         * href="https://www.youtube.com/embed/..."
         * >
         * Trailer
         * </a>
         */

        val trailerElement =
            document
                .select(
                    "a[href]"
                )
                .firstOrNull { element ->

                    val href =
                        element
                            .attr(
                                "href"
                            )
                            .trim()

                    val text =
                        element
                            .text()
                            .trim()

                    text.contains(
                        "Trailer",
                        ignoreCase = true
                    ) &&
                        (
                            href.contains(
                                "youtube.com",
                                ignoreCase = true
                            ) ||
                            href.contains(
                                "youtu.be",
                                ignoreCase = true
                            )
                        )
                }

        val rawUrl =
            trailerElement
                ?.attr(
                    "href"
                )
                ?.trim()

        return normalizeYoutubeUrl(
            rawUrl
        )
    }

    /*
     * ============================================================
     * CARD PARSER
     * ============================================================
     */

    private fun parseCard(
        element: Element
    ): SearchResponse? {

        val href =
            element
                .attr(
                    "href"
                )
                .trim()

        if (href.isBlank()) {
            return null
        }

        val title =
            element
                .selectFirst(
                    ".card-info h3"
                )
                ?.text()
                ?.trim()
                .orEmpty()

        if (title.isBlank()) {
            return null
        }

        val poster =
            element
                .selectFirst(
                    ".card-img img"
                )
                ?.attr(
                    "src"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        val year =
            element
                .selectFirst(
                    ".card-info span.text-zinc-500"
                )
                ?.text()
                ?.trim()
                ?.toIntOrNull()

        val targetUrl =
            absoluteUrl(
                href
            )
                ?: return null

        return when {

            href.startsWith(
                "/movie/"
            ) -> {

                newMovieSearchResponse(
                    name = title,
                    url = targetUrl,
                    type = TvType.Movie
                ) {

                    posterUrl =
                        poster

                    this.year =
                        year
                }
            }

            href.startsWith(
                "/tv/"
            ) -> {

                newTvSeriesSearchResponse(
                    name = title,
                    url = targetUrl,
                    type = TvType.TvSeries
                ) {

                    posterUrl =
                        poster

                    this.year =
                        year
                }
            }

            else -> null
        }
    }

    /*
     * ============================================================
     * MAIN PAGE LOADER
     * ============================================================
     */

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url =
            buildPageUrl(
                request.data,
                page
            )

        val document =
            app
                .get(
                    url
                )
                .document

        val items =
            document
                .select(
                    "a.card[href^=/movie/], " +
                        "a.card[href^=/tv/]"
                )
                .mapNotNull {

                    parseCard(
                        it
                    )
                }

        /*
         * Streamzy:
         * 20 item / page
         */
        val hasNext =
            items.size >= 20

        return newHomePageResponse(
            request,
            items,
            hasNext = hasNext
        )
    }

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     */

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {

        return search(
            query
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        if (query.isBlank()) {

            return emptyList()
        }

        /*
         * Confirmed:
         *
         * GET:
         *
         * https://streamzy.org/api/search?q=...
         */
        val response =
            app.get(
                "$mainUrl/api/search",
                params = mapOf(
                    "q" to query
                )
            )

        val data =
            response
                .parsedSafe<
                    StreamzySearchResponse
                    >()
                ?: return emptyList()

        return data
            .results
            .mapNotNull { item ->

                val mediaType =
                    item
                        .mediaType
                        .trim()
                        .lowercase()

                val poster =
                    item
                        .posterPath
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.let {

                            "$TMDB_IMAGE_BASE$it"
                        }

                val year =
                    item
                        .releaseDate
                        ?.take(4)
                        ?.toIntOrNull()

                when (
                    mediaType
                ) {

                    "movie" -> {

                        newMovieSearchResponse(
                            name =
                                item.title,

                            url =
                                "$mainUrl/movie/${item.id}",

                            type =
                                TvType.Movie
                        ) {

                            posterUrl =
                                poster

                            this.year =
                                year
                        }
                    }

                    "tv" -> {

                        newTvSeriesSearchResponse(
                            name =
                                item.title,

                            url =
                                "$mainUrl/tv/${item.id}",

                            type =
                                TvType.TvSeries
                        ) {

                            posterUrl =
                                poster

                            this.year =
                                year
                        }
                    }

                    else -> null
                }
            }
    }

    /*
     * ============================================================
     * LOAD ROUTER
     * ============================================================
     */

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            app
                .get(
                    url
                )
                .document

        val canonical =
            document
                .selectFirst(
                    "link[rel=canonical]"
                )
                ?.attr(
                    "href"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: url

        return when {

            canonical.contains(
                "/movie/"
            ) -> {

                loadMovie(
                    canonical,
                    document
                )
            }

            canonical.contains(
                "/tv/"
            ) -> {

                loadTv(
                    canonical,
                    document
                )
            }

            else -> null
        }
    }

    /*
     * ============================================================
     * MOVIE DETAIL
     * ============================================================
     */

    private suspend fun loadMovie(
        url: String,
        document: Document
    ): LoadResponse? {

        /*
         * TITLE
         */
        val title =
            document
                .selectFirst(
                    "h1"
                )
                ?.text()
                ?.trim()
                ?: return null

        /*
         * POSTER
         */
        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr(
                    "content"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        /*
         * BACKDROP
         */
        val backdrop =
            document
                .selectFirst(
                    ".hero-bg img"
                )
                ?.attr(
                    "src"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        /*
         * PLOT
         */
        val rawPlot =
            document
                .selectFirst(
                    "meta[property=og:description]"
                )
                ?.attr(
                    "content"
                )

        val plot =
            cleanText(
                rawPlot
            )

        /*
         * TRAILER
         *
         * Murni dari Streamzy.
         */
        val trailer =
            getTrailer(
                document
            )

        val pageText =
            document.text()

        /*
         * YEAR
         */
        val year =
            Regex(
                """Released:\s*(\d{4})"""
            )
                .find(
                    pageText
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?.toIntOrNull()

        /*
         * ========================================================
         * DURATION
         * ========================================================
         */

        val durationHour =
            Regex(
                """Duration:\s*(\d+)h\s*(\d+)m"""
            )
                .find(
                    pageText
                )
                ?.let { match ->

                    val hours =
                        match
                            .groupValues
                            .getOrNull(
                                1
                            )
                            ?.toIntOrNull()
                            ?: 0

                    val minutes =
                        match
                            .groupValues
                            .getOrNull(
                                2
                            )
                            ?.toIntOrNull()
                            ?: 0

                    hours * 60 +
                        minutes
                }

        val durationMinute =
            Regex(
                """Duration:\s*(\d+)\s*min"""
            )
                .find(
                    pageText
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?.toIntOrNull()

        val duration =
            durationHour
                ?: durationMinute

        /*
         * RATING
         */
        val rating =
            Regex(
                """IMDB:\s*([0-9.]+)"""
            )
                .find(
                    pageText
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )

        /*
         * GENRES
         */
        val genres =
            document
                .select(
                    "a[href^=/genre/][href*=type=movie]"
                )
                .map {

                    it.text()
                        .trim()
                }
                .filter {

                    it.isNotBlank()
                }
                .distinct()

        /*
         * CONTENT RATING
         */
        val contentRating =
            Regex(
                """\b(PG-13|NC-17|PG|R|G|NR)\b"""
            )
                .find(
                    pageText
                )
                ?.value

        /*
         * CAST
         */
        val actors =
            parseActors(
                document
            )

        return newMovieLoadResponse(
            name =
                title,

            url =
                url,

            type =
                TvType.Movie,

            /*
             * Playback utama belum dibuat.
             */
            dataUrl =
                ""
        ) {

            posterUrl =
                poster

            backgroundPosterUrl =
                backdrop

            this.year =
                year

            this.plot =
                plot

            /*
             * Hilangkan label
             * "Segera hadir..."
             */
            comingSoon =
                false

            /*
             * DURATION
             */
            if (
                duration != null
            ) {

                this.duration =
                    duration
            }

            /*
             * GENRES
             */
            if (
                genres.isNotEmpty()
            ) {

                tags =
                    genres
            }

            /*
             * CONTENT RATING
             */
            if (
                !contentRating
                    .isNullOrBlank()
            ) {

                this.contentRating =
                    contentRating
            }

            /*
             * RATING
             */
            if (
                !rating
                    .isNullOrBlank()
            ) {

                score =
                    Score.from(
                        rating,
                        10
                    )
            }

            /*
             * CAST
             */
            if (
                actors.isNotEmpty()
            ) {

                this.actors =
                    actors
            }

            /*
             * ====================================================
             * TRAILER
             * ====================================================
             *
             * Mengikuti pola:
             *
             * Adicinemax21.kt
             *
             * addTrailer(trailer)
             */
            addTrailer(
                trailer
            )
        }
    }

    /*
     * ============================================================
     * TV DETAIL
     * ============================================================
     */

    private suspend fun loadTv(
        url: String,
        document: Document
    ): LoadResponse? {

        /*
         * TITLE
         */
        val title =
            document
                .selectFirst(
                    "h1"
                )
                ?.text()
                ?.trim()
                ?: return null

        /*
         * POSTER
         */
        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr(
                    "content"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        /*
         * BACKDROP
         */
        val backdrop =
            document
                .selectFirst(
                    ".hero-bg img"
                )
                ?.attr(
                    "src"
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        /*
         * PLOT
         */
        val rawPlot =
            document
                .selectFirst(
                    "meta[property=og:description]"
                )
                ?.attr(
                    "content"
                )

        val plot =
            cleanText(
                rawPlot
            )

        /*
         * Trailer TV kalau halaman
         * Streamzy menyediakannya.
         *
         * Kalau tidak ada, null.
         */
        val trailer =
            getTrailer(
                document
            )

        val pageText =
            document.text()

        /*
         * YEAR
         */
        val year =
            Regex(
                """Released:\s*(\d{4})"""
            )
                .find(
                    pageText
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )
                ?.toIntOrNull()

        /*
         * RATING
         */
        val rating =
            Regex(
                """IMDB:\s*([0-9.]+)"""
            )
                .find(
                    pageText
                )
                ?.groupValues
                ?.getOrNull(
                    1
                )

        /*
         * GENRES
         */
        val genres =
            document
                .select(
                    "a[href^=/genre/][href*=type=tv]"
                )
                .map {

                    it.text()
                        .trim()
                }
                .filter {

                    it.isNotBlank()
                }
                .distinct()

        /*
         * CONTENT RATING
         */
        val contentRating =
            Regex(
                """\b(TV-MA|TV-14|TV-PG|TV-G|TV-Y7|TV-Y)\b"""
            )
                .find(
                    pageText
                )
                ?.value

        /*
         * CAST
         */
        val actors =
            parseActors(
                document
            )

        /*
         * ========================================================
         * SEASONS
         * ========================================================
         *
         * Streamzy detail:
         *
         * /watch/tv/{id}/1/1
         * /watch/tv/{id}/2/1
         * ...
         *
         * Saat ini hanya kita pakai
         * untuk nama season.
         */

        val seasonNames =
            document
                .select(
                    "a.card[href^=/watch/tv/] h3"
                )
                .mapNotNull { element ->

                    val text =
                        element
                            .text()
                            .trim()

                    val season =
                        Regex(
                            """Season\s+(\d+)"""
                        )
                            .find(
                                text
                            )
                            ?.groupValues
                            ?.getOrNull(
                                1
                            )
                            ?.toIntOrNull()
                            ?: return@mapNotNull null

                    SeasonData(
                        season =
                            season,

                        name =
                            "Season $season"
                    )
                }
                .distinctBy {
                    it.season
                }

        /*
         * Episode belum diimplementasikan.
         */
        val episodes =
            emptyList<Episode>()

        return newTvSeriesLoadResponse(
            name =
                title,

            url =
                url,

            type =
                TvType.TvSeries,

            episodes =
                episodes
        ) {

            posterUrl =
                poster

            backgroundPosterUrl =
                backdrop

            this.year =
                year

            this.plot =
                plot

            comingSoon =
                false

            /*
             * GENRES
             */
            if (
                genres.isNotEmpty()
            ) {

                tags =
                    genres
            }

            /*
             * CONTENT RATING
             */
            if (
                !contentRating
                    .isNullOrBlank()
            ) {

                this.contentRating =
                    contentRating
            }

            /*
             * SCORE
             */
            if (
                !rating
                    .isNullOrBlank()
            ) {

                score =
                    Score.from(
                        rating,
                        10
                    )
            }

            /*
             * ACTORS
             */
            if (
                actors.isNotEmpty()
            ) {

                this.actors =
                    actors
            }

            /*
             * SEASON NAMES
             */
            if (
                seasonNames.isNotEmpty()
            ) {

                this.seasonNames =
                    seasonNames
            }

            /*
             * Trailer hanya ditambah
             * kalau halaman TV punya trailer.
             */
            addTrailer(
                trailer
            )
        }
    }

    /*
     * ============================================================
     * ACTOR PARSER
     * ============================================================
     */

    private fun parseActors(
        document: Document
    ): List<ActorData> {

        return document
            .select(
                "a[href^=/person/]"
            )
            .mapNotNull { element ->

                /*
                 * Actor cards mempunyai image.
                 *
                 * Link Director/Writer tanpa image
                 * dilewati.
                 */
                val imageElement =
                    element
                        .selectFirst(
                            "img"
                        )
                        ?: return@mapNotNull null

                val image =
                    imageElement
                        .attr(
                            "src"
                        )
                        .trim()
                        .takeIf {

                            it.isNotBlank()
                        }

                /*
                 * Nama actor.
                 */
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
                            .attr(
                                "alt"
                            )
                            .substringBefore(
                                " in "
                            )
                            .trim()
                            .takeIf {

                                it.isNotBlank()
                            }

                        ?: return@mapNotNull null

                /*
                 * Role/character.
                 */
                val role =
                    element
                        .select(
                            "p"
                        )
                        .drop(
                            1
                        )
                        .firstOrNull()
                        ?.text()
                        ?.trim()
                        ?.takeIf {

                            it.isNotBlank()
                        }

                ActorData(
                    actor =
                        Actor(
                            name =
                                actorName,

                            image =
                                image
                        ),

                    roleString =
                        role
                )
            }
            .distinctBy {

                it.actor.name
            }
    }

    /*
     * ============================================================
     * LOAD LINKS
     * ============================================================
     *
     * Film/episode utama BELUM kita implementasikan.
     *
     * Trailer tidak lewat loadLinks().
     * Trailer lewat addTrailer().
     * ============================================================
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback:
            (SubtitleFile) -> Unit,
        callback:
            (ExtractorLink) -> Unit
    ): Boolean {

        return false
    }
}

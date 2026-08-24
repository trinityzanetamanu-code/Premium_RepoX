package com.streamzy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
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

    /*
     * Streamzy /api/search mengembalikan poster_path seperti:
     * /f1VCQIG2iCyOookdgOzwtUpwWC0.jpg
     *
     * Ini hanya base CDN gambar.
     * Tidak memakai TMDB API dan tidak membutuhkan API key.
     */
    companion object {
        private const val POSTER_CDN =
            "https://image.tmdb.org/t/p/w500"
    }

    /*
     * ============================================================
     * SEARCH MODELS
     * ============================================================
     *
     * Berdasarkan hasil probe:
     *
     * {
     *   "results": [
     *     {
     *       "id": 108978,
     *       "media_type": "tv",
     *       "title": "Reacher",
     *       "release_date": "2022-02-03",
     *       "poster_path": "...",
     *       "vote_average": 8.1
     *     }
     *   ]
     * }
     */

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

    /*
     * ============================================================
     * INTERNAL SEASON INFO
     * ============================================================
     */

    private data class StreamzySeasonInfo(
        val season: Int,
        val episodeCount: Int
    )

    /*
     * ============================================================
     * MAIN PAGE
     * ============================================================
     */

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

    /*
     * ============================================================
     * BASIC HELPERS
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

    /*
     * ============================================================
     * MAIN PAGE CARD
     * ============================================================
     */

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

        val poster = element
            .selectFirst(".card-img img")
            ?.attr("src")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

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

    /*
     * ============================================================
     * GET MAIN PAGE
     * ============================================================
     */

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

    /*
     * ============================================================
     * SEARCH
     * ============================================================
     */

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse>? {

        return search(query)
    }

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

    /*
     * ============================================================
     * LOAD
     * ============================================================
     */

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

    /*
     * ============================================================
     * MOVIE
     * ============================================================
     */

    private suspend fun loadMovie(
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

        /*
         * YEAR
         */

        val year =
            Regex(
                """Released:\s*(\d{4})""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        /*
         * DURATION
         *
         * Examples:
         * 2h 28m
         * 95 min
         */

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

        /*
         * RATING
         */

        val rating =
            Regex(
                """(?:IMDB|IMDb)\s*:?\s*([0-9.]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)

        /*
         * GENRES
         */

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

        /*
         * CONTENT RATING
         */

        val contentRating =
            Regex(
                """\b(PG-13|NC-17|PG|R|G|NR)\b"""
            )
                .find(pageText)
                ?.value

        /*
         * ACTORS
         */

        val actors =
            parseActors(document)

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            dataUrl = ""
        ) {

            posterUrl = poster
            backgroundPosterUrl = backdrop

            this.year = year
            this.plot = plot

            /*
             * Detail page benar-benar ada,
             * jadi jangan ditandai coming soon hanya
             * karena dataUrl kosong.
             */
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

            /*
             * TRAILER SENGAJA TIDAK DIPAKAI.
             *
             * Tidak ada addTrailer().
             */
        }
    }

    /*
     * ============================================================
     * SEASON / EPISODE PARSER
     * ============================================================
     *
     * Dari hasil V2 Reacher:
     *
     * /watch/tv/108978/1/1
     * /watch/tv/108978/2/1
     * /watch/tv/108978/3/1
     * /watch/tv/108978/4/1
     *
     * Halaman detail hanya mengekspos E01,
     * tetapi kartu season menyatakan:
     *
     * Season 1 8 Episodes
     * Season 2 8 Episodes
     * ...
     *
     * Karena itu kita pakai public link hanya
     * untuk menemukan season yang benar.
     *
     * Jumlah episode diambil dari teks halaman.
     */

    private fun parseSeasonInfo(
        document: Document,
        tvId: Int
    ): List<StreamzySeasonInfo> {

        val pageText =
            document.text()

        /*
         * Cari season dari link yang benar-benar
         * terdapat pada halaman detail.
         */

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
            .map { season ->

                /*
                 * Coba cari:
                 *
                 * Season 1 8 Episodes
                 */

                val episodeCount =
                    Regex(
                        """Season\s+$season\b.{0,120}?(\d+)\s+Episodes?""",
                        RegexOption.IGNORE_CASE
                    )
                        .find(pageText)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                        /*
                         * Kalau jumlah episode tidak ditemukan,
                         * setidaknya E01 memang secara eksplisit
                         * tersedia pada halaman detail.
                         */
                        ?: 1

                StreamzySeasonInfo(
                    season = season,
                    episodeCount = episodeCount
                )
            }
    }

    /*
     * ============================================================
     * TV SERIES
     * ============================================================
     */

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

        /*
         * STREAMZY TV ID
         */

        val tvId =
            Regex(
                """/tv/(\d+)"""
            )
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        /*
         * YEAR
         */

        val year =
            Regex(
                """(?:Released|First Air Date):\s*(\d{4})""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        /*
         * RATING
         */

        val rating =
            Regex(
                """(?:IMDB|IMDb)\s*:?\s*([0-9.]+)""",
                RegexOption.IGNORE_CASE
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)

        /*
         * GENRES
         */

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

        /*
         * CONTENT RATING
         */

        val contentRating =
            Regex(
                """\b(TV-MA|TV-14|TV-PG|TV-G|TV-Y7|TV-Y)\b"""
            )
                .find(pageText)
                ?.value

        /*
         * ACTORS
         */

        val actors =
            parseActors(document)

        /*
         * SEASON INFO
         */

        val seasonInfo =
            tvId
                ?.let {
                    parseSeasonInfo(
                        document,
                        it
                    )
                }
                .orEmpty()

        /*
         * NAMA SEASON
         */

        val seasonNames =
            seasonInfo.map {
                SeasonData(
                    season = it.season,
                    name = "Season ${it.season}"
                )
            }

        /*
         * ========================================================
         * BUILD EPISODE LIST
         * ========================================================
         *
         * Kita TIDAK membuat /watch URL palsu untuk E02 dst.
         *
         * Data episode berupa internal identifier saja:
         *
         * streamzy://episode/108978/1/2
         *
         * loadLinks() tetap false.
         */

        val episodes =
            if (tvId != null) {

                seasonInfo.flatMap { season ->

                    (1..season.episodeCount)
                        .map { episodeNumber ->

                            val episodeData =
                                "streamzy://episode/" +
                                    "$tvId/" +
                                    "${season.season}/" +
                                    episodeNumber

                            newEpisode(
                                url = episodeData,
                                fix = false
                            ) {

                                name =
                                    "Episode $episodeNumber"

                                this.season =
                                    season.season

                                this.episode =
                                    episodeNumber
                            }
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

            /*
             * TRAILER SENGAJA TIDAK DIPAKAI.
             */
        }
    }

    /*
     * ============================================================
     * ACTORS
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

    /*
     * ============================================================
     * LOAD LINKS
     * ============================================================
     *
     * Belum ada playback.
     */

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (
            SubtitleFile
        ) -> Unit,
        callback: (
            ExtractorLink
        ) -> Unit
    ): Boolean {

        return false
    }
}

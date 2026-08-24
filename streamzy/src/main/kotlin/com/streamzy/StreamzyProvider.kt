package com.streamzy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class StreamzyProvider : MainAPI() {

    override var mainUrl = "https://streamzy.org"
    override var name = "Streamzy"
    override var lang = "en"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val tmdbImageBase = "https://image.tmdb.org/t/p/w500"

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
        "$mainUrl/genre/drama?type=movie" to "Drama Movies",
        "$mainUrl/genre/comedy?type=movie" to "Comedy Movies",
        "$mainUrl/genre/horror?type=movie" to "Horror Movies",
        "$mainUrl/genre/romance?type=movie" to "Romance Movies",

        "$mainUrl/country/kr?type=movie" to "Korean Movies",
        "$mainUrl/country/kr?type=tv" to "Korean TV Shows"
    )

    /*
     * Tambah page dengan benar.
     *
     * /movies
     * -> /movies?page=2
     *
     * /genre/action?type=movie
     * -> /genre/action?type=movie&page=2
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

    /*
     * ============================================================
     * CARD PARSER
     * ============================================================
     */

    private fun parseCard(
        element: org.jsoup.nodes.Element
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

        return when {

            href.startsWith("/movie/") -> {

                newMovieSearchResponse(
                    name = title,
                    url = href,
                    type = TvType.Movie
                ) {

                    posterUrl = poster

                    this.year = year
                }
            }

            href.startsWith("/tv/") -> {

                newTvSeriesSearchResponse(
                    name = title,
                    url = href,
                    type = TvType.TvSeries
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

        val document = app
            .get(url)
            .document

        val items = document
            .select(
                "a.card[href^=/movie/], " +
                "a.card[href^=/tv/]"
            )
            .mapNotNull {
                parseCard(it)
            }

        /*
         * Streamzy umumnya menampilkan 20 item per halaman.
         *
         * Kalau kurang dari 20 kemungkinan sudah halaman terakhir.
         */
        val hasNext = items.size >= 20

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
        ).parsedSafe<StreamzySearchResponse>()
            ?: return emptyList()

        return response
            .results
            .mapNotNull { item ->

                val poster = item
                    .posterPath
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.let {
                        "$tmdbImageBase$it"
                    }

                val year = item
                    .releaseDate
                    ?.take(4)
                    ?.toIntOrNull()

                when (
                    item.mediaType.lowercase()
                ) {

                    "movie" -> {

                        newMovieSearchResponse(
                            name = item.title,
                            url = "/movie/${item.id}",
                            type = TvType.Movie
                        ) {

                            posterUrl = poster

                            this.year = year
                        }
                    }

                    "tv" -> {

                        newTvSeriesSearchResponse(
                            name = item.title,
                            url = "/tv/${item.id}",
                            type = TvType.TvSeries
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

        val document = app
            .get(url)
            .document

        val canonical = document
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

            canonical.contains("/movie/") -> {

                loadMovie(
                    canonical,
                    document
                )
            }

            canonical.contains("/tv/") -> {

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

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?: return null

        /*
         * Poster.
         *
         * Stage 4 mengonfirmasi og:image sebagai poster.
         */
        val poster = document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        /*
         * Hero background.
         */
        val backdrop = document
            .selectFirst(
                ".hero-bg img"
            )
            ?.attr("src")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        /*
         * Synopsis.
         */
        val plot = document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val pageText = document.text()

        /*
         * Released: 2021-12-15
         */
        val year = Regex(
            """Released:\s*(\d{4})"""
        )
            .find(pageText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        /*
         * Duration contoh:
         *
         * Duration: 2h 28m
         */
        val duration = Regex(
            """Duration:\s*(\d+)h\s*(\d+)m"""
        )
            .find(pageText)
            ?.let { match ->

                val hours = match
                    .groupValues
                    .getOrNull(1)
                    ?.toIntOrNull()
                    ?: 0

                val minutes = match
                    .groupValues
                    .getOrNull(2)
                    ?.toIntOrNull()
                    ?: 0

                (hours * 60) + minutes
            }

        /*
         * Fallback jika format hanya menit.
         */
        val durationMinutes = duration
            ?: Regex(
                """Duration:\s*(\d+)\s*min"""
            )
                .find(pageText)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        /*
         * IMDB: 7.9
         */
        val rating = Regex(
            """IMDB:\s*([0-9.]+)"""
        )
            .find(pageText)
            ?.groupValues
            ?.getOrNull(1)

        /*
         * Genre links sudah dikonfirmasi Stage 4.
         */
        val genres = document
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
         * Movie content rating.
         *
         * Contoh:
         * PG-13
         */
        val contentRating = Regex(
            """\b(PG-13|NC-17|PG|R|G|NR)\b"""
        )
            .find(pageText)
            ?.value

        /*
         * Actor cards.
         */
        val actors = parseActors(
            document
        )

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,

            /*
             * Playback belum diimplementasikan,
             * jadi dataUrl dibuat kosong.
             */
            dataUrl = ""
        ) {

            posterUrl = poster

            backgroundPosterUrl = backdrop

            this.year = year

            this.plot = plot

            if (durationMinutes != null) {
                this.duration = durationMinutes
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

            /*
             * MainAPI.kt:
             *
             * score: Score?
             *
             * gunakan Score.from(...)
             * secara langsung.
             */
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

    /*
     * ============================================================
     * TV DETAIL
     * ============================================================
     */

    private suspend fun loadTv(
        url: String,
        document: Document
    ): LoadResponse? {

        val title = document
            .selectFirst("h1")
            ?.text()
            ?.trim()
            ?: return null

        val poster = document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val backdrop = document
            .selectFirst(
                ".hero-bg img"
            )
            ?.attr("src")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val plot = document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }

        val pageText = document.text()

        /*
         * Released: 2022-02-03
         */
        val year = Regex(
            """Released:\s*(\d{4})"""
        )
            .find(pageText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        /*
         * IMDB: 8.1
         */
        val rating = Regex(
            """IMDB:\s*([0-9.]+)"""
        )
            .find(pageText)
            ?.groupValues
            ?.getOrNull(1)

        val genres = document
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

        val contentRating = Regex(
            """\b(TV-MA|TV-14|TV-PG|TV-G|TV-Y7|TV-Y)\b"""
        )
            .find(pageText)
            ?.value

        val actors = parseActors(
            document
        )

        /*
         * Dari Stage 4 kita tahu jumlah season
         * tersedia di halaman detail.
         *
         * Kita ambil informasi season untuk
         * seasonNames saja.
         */
        val seasonNames = document
            .select(
                "a.card[href^=/watch/tv/] h3"
            )
            .mapNotNull { element ->

                val text = element
                    .text()
                    .trim()

                val number = Regex(
                    """Season\s+(\d+)"""
                )
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: return@mapNotNull null

                SeasonData(
                    season = number,
                    name = "Season $number"
                )
            }
            .distinctBy {
                it.season
            }

        /*
         * Kita belum mempunyai daftar episode
         * publik terpisah dari route playback.
         *
         * Jadi untuk saat ini jangan membuat
         * episode palsu.
         */
        val episodes = emptyList<Episode>()

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = episodes
        ) {

            posterUrl = poster

            backgroundPosterUrl = backdrop

            this.year = year

            this.plot = plot

            if (
                genres.isNotEmpty()
            ) {
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

            if (
                actors.isNotEmpty()
            ) {
                this.actors = actors
            }

            if (
                seasonNames.isNotEmpty()
            ) {
                this.seasonNames =
                    seasonNames
            }
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
                 * Hanya ambil link person yang
                 * benar-benar punya image actor.
                 *
                 * Ini mencegah Director / Writer
                 * metadata dianggap actor.
                 */
                val imageElement = element
                    .selectFirst("img")
                    ?: return@mapNotNull null

                val image = imageElement
                    .attr("src")
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }

                /*
                 * Stage 4:
                 *
                 * <p class="... text-white ...">
                 *     Alan Ritchson
                 * </p>
                 */
                val actorName = element
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

                /*
                 * Role biasanya p kedua.
                 */
                val role = element
                    .select(
                        "p"
                    )
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
     * Belum diimplementasikan.
     *
     * Provider katalog, search dan metadata
     * bisa kita build/test lebih dulu.
     * ============================================================
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

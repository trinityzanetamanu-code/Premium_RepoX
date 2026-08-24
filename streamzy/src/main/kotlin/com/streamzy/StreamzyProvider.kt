package com.streamzy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class StreamzyProvider : MainAPI() {

    override var mainUrl = "https://streamzy.org"
    override var name = "Streamzy"
    override var lang = "en"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val tmdbImage = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Popular Movies",
        "$mainUrl/tv" to "Popular TV Shows",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/new-releases" to "New Movies",
        "$mainUrl/new-releases?type=tv" to "New TV Shows",
        "$mainUrl/top-rated" to "Top Rated Movies",
        "$mainUrl/top-rated?type=tv" to "Top Rated TV Shows",
        "$mainUrl/now-playing" to "Now Playing",
        "$mainUrl/upcoming" to "Upcoming"
    )

    private fun buildPageUrl(
        base: String,
        page: Int
    ): String {
        if (page <= 1) return base

        return if ("?" in base) {
            "$base&page=$page"
        } else {
            "$base?page=$page"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = buildPageUrl(
            request.data,
            page
        )

        val document = app.get(url).document

        val items = document
            .select("a.card[href^=/movie/], a.card[href^=/tv/]")
            .mapNotNull { card ->

                val href = card.attr("href")

                if (href.isBlank()) {
                    return@mapNotNull null
                }

                val title = card
                    .selectFirst(".card-info h3")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                if (title.isBlank()) {
                    return@mapNotNull null
                }

                val poster = card
                    .selectFirst(".card-img img")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }

                val year = card
                    .selectFirst(".card-info span.text-zinc-500")
                    ?.text()
                    ?.trim()
                    ?.toIntOrNull()

                when {
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

        return newHomePageResponse(
            request,
            items,
            hasNext = items.isNotEmpty()
        )
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
        ).parsedSafe<StreamzySearchResponse>()
            ?: return emptyList()

        return response.results.mapNotNull { item ->

            val poster = item.posterPath
                ?.takeIf { it.isNotBlank() }
                ?.let { "$tmdbImage$it" }

            val year = item.releaseDate
                ?.take(4)
                ?.toIntOrNull()

            when (item.mediaType.lowercase()) {

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

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val canonical = document
            .selectFirst("link[rel=canonical]")
            ?.attr("href")
            ?.ifBlank { url }
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

    private suspend fun loadMovie(
        url: String,
        document: org.jsoup.nodes.Document
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
            ?.takeIf { it.isNotBlank() }

        val backdrop = document
            .selectFirst(".hero-bg img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        val plot = document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        val text = document.text()

        val year = Regex(
            """Released:\s*(\d{4})"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val duration = Regex(
            """Duration:\s*(\d+)h\s*(\d+)m"""
        ).find(text)
            ?.let {
                val hours = it.groupValues[1].toIntOrNull() ?: 0
                val minutes = it.groupValues[2].toIntOrNull() ?: 0

                hours * 60 + minutes
            }

        val rating = Regex(
            """IMDB:\s*([0-9.]+)"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)

        val genres = document
            .select("a[href^=/genre/][href*=type=movie]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val contentRating = Regex(
            """\b(PG-13|PG|R|NC-17|G|NR)\b"""
        ).find(text)
            ?.value

        val cast = document
            .select("a[href^=/person/]")
            .mapNotNull { person ->

                val img = person
                    .selectFirst("img")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }

                val name = person
                    .selectFirst("p.text-white")
                    ?.text()
                    ?.trim()
                    ?: person
                        .selectFirst("img")
                        ?.attr("alt")
                        ?.substringBefore(" in ")

                if (name.isNullOrBlank()) {
                    null
                } else {
                    ActorData(
                        Actor(name, img)
                    )
                }
            }
            .distinctBy {
                it.actor.name
            }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = ""
        ) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot

            if (duration != null) {
                this.duration = duration
            }

            if (genres.isNotEmpty()) {
                tags = genres
            }

            if (!contentRating.isNullOrBlank()) {
                this.contentRating = contentRating
            }

            if (!rating.isNullOrBlank()) {
                addScore(rating)
            }

            if (cast.isNotEmpty()) {
                actors = cast
            }
        }
    }

    private suspend fun loadTv(
        url: String,
        document: org.jsoup.nodes.Document
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
            ?.takeIf { it.isNotBlank() }

        val backdrop = document
            .selectFirst(".hero-bg img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }

        val plot = document
            .selectFirst(
                "meta[property=og:description]"
            )
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        val text = document.text()

        val year = Regex(
            """Released:\s*(\d{4})"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val rating = Regex(
            """IMDB:\s*([0-9.]+)"""
        ).find(text)
            ?.groupValues
            ?.getOrNull(1)

        val genres = document
            .select("a[href^=/genre/][href*=type=tv]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val contentRating = Regex(
            """\b(TV-MA|TV-14|TV-PG|TV-G|TV-Y7|TV-Y)\b"""
        ).find(text)
            ?.value

        val cast = document
            .select("a[href^=/person/]")
            .mapNotNull { person ->

                val img = person
                    .selectFirst("img")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }

                val name = person
                    .selectFirst("p.text-white")
                    ?.text()
                    ?.trim()
                    ?: person
                        .selectFirst("img")
                        ?.attr("alt")
                        ?.substringBefore(" in ")

                if (name.isNullOrBlank()) {
                    null
                } else {
                    ActorData(
                        Actor(name, img)
                    )
                }
            }
            .distinctBy {
                it.actor.name
            }

        /*
         * Episode playback belum kita implementasikan.
         *
         * Halaman publik detail hanya mengonfirmasi card season.
         * Karena kita belum mempunyai metadata episode publik yang
         * terpisah dari route playback, jangan membuat episode palsu.
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

            if (genres.isNotEmpty()) {
                tags = genres
            }

            if (!contentRating.isNullOrBlank()) {
                this.contentRating = contentRating
            }

            if (!rating.isNullOrBlank()) {
                addScore(rating)
            }

            if (cast.isNotEmpty()) {
                actors = cast
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}

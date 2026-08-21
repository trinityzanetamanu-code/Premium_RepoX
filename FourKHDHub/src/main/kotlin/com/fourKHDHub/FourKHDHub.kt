package com.fourKHDHub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FourKHDHub : MainAPI() {
    override var name = "FourKHDHub"
    override var mainUrl = "https://4khdhub.one" 
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val tmdbApiKey = "1865f43a0549ca50d341dd9ab8b29f49"

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/page/" to "Movies",
        "$mainUrl/category/hindi-movies/page/" to "Hindi Movies",
        "$mainUrl/category/english-movies/page/" to "English Movies",
        "$mainUrl/category/disney/page/" to "Disney+",
        "$mainUrl/category/apple-tv/page/" to "Apple TV+",
        "$mainUrl/category/anime/page/" to "Anime",
        "$mainUrl/category/4k-hdr/page/" to "4K HDR",
        "$mainUrl/category/netflix/page/" to "Netflix",
        "$mainUrl/category/amazon-prime-video/page/" to "Amazon Prime Video",
        "$mainUrl/category/jiohotstar/page/" to "JioHotstar"
    )

    // Helper untuk memperbarui mainUrl dari remote
    private suspend fun updateMainUrl() {
        FourKHDHubProvider.getDomains()?.n4khdhub?.let { mainUrl = it }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrl()
        val document = app.get(request.data + page).document
        val home = document.select(".movie-card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst(".movie-card-title")?.text() ?: ""
        val href = this.selectFirst("a")?.attr("href") ?: ""
        val poster = this.selectFirst("img")?.attr("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        updateMainUrl()
        val document = app.get("$mainUrl/?s=$query").document
        return document.select(".movie-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        updateMainUrl()
        val document = app.get(url).document
        
        val rawTitle = document.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: ""
        val posterFallback = document.selectFirst("meta[property=og:image]")?.attr("content")
        var plot = document.selectFirst("meta[name=description]")?.attr("content")
        
        val htmlTags = document.select("a[rel=tag]").map { it.text() }.filter { it.isNotBlank() }

        var score: Score? = null
        var year: Int? = null
        var tmdbId: Int? = null
        var mediaType = "movie"

        try {
            val searchApi = "https://api.themoviedb.org/3/search/multi?api_key=$tmdbApiKey&query=$rawTitle"
            val tmdbRes = app.get(searchApi).parsedSafe<TmdbSearch>()
            val match = tmdbRes?.results?.firstOrNull()
            if (match != null) {
                tmdbId = match.id
                mediaType = match.media_type ?: "movie"
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val isSeries = mediaType == "tv" || document.html().contains("season", true)

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            var tmdbTags = listOf<String>()
            var tmdbActors = listOf<ActorData>()
            var tmdbTrailers = mutableListOf<TrailerData>()
            var backdrop: String? = null
            var poster: String? = posterFallback

            if (tmdbId != null) {
                try {
                    val tvApi = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos"
                    val tvDetails = app.get(tvApi).parsedSafe<TmdbTv>()
                    
                    if (tvDetails != null) {
                        plot = tvDetails.overview ?: plot
                        year = tvDetails.first_air_date?.substringBefore("-")?.toIntOrNull()
                        score = tvDetails.vote_average?.let { Score.from(it, 10) }
                        backdrop = tvDetails.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
                        poster = tvDetails.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster
                        
                        tmdbTags = tvDetails.genres?.mapNotNull { it.name } ?: emptyList()
                        tmdbActors = tvDetails.credits?.cast?.mapNotNull { cast ->
                            val imageUrl = cast.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                            ActorData(Actor(cast.name ?: "", imageUrl), roleString = cast.character)
                        } ?: emptyList()

                        tvDetails.videos?.results?.filter { it.site == "YouTube" && it.type == "Trailer" }?.forEach { vid ->
                            vid.key?.let { key ->
                                tmdbTrailers.add(TrailerData("https://www.youtube.com/watch?v=$key", null, false))
                            }
                        }

                        tvDetails.seasons?.forEach { season ->
                            val seasonNum = season.season_number ?: 1
                            if (seasonNum > 0) {
                                val seasonApi = "https://api.themoviedb.org/3/tv/$tmdbId/season/$seasonNum?api_key=$tmdbApiKey"
                                val seasonDetails = app.get(seasonApi).parsedSafe<TmdbSeason>()
                                
                                seasonDetails?.episodes?.forEach { ep ->
                                    // PERBAIKAN: gunakan newEpisode(url = ...) bukan newEpisode(data = ...)
                                    episodes.add(newEpisode(url = url) {
                                        this.name = ep.name
                                        this.season = seasonNum
                                        this.episode = ep.episode_number
                                        this.description = ep.overview
                                        this.posterUrl = ep.still_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster
                                        ep.vote_average?.let { this.score = Score.from(it, 10) }
                                        ep.air_date?.let { this.addDate(it) }
                                    })
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (episodes.isEmpty()) {
                // PERBAIKAN: gunakan newEpisode(url = url)
                episodes.add(newEpisode(url = url) {
                    this.name = rawTitle
                    this.season = 1
                    this.episode = 1
                })
            }

            val combinedTags = (tmdbTags + htmlTags).distinct()

            return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = combinedTags
                this.actors = tmdbActors
                this.trailers = tmdbTrailers
                tmdbId?.toString()?.let { this.syncData["tmdb"] = it }
            }

        } else {
            // Movie path
            var tmdbTags = listOf<String>()
            var tmdbActors = listOf<ActorData>()
            var tmdbTrailers = mutableListOf<TrailerData>()
            var backdrop: String? = null
            var poster: String? = posterFallback

            if (tmdbId != null) {
                try {
                    val movieApi = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,videos"
                    val movieDetails = app.get(movieApi).parsedSafe<TmdbMovieDetails>()
                    
                    if (movieDetails != null) {
                        plot = movieDetails.overview ?: plot
                        year = movieDetails.release_date?.substringBefore("-")?.toIntOrNull()
                        score = movieDetails.vote_average?.let { Score.from(it, 10) }
                        backdrop = movieDetails.backdrop_path?.let { "https://image.tmdb.org/t/p/original$it" }
                        poster = movieDetails.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: poster
                        
                        tmdbTags = movieDetails.genres?.mapNotNull { it.name } ?: emptyList()
                        tmdbActors = movieDetails.credits?.cast?.mapNotNull { cast ->
                            val imageUrl = cast.profile_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                            ActorData(Actor(cast.name ?: "", imageUrl), roleString = cast.character)
                        } ?: emptyList()

                        movieDetails.videos?.results?.filter { it.site == "YouTube" && it.type == "Trailer" }?.forEach { vid ->
                            vid.key?.let { key ->
                                tmdbTrailers.add(TrailerData("https://www.youtube.com/watch?v=$key", null, false))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val combinedTags = (tmdbTags + htmlTags).distinct()

            return newMovieLoadResponse(rawTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = combinedTags
                this.actors = tmdbActors
                this.trailers = tmdbTrailers
                tmdbId?.toString()?.let { this.syncData["tmdb"] = it }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("a[href], iframe[src]").forEach { element ->
            val link = element.attr("href").ifEmpty { element.attr("src") }
            if (link.isNotBlank()) {
                // PERBAIKAN: ubah URL relatif menjadi absolut
                val fixedLink = fixUrl(link)
                if (fixedLink.contains("hubcloud") || fixedLink.contains("pixeldrain") || fixedLink.contains("hubdrive")) {
                    loadExtractor(fixedLink, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // ===== TMDB DTOs (tidak berubah) =====
    data class TmdbSearch(val results: List<TmdbResult>?)
    data class TmdbResult(val id: Int?, val media_type: String?)
    data class TmdbMovieDetails(
        val overview: String?, val vote_average: Double?, val release_date: String?,
        val backdrop_path: String?, val poster_path: String?,
        val genres: List<TmdbGenre>?, val credits: TmdbCredits?, val videos: TmdbVideos?
    )
    data class TmdbTv(
        val overview: String?, val vote_average: Double?, val first_air_date: String?,
        val backdrop_path: String?, val poster_path: String?,
        val genres: List<TmdbGenre>?, val credits: TmdbCredits?, val videos: TmdbVideos?,
        val seasons: List<TmdbSeason>?
    )
    data class TmdbSeason(val season_number: Int?, val episodes: List<TmdbEpisode>?)
    data class TmdbEpisode(val episode_number: Int?, val name: String?, val overview: String?, val vote_average: Double?, val air_date: String?, val still_path: String?)
    data class TmdbGenre(val name: String?)
    data class TmdbCredits(val cast: List<TmdbCast>?)
    data class TmdbCast(val name: String?, val character: String?, val profile_path: String?)
    data class TmdbVideos(val results: List<TmdbVideo>?)
    data class TmdbVideo(val key: String?, val site: String?, val type: String?)
}

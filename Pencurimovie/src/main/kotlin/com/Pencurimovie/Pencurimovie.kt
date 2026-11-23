package com.Pencurimovie

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Readable reconstruction of the provider contained in Pencurimovie.cs3.
 *
 * The archive's provider is deliberately kept small: it parses the
 * Pencurimovie WordPress-style pages and delegates every player iframe to the
 * CloudStream extractor registry.
 */
class Pencurimovie : MainAPI() {
    override var mainUrl = "https://ww21.pencurimovie.sbs"
    override var name = "Pencurimovie"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "series" to "TV Series",
        "most-rating" to "Most Rating Movies",
        "top-imdb" to "Top IMDB Movies",
        "country/malaysia" to "Malaysia Movies",
        "country/indonesia" to "Indonesia Movies",
        "country/india" to "India Movies",
        "country/japan" to "Japan Movies",
        "country/thailand" to "Thailand Movies",
        "country/china" to "China Movies",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        // The original binary invokes the optional Phisher donation helper at
        // this point. The helper is bundled runtime code, not provider logic;
        // keeping the page request independent also makes this source module
        // buildable against the normal CloudStream plugin API.
        val document = app.get("$mainUrl/${request.data}/page/$page", timeout = 50L).document
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false,
            ),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = select("a").attr("oldtitle").substringBefore("(")
        val href = fixUrl(select("a").attr("href"))
        val posterUrl = fixUrlNull(select("a img").attr("src"))
        val quality = getQualityFromString(select("span.mli-quality").text())

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl?s=$query", timeout = 50L).document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 50L).document

        val title = document.selectFirst("div.mvic-desc h3")
            ?.text()
            ?.trim()
            .orEmpty()
            .substringBefore("(")
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.selectFirst("div.desc p.f-desc")?.text()?.trim()
        val tvType = if (url.contains("series")) TvType.TvSeries else TvType.Movie
        val trailer = document.select("meta[itemprop=embedUrl]").attr("content")
        val genre = document.select("div.mvic-info p:contains(Genre)").select("a").map { it.text() }
        val actors = document.select("div.mvic-info p:contains(Actors)").select("a").map { it.text() }
        val year = document.select("div.mvic-info p:contains(Release)").select("a").text().toIntOrNull()
        val recommendations = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        return if (tvType == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()

            document.select("div.tvseason").forEach { seasonInfo ->
                val season = seasonInfo.select("strong")
                    .text()
                    .substringAfter("Season")
                    .trim()
                    .toIntOrNull()

                seasonInfo.select("div.les-content a").forEach { episodeLink ->
                    Log.d("Phis", episodeLink.toString())
                    val episodeText = episodeLink.select("a").text()
                    val episodeName = episodeText.substringAfter("-").trim()
                    val href = episodeLink.select("a").attr("href")
                    val episodeNumber = episodeText
                        .substringAfter("Episode")
                        .substringBefore("-")
                        .trim()
                        .toIntOrNull()

                    episodes += newEpisode(href) {
                        this.episode = episodeNumber
                        this.name = episodeName
                        this.season = season
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        document.select("div.movieplay iframe").forEach { iframe ->
            // The archive intentionally passes the data-src value directly to
            // CloudStream; redirect and host-specific handling belong to the
            // registered extractor implementations.
            val href = iframe.attr("data-src")
            loadExtractor(href, subtitleCallback, callback)
        }

        return true
    }
}

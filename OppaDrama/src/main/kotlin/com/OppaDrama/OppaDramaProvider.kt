package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class OppaDramaProvider : MainAPI() {
    override var mainUrl = "http://45.11.57.192"
    override var name = "OPPADRAMA"
    override var lang = "id"
    
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override var hasMainPage = true
    override var hasQuickSearch = true

    // Header Cookie Anti-Bot agar tidak terkena HTTP 503 / response 434-byte
    private val headersMap = mapOf(
        "Cookie" to "user_is_human=true",
        "User-Agent" to USER_AGENT
    )

    // Katalog Halaman Utama
    override val mainPage = mainPageOf(
        "$mainUrl/series/?status=Ongoing&type=Drama&order=update" to "Drama Ongoing",
        "$mainUrl/series/?status=Completed&type=Drama&order=update" to "Drama Completed",
        "$mainUrl/series/?type=Movie&order=update" to "Film Terbaru",
        "$mainUrl/series/?type=TV+Show&order=update" to "Variety Show"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) {
            "${request.data}&page=$page"
        } else {
            request.data
        }

        val doc = app.get(url, headers = headersMap).document
        val items = doc.select(".listupd .bsx, .bs .bsx").mapNotNull { element ->
            toSearchResponse(element)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl, headers = headersMap).document
        
        return doc.select(".listupd .bsx, .bs .bsx").mapNotNull { element ->
            toSearchResponse(element)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun toSearchResponse(element: Element): SearchResponse? {
        val title = element.selectFirst(".tt, .title, a[title]")?.text()?.trim()
            ?: element.selectFirst("a")?.attr("title")?.trim() ?: return null
        val href = element.selectFirst("a")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (isLogoOrAd(src)) null else src
        }

        return newMovieSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    // Filter Khusus untuk Memastikan Gambar yang Diambil Bukan Logo / Banner Site
    private fun isLogoOrAd(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("logo") ||
               lower.contains("oppadrama") ||
               lower.contains("cropped-site-icon") ||
               lower.endsWith(".gif") ||
               lower.contains("avatar")
    }

    private fun getValidPoster(doc: Document): String? {
        val candidateImages = doc.select(
            ".single-info .thumb img, .bigcontent .thumb img, .poster img, meta[property=\"og:image\"]"
        )
        for (img in candidateImages) {
            val src = if (img.tagName() == "meta") {
                img.attr("content")
            } else {
                img.attr("data-src").ifEmpty { img.attr("src") }
            }
            if (src.isNotBlank() && !isLogoOrAd(src)) {
                return src
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headersMap).document

        // 1. Judul
        val title = doc.selectFirst("h1.entry-title, h1[itemprop=name], .infolimit h2, h1.title, .entry-title")?.text()?.trim() 
            ?: "OPPADRAMA"

        // 2. Poster Utama (Dengan Filter Anti-Logo)
        val poster = getValidPoster(doc)

        // 3. Sinopsis / Plot
        val plot = doc.selectFirst(
            ".desc.mindes, .desc, .mindes, .entry-content[itemprop=description], .entry-content p, .entry-content, [itemprop=description], .synopsis, .contexcerpt"
        )?.text()?.trim()

        // 4. Metadata
        val ratingText = doc.selectFirst(".rating strong, .num, [itemprop=ratingValue]")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val genres = doc.select(".genxed a, .genre a, .spe span:contains(Genres) a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a, .spe span:contains(Pemeran) a, .cast a").map { it.text().trim() }

        // 5. Ekstraksi Trailer
        val trailerUrl = doc.selectFirst("iframe[src*=youtube.com], iframe[src*=youtu.be]")?.attr("src")
            ?: doc.selectFirst("a[href*=youtube.com/watch], a[href*=youtu.be]")?.attr("href")

        // 6. Deteksi Tipe Konten (Movie vs Series)
        val isMovie = url.contains("/movie-") || 
                      genres.any { it.contains("Movie", true) || it.contains("Film", true) } ||
                      doc.select(".eplister ul li, .bxcl ul li, #chapterlist ul li").isEmpty()

        return if (isMovie) {
            // Menggunakan Movie Builder untuk Tombol Single Play
            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = url
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.score = Score.from10(ratingText)
                this.tags = genres
                this.addActors(actors)
                if (!trailerUrl.isNullOrBlank()) {
                    this.addTrailer(trailerUrl)
                }
            }
        } else {
            // Menggunakan TV Series Builder
            val episodeElements = doc.select(
                ".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li, #singlepisode .episodelist ul li"
            )

            val episodeList = episodeElements.mapNotNull { li ->
                val aTag = li.selectFirst("a") ?: return@mapNotNull null
                val epUrl = aTag.attr("href")
                val epTitle = li.selectFirst(".epl-title, .playinfo h4, .lchx, a")?.text()?.trim() 
                    ?: aTag.attr("title").ifEmpty { aTag.text() }
                
                val epNum = Regex("""(?i)(?:Eps|Episode|Ep)\s*(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.reversed()

            newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.AsianDrama,
                episodes = episodeList
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.score = Score.from10(ratingText)
                this.showStatus = if (statusText.contains("Ongoing", ignoreCase = true)) {
                    ShowStatus.Ongoing
                } else {
                    ShowStatus.Completed
                }
                this.tags = genres
                this.addActors(actors)
                if (!trailerUrl.isNullOrBlank()) {
                    this.addTrailer(trailerUrl)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, headers = headersMap)
        val doc = res.document
        var linksFoundCount = 0

        // HANYA MENGGUNAKAN SERVER STREAMING (3 SERVER)
        // 1. Ekstraksi Dinamis dari Dropdown Mirror (Base64 Encoded)
        val mirrorOptions = doc.select("select.mirror option, select[name=mirror] option")
        
        for (option in mirrorOptions) {
            val base64Value = option.attr("value").trim()
            val serverName = option.text().trim()

            if (base64Value.isBlank() || serverName.contains("Pilih Server", ignoreCase = true)) {
                continue
            }

            runCatching {
                val decodedHtml = base64Decode(base64Value)
                val iframeDoc = Jsoup.parse(decodedHtml)
                val iframeElements = iframeDoc.select("iframe[src], IFRAME[SRC]")

                for (iframe in iframeElements) {
                    val rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                    if (rawSrc.isNotBlank()) {
                        val fixedUrl = fixUrl(rawSrc)
                        if (loadExtractor(fixedUrl, referer = data, subtitleCallback, callback)) {
                            linksFoundCount++
                        }
                    }
                }
            }
        }

        // 2. Fallback: Iframe Default di DOM (#pembed / .player-embed)
        if (linksFoundCount == 0) {
            val defaultIframes = doc.select(".player-embed iframe, #pembed iframe, .mvelement iframe")
            for (iframe in defaultIframes) {
                val src = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    if (loadExtractor(fixedUrl, referer = data, subtitleCallback, callback)) {
                        linksFoundCount++
                    }
                }
            }
        }

        return linksFoundCount > 0
    }
}

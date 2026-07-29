package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
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

    // Header Cookie Anti-Bot agar tidak terkena HTTP 503 / 434-byte response
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
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        return newMovieSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headersMap).document

        // 1. Ekstraksi Judul Utama
        val title = doc.selectFirst("h1.entry-title, h1[itemprop=name], .infolimit h2, h1.title, .entry-title")?.text()?.trim() 
            ?: "OPPADRAMA"

        // 2. Ekstraksi Poster
        val poster = doc.selectFirst(".thumb img, .single-info .thumb img, .poster img, img[itemprop=image]")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        // 3. Ekstraksi Sinopsis / Plot (Selector Diperluas)
        val plot = doc.selectFirst(
            ".desc.mindes, .desc, .mindes, .entry-content[itemprop=description], .entry-content p, .entry-content, [itemprop=description], .synopsis, .contexcerpt"
        )?.text()?.trim()

        // 4. Metadata Tambahan
        val ratingText = doc.selectFirst(".rating strong, .num, [itemprop=ratingValue]")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val genres = doc.select(".genxed a, .genre a, .spe span:contains(Genres) a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a, .spe span:contains(Pemeran) a, .cast a").map { it.text().trim() }

        // 5. Ekstraksi Episode (Selector Diperluas mencakup DramaStream Series & Movie)
        val episodeElements = doc.select(
            ".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li, #singlepisode .episodelist ul li, .bixbox.episodedl ul li"
        )

        // Cek apakah halaman saat ini sudah memuat video player langsung
        val hasDirectPlayer = doc.selectFirst("select.mirror option, .player-embed iframe, #pembed iframe, .dlbox ul li a") != null

        val episodeList = if (episodeElements.isNotEmpty()) {
            episodeElements.mapNotNull { li ->
                val aTag = li.selectFirst("a") ?: return@mapNotNull null
                val epUrl = aTag.attr("href")
                val epTitle = li.selectFirst(".epl-title, .playinfo h4, .lchx, a")?.text()?.trim() 
                    ?: aTag.attr("title").ifEmpty { aTag.text() }
                
                val epNum = Regex("""(?i)(?:Eps|Episode|Ep)\s*(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.reversed() // Mengurutkan dari episode terkecil/awal
        } else {
            // Jika Movie/Episode tunggal yang halaman nya sudah memuat player
            listOf(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = if (genres.any { it.contains("Movie", true) }) TvType.Movie else TvType.AsianDrama,
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

        // 2. Fallback: Iframe Default di DOM (#pembed)
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

        // 3. Fallback: Tautan Langsung dari Kotak Unduhan (.dlbox)
        val downloadLinks = doc.select(".dlbox ul li, .bixbox.mctn .dlbox li")
        for (li in downloadLinks) {
            val aTag = li.selectFirst("a") ?: continue
            val downloadUrl = aTag.attr("href")
            val serverName = li.selectFirst(".q")?.text()?.trim() ?: "Server"
            val qualityText = li.selectFirst(".w")?.text()?.trim() ?: "HD"

            if (downloadUrl.isNotBlank()) {
                val fixedUrl = fixUrl(downloadUrl)
                
                // Coba lewat loadExtractor bawaan CloudStream dahulu
                val extracted = loadExtractor(fixedUrl, referer = data, subtitleCallback, callback)
                if (extracted) {
                    linksFoundCount++
                } else {
                    // Jika extractor bawaan tidak mendukung (misal Buzzheavier/DataNodes), buat ExtractorLink langsung
                    callback(
                        newExtractorLink(
                            source = serverName,
                            name = "$serverName - $qualityText",
                            url = fixedUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.quality = getQualityFromString(qualityText)
                        }
                    )
                    linksFoundCount++
                }
            }
        }

        return linksFoundCount > 0
    }
}

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

    // Cookie proteksi anti-bot agar tidak terkena HTTP 503 / response 434-byte
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

        // Mengambil judul utama drama
        val title = doc.selectFirst("h1.entry-title, .infolimit h2")?.text()?.trim() 
            ?: doc.selectFirst(".title")?.text()?.trim() 
            ?: "OPPADRAMA"

        val poster = doc.selectFirst(".thumb img, .single-info .thumb img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }

        val plot = doc.selectFirst(".desc.mindes, .synopsis")?.text()?.trim()
        val ratingText = doc.selectFirst(".rating strong")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val genres = doc.select(".genxed a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a").map { it.text().trim() }

        // Ekstraksi Daftar Episode dari Sidebar / Episode List
        val episodeElements = doc.select(".episodelist ul li, #singlepisode .episodelist ul li")
        
        val episodeList = if (episodeElements.isNotEmpty()) {
            episodeElements.mapNotNull { li ->
                val aTag = li.selectFirst("a") ?: return@mapNotNull null
                val epUrl = aTag.attr("href")
                val epTitle = li.selectFirst(".playinfo h4")?.text()?.trim() 
                    ?: aTag.attr("title").ifEmpty { aTag.text() }
                
                val epNum = Regex("""(?i)Episode\s+(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }
        } else {
            // Jika Halaman Berupa Single Movie / Episode Langsung
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
                // Dekode Base64 HTML
                val decodedHtml = base64Decode(base64Value)
                
                // Parsing DOM hasil dekode dengan Jsoup untuk menangani variasi tag (IFRAME/iframe, SRC/src, dll)
                val iframeDoc = Jsoup.parse(decodedHtml)
                val iframeElements = iframeDoc.select("iframe[src], IFRAME[SRC]")

                for (iframe in iframeElements) {
                    val rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                    if (rawSrc.isNotBlank()) {
                        val fixedUrl = fixUrl(rawSrc)
                        // Panggil loadExtractor dengan memasukkan Referer halaman episode
                        if (loadExtractor(fixedUrl, referer = data, subtitleCallback, callback)) {
                            linksFoundCount++
                        }
                    }
                }
            }
        }

        // 2. Fallback Skenario 1: Ambil Iframe Default Pemutar Video di DOM jika Dropdown Kosong/Gagal
        if (linksFoundCount == 0) {
            val defaultIframeSrc = doc.selectFirst(".player-embed iframe, #pembed iframe")?.attr("src")
            if (!defaultIframeSrc.isNullOrBlank()) {
                val fixedUrl = fixUrl(defaultIframeSrc)
                if (loadExtractor(fixedUrl, referer = data, subtitleCallback, callback)) {
                    linksFoundCount++
                }
            }
        }

        // 3. Fallback Skenario 2: Ambil Tautan Langsung dari Kotak Unduhan (.dlbox)
        val downloadLinks = doc.select(".dlbox ul li a, .bixbox.mctn .dlbox li a")
        for (link in downloadLinks) {
            val downloadUrl = link.attr("href")
            if (downloadUrl.isNotBlank()) {
                val fixedUrl = fixUrl(downloadUrl)
                if (loadExtractor(fixedUrl, referer = data, subtitleCallback, callback)) {
                    linksFoundCount++
                }
            }
        }

        return linksFoundCount > 0
    }
}

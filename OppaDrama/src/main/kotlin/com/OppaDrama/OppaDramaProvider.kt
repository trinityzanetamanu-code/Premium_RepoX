package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@Serializable
data class MovieVersionData(
    val url: String,
    val versionName: String
)

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

    // Header Cookie Anti-Bot
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
        
        val rawPoster = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val poster = if (isInvalidImage(rawPoster)) null else rawPoster

        return newMovieSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headersMap).document

        // 1. Judul Utama
        val title = doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text()?.trim() 
            ?: "OPPADRAMA"

        // 2. Poster Utama Spesifik (Menghindari Logo Header)
        val rawPoster = doc.selectFirst(".single-info .thumb img, .megavid .tb img, .poster img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val cleanPoster = if (isInvalidImage(rawPoster)) null else rawPoster

        // 3. Sinopsis / Plot
        val plot = doc.selectFirst(
            ".desc.mindes, .desc, .mindes, .entry-content[itemprop=description], .entry-content p, .entry-content, [itemprop=description], .synopsis, .contexcerpt"
        )?.text()?.trim()

        // 4. Metadata
        val ratingText = doc.selectFirst(".rating strong, .num, [itemprop=ratingValue]")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val typeText = doc.selectFirst(".spe span:contains(Tipe)")?.text() ?: ""
        val epxText = doc.selectFirst(".epx")?.text() ?: ""
        val genres = doc.select(".genxed a, .genre a, .spe span:contains(Genres) a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a, .spe span:contains(Pemeran) a, .cast a").map { it.text().trim() }

        // 5. Deteksi Movie vs TV Series
        val isMovie = typeText.contains("Movie", ignoreCase = true) || 
                      epxText.contains("Movie", ignoreCase = true) ||
                      title.startsWith("Movie ", ignoreCase = true) || 
                      url.contains("/movie-", ignoreCase = true)

        // 6. Ekstraksi Trailer YouTube
        val trailerUrl = doc.selectFirst("iframe[src*=youtube], iframe[src*=youtu.be], a.popup-youtube, a[href*=youtube.com], a[href*=youtu.be]")?.let { el ->
            el.attr("src").ifEmpty { el.attr("href") }
        }

        return if (isMovie) {
            // Mengambil seluruh versi rilis film (BluRay, WEBDL, dll.) jika ada
            val versionElements = doc.select(".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li")
            val movieVersions = if (versionElements.isNotEmpty()) {
                versionElements.mapNotNull { li ->
                    val aTag = li.selectFirst("a") ?: return@mapNotNull null
                    val verUrl = aTag.attr("href")
                    val verName = li.selectFirst(".playinfo h4, .epl-title, a")?.text()?.trim()
                        ?: aTag.attr("title").ifEmpty { aTag.text() }
                    MovieVersionData(verUrl, verName)
                }
            } else {
                listOf(MovieVersionData(url, title))
            }

            // Pemutar Tunggal (Single Play Button) untuk MOVIE
            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = movieVersions.toJson()
            ) {
                this.posterUrl = cleanPoster
                this.plot = plot
                this.score = Score.from10(ratingText)
                this.tags = genres
                this.addActors(actors)
                this.addTrailer(trailerUrl)
            }
        } else {
            // Daftar Episode untuk TV SERIES / DRAMA
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
                episodes = if (episodeList.isNotEmpty()) episodeList else listOf(
                    newEpisode(url) {
                        this.name = title
                        this.episode = 1
                    }
                )
            ) {
                this.posterUrl = cleanPoster
                this.plot = plot
                this.score = Score.from10(ratingText)
                this.showStatus = if (statusText.contains("Ongoing", ignoreCase = true)) {
                    ShowStatus.Ongoing
                } else {
                    ShowStatus.Completed
                }
                this.tags = genres
                this.addActors(actors)
                this.addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFoundCount = 0

        // Parse list versi rilis Movie jika berupa JSON Array, atau anggap single URL
        val versionList = tryParseJson<List<MovieVersionData>>(data) 
            ?: listOf(MovieVersionData(data, ""))

        for (version in versionList) {
            val pageUrl = version.url
            val labelSuffix = if (version.versionName.isNotBlank()) " (${version.versionName})" else ""

            val res = app.get(pageUrl, headers = headersMap)
            val doc = res.document

            // 1. Ekstraksi Dinamis dari Dropdown Mirror (Base64 Encoded)
            val mirrorOptions = doc.select("select.mirror option, select[name=mirror] option")
            
            for (option in mirrorOptions) {
                val base64Value = option.attr("value").trim()
                var serverName = option.text().trim()

                if (base64Value.isBlank() || serverName.contains("Pilih Server", ignoreCase = true)) {
                    continue
                }

                if (labelSuffix.isNotBlank()) {
                    serverName += labelSuffix
                }

                runCatching {
                    val decodedHtml = base64Decode(base64Value)
                    val iframeDoc = Jsoup.parse(decodedHtml)
                    val iframeElements = iframeDoc.select("iframe[src], IFRAME[SRC]")

                    for (iframe in iframeElements) {
                        var rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                        if (rawSrc.isNotBlank()) {
                            
                            // Normalisasi Domain Hydrax (abyssplayer.com -> abyss.to)
                            if (rawSrc.contains("abyssplayer.com")) {
                                rawSrc = rawSrc.replace("abyssplayer.com", "abyss.to")
                            }

                            val fixedUrl = fixUrl(rawSrc)

                            // Normalisasi Path TurboVIP (/t/ -> /v/)
                            if (rawSrc.contains("emturbovid.com/t/")) {
                                val altUrl = fixedUrl.replace("/t/", "/v/")
                                if (loadExtractor(altUrl, referer = pageUrl, subtitleCallback, callback)) {
                                    linksFoundCount++
                                    continue
                                }
                            }

                            if (loadExtractor(fixedUrl, referer = pageUrl, subtitleCallback, callback)) {
                                linksFoundCount++
                            } else if (fixedUrl.contains("emturbovid.com")) {
                                // Fallback Unpacker Khusus TurboVIP
                                if (extractTurboVipDirect(fixedUrl, pageUrl, serverName, callback)) {
                                    linksFoundCount++
                                }
                            }
                        }
                    }
                }
            }

            // 2. Fallback: Iframe Default di DOM (#pembed)
            val defaultIframes = doc.select(".player-embed iframe, #pembed iframe, .mvelement iframe")
            for (iframe in defaultIframes) {
                var src = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                if (src.isNotBlank()) {
                    if (src.contains("abyssplayer.com")) {
                        src = src.replace("abyssplayer.com", "abyss.to")
                    }
                    val fixedUrl = fixUrl(src)
                    if (loadExtractor(fixedUrl, referer = pageUrl, subtitleCallback, callback)) {
                        linksFoundCount++
                    }
                }
            }
        }

        return linksFoundCount > 0
    }

    // Custom Unpacker Khusus TurboVIP
    private suspend fun extractTurboVipDirect(
        url: String,
        referer: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val html = app.get(url, referer = referer).text
            val unpacked = getAndUnpack(html)
            
            val m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)

            if (!m3u8Url.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                true
            } else false
        }.getOrElse { false }
    }

    private fun isInvalidImage(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase()
        return lower.contains("logo") || 
               lower.contains("oppadrama") || 
               lower.contains("cropped-site-icon") || 
               lower.contains("loading.gif") ||
               lower.contains("gravatar.com")
    }
}

package com.OppaDrama

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

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

    private fun logDebug(message: String) {
        println("[OppaDrama Debug] $message")
    }

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

        logDebug("getMainPage -> Fetching $url")
        val doc = app.get(url, headers = headersMap).document
        val items = doc.select(".listupd .bsx, .bs .bsx").mapNotNull { element ->
            toSearchResponse(element)
        }

        logDebug("getMainPage -> Found ${items.size} items for category '${request.name}'")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        logDebug("search -> Searching for query: $query")
        val doc = app.get(searchUrl, headers = headersMap).document
        
        val results = doc.select(".listupd .bsx, .bs .bsx").mapNotNull { element ->
            toSearchResponse(element)
        }
        logDebug("search -> Found ${results.size} search results")
        return results
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun toSearchResponse(element: Element): SearchResponse? {
        val title = element.selectFirst(".tt, .title, a[title]")?.text()?.trim()
            ?: element.selectFirst("a")?.attr("title")?.trim() ?: return null
        val rawHref = element.selectFirst("a")?.attr("href") ?: return null
        val href = fixUrl(rawHref)
        
        val rawPoster = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val poster = if (isInvalidImage(rawPoster)) null else rawPoster

        return newMovieSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        logDebug("load -> Opening URL: $url")
        val doc = app.get(url, headers = headersMap).document

        // 1. Ekstraksi Parent URL dari Breadcrumb (Bebas nth-child, menggunakan penyeleksian logis)
        val breadcrumbLinks = doc.select(".ts-breadcrumb ol li a, .ts-breadcrumb a, .breadcrumb a")
        val parentUrl = breadcrumbLinks.map { it.attr("href") }.firstOrNull { link ->
            val fixed = fixUrl(link)
            fixed.isNotBlank() && 
            fixed != mainUrl && 
            fixed != "$mainUrl/" && 
            fixed != url && 
            !fixed.contains("/category/") && 
            !fixed.contains("/series/?")
        }?.let { fixUrl(it) } ?: url

        logDebug("load -> Resolved Parent URL: $parentUrl (Input URL: $url)")

        // 2. Judul Utama
        val title = doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text()?.trim() 
            ?: "OPPADRAMA"

        // 3. Poster Utama (Filtering Gambar Logo Header/Icon)
        val rawPoster = doc.selectFirst(".single-info .thumb img, .megavid .tb img, .poster img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val cleanPoster = if (isInvalidImage(rawPoster)) null else rawPoster

        // 4. Sinopsis / Plot (Sangat Spesifik & Bebas Teks SEO)
        val plotElement = doc.selectFirst(".single-info .desc.mindes, .desc.mindes, .entry-content .desc, .desc")
        plotElement?.select(".colap")?.remove() // Hapus tag pelipat jika ada
        
        var plot = plotElement?.text()?.trim()
        if (plot != null && (plot.startsWith("Download dan nonton", ignoreCase = true) || plot.startsWith("Tonton streaming", ignoreCase = true))) {
            // Fallback jika elemen teratas berisi teks SEO
            plot = doc.select(".entry-content p, .desc p")
                .map { it.text().trim() }
                .firstOrNull { !it.startsWith("Download dan nonton", ignoreCase = true) && !it.startsWith("Tonton streaming", ignoreCase = true) }
        }

        // 5. Metadata
        val ratingText = doc.selectFirst(".rating strong, .num, [itemprop=ratingValue]")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val typeText = doc.selectFirst(".spe span:contains(Tipe)")?.text() ?: ""
        val epxText = doc.selectFirst(".epx")?.text() ?: ""
        val genres = doc.select(".genxed a, .genre a, .spe span:contains(Genres) a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a, .spe span:contains(Pemeran) a, .cast a").map { it.text().trim() }

        // 6. Deteksi Movie vs TV Series
        val isMovie = typeText.contains("Movie", ignoreCase = true) || 
                      epxText.contains("Movie", ignoreCase = true) ||
                      title.startsWith("Movie ", ignoreCase = true) || 
                      url.contains("/movie-", ignoreCase = true)

        // 7. Ekstraksi Trailer YouTube
        val trailerUrl = doc.selectFirst("iframe[src*=youtube], iframe[src*=youtu.be], a.popup-youtube, a[href*=youtube.com], a[href*=youtu.be]")?.let { el ->
            el.attr("src").ifEmpty { el.attr("href") }
        }

        return if (isMovie) {
            // Mengambil seluruh versi rilis film (BluRay, WEBDL, HD, dll.)
            val versionElements = doc.select(".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li")
            val movieVersions = if (versionElements.isNotEmpty()) {
                versionElements.mapNotNull { li ->
                    val aTag = li.selectFirst("a") ?: return@mapNotNull null
                    val verUrl = fixUrl(aTag.attr("href"))
                    val verName = li.selectFirst(".playinfo h4, .epl-title, a")?.text()?.trim()
                        ?: aTag.attr("title").ifEmpty { aTag.text() }
                    MovieVersionData(verUrl, verName)
                }
            } else {
                listOf(MovieVersionData(url, title))
            }

            logDebug("load -> Identified as MOVIE with ${movieVersions.size} version(s)")

            newMovieLoadResponse(
                name = title,
                url = parentUrl,
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
                val epUrl = fixUrl(aTag.attr("href"))
                val epTitle = li.selectFirst(".epl-title, .playinfo h4, .lchx, a")?.text()?.trim() 
                    ?: aTag.attr("title").ifEmpty { aTag.text() }
                
                val epNum = Regex("""(?i)(?:Eps|Episode|Ep)\s*(\d+)""").find(epTitle ?: "")?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }.reversed()

            logDebug("load -> Identified as TV SERIES with ${episodeList.size} episode(s)")

            newTvSeriesLoadResponse(
                name = title,
                url = parentUrl,
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
        logDebug("loadLinks -> Incoming payload: $data")

        val versionList = tryParseJson<List<MovieVersionData>>(data) 
            ?: listOf(MovieVersionData(data, ""))

        // Deduplikasi Kuantitatif Multi-Thread
        val visitedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        // Subtitle Callback Wrapper dengan Deduplikasi
        val safeSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (visitedSubtitleUrls.add(sub.url)) {
                logDebug("Subtitle emitted: [${sub.lang}] -> ${sub.url}")
                subtitleCallback(sub)
            } else {
                logDebug("Subtitle skipped (Duplicate): ${sub.url}")
            }
        }

        // ExtractorLink Callback Wrapper dengan Deduplikasi
        val safeLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (visitedStreamUrls.add(link.url)) {
                logDebug("Source emitted: [${link.source}] ${link.name} -> ${link.url}")
                callback(link)
            } else {
                logDebug("Source skipped (Duplicate Stream URL): ${link.url}")
            }
        }

        // Pemrosesan Paralel seluruh Mode Rilis menggunakan Coroutines
        coroutineScope {
            versionList.map { version ->
                async {
                    val pageUrl = version.url
                    val labelSuffix = if (version.versionName.isNotBlank()) " (${version.versionName})" else ""
                    logDebug("Processing Mode/Version: $pageUrl | Label: $labelSuffix")

                    runCatching {
                        val res = app.get(pageUrl, headers = headersMap)
                        val doc = res.document

                        // 1. Ekstraksi Dinamis dari Dropdown Mirror (Base64 Encoded)
                        val mirrorOptions = doc.select("select.mirror option, select[name=mirror] option")
                        logDebug("Found ${mirrorOptions.size} mirror option(s) on $pageUrl")

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

                                        // Deduplikasi Pre-Extractor (Stase 1)
                                        if (!visitedEmbedUrls.add(fixedUrl)) {
                                            logDebug("Embed URL skipped (Pre-Extractor Duplicate): $fixedUrl")
                                            continue
                                        }

                                        logDebug("Attempting loadExtractor for: $fixedUrl | Server: $serverName")

                                        // Normalisasi Path TurboVIP (/t/ -> /v/)
                                        if (rawSrc.contains("emturbovid.com/t/")) {
                                            val altUrl = fixedUrl.replace("/t/", "/v/")
                                            if (loadExtractor(altUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)) {
                                                continue
                                            }
                                        }

                                        val loaded = loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                        if (!loaded && fixedUrl.contains("emturbovid.com")) {
                                            // Fallback Unpacker Khusus TurboVIP jika Extractor Bawaan Gagal
                                            logDebug("Calling extractTurboVipDirect fallback for $fixedUrl")
                                            extractTurboVipDirect(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                        }
                                    }
                                }
                            }.onFailure { err ->
                                logDebug("Failed to decode Base64 mirror: ${err.message}")
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

                                if (visitedEmbedUrls.add(fixedUrl)) {
                                    logDebug("Attempting loadExtractor for DOM Iframe: $fixedUrl")
                                    loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                }
                            }
                        }
                    }.onFailure { err ->
                        logDebug("Failed to process page $pageUrl: ${err.message}")
                    }
                }
            }.awaitAll()
        }

        logDebug("loadLinks -> Extraction completed. Total unique stream URLs emitted: ${visitedStreamUrls.size}")
        return visitedStreamUrls.isNotEmpty()
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
                logDebug("extractTurboVipDirect -> Direct M3U8 found: $m3u8Url")
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
            } else {
                logDebug("extractTurboVipDirect -> No M3U8 link found in unpacked JS")
                false
            }
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

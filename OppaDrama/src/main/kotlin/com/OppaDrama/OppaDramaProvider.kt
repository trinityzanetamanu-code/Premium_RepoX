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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    // Set 'true' saat proses troubleshooting, 'false' untuk rilis produksi
    private val DEBUG = false

    // Pembatas Concurrency: Maksimal 3 request paralel bersamaan
    private val concurrencySemaphore = Semaphore(3)

    // Header Cookie Anti-Bot
    private val headersMap = mapOf(
        "Cookie" to "user_is_human=true",
        "User-Agent" to USER_AGENT
    )

    private fun logDebug(message: String) {
        if (DEBUG) {
            println("[OppaDrama Debug] $message")
        }
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

        // 1. Ekstraksi Parent URL dari Breadcrumb (Bebas posisional nth-child)
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

        // 3. Poster Utama
        val rawPoster = doc.selectFirst(".single-info .thumb img, .megavid .tb img, .poster img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val cleanPoster = if (isInvalidImage(rawPoster)) null else rawPoster

        // 4. Sinopsis / Plot
        val plotElement = doc.selectFirst(".single-info .desc.mindes, .desc.mindes, .entry-content .desc, .desc")
        plotElement?.select(".colap")?.remove()
        
        var plot = plotElement?.text()?.trim()
        if (plot != null && (plot.startsWith("Download dan nonton", ignoreCase = true) || plot.startsWith("Tonton streaming", ignoreCase = true))) {
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

        // Deduplikasi Thread-Safe
        val visitedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val safeSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (visitedSubtitleUrls.add(sub.url)) {
                logDebug("Subtitle emitted: [${sub.lang}] -> ${sub.url}")
                subtitleCallback(sub)
            }
        }

        // Post-Extractor Deduplication: Deduplikasi strictly pada URL stream akhir
        val safeLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (visitedStreamUrls.add(link.url)) {
                logDebug("Source emitted: [${link.source}] ${link.name} -> ${link.url}")
                callback(link)
            } else {
                logDebug("Stream URL skipped (Duplicate Stream Link): ${link.url}")
            }
        }

        coroutineScope {
            versionList.map { version ->
                async {
                    // Terapkan batas concurrency request
                    concurrencySemaphore.withPermit {
                        val pageUrl = version.url
                        val labelSuffix = if (version.versionName.isNotBlank()) " (${version.versionName})" else ""
                        logDebug("Processing Mode/Version: $pageUrl | Label: $labelSuffix")

                        runCatching {
                            val res = app.get(pageUrl, headers = headersMap)
                            val doc = res.document

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
                                        val rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                        if (rawSrc.isNotBlank()) {
                                            val fixedUrl = fixUrl(rawSrc)

                                            // Pre-Extractor Deduplication
                                            if (!visitedEmbedUrls.add(fixedUrl)) {
                                                logDebug("Embed URL skipped (Pre-Extractor Duplicate): $fixedUrl")
                                                continue
                                            }

                                            val countBefore = visitedStreamUrls.size

                                            // 1. Penanganan TurboVIP (Hierarki Strict Fallback)
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                logDebug("Processing TurboVIP: $fixedUrl")
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, serverName, safeSubtitleCallback, safeLinkCallback)
                                            } 
                                            // 2. Penanganan Hydrax (Dengan Validasi Media ID)
                                            else if (fixedUrl.contains("abyss") || fixedUrl.contains("hydrax")) {
                                                val mediaId = Regex("""(?:v=|\/v\/|\/)([a-zA-Z0-9_-]+)""").find(fixedUrl)?.groupValues?.get(1)
                                                if (!mediaId.isNullOrBlank()) {
                                                    logDebug("[Hydrax] Media ID extracted: $mediaId -> Trying normalized abyss.to")
                                                    loadExtractor("https://abyss.to/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor("https://abyss.to/?v=$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                } else {
                                                    logDebug("[Hydrax] Media ID extraction failed. Fallback to raw URL: $fixedUrl")
                                                    loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                }
                                            } 
                                            // 3. Penanganan VidHidePro / Minochinos (Dengan Validasi Media ID)
                                            else if (fixedUrl.contains("minochinos.com") || fixedUrl.contains("vidhide") || fixedUrl.contains("filelions")) {
                                                val mediaId = Regex("""(?:v\/|\/d\/|\/v=|\/)([a-zA-Z0-9_-]+)""").find(fixedUrl)?.groupValues?.get(1)
                                                if (!mediaId.isNullOrBlank()) {
                                                    logDebug("[VidHide] Media ID extracted: $mediaId -> Trying normalized vidhidepro.com")
                                                    loadExtractor("https://vidhidepro.com/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor("https://vidhidepro.com/d/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                } else {
                                                    logDebug("[VidHide] Media ID extraction failed. Fallback to raw URL: $fixedUrl")
                                                    loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                }
                                            } else {
                                                // General Fallback
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }.onFailure { err ->
                                    logDebug("Failed to decode Base64 mirror: ${err.message}")
                                }
                            }

                            // Secondary Fallback: Iframe Default di DOM (#pembed)
                            if (visitedStreamUrls.isEmpty()) {
                                val defaultIframes = doc.select(".player-embed iframe, #pembed iframe, .mvelement iframe")
                                for (iframe in defaultIframes) {
                                    val src = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                    if (src.isNotBlank()) {
                                        val fixedUrl = fixUrl(src)
                                        if (visitedEmbedUrls.add(fixedUrl)) {
                                            logDebug("Attempting loadExtractor for DOM Iframe: $fixedUrl")
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, "TurboVIP", safeSubtitleCallback, safeLinkCallback)
                                            } else {
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }
                            }
                        }.onFailure { err ->
                            logDebug("Failed to process page $pageUrl: ${err.message}")
                        }
                    }
                }
            }.awaitAll()
        }

        logDebug("loadLinks -> Extraction completed. Total unique stream URLs emitted: ${visitedStreamUrls.size}")
        return visitedStreamUrls.isNotEmpty()
    }

    // Ekstraksi TurboVIP Berbasis Hierarki Strict Fallback
    private suspend fun extractTurboVipWithFallback(
        url: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val doc = app.get(url, referer = referer, headers = headersMap).document
            
            // Level 1: Baca data-hash langsung dari DOM #video_player
            var m3u8Url = doc.selectFirst("#video_player[data-hash]")?.attr("data-hash")?.trim()

            if (!m3u8Url.isNullOrBlank()) {
                logDebug("[TurboVIP Level 1 Success] Found M3U8 via data-hash: $m3u8Url")
            } else {
                // Level 2 & 3: JS Unpacker -> Regex M3U8
                logDebug("[TurboVIP Level 1 Failed] Trying Level 2 JS Unpacker")
                val html = doc.html()
                val unpacked = getAndUnpack(html)
                m3u8Url = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""file:\s*"([^"]+)"""").find(unpacked)?.groupValues?.get(1)
            }

            if (!m3u8Url.isNullOrBlank()) {
                val fixedM3u8 = fixUrl(m3u8Url)
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = fixedM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://turboviplay.com/"
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                true
            } else {
                // Level 4: loadExtractor bawaan CloudStream sebagai pertahanan terakhir
                logDebug("[TurboVIP Level 2 Failed] Trying Level 4 loadExtractor built-in")
                val altUrl = if (url.contains("/t/")) url.replace("/t/", "/v/") else url
                loadExtractor(altUrl, referer = url, subtitleCallback, callback)
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

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
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class MovieVersionData(
    val url: String,
    val versionName: String
)

/**
 * OkHttp Interceptor khusus untuk membersihkan pembungkus PNG (941 byte pertama)
 * pada segmen HLS TurboVIP agar ExoPlayer tidak mengalami Error 3001.
 */
class TurboVipInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()

        // Filter request segmen dari domain/ekstensi TurboVIP yang membungkus biner
        if (url.contains("googleusercontent.com") || url.contains(".png") || url.contains("turboviplay") || url.contains("turbosplayer")) {
            val body = response.body ?: return response
            val source = body.source()

            // Periksa apakah byte awal diawali Magic Number PNG: 0x89 50 4E 47
            if (source.rangeEquals(0, okio.ByteString.of(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x7G_byte = 0x47.toByte()))) {
                // Lewati (skip) 941 byte header PNG dan padding 0xFF pembungkus
                source.skip(941L)

                val newLength = if (body.contentLength() >= 941L) body.contentLength() - 941L else -1L
                val strippedBody = source.asResponseBody(body.contentType(), newLength)

                return response.newBuilder()
                    .body(strippedBody)
                    .build()
            }
        }
        return response
    }
}

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

    private val DEBUG = false
    private val concurrencySemaphore = Semaphore(3)

    private val headersMap = mapOf(
        "Cookie" to "user_is_human=true",
        "User-Agent" to USER_AGENT
    )

    private fun logDebug(message: String) {
        if (DEBUG) {
            println("[OppaDrama Debug] $message")
        }
    }

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
        val doc = app.get(url, headers = headersMap).document

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

        val title = doc.selectFirst(".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title")?.text()?.trim() 
            ?: "OPPADRAMA"

        val rawPoster = doc.selectFirst(".single-info .thumb img, .megavid .tb img, .poster img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        val cleanPoster = if (isInvalidImage(rawPoster)) null else rawPoster

        val plotElement = doc.selectFirst(".single-info .desc.mindes, .desc.mindes, .entry-content .desc, .desc")
        plotElement?.select(".colap")?.remove()
        
        var plot = plotElement?.text()?.trim()
        if (plot != null && (plot.startsWith("Download dan nonton", ignoreCase = true) || plot.startsWith("Tonton streaming", ignoreCase = true))) {
            plot = doc.select(".entry-content p, .desc p")
                .map { it.text().trim() }
                .firstOrNull { !it.startsWith("Download dan nonton", ignoreCase = true) && !it.startsWith("Tonton streaming", ignoreCase = true) }
        }

        val ratingText = doc.selectFirst(".rating strong, .num, [itemprop=ratingValue]")?.text()?.replace("Rating", "")?.trim()
        val statusText = doc.selectFirst(".spe span:contains(Status)")?.text() ?: ""
        val typeText = doc.selectFirst(".spe span:contains(Tipe)")?.text() ?: ""
        val epxText = doc.selectFirst(".epx")?.text() ?: ""
        val genres = doc.select(".genxed a, .genre a, .spe span:contains(Genres) a").map { it.text().trim() }
        val actors = doc.select(".spe span:contains(Artis) a, .spe span:contains(Pemeran) a, .cast a").map { it.text().trim() }

        val isMovie = typeText.contains("Movie", ignoreCase = true) || 
                      epxText.contains("Movie", ignoreCase = true) ||
                      title.startsWith("Movie ", ignoreCase = true) || 
                      url.contains("/movie-", ignoreCase = true)

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
        val versionList = tryParseJson<List<MovieVersionData>>(data) 
            ?: listOf(MovieVersionData(data, ""))

        val visitedEmbedUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedStreamUrls = ConcurrentHashMap.newKeySet<String>()
        val visitedSubtitleUrls = ConcurrentHashMap.newKeySet<String>()

        val safeSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (visitedSubtitleUrls.add(sub.url)) {
                subtitleCallback(sub)
            }
        }

        val safeLinkCallback: (ExtractorLink) -> Unit = { link ->
            if (visitedStreamUrls.add(link.url)) {
                callback(link)
            }
        }

        coroutineScope {
            versionList.map { version ->
                async {
                    concurrencySemaphore.withPermit {
                        val pageUrl = version.url
                        val labelSuffix = if (version.versionName.isNotBlank()) " (${version.versionName})" else ""

                        runCatching {
                            val res = app.get(pageUrl, headers = headersMap)
                            val doc = res.document

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
                                        val rawSrc = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                        if (rawSrc.isNotBlank()) {
                                            val fixedUrl = fixUrl(rawSrc)

                                            if (!visitedEmbedUrls.add(fixedUrl)) {
                                                continue
                                            }

                                            val countBefore = visitedStreamUrls.size

                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, serverName, safeSubtitleCallback, safeLinkCallback)
                                            } 
                                            else if (fixedUrl.contains("abyss") || fixedUrl.contains("hydrax")) {
                                                val mediaId = Regex("""(?:v=|\/v\/|\/)([a-zA-Z0-9_-]+)""").find(fixedUrl)?.groupValues?.get(1)
                                                if (!mediaId.isNullOrBlank()) {
                                                    loadExtractor("https://abyss.to/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor("https://abyss.to/?v=$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                } else {
                                                    loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                }
                                            } 
                                            else if (fixedUrl.contains("minochinos.com") || fixedUrl.contains("vidhide") || fixedUrl.contains("filelions")) {
                                                val mediaId = Regex("""(?:v\/|\/d\/|\/v=|\/)([a-zA-Z0-9_-]+)""").find(fixedUrl)?.groupValues?.get(1)
                                                if (!mediaId.isNullOrBlank()) {
                                                    loadExtractor("https://vidhidepro.com/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor("https://vidhidepro.com/d/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                } else {
                                                    loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                }
                                            } else {
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }.onFailure { }
                            }

                            if (visitedStreamUrls.isEmpty()) {
                                val defaultIframes = doc.select(".player-embed iframe, #pembed iframe, .mvelement iframe")
                                for (iframe in defaultIframes) {
                                    val src = iframe.attr("src").ifEmpty { iframe.attr("SRC") }
                                    if (src.isNotBlank()) {
                                        val fixedUrl = fixUrl(src)
                                        if (visitedEmbedUrls.add(fixedUrl)) {
                                            if (fixedUrl.contains("emturbovid.com") || fixedUrl.contains("turboviplay.com")) {
                                                extractTurboVipWithFallback(fixedUrl, pageUrl, "TurboVIP", safeSubtitleCallback, safeLinkCallback)
                                            } else {
                                                loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                            }
                                        }
                                    }
                                }
                            }
                        }.onFailure { }
                    }
                }
            }.awaitAll()
        }

        return visitedStreamUrls.isNotEmpty()
    }

    private suspend fun extractTurboVipWithFallback(
        url: String,
        referer: String,
        serverName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return runCatching {
            val doc = app.get(url, referer = referer, headers = headersMap).document
            
            var m3u8Url = doc.selectFirst("#video_player[data-hash]")?.attr("data-hash")?.trim()

            if (m3u8Url.isNullOrBlank()) {
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
                val altUrl = if (url.contains("/t/")) url.replace("/t/", "/v/") else url
                loadExtractor(altUrl, referer = url, subtitleCallback, callback)
            }
        }.getOrElse { false }
    }

    /**
     * Daftarkan Interceptor OkHttp khusus untuk membersihkan Header PNG TurboVIP 
     * secara real-time pada layer jaringan sebelum diterima oleh ExoPlayer.
     */
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return if (extractorLink.source.contains("TurboVIP", ignoreCase = true) || 
                   extractorLink.url.contains("turboviplay") || 
                   extractorLink.url.contains("turbosplayer") ||
                   extractorLink.url.contains("googleusercontent.com")) {
            TurboVipInterceptor()
        } else {
            super.getVideoInterceptor(extractorLink)
        }
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

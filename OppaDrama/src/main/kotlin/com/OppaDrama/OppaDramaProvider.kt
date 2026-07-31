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

    // Routing TurboVIP lewat proxy lokal (LocalProxy) yang memotong prefix
    // PNG 806+135 byte. Set 'false' untuk kembali ke perilaku lama TANPA
    // build ulang -- berguna untuk A/B: langsung vs lewat proxy.
    private val USE_PROXY = true

    // DIAGNOSTIK SEMENTARA untuk mencari sebab duplikasi Video Trek.
    // Saat true, extractor FileLions ikut mengambil master playlist lalu
    // melaporkan strukturnya ke logcat. Menambah satu request per loadLinks.
    // Set false setelah penyebabnya ketemu.
    private val FL_DIAG = true

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
                                                // H-1 TERBUKTI: regex lama
                                                //   (?:v=|\/v\/|\/)([a-zA-Z0-9_-]+)
                                                // menangkap nama HOST, bukan ID. Alternatif \/ selalu
                                                // menang di posisi paling kiri pada // di https://,
                                                // sehingga hasilnya 'abyssplayer' bukan '_pGSwC03aH'.
                                                // Diganti dengan parsing URL, bukan regex rakus.
                                                val mediaId = extractMediaId(fixedUrl)
                                                LocalProxy.obs("HYDRAX", "in=$fixedUrl id=$mediaId")

                                                // Kandidat diurut berdasarkan BUKTI, bukan asumsi.
                                                // URL asli didahulukan karena HAR browser membuktikan
                                                // abyssplayer.com benar-benar menyajikan video; host
                                                // abyss.to tidak punya bukti pendukung apa pun.
                                                val candidates = if (!mediaId.isNullOrBlank()) listOf(
                                                    fixedUrl,
                                                    "https://abyssplayer.com/?v=$mediaId",
                                                    "https://abyss.to/v/$mediaId",
                                                    "https://abyss.to/?v=$mediaId"
                                                ).distinct() else listOf(fixedUrl)

                                                // Extractor sendiri didahulukan. H-3 membuktikan
                                                // loadExtractor tidak punya penangan untuk host ini,
                                                // jadi kandidat di bawah hanya jaring pengaman.
                                                val hxOk = try {
                                                    extractHydrax(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                                } catch (e: Exception) {
                                                    LocalProxy.obs("HX-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                                                    false
                                                }

                                                for (cand in candidates) {
                                                    if (hxOk || visitedStreamUrls.size > countBefore) break
                                                    loadExtractor(cand, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    // Log per kandidat: inilah yang menjaga atribusi
                                                    // sebab-akibat tetap tunggal meski ada 4 kandidat.
                                                    LocalProxy.obs(
                                                        "HYDRAX-TRY",
                                                        "url=$cand hasil=" +
                                                            if (visitedStreamUrls.size > countBefore) "BERHASIL" else "kosong"
                                                    )
                                                }
                                                if (visitedStreamUrls.size == countBefore) {
                                                    LocalProxy.obs("HYDRAX-GAGAL", "semua ${candidates.size} kandidat kosong")
                                                }
                                            } 
                                            // 3. Penanganan FileLions / Minochinos (keluarga XFileSharing)
                                            else if (fixedUrl.contains("minochinos.com") || fixedUrl.contains("vidhide") || fixedUrl.contains("filelions")) {
                                                // Extractor sendiri didahulukan. Mekanismenya sudah
                                                // terpetakan penuh lewat fl1/fl2/fl3: unpack packer
                                                // p,a,c,k,e,d lalu ambil URL dari objek links.
                                                val flOk = try {
                                                    extractFileLions(fixedUrl, pageUrl, serverName, safeLinkCallback)
                                                } catch (e: Exception) {
                                                    LocalProxy.obs("FL-ERROR", "${e.javaClass.simpleName}: ${e.message}")
                                                    false
                                                }

                                                if (!flOk && visitedStreamUrls.size == countBefore) {
                                                    // H-2: regex lama (?:v\/|\/d\/|\/v=|\/) menangkap nama
                                                    // HOST, bukan ID - alternatif \/ menang di // pada
                                                    // https://, sehingga hasilnya 'minochinos' bukan
                                                    // 'mnqiexinkl9c'. Cacat yang sama persis dengan H-1.
                                                    // Diganti extractMediaId() yang sudah tervalidasi.
                                                    val mediaId = extractMediaId(fixedUrl)
                                                    LocalProxy.obs("FL-FALLBACK", "in=$fixedUrl id=$mediaId")
                                                    if (!mediaId.isNullOrBlank()) {
                                                        loadExtractor("https://vidhidepro.com/v/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                        if (visitedStreamUrls.size == countBefore) {
                                                            loadExtractor("https://vidhidepro.com/d/$mediaId", referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                        }
                                                    }
                                                    if (visitedStreamUrls.size == countBefore) {
                                                        loadExtractor(fixedUrl, referer = pageUrl, safeSubtitleCallback, safeLinkCallback)
                                                    }
                                                    LocalProxy.obs(
                                                        "FL-FALLBACK-HASIL",
                                                        if (visitedStreamUrls.size > countBefore) "BERHASIL" else "kosong"
                                                    )
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

                // Segmen TurboVIP dibungkus PNG (806 byte) + padding 0xFF (135 byte).
                // TsExtractor ExoPlayer gagal dengan "Cannot find sync byte" karena
                // paket TS pertama baru mulai di offset 941. LocalProxy memotong
                // prefix itu dan menulis ulang URI segmen di playlist supaya ikut
                // lewat proxy. Offset dicari dinamis, TIDAK di-hardcode.
                val servedUrl = if (USE_PROXY) {
                    LocalProxy.proxyUrl(fixedM3u8) ?: fixedM3u8
                } else {
                    fixedM3u8
                }
                val viaProxy = servedUrl != fixedM3u8
                logDebug("[TurboVIP] serve=$servedUrl viaProxy=$viaProxy")

                callback(
                    newExtractorLink(
                        source = serverName,
                        name = if (viaProxy) "$serverName [proxy]" else serverName,
                        url = servedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        if (viaProxy) {
                            // Header upstream diurus LocalProxy sendiri. Memaksa
                            // referer turboviplay.com ke 127.0.0.1 tidak ada gunanya,
                            // dan H-5/H-6 sudah terbukti: header tidak mengubah byte.
                            this.referer = ""
                        } else {
                            this.referer = "https://turboviplay.com/"
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
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

    /**
     * Ekstraksi Media ID berbasis PARSING URL, bukan regex rakus.
     *
     * Dipakai HANYA oleh cabang Hydrax. Cabang VidHide sengaja TIDAK diubah
     * (lihat H-2) supaya perubahan pada build ini punya satu sebab tunggal
     * yang bisa diatribusikan.
     *
     * Aturan:
     *   1. kalau ada parameter query v=  -> pakai nilainya
     *   2. kalau tidak, pakai segmen path terakhir
     *   3. kalau segmen itu tidak masuk akal sebagai ID -> null, JANGAN
     *      mengembalikan nama host seperti regex lama
     *
     * Terverifikasi pada 6 bentuk URL, termasuk kasus tanpa ID.
     */
    /**
     * Extractor Hydrax sendiri, dibangun bertahap.
     *
     * H-3 membuktikan CloudStream tidak punya extractor yang cocok untuk
     * abyssplayer.com / abyss.to (nol request jaringan pada 3 bentuk URL).
     * Fungsi ini menggantikan ketergantungan itu.
     *
     * Setiap tahap melapor lewat obs, sehingga run-nya sendiri yang
     * menunjukkan sampai mana ia berhasil - bukan prediksi.
     *
     *   HX-1  ambil halaman embed
     *   HX-2  temukan blob base64 config
     *   HX-3  dekode base64 + baca struktur config
     *   HX-4  hasilkan URL media
     *
     * CATATAN ENKODING PENTING:
     * JSON hasil dekode base64 BUKAN UTF-8 valid - ia mencampur escape
     * \uXXXX dengan byte mentah tinggi. Memakai .text / UTF-8 akan
     * merusaknya persis seperti bug hx2 (347 byte hancur jadi U+FFFD).
     * Karena itu di sini dipakai ISO_8859_1 yang memetakan byte 0..255
     * ke char 0..255 secara lossless.
     *
     * @return true kalau berhasil menghasilkan minimal satu link
     */
    private suspend fun extractHydrax(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ---------------- HX-1 ----------------
        val html = try {
            app.get(
                embedUrl,
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            LocalProxy.obs("HX-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        LocalProxy.obs("HX-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        // ---------------- HX-2 ----------------
        val b64 = Regex("""["']([A-Za-z0-9+/=]{200,})["']""")
            .find(html)?.groupValues?.get(1)
        if (b64.isNullOrBlank()) {
            LocalProxy.obs("HX-2-GAGAL", "blob base64 config tidak ditemukan")
            return false
        }
        LocalProxy.obs("HX-2-OK", "b64=${b64.length} char")

        // ---------------- HX-3 ----------------
        val rawBytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            LocalProxy.obs("HX-3-GAGAL", "base64 decode: ${e.message}")
            return false
        }
        // ISO_8859_1, BUKAN UTF-8. Lihat catatan enkoding di atas.
        val json = String(rawBytes, Charsets.ISO_8859_1)
        val slug = Regex(""""slug"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
        val md5id = Regex(""""md5_id"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)
        val mediaLen = Regex(""""media"\s*:\s*"((?:\\.|[^"\\])*)"""")
            .find(json)?.groupValues?.get(1)?.length ?: -1
        LocalProxy.obs(
            "HX-3-OK",
            "json=${rawBytes.size}B slug=$slug md5_id=$md5id media_field=$mediaLen"
        )

        // ---------------- HX-4 ----------------
        // Cek termurah lebih dulu: apakah ada URL http yang tersimpan polos
        // di mana pun dalam config. Kalau ada, tidak perlu dekode apa pun.
        val plain = Regex("""https?://[^\s"'\\<>]{12,}""").findAll(json)
            .map { it.value }
            .filter { u ->
                !u.contains("blogger.googleusercontent") &&
                    !u.contains("oppa.biz") &&
                    !u.contains("iamcdn.net")
            }
            .distinct().toList()

        if (plain.isNotEmpty()) {
            LocalProxy.obs("HX-4-URL-POLOS", "ditemukan ${plain.size}: ${plain.take(3)}")
            plain.forEach { u ->
                callback(
                    newExtractorLink(
                        source = serverName,
                        name = "$serverName [direct]",
                        url = u,
                        type = ExtractorLinkType.VIDEO
                    ) { this.referer = embedUrl }
                )
            }
            return true
        }

        LocalProxy.obs(
            "HX-4-BERHENTI",
            "tidak ada URL polos di config. URL media ada di field 'media' " +
                "($mediaLen char terenkode) dan perlu didekode. " +
                "Inilah titik berhenti extractor."
        )
        return false
    }

    /**
     * Membalik packer Dean Edwards p,a,c,k,e,d.
     *
     * Ini SUBSTITUSI TEKS MURNI, bukan eksekusi. Packer ini dibuat tahun 2004
     * untuk memperkecil ukuran berkas JavaScript: kata-kata panjang diganti
     * token pendek basis-N, kamusnya disimpan di akhir. Membalikkannya sama
     * dengan mendekompresi teks.
     *
     * Format argumen:
     *   }('PAYLOAD', RADIX, COUNT, 'kata0|kata1|...'.split('|'), 0, {})
     *
     * Alfabet basis-N mengikuti perilaku JS: c.toString(36) untuk c <= 35
     * (0-9a-z), lalu String.fromCharCode(c+29) untuk c > 35 (A-Z). Gabungannya
     * persis "0..9a..zA..Z", sehingga satu tabel melayani radix 36 maupun 62.
     *
     * Tervalidasi pada data nyata minochinos: 13158 char, radix 36, count 612.
     */
    private fun unpackDeanEdwards(packed: String): String? {
        val m = Regex(
            """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        ).find(packed) ?: return null

        val payload = unescapeJs(m.groupValues[1])
        val radix = m.groupValues[2].toIntOrNull() ?: return null
        val count = m.groupValues[3].toIntOrNull() ?: return null
        val words = m.groupValues[4].split("|")
        if (radix < 2 || radix > 62 || count <= 0) return null

        val map = HashMap<String, String>(count * 2)
        for (i in 0 until count) {
            val tok = baseN(i, radix)
            val w = words.getOrNull(i)
            map[tok] = if (w.isNullOrEmpty()) tok else w
        }
        // satu kali jalan, bukan `count` kali replace berturut-turut
        return Regex("""\b[0-9A-Za-z]+\b""").replace(payload) { mr ->
            map[mr.value] ?: mr.value
        }
    }

    private fun baseN(num: Int, radix: Int): String {
        if (num == 0) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % radix])
            n /= radix
        }
        return sb.toString()
    }

    /** Payload adalah literal string JS berkutip tunggal; buka escape-nya. */
    private fun unescapeJs(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    else -> { sb.append(n); i += 2 }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /**
     * Extractor FileLions (host minochinos.com dan keluarga XFileSharing).
     *
     * Seluruh keputusan di bawah berbasis pengukuran fl1/fl2/fl3:
     *   FL-11  Referer TIDAK wajib (200 pada dengan/tanpa/salah referer)
     *   FL-12  master HLS valid, EXT-X-KEY=0, BYTERANGE=0, 2 varian
     *   FL-13  segmen TS polos, 0x47 di offset 0, tanpa wrapper
     *   FL-14  token s=waktu-request, e=36 jam -> ambil ulang tiap loadLinks
     *   FL-15  hls2 dan hls3 berada di DUA host berbeda
     *
     * Karena FL-13, LocalProxy tidak dipakai sama sekali di jalur ini.
     *
     * Tahapan melapor sendiri lewat obs supaya titik berhenti selalu terlihat.
     */
    private suspend fun extractFileLions(
        embedUrl: String,
        pageUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // ---------------- FL-1 ----------------
        val html = try {
            app.get(
                embedUrl,
                referer = pageUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
        } catch (e: Exception) {
            LocalProxy.obs("FL-1-GAGAL", "${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        LocalProxy.obs("FL-1-OK", "html=${html.length} char")
        if (html.isBlank()) return false

        // ---------------- FL-2 ----------------
        // BUG YANG DIPERBAIKI: regex lama memakai `.*?\)\)` - lazy, tanpa
        // jangkar. Ia berhenti di "))" PERTAMA yang muncul DI DALAM payload,
        // sehingga blok terpotong jadi ~1000 char padahal aslinya ~11000
        // (terukur: Kotlin lapor 1070, fl1.py lapor 11460 untuk halaman
        // setara). Versi Python memakai jangkar `\s*(?:</script>|$)` dan
        // jangkar itu tidak ikut terbawa saat porting.
        //
        // Sekarang dijangkarkan ke `.split('|')` yang hanya muncul SEKALI,
        // yaitu di argumen keempat packer. Deterministik, tidak bergantung
        // pada tebakan posisi tutup kurung.
        val kandidat = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\s*\)?\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(html).map { it.value }.toList()

        if (kandidat.isEmpty()) {
            LocalProxy.obs("FL-2-GAGAL", "blok packed tidak ditemukan")
            return false
        }
        LocalProxy.obs(
            "FL-2-OK",
            "blok=${kandidat.size} ukuran=${kandidat.map { it.length }} " +
                "(bandingkan dengan fl1.py di Termux)"
        )

        // ---------------- FL-3 ----------------
        // Halaman bisa memuat lebih dari satu blok packed (iklan, player, dll).
        // Coba semuanya, pakai yang pertama menghasilkan URL media.
        var code: String? = null
        var links = LinkedHashMap<String, String>()
        for ((idx, blok) in kandidat.withIndex()) {
            val hasil = unpackDeanEdwards(blok)
            if (hasil.isNullOrBlank()) {
                LocalProxy.obs("FL-3-SKIP", "blok[$idx] unpack gagal")
                continue
            }
            val l = LinkedHashMap<String, String>()
            Regex(""""(\w+)"\s*:\s*"(https?://[^"]+)"""").findAll(hasil).forEach {
                l[it.groupValues[1]] = it.groupValues[2]
            }
            if (l.isEmpty()) {
                Regex("""(\w+)\s*:\s*"(https?://[^"]+)"""").findAll(hasil).forEach {
                    l[it.groupValues[1]] = it.groupValues[2]
                }
            }
            LocalProxy.obs(
                "FL-3-OK",
                "blok[$idx] unpacked=${hasil.length} char links=${l.keys}"
            )
            if (l.values.any { it.contains(".m3u8") || it.contains(".mp4") }) {
                code = hasil
                links = l
                break
            }
        }
        if (code == null) {
            LocalProxy.obs("FL-3-GAGAL", "tidak ada blok yang menghasilkan URL media")
            return false
        }

        // ---------------- FL-4 ----------------
        // Urutan preferensi mengikuti kode player itu sendiri:
        //   sources:[{file: links.hls4 || links.hls3 || links.hls2}]
        val urutan = listOf("hls4", "hls3", "hls2")
        val terpilih = LinkedHashMap<String, String>()
        for (k in urutan) links[k]?.let { terpilih[k] = it }
        // sisanya yang berupa media tapi bukan hls2/3/4
        links.forEach { (k, v) ->
            if (k !in terpilih && (v.contains(".m3u8") || v.contains(".mp4"))) {
                terpilih[k] = v
            }
        }

        if (terpilih.isEmpty()) {
            LocalProxy.obs("FL-4-BERHENTI", "tidak ada URL media di hasil unpack " +
                "(links ditemukan: ${links.keys})")
            return false
        }

        var n = 0
        terpilih.forEach { (label, url) ->
            val host = runCatching { java.net.URL(url).host }.getOrNull() ?: "?"

            // FL-16: deteksi lama `url.contains(".m3u8")` GAGAL untuk master
            // yang disajikan sebagai .txt. Terukur di logcat:
            //   label=hls3 hls=false url=.../hls3/01/08485/..._,l,n,h,.urlset/master.txt
            // Akibatnya link diemit sebagai VIDEO progresif padahal isinya
            // playlist HLS, dan sumber hls3 rusak total. Situs memberi nama
            // kuncinya sendiri (hls2/hls3/hls4), jadi label itu dipakai sebagai
            // penentu yang lebih andal daripada ekstensi berkas.
            val isHls = label.startsWith("hls") ||
                url.contains(".m3u8") || url.contains(".txt")

            // Master disaring lewat proxy mode clean untuk membuang varian
            // I-frame. Terukur di FL-5-MASTER: normal=3 dan iframe=3 dengan
            // himpunan resolusi identik, total 6 entri - persis jumlah baris
            // ganda di Video Trek. Hanya master yang lewat proxy; URI varian
            // ditulis absolut sehingga segmen tetap langsung ke CDN.
            val servedUrl = if (isHls) {
                LocalProxy.proxyUrl(url, clean = true) ?: url
            } else {
                url
            }
            val viaProxy = servedUrl != url

            LocalProxy.obs(
                "FL-4-EMIT",
                "label=$label host=$host hls=$isHls clean=$viaProxy url=$url"
            )
            callback(
                newExtractorLink(
                    source = serverName,
                    name = "$serverName [$label]",
                    url = servedUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    // FL-11: Referer tidak wajib pada CDN FileLions.
                    this.referer = ""
                }
            )
            n++
        }
        LocalProxy.obs("FL-4-OK", "emit=$n dari ${links.size} link")

        // ---------------- FL-5 (diagnostik sementara) ----------------
        // Mengambil master playlist lalu melaporkan strukturnya. Tujuannya
        // menjawab satu pertanyaan: apakah duplikasi Video Trek berasal dari
        // isi master, atau dari cara ExoPlayer menafsirkannya.
        //
        // Yang dilaporkan per master:
        //   normal  = resolusi dari #EXT-X-STREAM-INF        (varian playback)
        //   iframe  = resolusi dari #EXT-X-I-FRAME-STREAM-INF (trick-play)
        //
        // Kalau normal dan iframe berisi resolusi yang SAMA dan jumlah
        // gabungannya cocok dengan jumlah baris di Video Trek, sebabnya
        // master playlist. Kalau tidak cocok, sebabnya di lapisan lain.
        if (FL_DIAG) {
            terpilih.forEach { (label, url) ->
                if (!url.contains(".m3u8")) return@forEach
                try {
                    val pl = app.get(
                        url,
                        headers = mapOf("User-Agent" to USER_AGENT)
                    ).text
                    val resDari = { tag: String ->
                        Regex("""$tag:[^\n]*""").findAll(pl).map { mr ->
                            Regex("""RESOLUTION=(\d+x\d+)""")
                                .find(mr.value)?.groupValues?.get(1) ?: "?"
                        }.toList()
                    }
                    val normal = resDari("#EXT-X-STREAM-INF")
                    val iframe = resDari("#EXT-X-I-FRAME-STREAM-INF")
                    LocalProxy.obs(
                        "FL-5-MASTER",
                        "label=$label bytes=${pl.length} " +
                            "normal=${normal.size}$normal " +
                            "iframe=${iframe.size}$iframe " +
                            "key=${pl.contains("#EXT-X-KEY")} " +
                            "total_entri=${normal.size + iframe.size}"
                    )
                } catch (e: Exception) {
                    LocalProxy.obs("FL-5-GAGAL", "label=$label ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        return n > 0
    }

    private fun extractMediaId(url: String): String? {
        val base = url.substringBefore('#')
        Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(base)?.let { return it.groupValues[1] }
        val seg = base.substringBefore('?').trimEnd('/').substringAfterLast('/')
        return if (seg.matches(Regex("""[A-Za-z0-9_-]{4,}"""))) seg else null
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

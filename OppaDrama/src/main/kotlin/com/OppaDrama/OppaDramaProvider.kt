package com.OppaDrama

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlin.coroutines.cancellation.CancellationException

class OppaDramaProvider : MainAPI() {
    override var name = "OPPADRAMA"
    override var mainUrl = "http://45.11.57.192"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 1500L

    // Injeksi Headers & Cookie hasil sniffing lalu lintas paket data browser desktop
    private val desktopBypassHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Cookie" to "user_is_human=true",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control" to "max-age=0",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    // Adopsi penuh susunan daftar kategori terlengkap dari WordPress Dramastream Engine
    override val mainPage = mainPageOf(
        "$mainUrl/series/?status=&type=&order=update" to "Latest Update",
        "$mainUrl/series/?status=Completed&type=Drama&order=update" to "Completed Drama",
        "$mainUrl/series/?country%5B%5D=china&type=Drama&order=update" to "Drama China",
        "$mainUrl/series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "$mainUrl/series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "$mainUrl/series/?country%5B%5D=philippines&type=Drama&order=update" to "Drama Philippines",
        "$mainUrl/series/?country%5B%5D=taiwan&type=Drama&order=update" to "Drama Taiwan",
        "$mainUrl/series/?country%5B%5D=thailand&type=Drama&order=update" to "Drama Thailand",
        "$mainUrl/series/?country%5B%5D=usa&type=Drama&order=update" to "Drama Western",
        "$mainUrl/series/?type=Movie&order=update" to "All Movies",
        "$mainUrl/series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Korean Movie",
        "$mainUrl/series/?country%5B%5D=japan&type=Movie&order=update" to "Japan Movie",
        "$mainUrl/series/?country%5B%5D=china&type=Movie&order=update" to "Chinese Movie",
        "$mainUrl/series/?country%5B%5D=thailand&type=Movie&order=update" to "Thailand Movie",
        "$mainUrl/series/?country%5B%5D=taiwan&type=Movie&order=update" to "Taiwan Movie",
        "$mainUrl/series/?country%5B%5D=philippines&type=Movie&order=update" to "Philippines Movie",
        "$mainUrl/series/?country%5B%5D=india&type=Movie&order=update" to "India Movie",
        "$mainUrl/series/?country%5B%5D=united-states&type=Movie&order=update" to "Western Movie"
    )

    private fun Element.extractPoster(): String? {
        val img = this.select("img").first() ?: return null
        val rawUrl = when {
            img.hasAttr("data-src") -> img.attr("data-src")
            img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
            img.hasAttr("srcset") -> img.attr("srcset").substringBefore(" ")
            else -> img.attr("src")
        }
        if (rawUrl.isNullOrBlank()) return null
        return rawUrl.replace(Regex("[?&]resize=\\d+,\\d+"), "")
            .replace(Regex("[?&]quality=\\d+"), "")
    }

    private fun cleanTitle(title: String): String =
        title.replace(Regex("\\s*(?:Episode|Ep|Eps)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "").trim()

    // Parser kartu hasil (dipakai bersama oleh getMainPage & search)
    private fun Element.toCardSearchResponse(forceMovie: Boolean = false): SearchResponse? {
        val anchor = this.select("div.bsx a").first()
        val rawTitle = this.select("h2[itemprop=headline]").first()?.text() ?: anchor?.attr("title")
        val link = fixUrlNull(anchor?.attr("href")) ?: return null
        if (rawTitle.isNullOrEmpty()) return null

        val poster = this.extractPoster()
        val typeStr = this.select(".typez").first()?.text()
        val title = cleanTitle(rawTitle)

        val isMovie = forceMovie ||
                typeStr?.contains("Movie", ignoreCase = true) == true ||
                link.contains("/movie-")

        return if (isMovie) {
            newMovieSearchResponse(title, link, TvType.Movie) { this.posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, link, TvType.AsianDrama) { this.posterUrl = poster }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // Penanganan paginasi struktur tautan dinamis arsip kategori WordPress
        val targetUrl = if (page > 1) {
            request.data.replace("/series/?", "/series/page/$page/?")
        } else {
            request.data
        }

        // Idiomatik NiceHttp: .document langsung, tanpa Jsoup.parse manual
        val document = app.get(targetUrl, headers = desktopBypassHeaders).document
        val forceMovie = request.data.contains("type=Movie")
        var items = document.select("article.bs")
            .mapNotNull { it.toCardSearchResponse(forceMovie) }

        // Fallback: sebagian konfigurasi WordPress menolak pola /series/page/N/
        // dan hanya menerima query "&paged=N". Coba pola kedua bila kosong.
        if (items.isEmpty() && page > 1) {
            val altUrl = "${request.data}&paged=$page"
            items = app.get(altUrl, headers = desktopBypassHeaders).document
                .select("article.bs")
                .mapNotNull { it.toCardSearchResponse(forceMovie) }
            Log.d("OppaDrama", "mainPage p=$page fallback=$altUrl items=${items.size}")
        } else {
            Log.d("OppaDrama", "mainPage p=$page url=$targetUrl items=${items.size}")
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val document = app.get("$mainUrl/?s=$query", headers = desktopBypassHeaders).document
        return document.select("article.bs").mapNotNull { it.toCardSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = desktopBypassHeaders).document

        var isMovie = url.contains("/movie-")
        for (span in document.select("div.spe span")) {
            val label = span.select("b").first()?.text()?.lowercase() ?: ""
            if (label.contains("tipe") || label.contains("type")) {
                if (span.text().lowercase().contains("movie")) {
                    isMovie = true
                }
            }
        }

        // Jika ini halaman episode tunggal, ambil tautan bapak serialnya
        // lewat indeks Breadcrumb kedua
        val breadcrumbs = document.select(".ts-breadcrumb ol li a")
        if (breadcrumbs.size >= 3 && !isMovie) {
            val parentUrl = fixUrlNull(breadcrumbs[1].attr("href"))
            if (!parentUrl.isNullOrBlank() && parentUrl != url) {
                val parentDocument = app.get(parentUrl, headers = desktopBypassHeaders).document
                return loadSeries(parentUrl, parentDocument)
            }
        }

        return if (isMovie) loadMovie(url, document) else loadSeries(url, document)
    }

    private suspend fun loadSeries(url: String, document: Document): LoadResponse? {
        val title = document.select("h1.entry-title").first()?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.select("div.bigcontent img, div.thumb img").first()?.extractPoster())
        val info = parseInfo(document)

        val episodes = mutableListOf<Episode>()
        val reversedAnchors = document.select("div.eplister ul li a").toList().reversed()

        for (i in reversedAnchors.indices) {
            val anchor = reversedAnchors[i]
            val href = anchor.attr("href")
            val epNumber = anchor.select("div.epl-num").first()?.text()?.trim()?.toIntOrNull() ?: (i + 1)
            val epTitle = anchor.select("div.epl-title").first()?.text()?.trim() ?: "Episode $epNumber"
            val epPoster = fixUrlNull(anchor.select("img").first()?.extractPoster())

            episodes.add(newEpisode(href) {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = epPoster
            })
        }

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendation() }

        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actorNames = document.select("div.spe span:has(b:matchesOwn(^Artis\$)) a")
            .map { it.text().trim() }.filter { it.isNotBlank() }
        val trailerUrl = document.select("div.bixbox.trailer iframe").first()?.attr("src")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.showStatus = info.status
            this.duration = info.duration
            this.tags = tags
            this.recommendations = recommendations
            info.rating?.let { this.score = Score.from(it, 10) }
            // Helper standar: menghormati setting isTrailersEnabled milik pengguna
            addActors(actorNames)
            addTrailer(trailerUrl)
        }
    }

    private suspend fun loadMovie(url: String, document: Document): LoadResponse? {
        val title = document.select("h1.entry-title").first()?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.select("div.bigcontent img, div.thumb img").first()?.extractPoster())
        val info = parseInfo(document)

        val recommendations = document.select("div.listupd article.bs")
            .mapNotNull { it.toRecommendation() }

        val tags = document.select("div.genxed a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actorNames = document.select("div.spe span:has(b:matchesOwn(^Artis\$)) a")
            .map { it.text().trim() }.filter { it.isNotBlank() }
        val trailerUrl = document.select("div.bixbox.trailer iframe").first()?.attr("src")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = info.year
            this.plot = info.plot
            this.duration = info.duration
            this.tags = tags
            this.recommendations = recommendations
            info.rating?.let { this.score = Score.from(it, 10) }
            addActors(actorNames)
            addTrailer(trailerUrl)
        }
    }

    private fun Element.toRecommendation(): SearchResponse? {
        val anchor = this.select("a").first() ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.attr("title")
            .ifBlank { this.select("div.tt").first()?.text()?.trim() }
            ?.takeIf { it.isNotBlank() } ?: return null
        val poster = fixUrlNull(this.select("img").first()?.extractPoster())
        val looksLikeEpisode = Regex("[-_]episode[-_]?\\d+", RegexOption.IGNORE_CASE).containsMatchIn(href)
        val type = if (looksLikeEpisode) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(cleanTitle(title), href, type) {
            this.posterUrl = poster
        }
    }

    // Domain keluarga Abyss/Hydrax. Extractor core "ByseSX" mencocokkan domain ini
    // LEBIH DULU (extractor plugin diproses setelah extractor core), lalu gagal
    // parse API situs ini (MissingFieldException di logcat) tapi pencarian tetap
    // dianggap selesai. Maka URL keluarga ini di-routing langsung ke AbyssExtractor
    // sebelum menyentuh loadExtractor.
    private val abyssHosts = listOf("abyss.to", "abyssplayer.com", "short.icu", "abysscdn.com")

    private suspend fun dispatchEmbed(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = httpsify(url)
        val host = fixed.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        if (abyssHosts.any { host == it || host.endsWith(".$it") }) {
            AbyssExtractor().getUrl(fixed, referer, subtitleCallback, callback)
        } else {
            loadExtractor(fixed, referer, subtitleCallback, callback)
        }
    }

    private suspend fun parseEmbeds(
        doc: Document,
        dataUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Semua extractor kustom (Minochinos, Abyss + alias AbyssPlayer, dll.)
        // sudah terdaftar via registerExtractorAPI, sehingga loadExtractor()
        // otomatis mencocokkannya berdasarkan prefix mainUrl / Levenshtein mirror.
        // Fallback instansiasi manual tidak diperlukan lagi.
        doc.select("div.player-embed iframe").first()?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                dispatchEmbed(src, dataUrl, subtitleCallback, callback)
            }
        }

        val mirrors = doc.select("select.mirror option[value]:not([disabled])")
        for (option in mirrors) {
            val encoded = option.attr("value").trim()
            if (encoded.isBlank() || encoded.equals("Pilih Server Video", ignoreCase = true)) continue
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))
                val mirrorSrc = Jsoup.parse(decoded).select("iframe").first()?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }
                }
                if (!mirrorSrc.isNullOrBlank()) {
                    dispatchEmbed(mirrorSrc, dataUrl, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Jangan menelan CancellationException (mekanisme timeout core)
                if (e is CancellationException) throw e
                Log.e("OppaDrama", "Mirror decode/extract gagal: ${e.message}")
            }
        }

        for (a in doc.select("div.dlbox li span.e a[href]")) {
            val href = a.attr("href").trim()
            if (href.isNotBlank()) {
                dispatchEmbed(href, dataUrl, subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = desktopBypassHeaders).document

        var isMovie = data.contains("/movie-")
        for (span in document.select("div.spe span")) {
            val label = span.select("b").first()?.text()?.lowercase() ?: ""
            if (label.contains("tipe") || label.contains("type")) {
                if (span.text().lowercase().contains("movie")) {
                    isMovie = true
                }
            }
        }

        if (isMovie) {
            val pseudoEpisodes = document.select("div.eplister ul li a")
            if (pseudoEpisodes.isNotEmpty()) {
                for (anchor in pseudoEpisodes) {
                    val href = anchor.attr("href")
                    if (!href.isNullOrBlank()) {
                        val subDocument = app.get(href, headers = desktopBypassHeaders).document
                        parseEmbeds(subDocument, href, subtitleCallback, callback)
                    }
                }
                return true
            }
        }

        parseEmbeds(document, data, subtitleCallback, callback)
        return true
    }

    private data class SeriesInfo(
        val status: ShowStatus,
        val year: Int?,
        val plot: String?,
        val rating: Double?,
        val duration: Int?
    )

    private fun parseInfo(document: Document): SeriesInfo {
        val plot = document.select("div.entry-content p, div.desc p")
            .joinToString("\n") { it.text() }.trim().ifBlank { null }
        var status: ShowStatus = ShowStatus.Completed
        var year: Int? = null
        var duration: Int? = null
        var rating: Double? = null

        for (span in document.select("div.spe > span")) {
            val labelElement = span.select("b").first() ?: continue
            val label = labelElement.text().trim().removeSuffix(":")
            val value = span.text().replace(labelElement.text(), "").trim()

            when (label.lowercase()) {
                "status" -> status = if (value.lowercase().contains("ongoing")) ShowStatus.Ongoing else ShowStatus.Completed
                "dirilis" -> {
                    val yearMatch = Regex("(\\d{4})").find(value)?.groupValues?.getOrNull(1)
                    year = yearMatch?.toIntOrNull()
                }
                "durasi" -> duration = parseDurationMinutes(value)
                "rating" -> rating = value.toDoubleOrNull()
            }
        }

        if (rating == null) {
            val ratingText = document.select("div.rating strong").first()?.text()
            if (ratingText != null) {
                rating = ratingText.replace("Rating", "", ignoreCase = true).trim().toDoubleOrNull()
            }
        }
        return SeriesInfo(status, year, plot, rating, duration)
    }

    private fun parseDurationMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val hours = Regex("(\\d+)\\s*hr").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*min").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return if (total > 0) total else null
    }
}

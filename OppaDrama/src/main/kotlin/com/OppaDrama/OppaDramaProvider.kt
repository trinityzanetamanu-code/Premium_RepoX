package com.OppaDrama

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    /* [TIMING] Jejak akhir pemanggilan getMainPage sebelumnya. Dipakai untuk
     * mengukur JEDA antar kategori — inilah yang memperlihatkan biaya
     * sequentialMainPageDelay secara langsung. Hapus bersama blok TIMING. */
    private val lastMainPageFinish = java.util.concurrent.atomic.AtomicLong(0L)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val t0 = System.currentTimeMillis()

        // Penanganan paginasi struktur tautan dinamis arsip kategori WordPress
        val targetUrl = if (page > 1) {
            request.data.replace("/series/?", "/series/page/$page/?")
        } else {
            request.data
        }

        // [TIMING] .document dipecah jadi tiga tahap supaya jaringan, pembacaan
        // body, dan parsing Jsoup bisa diukur terpisah. Hasil akhirnya identik
        // dengan .document — hanya visibilitasnya yang bertambah.
        val response = app.get(targetUrl, headers = desktopBypassHeaders)
        val tConnect = System.currentTimeMillis()

        val html = response.text
        val tBody = System.currentTimeMillis()

        val document = Jsoup.parse(html)
        val tParse = System.currentTimeMillis()

        val forceMovie = request.data.contains("type=Movie")
        val selected = document.select("article.bs")
        val tSelect = System.currentTimeMillis()

        var items = selected.mapNotNull { it.toCardSearchResponse(forceMovie) }
        val tMap = System.currentTimeMillis()

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

        val out = newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        val tBuild = System.currentTimeMillis()

        // [TIMING] Jeda sejak kategori sebelumnya selesai. Nilai ~1500 ms berarti
        // waktu itu dihabiskan menunggu, bukan bekerja.
        val prev = lastMainPageFinish.getAndSet(tBuild)
        val gap = if (prev == 0L) -1L else t0 - prev

        Log.i(
            "OppaDrama",
            "TIMING [${request.name}] p=$page " +
                    "gap=${gap}ms | http=${tConnect - t0} body=${tBody - tConnect} " +
                    "jsoup=${tParse - tBody} select=${tSelect - tParse} map=${tMap - tSelect} " +
                    "build=${tBuild - tMap} | TOTAL=${tBuild - t0}ms | " +
                    "code=${response.code} htmlLen=${html.length} nodes=${selected.size} " +
                    "items=${items.size} | thread=${Thread.currentThread().name}"
        )

        return out
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

    /** Deteksi host keluarga Abyss dari URL yang sudah di-httpsify. */
    private fun isAbyssUrl(fixedUrl: String): Boolean {
        val host = fixedUrl.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        return abyssHosts.any { host == it || host.endsWith(".$it") }
    }

    /* ── [DIAG] Penghitung link per embed. Hapus bersama baris ber-tag EMBED. ──
     *
     * Meneruskan link apa adanya — tidak menyaring, tidak mengubah urutan, tidak
     * mengubah isi. Satu-satunya efeknya adalah menghitung, sehingga kita bisa
     * membedakan "extractor tidak pernah dipanggil" dari "extractor dipanggil
     * tapi tidak menghasilkan apa pun".
     */
    private class CountingCallback(
        private val delegate: (ExtractorLink) -> Unit
    ) : (ExtractorLink) -> Unit {
        private val n = AtomicInteger(0)
        val count: Int get() = n.get()
        override fun invoke(link: ExtractorLink) {
            n.incrementAndGet()
            delegate(link)
        }
    }

    /**
     * Routing satu URL embed ke extractor yang tepat.
     * @return URL final (ter-httpsify) yang di-dispatch — dipakai loadLinks
     *         sebagai daftar kandidat untuk fallback WebView.
     */
    private suspend fun dispatchEmbed(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        asal: String = "?",   // [DIAG] penanda selector asal, mis. "MIRROR#2"
    ): String {
        val fixed = httpsify(url)
        val host = fixed.substringAfter("://").substringBefore("/").substringBefore(":")

        // [DIAG]
        val counter = CountingCallback(callback)
        val mulai = System.currentTimeMillis()
        var jalur: String

        if (isAbyssUrl(fixed)) {
            jalur = "ABYSS-LANGSUNG"
            AbyssExtractor().getUrl(fixed, referer, subtitleCallback, counter)
        } else {
            val cocok = loadExtractor(fixed, referer, subtitleCallback, counter)
            jalur = if (cocok) "loadExtractor=COCOK" else "loadExtractor=TIDAK-COCOK"
        }

        // [DIAG] Baris inilah yang mengungkap embed yang selama ini diam.
        val durasi = System.currentTimeMillis() - mulai
        Log.i(
            "OppaDrama",
            "EMBED $asal | host=$host | $jalur | ${counter.count} link | ${durasi}ms | $fixed"
        )

        return fixed
    }

    /**
     * Mengurai seluruh sumber embed dari satu dokumen dan men-dispatch-nya.
     * @return daftar URL embed yang telah dicoba (untuk kandidat fallback).
     *         Alur dispatch tidak berubah dari implementasi sebelumnya.
     */
    private suspend fun parseEmbeds(
        doc: Document,
        dataUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): List<String> {
        // TAHAP 1 — kumpulkan seluruh URL embed lebih dulu, tanpa request apa pun.
        //
        // Player default pada halaman ini juga terdaftar sebagai opsi pertama di
        // dropdown mirror, sehingga embed yang sama sebelumnya diproses dua kali
        // (terlihat di logcat: 4 link Emturbovid identik muncul dua kali, terpaut
        // ~2 detik). Mengumpulkan dulu memungkinkan deduplikasi sebelum request.
        // [DIAG] Pasangan (asal, url) supaya tiap embed bisa dilacak balik ke
        // selector sumbernya. Kembalikan ke mutableListOf<String>() saat bersih-bersih.
        val rawEmbeds = mutableListOf<Pair<String, String>>()

        doc.select("div.player-embed iframe").first()?.let { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) rawEmbeds.add("IFRAME" to src)
        }

        val mirrors = doc.select("select.mirror option[value]:not([disabled])")
        for ((idx, option) in mirrors.withIndex()) {
            val encoded = option.attr("value").trim()
            if (encoded.isBlank() || encoded.equals("Pilih Server Video", ignoreCase = true)) continue
            try {
                val decoded = base64Decode(encoded.replace("\\s".toRegex(), ""))
                val mirrorSrc = Jsoup.parse(decoded).select("iframe").first()?.let { el ->
                    el.attr("src").ifBlank { el.attr("data-src") }
                }
                if (!mirrorSrc.isNullOrBlank()) rawEmbeds.add("MIRROR#$idx" to mirrorSrc)
            } catch (e: Exception) {
                // Jangan menelan CancellationException (mekanisme timeout core)
                if (e is CancellationException) throw e
                Log.e("OppaDrama", "Mirror decode gagal: ${e.message}")
            }
        }

        // div.dlbox SENGAJA TIDAK DIAMBIL.
        //
        // Terverifikasi dari HAR (26 Jul 2026, halaman
        // /movie-project-hail-mary-2026-web-line/): seluruh isi dlbox adalah
        // tautan UNDUHAN, bukan pemutar streaming.
        //
        //   Buzzheavier  -> https://buzzheavier.com/3hxvid3be2pf
        //   DataNodes    -> https://datanodes.to/fzjo7hpgrf1v
        //   EarnVids     -> https://vidhidepro.com/d/daqht188fqm4   <- perhatikan /d/
        //   GD/Telegram  -> https://fpgo.xyz/file/6a004b8b0ed6acff53ed1c12
        //
        // Bandingkan dengan server streaming FileLions di select.mirror:
        //   https://minochinos.com/v/daqht188fqm4                    <- /v/, ID SAMA
        //
        // Jadi EarnVids dan FileLions adalah berkas yang sama; hanya jalur
        // unduh versus jalur tonton. Mengambil dlbox berarti memanggil empat
        // extractor yang tidak akan pernah menghasilkan stream (terukur ~12
        // detik terbuang di logcat 10:51), lalu menyodorkan entri rusak ke
        // daftar sumber. Situs hanya punya TIGA server streaming.

        // TAHAP 2 — deduplikasi.
        //
        // Kunci dedup SENGAJA berupa string URL persis sesudah httpsify() + trim(),
        // tanpa normalisasi lain. Alasannya:
        //   - lowercase() BERBAHAYA: subdomain CDN seperti "wt4PjIIVE9AGjPL" peka
        //     huruf besar-kecil, dua mirror bisa berbeda hanya di kapitalisasi.
        //   - membuang query BERBAHAYA: AbyssExtractor membaca identitas video dari
        //     parameter "v", dan token CDN juga hidup di query string.
        // Dua string byte-identik dijamin menghasilkan request dan hasil yang sama,
        // jadi membuang yang kedua tidak mungkin menghilangkan mirror yang berbeda.
        // Bila ragu, biasnya ke arah aman: keduanya tetap diproses.
        val uniqueEmbeds = rawEmbeds
            .map { (asal, u) -> asal to httpsify(u).trim() }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }   // urutan tetap, kemunculan pertama menang

        val skipped = rawEmbeds.size - uniqueEmbeds.size
        if (skipped > 0) {
            Log.i("OppaDrama", "Dedup embed: ${rawEmbeds.size} -> ${uniqueEmbeds.size} ($skipped duplikat dilewati)")
        }

        // TAHAP 3 — dispatch. Urutannya identik dengan sebelumnya.
        val attempted = mutableListOf<String>()
        for ((asal, embed) in uniqueEmbeds) {
            try {
                attempted.add(dispatchEmbed(embed, dataUrl, subtitleCallback, callback, asal))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // [DIAG] embed yang melempar sekarang ikut tercatat dengan asalnya
                Log.e("OppaDrama", "EMBED $asal | EXCEPTION ${e.javaClass.simpleName}: ${e.message} | $embed")
            }
        }

        return attempted
    }


    /* ── Deteksi halaman tantangan anti-bot ──────────────────────────────────
     *
     * Diverifikasi langsung terhadap server pada 26 Jul 2026: request tanpa
     * cookie `user_is_human=true` TIDAK menerima 403 maupun redirect, melainkan
     * HTTP 200 berisi halaman interstisial "Verifying your browser...".
     *
     * Itu bentuk kegagalan paling berbahaya bagi parser ini: statusnya sukses,
     * Jsoup mengurainya tanpa keluhan, tetapi seluruh selector (player-embed,
     * select.mirror, dlbox) menghasilkan nol elemen. Gejalanya di layar identik
     * dengan "situs tidak punya sumber" padahal sebenarnya kita ditolak.
     *
     * Guard ini mengubah kegagalan senyap menjadi pesan yang jelas.
     */
    private fun Document.isAntiBotChallenge(): Boolean {
        val body = this.body()?.text()?.lowercase() ?: return false
        if (body.length > 2000) return false   // halaman asli jauh lebih panjang
        return body.contains("verifying your browser") ||
                body.contains("checking your browser") ||
                body.contains("check your connection")
    }

    /** Ambil halaman + hentikan lebih awal bila yang datang halaman tantangan. */
    private suspend fun getPageOrThrow(url: String): Document {
        val document = app.get(url, headers = desktopBypassHeaders).document
        if (document.isAntiBotChallenge()) {
            Log.e("OppaDrama", "Diblokir anti-bot (cookie user_is_human tidak lagi diterima): $url")
            throw ErrorLoadingException("Situs menolak permintaan (halaman verifikasi browser). Mekanisme bypass perlu diperbarui.")
        }
        return document
    }

    /**
     * LAST RESORT: dijalankan HANYA bila seluruh pipeline HTTP selesai tanpa
     * satu pun ExtractorLink. Kandidat diurutkan non-Abyss lebih dulu (host
     * MSE/blob seperti Abyss diketahui sulit di-sniff), dibatasi maksimal
     * [MAX_WEBVIEW_ATTEMPTS] percobaan, dan berhenti pada keberhasilan pertama.
     * Seluruh kegagalan di sini bersifat non-fatal terhadap alur loadLinks.
     */
    private suspend fun runWebViewFallbackIfNeeded(
        linkFound: AtomicBoolean,
        attemptedEmbeds: List<String>,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (linkFound.get() || attemptedEmbeds.isEmpty()) return

        Log.w("OppaDrama", "Seluruh extractor gagal (${attemptedEmbeds.size} embed dicoba). Mengaktifkan fallback WebView.")

        val candidates = attemptedEmbeds
            .distinct()
            .sortedBy { if (isAbyssUrl(it)) 1 else 0 }
            .take(MAX_WEBVIEW_ATTEMPTS)

        for (embed in candidates) {
            if (WebViewFallback.sniff(embed, pageUrl, callback)) {
                linkFound.set(true)
                break
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tLoadLinks0 = System.currentTimeMillis()
        // Penanda keberhasilan pipeline utama. AtomicBoolean dipakai (bukan var
        // Boolean) demi jaminan visibilitas memori bila sebuah extractor core
        // memanggil callback dari dispatcher thread berbeda. Pada jalur sukses,
        // satu-satunya overhead tambahan adalah satu operasi set() ini.
        val linkFound = AtomicBoolean(false)
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            linkFound.set(true)
            callback(link)
        }

        val document = getPageOrThrow(data)
        val tPage = System.currentTimeMillis()
        Log.i("OppaDrama", "TIMING loadLinks tahap=ambilHalaman ${tPage - tLoadLinks0}ms url=$data")

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
                val attemptedEmbeds = mutableListOf<String>()
                for (anchor in pseudoEpisodes) {
                    val href = anchor.attr("href")
                    if (!href.isNullOrBlank()) {
                        val tSub0 = System.currentTimeMillis()
                        val subDocument = getPageOrThrow(href)
                        Log.i("OppaDrama", "TIMING loadLinks tahap=halamanVersi ${System.currentTimeMillis() - tSub0}ms url=$href")
                        attemptedEmbeds += parseEmbeds(subDocument, href, subtitleCallback, trackingCallback)
                    }
                }
                // Fallback dievaluasi SETELAH seluruh sub-halaman selesai diproses.
                runWebViewFallbackIfNeeded(linkFound, attemptedEmbeds, data, trackingCallback)
                return true
            }
        }

        val attemptedEmbeds = parseEmbeds(document, data, subtitleCallback, trackingCallback)
        // Fallback dievaluasi SETELAH iframe utama + semua mirror + dlbox selesai.
        runWebViewFallbackIfNeeded(linkFound, attemptedEmbeds, data, trackingCallback)
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

    companion object {
        /** Batas percobaan sniffing WebView per pemanggilan loadLinks. */
        private const val MAX_WEBVIEW_ATTEMPTS = 2
    }
}

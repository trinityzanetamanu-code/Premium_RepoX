package com.Cinemacity

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Cinemacity : MainAPI() {

    override var mainUrl = "https://cinemacity.cc"
    override var name = "CinemaCity"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        private const val loginCookie = ""
        private val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val episodeRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val imdbRegex = Regex("""tt\d+""")
        private val subtitleRegex = Regex("""\[(.+?)](https?://.+)""")
        private val yearRegex = Regex("""\((\d{4})""")
        private val titleYearRegex = Regex("""\s*\(\d{4}(?:–\d{4}|–)?\)\s*$""")
        private val durationRegex = Regex("""(?:(\d+)\s*h)?\s*(?:(\d+)\s*m)?""")
        private val cfMarkers = listOf(
            "<title>just a moment",
            "id=\"challenge-form\"",
            "cf-browser-verification",
            "checking your browser before accessing",
            "attention required"
        )
        private const val TAG = "Phisher"

        /**
         * Menjamin HANYA SATU dialog bypass Cloudflare berjalan pada satu waktu.
         *
         * Tanpa ini, setiap request yang terblokir memanggil dialognya sendiri.
         * Saat layar utama memuat beberapa kategori sekaligus, hasilnya beberapa
         * WebView berebut memuat halaman tantangan yang sama - Cloudflare
         * menganggapnya perilaku bot dan verifikasinya tidak pernah selesai.
         */
        private val cfMutex = Mutex()

        private fun hasClearance(): Boolean =
            CinemacityPlugin.cfCookies.contains("cf_clearance=")
    }

    // ---------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------

    private suspend fun appGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        tag: String = "OTHER"                     // DIAGNOSTIC: label saja, punya default
    ): com.lagradost.nicehttp.NiceResponse {
        var response = app.get(url, headers = headers, interceptor = CinemacityCFBypassInterceptor)
        ccDiagHttp(tag, "TRY1", response, url)    // DIAGNOSTIC

        if (isCloudflareBlocked(response.code, response.text)) {
            Log.d(TAG, "CF Challenge detected, attempting bypass...")

            // Hanya satu pemanggil yang boleh membuka dialog. Pemanggil lain
            // menunggu di sini, lalu memakai cookie yang sudah didapat.
            val success = cfMutex.withLock {
                if (hasClearance()) {
                    Log.d(TAG, "cf_clearance sudah ada dari permintaan lain, dialog dilewati.")
                    Log.d(TAG, "[$tag/CF] hasClearance=true -> DIALOG DILEWATI, retry pakai cookie lama")  // DIAGNOSTIC
                    true
                } else {
                    Log.d(TAG, "[$tag/CF] hasClearance=false -> buka dialog WebView")                      // DIAGNOSTIC
                    showCinemacityCFBypassDialogAndWait()
                }
            }
            Log.d(TAG, "[$tag/CF] bypassResult=$success")                                                  // DIAGNOSTIC

            if (success) {
                Log.d(TAG, "CF Bypass success, retrying request...")
                response = app.get(url, headers = headers, interceptor = CinemacityCFBypassInterceptor)
                ccDiagHttp(tag, "TRY2", response, url)                                                     // DIAGNOSTIC
            } else {
                if (ActivityHelper.currentActivity == null) {
                    throw ErrorLoadingException("Cloudflare Aktif! Tutup paksa aplikasi CloudStream (Clear Recent Apps) lalu buka kembali.")
                } else {
                    throw ErrorLoadingException("Bypass Cloudflare dibatalkan/gagal. Coba muat ulang.")
                }
            }
        }

        if (isCloudflareBlocked(response.code, response.text)) {
            Log.d(TAG, "[$tag/CF] STOP: masih ter-challenge setelah retry")   // DIAGNOSTIC
            throw ErrorLoadingException("CinemaCity: Terhalang Cloudflare. Coba muat ulang.")
        }

        return response
    }

    // ===============================================================
    // DIAGNOSTIC ONLY — read-only, tidak mengubah request/state apa pun.
    // Hapus seluruh blok ini setelah root cause terkunci.
    //
    // Member NiceResponse yang dipakai DIBATASI pada yang sudah terbukti
    // ada di file ini sebelum patch: .code, .text, .document.
    // .url dan .headers TIDAK dipakai (belum terverifikasi di project ini);
    // URL diambil dari parameter `url` milik appGet.
    // ===============================================================

    /** Nama cookie + panjang + fingerprint. TIDAK pernah mencetak nilai asli. */
    private fun ccDiagCookieFp(): String {
        val raw = CinemacityPlugin.cfCookies
        if (raw.isBlank()) return "NONE"
        return raw.split(";").mapNotNull { part ->
            val t = part.trim()
            if (t.isEmpty()) return@mapNotNull null
            val name = t.substringBefore('=', "")
            val value = t.substringAfter('=', "")
            if (name.isBlank()) null
            else "$name(len=${value.length},fp=${value.hashCode().toString(16)})"
        }.joinToString(",")
    }

    /**
     * Tahap C-G: apa yang benar-benar diterima OkHttp.
     *
     * CATATAN BACA: `pjRaw` hampir selalu false pada halaman detail normal,
     * karena `new Playerjs(` berada di dalam base64. pjRaw=true justru anomali.
     */
    private fun ccDiagHttp(
        tag: String,
        phase: String,
        res: com.lagradost.nicehttp.NiceResponse,
        requestedUrl: String
    ) {
        val body = res.text
        val low = body.lowercase()
        val head = body.take(400).replace('\n', ' ').replace('\r', ' ')

        val hasAtob = body.contains("atob(")
        val hasPjRaw = body.contains("new Playerjs(")
        val hasFileKey = low.contains("\"file\"")
        val hasJustAMoment = low.contains("just a moment")
        val hasCfChl = body.contains("cf-chl") || body.contains("__cf_chl")
        val hasChallengeWord = low.contains("challenge")
        val hasShortItem = body.contains("dar-short_item")
        val hasPlayerShell = body.contains("cc-player-shell")
        val hasDleLogout = low.contains("action=logout")
        val cookieFp = ccDiagCookieFp()

        Log.d(TAG, "[$tag/$phase] reqUrl=$requestedUrl")
        Log.d(TAG, "[$tag/$phase] code=${res.code} bodyLen=${body.length}")
        Log.d(
            TAG,
            "[$tag/$phase] atob=$hasAtob pjRaw=$hasPjRaw fileKey=$hasFileKey" +
                " justAMoment=$hasJustAMoment cfChl=$hasCfChl challengeWord=$hasChallengeWord" +
                " shortItem=$hasShortItem playerShell=$hasPlayerShell dleLogout=$hasDleLogout"
        )
        Log.d(TAG, "[$tag/$phase] cookies=$cookieFp")
        Log.d(TAG, "[$tag/$phase] head=$head")

        // ---------------------------------------------------------------
        // OPSIONAL — header Cloudflare (cf-mitigated / cf-ray).
        // SAYA TIDAK BISA MEMVERIFIKASI property `headers` pada NiceResponse
        // versi project ini, jadi baris ini SENGAJA dinonaktifkan.
        // Uncomment, build sekali. Kalau hijau: biarkan (datanya berguna).
        // Kalau merah: comment lagi — sisa diagnostik tetap lengkap dan
        // pembuktian D1 tidak bergantung pada baris ini.
        //
        // Log.d(TAG, "[$tag/$phase] cf-mitigated=${res.headers["cf-mitigated"] ?: "-"} cf-ray=${res.headers["cf-ray"] ?: "-"}")
        // ---------------------------------------------------------------
    }

    /**
     * Tahap H-L, READ-ONLY.
     *
     * Membaca ulang dokumen HANYA untuk pelaporan. Tidak memanggil,
     * tidak memakai, dan tidak mengubah extractPlayerJsFile() maupun
     * normalizeFile()/buildMovieData()/buildEpisodes().
     * Semua akses JSON memakai opt... dan has(), yang bersifat read-only.
     */
    private fun ccDiagLadder(tag: String, doc: org.jsoup.nodes.Document, fileArray: JSONArray?) {
        val scripts = doc.select("script:containsData(atob)")
        Log.d(TAG, "[$tag/LADDER] scriptsWithAtob=${scripts.size}")

        var blocksWithPlayerjs = 0
        scripts.forEachIndexed { i, el ->
            val data = el.data()
            val matches = Regex("""atob\(["']([^"']+)["']\)""").findAll(data).toList()
            val atobOccurrences = data.split("atob(").size - 1
            Log.d(
                TAG,
                "[$tag/LADDER] script[$i] dataLen=${data.length}" +
                    " atobOccurrences=$atobOccurrences regexHits=${matches.size}"
            )
            matches.forEachIndexed { j, m ->
                val b64 = m.groupValues[1]
                val dec = try {
                    base64Decode(b64)
                } catch (e: Exception) {
                    Log.d(TAG, "[$tag/LADDER] script[$i].atob[$j] DECODE_FAIL ${e.message}")
                    ""
                }
                val hasPj = dec.contains("new Playerjs(")
                var fileValLen = -1
                if (hasPj) {
                    blocksWithPlayerjs++
                    val fm = Regex("""file\s*:\s*'""").find(dec)
                    if (fm != null) {
                        val end = dec.indexOf('\'', fm.range.last + 1)
                        fileValLen = if (end < 0) -2 else end - (fm.range.last + 1)
                    }
                }
                Log.d(
                    TAG,
                    "[$tag/LADDER] script[$i].atob[$j] b64Len=${b64.length}" +
                        " decLen=${dec.length} newPlayerjs=$hasPj fileValLen=$fileValLen"
                )
            }
        }
        Log.d(TAG, "[$tag/LADDER] blocksWithPlayerjs=$blocksWithPlayerjs")

        if (fileArray == null) {
            Log.d(TAG, "[$tag/LADDER] fileArray=NULL")
            return
        }
        val first = fileArray.optJSONObject(0)
        Log.d(
            TAG,
            "[$tag/LADDER] fileArray.len=${fileArray.length()}" +
                " hasFolder=${first?.has("folder")}" +
                " item0.titleLen=${first?.optString("title")?.length ?: -1}" +
                " item0.fileLen=${first?.optString("file")?.length ?: -1}" +
                " item0.subLen=${first?.optString("subtitle")?.length ?: -1}"
        )
        val folder = first?.optJSONArray("folder")
        if (folder != null) {
            Log.d(
                TAG,
                "[$tag/LADDER] season0.episodes=${folder.length()}" +
                    " ep0.fileLen=${folder.optJSONObject(0)?.optString("file")?.length ?: -1}"
            )
        }
    }

    private fun isCloudflareBlocked(code: Int, text: String): Boolean {
        val lower = text.lowercase()
        return cfMarkers.any { lower.contains(it) }
    }

    private fun siteCookieHeader(): Map<String, String> = mapOf("Cookie" to buildCookieValue())

    private fun buildCookieValue(): String {
        val cf = CinemacityPlugin.cfCookies
        return if (cf.isEmpty()) loginCookie
        else if (loginCookie.isEmpty()) cf
        else "$loginCookie; $cf"
    }

    // ---------------------------------------------------------------
    // KATEGORI — diambil dari menu asli situs (home.txt):
    //   /movies/  /tv-series/  dan  /genre/<slug>/
    // Paginasi situs: "<url>page/N/"
    // ---------------------------------------------------------------

    // CATATAN PENTING:
    // CloudStream memuat SELURUH entri mainPage saat layar utama dibuka.
    // Daftar yang panjang berarti puluhan request serentak ke situs yang
    // dilindungi Cloudflare -> tantangan CF ikut terpicu dan gagal.
    // Karena itu daftar ini sengaja DIPENDEKKAN. Genre lain tetap bisa
    // diakses lewat pencarian.
    override val mainPage = mainPageOf(
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/tv-series/" to "TV Series",
        "$mainUrl/genre/anime/" to "Anime",
        "$mainUrl/genre/asian/" to "Asian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = appGet(url, siteCookieHeader(), "MAINPAGE").document

        val rawNodes = doc.select("div.dar-short_item")          // DIAGNOSTIC (read-only)
        val items = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        Log.d(
            TAG,
            "[MAINPAGE] name=${request.name} page=$page rawItems=${rawNodes.size} parsed=${items.size}"
        )   // DIAGNOSTIC
        return newHomePageResponse(request.name, items)
    }

    /**
     * Struktur nyata satu item (home.txt / pencarian.txt):
     *
     *   <div class="dar-short_item swiper-slide">
     *     <div class="dar-short_bg e-cover">
     *       <a href="POSTER_PENUH.webp" data-highslide="single">    <- BUKAN link detail
     *         <img class="xfieldimage poster" src="/uploads/.../thumbs/....webp">
     *       </a>
     *       <div><span>CAM-Rip</span></div>       film
     *       <div><span>S2 • E8</span></div>       serial
     *     </div>
     *     <a href=".../movies/2794-....html" class="e-nowrap">The Wrong Girls (2026)</a>
     *     <div class="dar-short_meta"><span>Comedy</span> • <span>2026</span> • <span>1h 39m</span></div>
     *   </div>
     *
     * Serial memakai meta pertama "<span>2 Seasons</span>".
     */
    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.e-nowrap")
            ?: this.select("a:not([data-highslide])").firstOrNull { it.attr("href").contains(".html") }
            ?: return null

        val href = linkElement.attr("href")
        if (href.isBlank()) return null

        val rawTitle = linkElement.text().trim()
        if (rawTitle.isBlank()) return null
        val title = rawTitle.replace(titleYearRegex, "").trim()
        val year = yearRegex.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val poster = this.selectFirst("img.poster")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")

        val cfHeaders = CinemacityPlugin.getCfHeaders()

        // badge: kualitas untuk film, "S2 • E8" untuk serial
        val badge = this.selectFirst("div.dar-short_bg div span")?.text()?.trim()

        val isSeries = href.contains("/tv-series/") ||
            this.select("div.dar-short_meta span").any {
                it.text().contains("Season", ignoreCase = true)
            }

        return if (isSeries) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.posterHeaders = cfHeaders
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.posterHeaders = cfHeaders
                this.year = year
                this.quality = getQualityFromString(badge)
            }
        }
    }

    // ---------------------------------------------------------------
    // SEARCH — halaman hasil memakai struktur item yang SAMA
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=${query.trim()}"
        val doc = appGet(searchUrl, siteCookieHeader(), "SEARCH").document
        val rawNodes = doc.select("div.dar-short_item")           // DIAGNOSTIC (read-only)
        Log.d(TAG, "[SEARCH] query=$query rawItems=${rawNodes.size}")   // DIAGNOSTIC
        return doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
    }

    // ---------------------------------------------------------------
    // LOAD
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val detailTag = if (url.contains("/tv-series/")) "DETAIL-SERIES" else "DETAIL-MOVIE"   // DIAGNOSTIC
        val res = appGet(url, siteCookieHeader(), detailTag)
        val doc = res.document

        // <h1>Obsession (2025) </h1> lebih bersih daripada og:title
        val rawTitle = doc.selectFirst("div.dar-full_center h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" » CinemaCity")?.trim()
            ?: ""
        val title = rawTitle.replace(titleYearRegex, "").trim()
        val year = yearRegex.find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        // data-vbg berisi URL YOUTUBE (trailer), BUKAN gambar.
        // Latar yang benar: img.background di dalam div.dar-full_bg
        val background = doc.selectFirst("div.dar-full_bg img.background")?.attr("src")
            ?: doc.selectFirst("img.background")?.attr("src")
            ?: poster

        val trailer = doc.selectFirst("div.dar-full_bg [data-vbg]")?.attr("data-vbg")
            ?.takeIf { it.contains("youtube", ignoreCase = true) }

        val plot = doc.select("#about div.ta-full_text1").text().trim().takeIf { it.isNotBlank() }
        val tagline = doc.selectFirst("div.dar-full_subtitle")?.text()?.trim()

        // <div class="dar-full_meta"><span><a>Horror</a></span> • <span><a>WEB-DL</a></span>
        //   • <span><a>R</a></span> • <span>1h 48m</span></div>
        val tags = doc.select("div.dar-full_meta span a[href*=/genre/]").map { it.text().trim() }
        val duration = doc.select("div.dar-full_meta span")
            .map { it.text().trim() }
            .firstOrNull { it.matches(Regex("""\d+\s*h(\s*\d+\s*m)?|\d+\s*m""")) }
            ?.let { parseDuration(it) }

        // <div class="ta-full_rating-source" onclick="...imdb.com/title/tt37287335...">
        val imdbId = doc.select("div.ta-full_rating1 > div").firstOrNull()
            ?.attr("onclick")?.let { imdbRegex.find(it)?.value }
        val rating = doc.selectFirst("div.ta-full_rating1 strong.ta-full_rating-value")
            ?.text()?.trim()

        // #persons -> <li><span>Stars</span><span><a>Nama</a>, ...</span></li>
        val actors = doc.select("#persons li").firstOrNull {
            it.selectFirst("span")?.text()?.contains("Stars", ignoreCase = true) == true
        }?.select("a")?.mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } } ?: emptyList()

        val tvType = if (url.contains("/tv-series/")) TvType.TvSeries else TvType.Movie

        // ---------- PlayerJS ----------
        val fileArray = extractPlayerJsFile(doc)
        // DIAGNOSTIC: dinonaktifkan sementara — pada serial baris ini mencetak
        // ratusan KB ke logcat dan memotong (truncate) log diagnostik lain.
        // if (fileArray != null) Log.d(TAG, fileArray.toString())
        ccDiagLadder(detailTag, doc, fileArray)   // DIAGNOSTIC

        // Ekstraksi movie dihitung LEBIH DULU, tanpa dipagari TvType (koreksi A.3)
        val movieData = fileArray?.let { buildMovieData(it) }
        // DIAGNOSTIC: memakai hasil pipeline yang SUDAH dihitung di atas,
        // buildMovieData TIDAK dipanggil ulang.
        Log.d(
            TAG,
            "[$detailTag] tvType=$tvType titleLen=${title.length} movieDataNull=${movieData == null} episodesWillBuild=${tvType == TvType.TvSeries}"
        )

        val cfHeaders = CinemacityPlugin.getCfHeaders()
        val fullPlot = listOfNotNull(tagline, plot).joinToString("\n\n").takeIf { it.isNotBlank() }

        return if (tvType != TvType.TvSeries) {
            val data = movieData
                ?: doc.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?.let { JSONObject().put("streamUrl", it).toString() }
                ?: throw ErrorLoadingException("PlayerJS/IFrame not found; only torrent links available")

            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.backgroundPosterUrl = background?.let { fixUrl(it) }
                this.plot = fullPlot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = Score.from(rating, 10)
                this.posterHeaders = cfHeaders
                addImdbId(imdbId)
                addActors(actors)
                if (trailer != null) addTrailer(trailer)
            }
        } else {
            val episodes = fileArray?.let { buildEpisodes(it) } ?: emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.backgroundPosterUrl = background?.let { fixUrl(it) }
                this.plot = fullPlot
                this.year = year
                this.tags = tags
                this.duration = duration
                this.score = Score.from(rating, 10)
                this.posterHeaders = cfHeaders
                addImdbId(imdbId)
                addActors(actors)
                if (trailer != null) addTrailer(trailer)
            }
        }
    }

    private fun parseDuration(text: String): Int? {
        val m = durationRegex.find(text) ?: return null
        val h = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val min = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return (h * 60 + min).takeIf { it > 0 }
    }

    /**
     * Bentuk nyata di halaman detail (terverifikasi dari detailmovie/detailseries):
     *
     *   <script>...atob("BASE64")...</script>   <- blok atob KEDUA (indeks 1)
     *   hasil decode:
     *     window.playerjs_1 = new Playerjs({id: mountId, ready:"PlayerjsReady",
     *          file:'[{"title":"WEB-DL","file":"https://...","subtitle":"..."}]',
     *          poster:"...", ...});
     *
     * `file` adalah STRING JS berkutip tunggal berisi JSON. JSONObject Android
     * bersifat lenient (kunci tanpa kutip, string berkutip tunggal, sisa teks
     * setelah objek diabaikan), sehingga aman mem-parsing potongan setelah
     * "new Playerjs(".
     *
     * Pendekatan regex `\[\{.*?\}]` TIDAK dipakai: pada serial ia berhenti di
     * `}]` pertama (penutup folder) dan menghasilkan JSON rusak. Diuji pada
     * data nyata — regex: 16 KB gagal parse; cara ini: 238 KB valid,
     * 2 musim, 8 episode.
     */
    private fun extractPlayerJsFile(doc: org.jsoup.nodes.Document): JSONArray? {
        val scripts = doc.select("script:containsData(atob)")

        // urutan asli plugin: blok atob indeks 1 lebih dulu
        val ordered = ArrayList<org.jsoup.nodes.Element>()
        scripts.getOrNull(1)?.let { ordered.add(it) }
        scripts.forEachIndexed { i, el -> if (i != 1) ordered.add(el) }

        for (script in ordered) {
            val b64 = Regex("""atob\(["']([^"']+)["']\)""").find(script.data())
                ?.groupValues?.getOrNull(1) ?: continue

            val decoded = try {
                base64Decode(b64)
            } catch (e: Exception) {
                continue
            }
            if (!decoded.contains("new Playerjs(")) continue

            val payload = decoded.substringAfter("new Playerjs(")

            val fileValue: Any = try {
                JSONObject(payload).opt("file")
            } catch (e: Exception) {
                null
            } ?: Regex("""file\s*:\s*'([^']*)'""").find(payload)
                ?.groupValues?.getOrNull(1)
            ?: continue

            return try {
                normalizeFile(fileValue)
            } catch (e: Exception) {
                Log.d(TAG, "normalizeFile gagal: ${e.message}")
                null
            }
        }
        return null
    }

    private fun normalizeFile(fileValue: Any): JSONArray {
        if (fileValue is JSONArray) return fileValue
        if (fileValue is String) {
            val s = fileValue.trim()
            if (s.isBlank()) throw ErrorLoadingException("PlayerJS: empty file string")
            if (s.startsWith("[") && s.endsWith("]")) return JSONArray(s)
            if (s.startsWith("{") && s.endsWith("}")) return JSONArray().put(JSONObject(s))
            return JSONArray().put(JSONObject().put("file", s))
        }
        throw ErrorLoadingException("PlayerJS: unsupported file type")
    }

    /**
     * MOVIE — file[0] TANPA kunci "folder".
     * Nyata: {"title":"WEB-DL","file":"https://...master.m3u8","subtitle":"[Lang]url,..."}
     */
    private fun buildMovieData(arr: JSONArray): String? {
        val first = arr.optJSONObject(0) ?: return null
        if (first.has("folder")) return null
        val streamUrl = first.optString("file").takeIf { it.isNotBlank() } ?: return null
        return JSONObject()
            .put("streamUrl", streamUrl)
            .put("subtitleTracks", parseSubtitles(first.optString("subtitle")))
            .toString()
    }

    /**
     * SERIES — file[] = Season { title:"Season 1", folder:[ Episode ] }
     * Episode = { title:"Episode 1", file:"https://...", subtitle:"...",
     *             original_language, id, vars{cc_season, cc_episode, ...} }
     *
     * `vars` SENGAJA tidak dipakai; penomoran tetap dari regex judul.
     */
    private fun buildEpisodes(arr: JSONArray): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            val season = arr.optJSONObject(i) ?: continue
            val seasonNo = seasonRegex.find(season.optString("title"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (i + 1)
            val folder = season.optJSONArray("folder") ?: continue

            for (j in 0 until folder.length()) {
                val ep = folder.optJSONObject(j) ?: continue
                val epTitle = ep.optString("title")
                val epNo = episodeRegex.find(epTitle)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (j + 1)

                val urls = mutableListOf<String>()
                ep.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                ep.optJSONArray("folder")?.let { nested ->
                    for (k in 0 until nested.length()) {
                        val src = nested.optJSONObject(k) ?: continue
                        src.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    }
                }
                if (urls.isEmpty()) continue

                val data = JSONObject()
                    .put("streams", JSONArray(urls))
                    .put("subtitleTracks", parseSubtitles(ep.optString("subtitle")))
                    .toString()

                episodes.add(
                    newEpisode(data) {
                        this.name = epTitle.takeIf { it.isNotBlank() }
                        this.season = seasonNo
                        this.episode = epNo
                    }
                )
            }
        }
        return episodes
    }

    /** "[English (Full)]https://...vtt,[English (SDH)]https://...vtt" */
    private fun parseSubtitles(source: String?): JSONArray {
        val out = JSONArray()
        if (source.isNullOrBlank()) return out
        source.split(",").forEach { part ->
            val m = subtitleRegex.find(part.trim()) ?: return@forEach
            val lang = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val subUrl = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (lang.isNotEmpty() && subUrl.isNotEmpty()) {
                out.put(JSONObject().put("language", lang).put("subtitleUrl", subUrl))
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    // LOADLINKS — tanpa network
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = try {
            JSONObject(data)
        } catch (e: Exception) {
            return false
        }

        obj.optJSONArray("subtitleTracks")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val lang = s.optString("language")
                val subUrl = s.optString("subtitleUrl")
                if (lang.isNotBlank() && subUrl.isNotBlank()) {
                    subtitleCallback(newSubtitleFile(lang, subUrl))
                }
            }
        }

        val urls = mutableListOf<String>()
        obj.optJSONArray("streams")?.let { streams ->
            for (i in 0 until streams.length()) {
                streams.optString(i).takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }
        }
        if (urls.isEmpty()) {
            obj.optString("streamUrl").takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        if (urls.isEmpty()) return false

        val linkHeaders = mapOf("Cookie" to buildCookieValue())

        // DIAGNOSTIC: host + panjang + tail saja, URL CDN tidak dicetak penuh.
        val diagSubCount = obj.optJSONArray("subtitleTracks")?.length() ?: 0
        Log.d(TAG, "[LINKS] urlCount=${urls.size} subCount=$diagSubCount")
        urls.forEachIndexed { i, u ->
            val diagHost = u.substringAfter("//", "").substringBefore("/")
            val diagTail = u.takeLast(60)
            Log.d(TAG, "[LINKS] url[$i] len=${u.length} host=$diagHost tail=$diagTail")
        }

        urls.forEach { streamUrl ->
            // Nyata: https://s1.cccdn.net/<hash>:<ts>/public_files/,<...>,.urlset/master.m3u8
            if (streamUrl.contains(".m3u8") ||
                streamUrl.contains(".mp4") ||
                streamUrl.contains("/public_files/")
            ) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name • HLS • Master ",
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = linkHeaders
                    }
                )
            } else {
                loadExtractor(streamUrl, mainUrl, subtitleCallback, callback)
            }
        }
        Log.d(TAG, "[LINKS] selesai, callback dikirim untuk ${urls.size} url")   // DIAGNOSTIC
        return true
    }
}

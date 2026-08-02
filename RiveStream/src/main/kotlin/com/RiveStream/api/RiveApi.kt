package com.RiveStream.api

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  RiveStream — klien API
 * ============================================================================
 *
 *  Seluruh data RiveStream lewat SATU endpoint dispatcher:
 *
 *      GET https://www.rivestream.app/api/backendfetch
 *          ?requestID=<nama>&<parameter lain>&secretKey=<kunci>
 *
 *  Metadata berbentuk TMDB apa adanya (page/results/total_pages), sedangkan
 *  link stream dikembalikan sebagai JSON berisi `sources` dan `captions`.
 *
 *  ------------------------------------------------------------------------
 *  Fakta yang sudah diverifikasi (jangan diubah tanpa bukti baru)
 *  ------------------------------------------------------------------------
 *
 *  1. `secretKey` WAJIB dan diperiksa server.
 *     Sumbernya: TMDB id untuk permintaan ber-id, kata kunci untuk pencarian,
 *     dan literal "rive" untuk permintaan tanpa argumen.
 *     Kunci salah -> 403 "Invalid secret key". Kunci hilang -> 400.
 *
 *  2. Referer TIDAK wajib untuk /api/backendfetch. Lima kombinasi header
 *     diuji (tanpa Referer, tanpa User-Agent, Referer asing) — semuanya 200.
 *     Tetap dikirim karena tidak merugikan dan menyamai perilaku situs.
 *
 *  3. Referer WAJIB untuk URL proxy FlowCast (proxy.valhallastream.dpdns.org).
 *     Tanpa Referer -> 403. Dengan Referer rivestream.app -> 200.
 *     Referer 123movienow.cc justru DITOLAK, walaupun nilai itu tertulis di
 *     parameter `headers` milik URL proxy — parameter tersebut untuk diteruskan
 *     ke hulu, bukan untuk kita kirim.
 *
 *  4. `tvEpisodes` mengembalikan SATU MUSIM per permintaan.
 *     Tanpa parameter `season`, defaultnya musim 0 (Specials).
 *     Parameter `language` tidak berpengaruh pada isinya.
 *
 *  5. Enam layanan stream sering gagal sendiri-sendiri: `{"data":null}` atau
 *     `{"error":"Internal Server Error"}`. Ini normal. Panggil semuanya dan
 *     abaikan yang gagal, jangan berhenti di kegagalan pertama.
 *
 *  6. Server memutus koneksi bila permintaan terlalu rapat
 *     (RemoteDisconnected / Connection reset). Jeda minimum diberlakukan
 *     lewat [rateLimit].
 *
 *  7. `movieOnlineSubtitles` dan `tvOnlineSubtitles` SELALU balas 500.
 *     Subtitle diambil dari field `captions` di respons stream.
 * ============================================================================
 */
object RiveApi {

    const val MAIN_URL = "https://www.rivestream.app"
    private const val ENDPOINT = "$MAIN_URL/api/backendfetch"

    /** Referer untuk permintaan API maupun untuk URL stream FlowCast. */
    const val REFERER = "$MAIN_URL/"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Basis gambar TMDB. Poster dan backdrop hanya berisi path relatif. */
    const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
    const val IMG_BACKDROP = "https://image.tmdb.org/t/p/original"

    /**
     * Daftar layanan cadangan, dipakai bila `VideoProviderServices` gagal.
     * Nilai ini terpantau dari server pada 2026-08-02.
     */
    val FALLBACK_SERVICES = listOf(
        "primevids", "flowcast", "asiacloud", "hindicast", "guru", "ophim"
    )

    // --------------------------------------------------------------- limiter

    private val rateMutex = Mutex()
    private var lastRequestAt = 0L

    /** Jeda minimum antar permintaan, dalam milidetik. */
    private const val MIN_INTERVAL_MS = 350L

    private suspend fun rateLimit() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------ inti fetch

    private fun buildUrl(requestID: String, params: Map<String, String?>, key: String): String {
        val sb = StringBuilder(ENDPOINT).append("?requestID=").append(enc(requestID))
        for ((k, v) in params) {
            if (v.isNullOrBlank()) continue
            sb.append('&').append(enc(k)).append('=').append(enc(v))
        }
        sb.append("&secretKey=").append(enc(key))
        return sb.toString()
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Panggil dispatcher dan kembalikan teks respons mentah.
     *
     * @param keySource nilai sumber `secretKey`: TMDB id, kata kunci, atau
     *                  `null` untuk memakai literal "rive".
     * @return teks respons, atau null bila gagal.
     */
    suspend fun raw(
        requestID: String,
        params: Map<String, String?> = emptyMap(),
        keySource: String? = null
    ): String? {
        val key = SecretKey.of(keySource)
        val url = buildUrl(requestID, params, key)
        return try {
            rateLimit()
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to REFERER,
                    "Accept" to "application/json, text/plain, */*"
                )
            )
            val body = res.text
            // Server membalas halaman HTML saat galat internal (status 500).
            if (body.isBlank() || !(body.startsWith("{") || body.startsWith("["))) null
            else body
        } catch (e: Exception) {
            null
        }
    }

    /** Seperti [raw], tapi langsung diurai menjadi [JSONObject]. */
    suspend fun obj(
        requestID: String,
        params: Map<String, String?> = emptyMap(),
        keySource: String? = null
    ): JSONObject? {
        val text = raw(requestID, params, keySource) ?: return null
        return try {
            if (text.startsWith("[")) JSONObject().put("data", JSONArray(text))
            else JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Ambil array `results` dari respons berbentuk daftar TMDB.
     * Berlaku untuk trending, popular, latest, topRated, filter, dan search.
     */
    suspend fun results(
        requestID: String,
        params: Map<String, String?> = emptyMap(),
        keySource: String? = null
    ): JSONArray? = obj(requestID, params, keySource)?.optJSONArray("results")

    // ------------------------------------------------------------- katalog

    /**
     * Daftar katalog. Semuanya berbentuk TMDB: page / results / total_pages.
     * Tidak memerlukan id, jadi `secretKey` memakai literal "rive".
     */
    suspend fun catalog(
        requestID: String,
        page: Int = 1,
        language: String = "en-US",
        sortBy: String? = null
    ): JSONArray? = results(
        requestID,
        mapOf(
            "language" to language,
            "page" to page.toString(),
            "sortBy" to sortBy
        )
    )

    /** Nama requestID katalog yang sudah terverifikasi mengembalikan `results`. */
    object Catalog {
        const val TRENDING = "trending"
        const val TRENDING_MOVIE = "trendingMovie"
        const val TRENDING_TV = "trendingTv"
        const val TRENDING_MOVIE_DAY = "trendingMovieDay"
        const val TRENDING_TV_DAY = "trendingTvDay"
        const val LATEST_MOVIE = "latestMovie"
        const val LATEST_TV = "latestTv"
        const val POPULAR_MOVIE = "popularMovie"
        const val POPULAR_TV = "popularTv"
        const val TOP_RATED_MOVIE = "topRatedMovie"
        const val TOP_RATED_TV = "topRatedTv"
        const val ON_THE_AIR_TV = "onTheAirTv"
    }

    // ------------------------------------------------------------ pencarian

    /**
     * Pencarian gabungan film dan serial.
     *
     * `secretKey` di sini memakai CABANG TEKS: indeksnya dihitung dari jumlah
     * kode karakter kata kunci, bukan dari angka. Sudah diverifikasi — kunci
     * dari id maupun literal "rive" sama-sama ditolak 403.
     *
     * Tiap item punya `media_type` bernilai "movie" atau "tv".
     */
    suspend fun searchMulti(
        query: String,
        page: Int = 1,
        language: String = "en-US"
    ): JSONArray? = results(
        "searchMulti",
        mapOf("query" to query, "language" to language, "page" to page.toString()),
        keySource = query
    )

    // --------------------------------------------------------------- detail

    /** Objek TMDB lengkap sebuah film. */
    suspend fun movieData(id: String, language: String = "en-US"): JSONObject? =
        obj("movieData", mapOf("id" to id, "language" to language), keySource = id)

    /** Objek TMDB lengkap sebuah serial, termasuk array `seasons`. */
    suspend fun tvData(id: String, language: String = "en-US"): JSONObject? =
        obj("tvData", mapOf("id" to id, "language" to language), keySource = id)

    /**
     * Daftar episode SATU musim.
     *
     * Panggil sekali per musim. Nomor musim diambil dari `tvData().seasons[]`.
     * Tanpa parameter `season`, server mengembalikan musim 0 (Specials).
     *
     * @return objek musim, dengan array `episodes` di dalamnya.
     */
    suspend fun tvEpisodes(id: String, season: Int): JSONObject? =
        obj("tvEpisodes", mapOf("id" to id, "season" to season.toString()), keySource = id)

    /** Detail satu episode, termasuk `crew` dan `guest_stars`. */
    suspend fun tvEpisodeDetail(id: String, season: Int, episode: Int): JSONObject? =
        obj(
            "tvEpisodeDetail",
            mapOf("id" to id, "season" to season.toString(), "episode" to episode.toString()),
            keySource = id
        )

    // --------------------------------------------------------------- stream

    /**
     * Daftar layanan stream yang tersedia.
     * Dipanggil tanpa argumen, sehingga `secretKey` memakai literal "rive".
     */
    suspend fun videoProviderServices(): List<String> {
        val arr = obj("VideoProviderServices")?.optJSONArray("data")
            ?: return FALLBACK_SERVICES
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out.ifEmpty { FALLBACK_SERVICES }
    }

    /**
     * Link stream sebuah film dari satu layanan.
     *
     * @return objek `data` berisi `sources` dan `captions`, atau null bila
     *         layanan itu tidak punya sumber untuk judul ini. Kegagalan satu
     *         layanan adalah hal biasa dan bukan galat.
     */
    suspend fun movieVideoProvider(id: String, service: String): JSONObject? =
        obj(
            "movieVideoProvider",
            mapOf("id" to id, "service" to service),
            keySource = id
        )?.optJSONObject("data")

    /** Link stream satu episode serial dari satu layanan. */
    suspend fun tvVideoProvider(
        id: String,
        service: String,
        season: Int,
        episode: Int
    ): JSONObject? = obj(
        "tvVideoProvider",
        mapOf(
            "id" to id,
            "service" to service,
            "season" to season.toString(),
            "episode" to episode.toString()
        ),
        keySource = id
    )?.optJSONObject("data")

    // ------------------------------------------------------------- pembantu

    /** Ubah path poster TMDB menjadi URL penuh. */
    fun posterUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG_POSTER + it }

    /** Ubah path backdrop TMDB menjadi URL penuh. */
    fun backdropUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG_BACKDROP + it }
}

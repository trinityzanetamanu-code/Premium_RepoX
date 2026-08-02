package com.RiveStream

import com.RiveStream.api.RiveApi
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  RiveStream — provider CloudStream
 * ============================================================================
 *
 *  TAHAP 4: mainPage(), search(), dan load().
 *  loadLinks() BELUM ditulis. Memutar sebuah judul masih akan gagal sampai
 *  Tahap 5 selesai — itu perilaku yang diharapkan pada tahap ini.
 *
 *  Bagian mainPage() dan search() TIDAK diubah dari Tahap 3 yang sudah
 *  terverifikasi di perangkat.
 *
 *  ------------------------------------------------------------------------
 *  Dasar [TERBUKTI] untuk load()
 *  ------------------------------------------------------------------------
 *
 *  1. `movieData` dan `tvData` mengembalikan objek TMDB apa adanya.
 *
 *  2. `tvEpisodes(id, season)` mengembalikan SATU musim per permintaan,
 *     berisi array `episodes`. Tanpa parameter `season`, server memberi
 *     musim 0 (Specials).
 *
 *  3. Daftar musim diambil dari `tvData.seasons[]`, BUKAN dari
 *     `number_of_seasons`. Breaking Bad: number_of_seasons = 5, tapi
 *     seasons[] berisi 6 entri karena musim 0 ikut terdaftar.
 *
 *  4. `vote_average` bertipe CAMPURAN: Int untuk film (8), Double untuk
 *     serial (8.949). Harus dibaca dengan optDouble, bukan optInt.
 *
 *  5. `episode_run_time` bisa berupa array KOSONG (Breaking Bad).
 *     Durasi serial karena itu boleh null.
 *
 *  6. Id IMDb berada di tempat berbeda:
 *         film   -> `imdb_id` di tingkat atas
 *         serial -> `external_ids.imdb_id`
 *
 *  7. Satuan berbeda antara dua model CloudStream:
 *         LoadResponse.duration -> MENIT
 *         Episode.runTime       -> DETIK
 *     TMDB memberi menit, jadi runtime episode dikalikan 60.
 *
 *  ------------------------------------------------------------------------
 *  Yang sengaja BELUM dikerjakan
 *  ------------------------------------------------------------------------
 *
 *  - `actors` (butuh requestID `movieCasts` / `tvCasts`) dan
 *    `recommendations` (butuh `movieSimilar` / `tvSimilar`). Keduanya
 *    menambah permintaan jaringan dan belum pernah diuji, jadi statusnya
 *    masih [ASUMSI]. Ditambahkan setelah Tahap 4 stabil.
 *
 *  - Musim 0 (Specials) IKUT ditampilkan karena datanya memang tersedia.
 *    Untuk menyembunyikannya, tambahkan syarat `seasonNumber > 0` pada
 *    perulangan di [loadTvEpisodes]. Satu baris, tanpa efek ke bagian lain.
 * ============================================================================
 */
class RiveStreamProvider : MainAPI() {

    override var mainUrl = RiveApi.MAIN_URL
    override var name = "RiveStream"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    /**
     * Kunci tiap bagian berformat `requestID:tipe`.
     *
     * Bagian `tipe` menentukan bagaimana item dipetakan, karena endpoint
     * katalog per-tipe tidak selalu menyertakan `media_type`:
     *   movie  -> semua item dianggap film
     *   tv     -> semua item dianggap serial
     *   multi  -> pakai `media_type` milik tiap item
     */
    override val mainPage = mainPageOf(
        "trending:multi" to "Trending",
        "trendingMovie:movie" to "Trending Movies",
        "trendingTv:tv" to "Trending TV Shows",
        "latestMovie:movie" to "Latest Movies",
        "latestTv:tv" to "Latest TV Shows",
        "popularMovie:movie" to "Popular Movies",
        "popularTv:tv" to "Popular TV Shows",
        "topRatedMovie:movie" to "Top Rated Movies",
        "topRatedTv:tv" to "Top Rated TV Shows",
        "onTheAirTv:tv" to "On The Air"
    )

    // ------------------------------------------------------------ main page

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val parts = request.data.split(":")
        val requestID = parts.getOrElse(0) { "trending" }
        val typeHint = parts.getOrElse(1) { "multi" }

        val results = RiveApi.catalog(requestID, page)
        val items = results.toSearchResponses(typeHint)

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // -------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        // searchMulti memakai secretKey cabang TEKS. Ditangani RiveApi.
        return RiveApi.searchMulti(query).toSearchResponses("multi")
    }

    // ---------------------------------------------------------------- load

    /**
     * Muat detail sebuah judul.
     *
     * URL yang masuk berbentuk `.../watch?type=movie|tv&id=<tmdbId>`,
     * sama seperti yang dihasilkan [buildUrl].
     */
    override suspend fun load(url: String): LoadResponse? {
        val type = url.queryParam("type") ?: return null
        val id = url.queryParam("id")?.takeIf { it.isNotBlank() } ?: return null

        return if (type.equals("tv", true)) loadTv(id, url) else loadMovie(id, url)
    }

    // ----------------------------------------------------------- load film

    private suspend fun loadMovie(id: String, url: String): LoadResponse? {
        val data = RiveApi.movieData(id) ?: return null

        val title = data.optStringOrNull("title")
            ?: data.optStringOrNull("original_title")
            ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) {
            this.posterUrl = RiveApi.posterUrl(data.optStringOrNull("poster_path"))
            this.backgroundPosterUrl = RiveApi.backdropUrl(data.optStringOrNull("backdrop_path"))
            this.plot = data.optStringOrNull("overview")
            this.year = data.optStringOrNull("release_date")?.take(4)?.toIntOrNull()
            this.tags = data.genreNames()
            // TMDB memberi runtime dalam menit; LoadResponse.duration juga menit.
            this.duration = data.optInt("runtime", 0).takeIf { it > 0 }

            data.voteAverage()?.let { addScore(it, 10) }
            addImdbId(data.optStringOrNull("imdb_id"))
        }
    }

    // --------------------------------------------------------- load serial

    private suspend fun loadTv(id: String, url: String): LoadResponse? {
        val data = RiveApi.tvData(id) ?: return null

        val title = data.optStringOrNull("name")
            ?: data.optStringOrNull("original_name")
            ?: return null

        val episodes = loadTvEpisodes(id, data)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = RiveApi.posterUrl(data.optStringOrNull("poster_path"))
            this.backgroundPosterUrl = RiveApi.backdropUrl(data.optStringOrNull("backdrop_path"))
            this.plot = data.optStringOrNull("overview")
            this.year = data.optStringOrNull("first_air_date")?.take(4)?.toIntOrNull()
            this.tags = data.genreNames()

            // episode_run_time kadang berupa array kosong, mis. Breaking Bad.
            this.duration = data.optJSONArray("episode_run_time")
                ?.takeIf { it.length() > 0 }
                ?.optInt(0, 0)
                ?.takeIf { it > 0 }

            this.showStatus = when {
                data.optBoolean("in_production", false) -> ShowStatus.Ongoing
                data.optStringOrNull("status")?.equals("Ended", true) == true ->
                    ShowStatus.Completed
                data.optStringOrNull("status")?.equals("Canceled", true) == true ->
                    ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }

            data.voteAverage()?.let { addScore(it, 10) }
            // Untuk serial, id IMDb berada di dalam external_ids.
            addImdbId(data.optJSONObject("external_ids")?.optStringOrNull("imdb_id"))
        }
    }

    /**
     * Ambil seluruh episode sebuah serial.
     *
     * Satu permintaan per musim, sesuai perilaku `tvEpisodes` yang sudah
     * diverifikasi. Daftar musim diambil dari `seasons[]`, bukan dari
     * `number_of_seasons`, karena musim 0 tidak terhitung di angka itu.
     *
     * Permintaan dijalankan berurutan. RiveApi sudah memberi jeda minimum
     * antar permintaan, sehingga menjalankannya paralel tidak mempercepat
     * apa pun dan justru memperbesar risiko koneksi diputus server.
     */
    private suspend fun loadTvEpisodes(id: String, data: JSONObject): List<Episode> {
        val seasons = data.optJSONArray("seasons") ?: return emptyList()
        val out = ArrayList<Episode>()

        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNumber = season.optInt("season_number", -1)
            if (seasonNumber < 0) continue

            val detail = RiveApi.tvEpisodes(id, seasonNumber) ?: continue
            val list = detail.optJSONArray("episodes") ?: continue

            for (j in 0 until list.length()) {
                val ep = list.optJSONObject(j) ?: continue
                val episodeNumber = ep.optInt("episode_number", -1)
                if (episodeNumber < 0) continue

                // Nomor musim diambil dari episodenya sendiri bila tersedia,
                // agar tetap benar kalau server mengembalikan musim lain.
                val epSeason = ep.optInt("season_number", seasonNumber)

                out.add(
                    newEpisode(episodeUrl(id, epSeason, episodeNumber)) {
                        this.name = ep.optStringOrNull("name")
                        this.season = epSeason
                        this.episode = episodeNumber
                        this.description = ep.optStringOrNull("overview")
                        this.posterUrl = RiveApi.posterUrl(ep.optStringOrNull("still_path"))
                        // TMDB memberi menit, Episode.runTime dalam DETIK.
                        this.runTime = ep.optInt("runtime", 0)
                            .takeIf { it > 0 }
                            ?.let { it * 60 }
                        addDate(ep.optStringOrNull("air_date"))
                    }
                )
            }
        }

        out.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return out
    }

    // ------------------------------------------------------------ pemetaan

    /** Ubah array hasil TMDB menjadi daftar SearchResponse. */
    private fun JSONArray?.toSearchResponses(typeHint: String): List<SearchResponse> {
        if (this == null) return emptyList()
        val out = ArrayList<SearchResponse>(this.length())
        for (i in 0 until this.length()) {
            val item = this.optJSONObject(i) ?: continue
            toSearchResponse(item, typeHint)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Ubah satu item TMDB menjadi SearchResponse.
     *
     * Mengembalikan null bila item bukan tontonan (mis. `person`), tidak punya
     * id, atau tidak punya judul.
     */
    private fun toSearchResponse(item: JSONObject, typeHint: String): SearchResponse? {
        val id = item.optInt("id", 0).takeIf { it > 0 } ?: return null

        val mediaType = item.optString("media_type", "").ifBlank { null }
        // Item person muncul di searchMulti dan harus dibuang.
        if (mediaType == "person") return null

        val isTv = when {
            mediaType == "tv" -> true
            mediaType == "movie" -> false
            typeHint == "tv" -> true
            typeHint == "movie" -> false
            // Tanpa petunjuk apa pun: serial memakai `name`, film memakai `title`.
            else -> item.has("name") && !item.has("title")
        }

        val title = (if (isTv) {
            item.optStringOrNull("name") ?: item.optStringOrNull("original_name")
        } else {
            item.optStringOrNull("title") ?: item.optStringOrNull("original_title")
        }) ?: return null

        // Sembunyikan konten dewasa. TMDB menandainya lewat `adult`.
        if (item.optBoolean("adult", false)) return null

        val poster = RiveApi.posterUrl(item.optStringOrNull("poster_path"))
        val year = (if (isTv) item.optStringOrNull("first_air_date")
        else item.optStringOrNull("release_date"))
            ?.take(4)?.toIntOrNull()

        val url = buildUrl(if (isTv) "tv" else "movie", id)

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    /**
     * URL internal sebuah judul.
     *
     * Memakai format halaman tonton milik situs, sehingga URL ini juga sah
     * dibuka di peramban dan mudah diurai kembali di load() nanti.
     */
    private fun buildUrl(type: String, id: Int): String =
        "$mainUrl/watch?type=$type&id=$id"

    /**
     * URL data satu episode. Musim dan episode ikut dibawa di sini supaya
     * loadLinks() pada Tahap 5 tidak perlu meminta ulang detail serial.
     */
    private fun episodeUrl(id: String, season: Int, episode: Int): String =
        "$mainUrl/watch?type=tv&id=$id&season=$season&episode=$episode"

    // ------------------------------------------------------------ pembantu

    /** optString yang mengembalikan null, bukan string "null" atau kosong. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        this.optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    /** Daftar nama genre dari array `genres`. */
    private fun JSONObject.genreNames(): List<String>? {
        val arr = this.optJSONArray("genres") ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it) }
        }
        return out.ifEmpty { null }
    }

    /**
     * Nilai `vote_average` sebagai teks.
     *
     * Tipenya campuran di server: Int untuk film (8), Double untuk serial
     * (8.949). optDouble menangani keduanya; optInt akan memotong pecahannya.
     */
    private fun JSONObject.voteAverage(): String? {
        val v = this.optDouble("vote_average", -1.0)
        return if (v > 0.0) v.toString() else null
    }

    /** Ambil satu parameter query dari URL. */
    private fun String.queryParam(key: String): String? {
        val q = this.substringAfter('?', "").substringBefore('#')
        if (q.isBlank()) return null
        for (pair in q.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) == key) {
                return java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
        }
        return null
    }
}

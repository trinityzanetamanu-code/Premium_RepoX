package com.RiveStream

import com.RiveStream.api.RiveApi
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  RiveStream — provider CloudStream
 * ============================================================================
 *
 *  TAHAP 3: hanya mainPage() dan search().
 *  load() dan loadLinks() sengaja BELUM ditulis. Menyentuh sebuah judul akan
 *  gagal sampai Tahap 4 selesai — itu perilaku yang diharapkan pada tahap ini.
 *
 *  ------------------------------------------------------------------------
 *  Dasar [TERBUKTI] yang dipakai di berkas ini
 *  ------------------------------------------------------------------------
 *
 *  1. Katalog dan pencarian mengembalikan bentuk TMDB apa adanya:
 *         { page, results[], total_pages, total_results }
 *
 *  2. Field item katalog film : id, title, original_title, poster_path,
 *     backdrop_path, overview, release_date, vote_average, genre_ids, adult
 *     Field item katalog seri : id, name, original_name, first_air_date, ...
 *
 *  3. `searchMulti` menyertakan `media_type` bernilai "movie", "tv", atau
 *     "person". Item person harus dibuang karena bukan tontonan.
 *
 *  4. Endpoint katalog TIDAK memakai id, jadi `secretKey` memakai literal
 *     "rive". Ini sudah ditangani di dalam RiveApi.
 *
 *  ------------------------------------------------------------------------
 *  Catatan [ASUMSI] yang masih berlaku di tahap ini
 *  ------------------------------------------------------------------------
 *
 *  - Endpoint katalog selain yang sudah diuji langsung (trendingMovie,
 *    trendingTv, latestMovie, latestTv, popularMovie, popularTv) diperkirakan
 *    berbentuk sama karena berasal dari daftar requestID yang sama di
 *    `fetchBackend.tsx`. Kalau salah satu kosong di perangkat, cukup hapus
 *    barisnya dari [mainPage] — tidak perlu mengubah kode lain.
 *
 *  - Nilai `total_pages` belum dipakai untuk membatasi halaman. Saat ini
 *    halaman berikutnya dianggap ada selama hasilnya tidak kosong.
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

    /** optString yang mengembalikan null, bukan string "null" atau kosong. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        this.optString(key, "").takeIf { it.isNotBlank() && it != "null" }
}

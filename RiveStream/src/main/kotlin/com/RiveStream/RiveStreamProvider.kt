package com.RiveStream

import com.RiveStream.api.RiveApi
import com.RiveStream.byse.ByseClient
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.app
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
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  RiveStream — provider CloudStream
 * ============================================================================
 *
 *  TAHAP 5: mainPage(), search(), load(), dan loadLinks(). Provider lengkap.
 *
 *  Bagian Tahap 3 dan 4 TIDAK diubah — keduanya sudah terverifikasi di
 *  perangkat.
 *
 *  ------------------------------------------------------------------------
 *  Dasar [TERBUKTI] untuk loadLinks()
 *  ------------------------------------------------------------------------
 *
 * 11. `VideoProviderServices` mengembalikan enam nama layanan sebagai array
 *     string di dalam `data`.
 *
 * 12. Respons tiap layanan punya EMPAT bentuk, dan tiga di antaranya berarti
 *     "tidak ada sumber":
 *         {"data":{sources,captions}}      -> berhasil
 *         {"data":null}                    -> layanan tidak punya judul ini
 *         {"error":"Internal Server Error"}-> layanan sedang bermasalah
 *         {"error":"Invalid provider"}     -> nama layanan tidak dikenal
 *     Ketiga bentuk gagal itu NORMAL dan harus diabaikan diam-diam, bukan
 *     menggagalkan layanan lain.
 *
 * 13. `sources[]` punya key: format, quality, size, source, url.
 *     `quality` bertipe campuran: angka (720, 480, 360) maupun teks
 *     ("tcloud", "ipcloud", "HLS").
 *
 * 14. `format` yang teramati hanya "hls" dan "mp4".
 *
 * 15. `captions[]` punya key `label` dan `file`. Label berbentuk
 *     "English - FlowCast", jadi nama bahasanya diambil sebelum " - ".
 *
 * 16. Proxy FlowCast MEWAJIBKAN Referer rivestream.app. Tanpa Referer 403.
 *     Referer 123movienow.cc — yang tertulis di dalam URL proxy — justru
 *     ditolak, karena nilai itu untuk diteruskan proxy ke hulu.
 *
 * 17. Proxy FlowCast mendukung Range: `accept-ranges: bytes` dan membalas 206
 *     dengan `content-range`. Seek bekerja, jadi FlowCast tidak diturunkan
 *     prioritasnya.
 *
 *  ------------------------------------------------------------------------
 *  Catatan urutan sumber
 *  ------------------------------------------------------------------------
 *
 *  CloudStream mengurutkan tautan menurun berdasarkan `quality`, dan
 *  `Qualities.Unknown` bernilai 400. Karena FlowCast memberi kualitas angka
 *  sedangkan PrimeVids dan ophim tidak, urutannya menjadi:
 *
 *      FlowCast 720 -> FlowCast 480 -> PrimeVids/ophim (400) -> FlowCast 360
 *
 *  FlowCast otomatis berada di atas tanpa memaksa apa pun, dan tidak ada
 *  layanan yang dibuang.
 *
 *  ------------------------------------------------------------------------
 *  [ASUMSI] yang tersisa
 *  ------------------------------------------------------------------------
 *
 *  - URL subtitle CloudFront (cacdn.hakunaymatata.com) dikirim TANPA header.
 *    Belum diuji apakah butuh Referer. Kalau subtitle gagal tampil, itu titik
 *    pertama yang harus diperiksa.
 *
 *  ------------------------------------------------------------------------
 *  PATCH STABILITAS PLAYBACK (berdasarkan logcat_2026_08_03_02_53.txt)
 *  ------------------------------------------------------------------------
 *
 *  Akar masalah [TERBUKTI] dari jejak ExoPlayer sungguhan, BUKAN dugaan:
 *
 *  18. HindiCast: 403 di position=0, dengan header Referer/User-Agent yang
 *      SUDAH benar terkirim (terlihat di baris `playerError:` logcat).
 *      Layanan hulu di balik proxy valhallastream.dpdns.org untuk HindiCast
 *      memang sedang mati sisi server RiveStream, bukan kesalahan header.
 *
 *  19. FlowCast: campuran 403/404 langsung di awal, plus SocketTimeout
 *      berulang. Proxy valhallastream.dpdns.org sendiri yang tidak stabil.
 *
 *  20. PrimeVids (HLS): master playlist BERHASIL diambil (kalau tidak,
 *      ExoPlayer tidak akan mencoba membuka turunannya), tapi salah satu
 *      SEGMEN turunan sudah 404. Jejak exception-nya melalui
 *      DataSourceInputStream membuka child manifest, bukan gagal di URL
 *      master. CDN cdn.1shows.app mengganti/menghapus segmen sebelum
 *      ExoPlayer sempat mengambilnya.
 *
 *  21. Ophim ("Vietsub"): gagal di position=266400 (~4 menit berjalan),
 *      persis pola "gagal saat maju-mundur". Ini juga kedaluwarsa token
 *      segmen HLS di CDN phim1280.tv, bukan header yang salah.
 *
 *  KESIMPULAN: tidak satu pun kegagalan ini disebabkan Referer, Origin,
 *  atau User-Agent yang salah dari loadLinks(). Logcat membuktikan header
 *  yang dikirim sudah sama persis dengan yang dirancang di Tahap 5. Ini
 *  ketidakstabilan CDN/proxy pihak ketiga, di luar kendali provider —
 *  sifatnya sama dengan tunnel Cloudflare pada Hydrax yang berumur pendek.
 *
 *  YANG BISA diperbaiki dari sisi kita: mempercepat CloudStream berpindah
 *  dari sumber yang SUDAH TERBUKTI mati sebelum pengguna menekan putar,
 *  bukan menunggu ExoPlayer menemukannya sendiri. Karena itu setiap sumber
 *  diverifikasi dengan satu permintaan Range kecil sebelum dikirim ke
 *  callback — pola ini sudah ada di CloudStream sendiri
 *  (ExtractorLink.getVideoSize() memakai app.head dengan timeout).
 *
 *  ------------------------------------------------------------------------
 *  HASIL INVESTIGASI TERMUX (menggantikan dugaan di atas)
 *  ------------------------------------------------------------------------
 *
 *  Investigasi lanjutan di luar aplikasi membantah sebagian dugaan awal.
 *  Yang berikut ini [TERBUKTI] lewat pengujian langsung, bukan dari logcat:
 *
 *  22. PrimeVids TIDAK PERNAH mengembalikan video. Seluruh 349 segmen pada
 *      satu playlist teridentifikasi PNG lewat magic byte, dan pola yang sama
 *      berulang pada judul lain (1396, 969681, 1399) dengan host iklan
 *      p1.ipstatp.com / p16-oec-sg.ibyteimg.com. Playlist juga tidak memuat
 *      EXT-X-DISCONTINUITY, EXT-X-KEY, maupun EXT-X-MAP — jadi bukan video
 *      dengan sisipan iklan, melainkan seluruhnya bukan video.
 *      Karena itu layanan `primevids` DIBUANG seluruhnya. Tidak perlu
 *      pemeriksaan isi segmen saat runtime.
 *
 *  23. Proxy Valhalla adalah sumber ketidakstabilan FlowCast, BUKAN CDN
 *      hakunaymatata. Simulasi sesi ExoPlayer (8 siklus buka-baca-tutup-seek):
 *          kualitas 720: proxy 8/8 @ 64 KB/s   | hulu 8/8 @ 722 KB/s
 *          kualitas 480: proxy 3/8 @ 169 KB/s  | hulu 8/8 @ 769 KB/s
 *          kualitas 360: proxy 5/8 @ 556 KB/s  | hulu 8/8 @ 824 KB/s
 *      Hipotesis lama bahwa path `/bt/` tidak mendukung akses acak GUGUR:
 *      lewat hulu, seek 50% dan 90% berhasil di semua kualitas.
 *
 *  24. URL hulu beserta header yang dibutuhkannya SUDAH tersedia di dalam URL
 *      proxy itu sendiri, pada parameter `url` dan `headers`. Melewati proxy
 *      karena itu tidak memerlukan informasi tambahan apa pun.
 *
 *  25. Hulu MENOLAK Referer rivestream.app dan MENERIMA Referer
 *      123movienow.cc — kebalikan dari aturan proxy. Penolakannya berbentuk
 *      429, bukan 403, sehingga sempat disalahartikan sebagai pembatasan laju.
 *      Dari tujuh kombinasi header, hanya dua yang memuat Referer
 *      123movienow.cc yang lolos.
 *      KONSEKUENSI: header untuk pemeriksaan maupun untuk ExtractorLink harus
 *      mengikuti hasil ekstraksi, bukan memakai Referer situs secara seragam.
 *
 *  26. [ASUMSI] Parameter `t` pada URL hulu tampak seperti batas waktu, tetapi
 *      URL dengan `t` yang sudah lewat ~8 jam tetap membalas 206. Jadi nilai
 *      itu tampaknya tidak ditegakkan. Belum terjelaskan, risiko rendah karena
 *      tautan tetap dibangun ulang tiap kali loadLinks() dipanggil.
 *
 *  BATASAN JUJUR: pemeriksaan hanya menangkap kegagalan yang sudah terjadi
 *  saat loadLinks() dipanggil. Ia tidak bisa mencegah sumber mati di tengah
 *  pemutaran, dan itu memang di luar kendali provider.
 *
 *  ------------------------------------------------------------------------
 *  Dasar [TERBUKTI] tambahan untuk load()
 *  ------------------------------------------------------------------------
 *
 *  5. `tvEpisodes(id, season)` mengembalikan SATU musim per permintaan.
 *     Tanpa parameter `season`, server memberi musim 0 (Specials).
 *
 *  6. Daftar musim diambil dari `tvData.seasons[]`, BUKAN dari
 *     `number_of_seasons`. Breaking Bad: number_of_seasons = 5, sedangkan
 *     seasons[] berisi 6 entri karena musim 0 ikut terdaftar.
 *
 *  7. `vote_average` bertipe CAMPURAN: Int untuk film (8), Double untuk
 *     serial (8.949). Harus dibaca dengan optDouble, bukan optInt.
 *
 *  8. `episode_run_time` bisa berupa array KOSONG (Breaking Bad), sehingga
 *     durasi serial boleh null.
 *
 *  9. Id IMDb berada di tempat berbeda:
 *         film   -> `imdb_id` di tingkat atas
 *         serial -> `external_ids.imdb_id`
 *
 * 10. Satuan berbeda antara dua model CloudStream:
 *         LoadResponse.duration -> MENIT
 *         Episode.runTime       -> DETIK
 *     TMDB memberi menit, jadi runtime episode dikalikan 60.
 *
 *  ------------------------------------------------------------------------
 *  Yang sengaja BELUM dikerjakan
 *  ------------------------------------------------------------------------
 *
 *  - `actors` (butuh `movieCasts` / `tvCasts`) dan `recommendations` (butuh
 *    `movieSimilar` / `tvSimilar`). Keduanya menambah permintaan jaringan dan
 *    belum pernah diuji, jadi masih [ASUMSI].
 *
 *  - Musim 0 (Specials) IKUT ditampilkan karena datanya tersedia. Untuk
 *    menyembunyikannya, ubah syarat `seasonNumber < 0` menjadi
 *    `seasonNumber <= 0` di [loadTvEpisodes]. Satu baris, tanpa efek lain.
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
     * Layanan yang selalu dilewati.
     *
     * `primevids` terbukti tidak pernah mengembalikan video: seluruh segmen
     * playlist-nya berupa gambar PNG dari domain iklan, pada semua judul yang
     * diuji. Memanggilnya hanya membuang waktu dan memunculkan sumber palsu
     * di daftar pengguna. Lihat catatan nomor 22 di bagian atas berkas.
     */
    private val skippedServices = setOf("primevids")

    /** Host proxy yang perlu dilewati dengan mengurai URL hulunya. */
    private val proxyMarkers = listOf("valhallastream", "/proxy?")

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
            this.backgroundPosterUrl =
                RiveApi.backdropUrl(data.optStringOrNull("backdrop_path"))
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
            this.backgroundPosterUrl =
                RiveApi.backdropUrl(data.optStringOrNull("backdrop_path"))
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

    // ----------------------------------------------------------- loadLinks

    /**
     * Kumpulkan seluruh sumber stream dari semua layanan.
     *
     * Layanan dipanggil BERURUTAN, bukan paralel. RiveApi sudah memberlakukan
     * jeda minimum antar permintaan lewat mutex, sehingga pemanggilan paralel
     * tetap diserialkan dan tidak mempercepat apa pun — hanya menambah risiko
     * koneksi diputus server.
     *
     * @return true bila sedikitnya satu tautan berhasil dikumpulkan.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = data.queryParam("type") ?: return false
        val id = data.queryParam("id")?.takeIf { it.isNotBlank() } ?: return false
        val isTv = type.equals("tv", true)

        val season = data.queryParam("season")?.toIntOrNull()
        val episode = data.queryParam("episode")?.toIntOrNull()
        if (isTv && (season == null || episode == null)) return false

        val services = RiveApi.videoProviderServices()
        val seenLinks = HashSet<String>()
        val seenSubs = HashSet<String>()
        var emitted = 0

        for (service in services) {
            // primevids terbukti selalu mengembalikan playlist iklan, bukan
            // video. Dilewati tanpa permintaan jaringan sama sekali.
            if (service.lowercase() in skippedServices) continue

            // Kegagalan satu layanan tidak boleh menghentikan yang lain.
            val result = try {
                if (isTv) {
                    RiveApi.tvVideoProvider(id, service, season!!, episode!!)
                } else {
                    RiveApi.movieVideoProvider(id, service)
                }
            } catch (e: Exception) {
                null
            } ?: continue

            emitted += emitSources(result, service, seenLinks, callback)
            emitCaptions(result, seenSubs, subtitleCallback)
        }

        // Sumber embed. Terpisah dari VideoProvider dan memakai rantai
        // sendiri; kegagalannya tidak boleh menjatuhkan sumber non-embed.
        emitted += try {
            emitEmbed(id, isTv, season, episode, seenLinks, callback)
        } catch (e: Exception) {
            0
        }

        return emitted > 0
    }

    /**
     * Kumpulkan sumber dari jalur embed.
     *
     * `movieEmbedProvider` mengembalikan tautan ke hoster Byse berbentuk
     * `https://bysekoze.com/e/{code}`. Tautan itu BUKAN media langsung: ia
     * halaman player yang memerlukan rantai attestation, Proof of Work, dan
     * dekripsi AES-GCM sebelum menghasilkan URL HLS. Seluruh rantai itu
     * ditangani ByseClient.
     *
     * Rantai memakan waktu beberapa detik karena mencakup PoW dan enam
     * permintaan jaringan, sehingga sengaja dijalankan SETELAH sumber
     * non-embed dikirim. Pengguna sudah melihat daftar lebih dulu, dan
     * sumber embed menyusul.
     */
    private suspend fun emitEmbed(
        id: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        seen: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        for (service in RiveApi.embedProviderServices()) {
            val data = try {
                if (isTv) {
                    RiveApi.tvEmbedProvider(id, service, season ?: 1, episode ?: 1)
                } else {
                    RiveApi.movieEmbedProvider(id, service)
                }
            } catch (e: Exception) {
                null
            } ?: continue

            val sources = data.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val item = sources.optJSONObject(i) ?: continue
                val link = item.optStringOrNull("link") ?: continue

                // `host` berformat hoster-jaringan-kualitas, mis.
                // "byse-flowcast-1080". Bagian ketiga dipakai sebagai kualitas.
                val hostTag = item.optStringOrNull("host").orEmpty()
                val labelKualitas = hostTag.split("-").getOrNull(2)

                val target = ByseClient.uraiTautan(link) ?: continue
                val hasil = ByseClient.resolve(target.second, target.first) ?: continue

                for (sumber in hasil.sources) {
                    if (!seen.add(sumber.url)) continue

                    val tipe = when {
                        sumber.mimeType?.contains("mpegurl", true) == true ->
                            ExtractorLinkType.M3U8
                        sumber.url.substringBefore('?').endsWith(".m3u8", true) ->
                            ExtractorLinkType.M3U8
                        sumber.mimeType?.contains("mp4", true) == true ->
                            ExtractorLinkType.VIDEO
                        else -> null
                    }

                    // Kualitas diambil dari height bila ada, karena field
                    // `quality` pada respons Byse bisa berupa huruf seperti "h".
                    val kualitas = sumber.height
                        ?: getQualityFromName(sumber.label ?: labelKualitas)

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "Byse",
                            url = sumber.url,
                            type = tipe
                        ) {
                            this.quality = kualitas
                            // Referer WAJIB halaman player, bukan situs Rive.
                            this.referer = hasil.referer
                            this.headers = mapOf("User-Agent" to ByseClient.USER_AGENT)
                        }
                    )
                    count++
                }
            }
        }
        return count
    }

    /**
     * Ubah `sources[]` satu layanan menjadi ExtractorLink.
     *
     * @return jumlah tautan yang berhasil dikirim.
     */
    private suspend fun emitSources(
        result: JSONObject,
        service: String,
        seen: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val sources = result.optJSONArray("sources") ?: return 0
        var count = 0

        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val url = item.optStringOrNull("url") ?: continue
            if (!seen.add(url)) continue

            // Lewati proxy Valhalla bila memungkinkan: URL hulu terbukti jauh
            // lebih andal (8/8 berbanding 3/8 pada seek) dan berkali lipat
            // lebih cepat. Lihat catatan nomor 23 dan 24.
            val (finalUrl, finalHeaders) = resolveLink(url)

            // Periksa memakai header HASIL EKSTRAKSI, bukan Referer situs.
            // Hulu menolak Referer rivestream.app dengan 429 (catatan 25),
            // jadi memakai header seragam justru akan membuang sumber sehat.
            if (!isReachable(finalUrl, finalHeaders)) continue

            // Nama layanan versi server lebih informatif, mis. "Vietsub (Tap 1)"
            // milik ophim. Kalau tidak ada, pakai nama service apa adanya.
            val label = item.optStringOrNull("source") ?: service

            // quality bertipe campuran: 720 (angka) maupun "tcloud" (teks).
            // getQualityFromName mengembalikan Qualities.Unknown untuk teks.
            val quality = getQualityFromName(item.optStringOrNull("quality"))

            val linkType = when (item.optStringOrNull("format")?.lowercase()) {
                "hls" -> ExtractorLinkType.M3U8
                "mp4" -> ExtractorLinkType.VIDEO
                // Format tak dikenal dibiarkan ditebak CloudStream.
                else -> null
            }

            callback(
                newExtractorLink(
                    source = this.name,
                    name = label,
                    url = finalUrl,
                    type = linkType
                ) {
                    this.quality = quality
                    // Referer dan header lain mengikuti hasil ekstraksi:
                    //   URL hulu   -> Referer 123movienow.cc (+ Origin)
                    //   URL biasa  -> Referer rivestream.app
                    this.referer = finalHeaders["Referer"] ?: RiveApi.REFERER
                    this.headers = finalHeaders.filterKeys { it != "Referer" }
                }
            )
            count++
        }
        return count
    }

    /**
     * Uji cepat apakah sebuah URL sumber benar-benar bisa dibuka, sebelum
     * dikirim ke pemutar.
     *
     * Memakai satu permintaan Range kecil dengan timeout pendek — pola yang
     * sama seperti [ExtractorLink.getVideoSize] bawaan CloudStream (app.head
     * dengan parameter timeout). Range dipakai alih-alih HEAD murni karena
     * sebagian CDN pada RiveStream (mis. proxy FlowCast/HindiCast) hanya
     * menjawab benar untuk permintaan GET.
     *
     * 403/404/5xx/timeout dianggap tidak bisa dipakai. Kegagalan jaringan
     * lain (nama domain berubah, dsb.) diperlakukan sama: sumber dilewati,
     * bukan menggagalkan seluruh loadLinks().
     */
    private suspend fun isReachable(url: String, headers: Map<String, String>): Boolean {
        return try {
            // Bentuk pemanggilan ini SENGAJA disamakan persis dengan RiveApi.raw()
            // (Tahap 2, sudah terbukti compile & jalan): hanya parameter `url` dan
            // `headers`, dengan Referer dimasukkan sebagai entri header biasa.
            // Overload app.get dengan parameter bernama `referer=`/`timeout=`
            // terpisah baru terlihat lewat wildcard import com.lagradost.cloudstream3.*,
            // yang tidak dipakai di berkas ini (gaya impor di sini eksplisit sejak
            // Tahap 1). Batas waktu karena itu dikendalikan lewat
            // kotlinx.coroutines.withTimeoutOrNull, bukan parameter app.get.
            withTimeoutOrNull(8000L) {
                val res = app.get(url, headers = headers + ("Range" to "bytes=0-1"))
                res.code in 200..299
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ubah URL sumber menjadi pasangan (URL final, header final).
     *
     * Bila URL adalah proxy Valhalla, URL hulu dan headernya diambil dari
     * parameter `url` dan `headers` milik URL proxy itu sendiri. Terbukti
     * jauh lebih andal dan cepat daripada melewati proxy (catatan 23-24).
     *
     * Bila bukan URL proxy, atau parameter `url` tidak ada, URL dikembalikan
     * apa adanya dengan Referer situs — supaya tidak ada sumber yang hilang
     * hanya karena bentuk URL-nya tidak dikenali.
     *
     * Spesifikasi ini kembaran dari extract_final_link() di rive_final_check.py
     * yang dipakai untuk verifikasi di luar aplikasi. Perubahan di satu sisi
     * harus diikuti di sisi lain.
     */
    private fun resolveLink(url: String): Pair<String, Map<String, String>> {
        val siteHeaders = mapOf(
            "User-Agent" to RiveApi.USER_AGENT,
            "Referer" to RiveApi.REFERER
        )
        if (proxyMarkers.none { url.contains(it) }) return url to siteHeaders

        val inner = url.queryParam("url")?.takeIf { it.startsWith("http") }
            ?: return url to siteHeaders

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = RiveApi.USER_AGENT

        // Parameter `headers` berisi JSON, mis.
        //   {"Referer":"https://123movienow.cc/","Origin":"https://123movienow.cc"}
        url.queryParam("headers")?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) headers[k] = v
                }
            }
        }
        if (!headers.containsKey("Referer")) headers["Referer"] = RiveApi.REFERER

        return inner to headers
    }

    /**
     * Ubah `captions[]` satu layanan menjadi SubtitleFile.
     *
     * Subtitle TIDAK diambil dari `movieOnlineSubtitles` / `tvOnlineSubtitles`
     * karena kedua endpoint itu terbukti selalu membalas 500.
     */
    private suspend fun emitCaptions(
        result: JSONObject,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val captions = result.optJSONArray("captions") ?: return

        for (i in 0 until captions.length()) {
            val item = captions.optJSONObject(i) ?: continue
            val file = item.optStringOrNull("file") ?: continue
            if (!seen.add(file)) continue

            // Label berbentuk "English - FlowCast"; ambil bagian bahasanya.
            val raw = item.optStringOrNull("label") ?: "Unknown"
            val lang = raw.substringBefore(" - ").trim().ifBlank { raw }

            subtitleCallback(newSubtitleFile(lang, file))
        }
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

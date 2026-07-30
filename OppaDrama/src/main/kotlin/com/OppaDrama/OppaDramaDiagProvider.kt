package com.OppaDrama

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * MILESTONE 1 — pembacaan hasil TANPA adb dan TANPA logcat.
 *
 * Provider Termux tidak bisa membaca logcat aplikasi lain tanpa root, dan
 * seluruh investigasi ini sudah membuktikan bahwa alat ukur yang tidak bisa
 * dibaca langsung adalah sumber kesalahan. Jadi hasil M1 ditampilkan sebagai
 * judul item di halaman utama, terbaca langsung di UI CloudStream.
 *
 * Provider ini TIDAK menyentuh OppaDramaProvider sama sekali. Ia berdiri
 * sendiri, sehingga provider produksi Anda tetap utuh dan bisa dihapus
 * begitu M1 selesai.
 */
class OppaDramaDiagProvider : MainAPI() {
    override var mainUrl = "http://127.0.0.1"
    override var name = "OPPADRAMA-DIAG"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Others)

    override val mainPage = mainPageOf(
        "m1" to "Milestone 1 — ServerSocket"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = mutableListOf<String>()

        // R1 — apakah socket bisa dibuka
        val running = LocalServer.isRunning
        rows += "R1 bind socket        : " + if (running) "OK port=${LocalServer.port}" else "GAGAL"
        LocalServer.lastError?.let { rows += "R1 error terakhir     : $it" }

        // R2 — lifecycle: uptime yang terus naik = server bertahan
        rows += "R2 uptime            : ${LocalServer.uptimeSec} detik"
        rows += "R2 started_at        : ${LocalServer.startedAtMs}"

        // R3 — request berulang: lakukan dua request nyata lewat stack HTTP app
        val probes = mutableListOf<String>()
        if (running) {
            repeat(2) { i ->
                probes += try {
                    val res = app.get("http://127.0.0.1:${LocalServer.port}/ping$i")
                    val served = Regex("""served=(\d+)""")
                        .find(res.text)?.groupValues?.get(1) ?: "?"
                    "HTTP ${res.code} served=$served"
                } catch (e: Throwable) {
                    "GAGAL ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        } else {
            probes += "dilewati, server mati"
        }
        probes.forEachIndexed { i, s -> rows += "R3 probe ${i + 1}           : $s" }
        rows += "R3 total dilayani    : ${LocalServer.served.get()}"
        rows += "   error ditelan     : ${LocalServer.swallowed.get()}"

        val verdict = when {
            !running -> "VERDICT: R1 GAGAL — arsitektur proxy gugur, hentikan"
            LocalServer.served.get() >= 2 -> "VERDICT: R1+R3 LULUS — cek uptime lagi nanti untuk R2"
            else -> "VERDICT: socket hidup tapi request belum terlayani"
        }

        val items: List<SearchResponse> = (listOf(verdict) + rows).mapIndexed { i, text ->
            newMovieSearchResponse(text, "diag://$i", TvType.Others)
        }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse =
        newMovieLoadResponse("Diagnostik M1", url, TvType.Others, url) {
            this.plot = "Item diagnostik. Tarik-segarkan halaman utama untuk memperbarui angka."
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}

package com.OppaDrama

/* ═══════════════════════════════════════════════════════════════════════════
 *  OppaDiag.kt — INSTRUMENTASI DIAGNOSTIK SEMENTARA
 *  ───────────────────────────────────────────────────────────────────────────
 *  Berkas ini HANYA untuk investigasi. Setelah selesai:
 *    1. hapus berkas ini
 *    2. hapus semua baris yang mengandung "OppaDiag" atau "OppaProbe"
 *       di Plugin, Provider, dan Extractors
 *  Tidak ada bagian lain dari plugin yang bergantung pada berkas ini.
 *
 *  PRINSIP: netral terhadap perilaku.
 *    - Semua fungsi menelan Throwable sendiri, kecuali yang sengaja rethrow.
 *    - DiagCallback meneruskan link apa adanya, tanpa filter, tanpa urutan baru.
 *    - Probe memakai TLD ".invalid" yang tidak mungkin cocok dengan URL nyata.
 *    - Satu-satunya penambahan jaringan adalah probePlaylist(), dibatasi 2×
 *      per sesi dan dapat dimatikan lewat ENABLE_PLAYLIST_PROBE.
 * ═══════════════════════════════════════════════════════════════════════════ */

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*  CATATAN IMPORT
 *  Bila `import com.lagradost.cloudstream3.utils.extractorApis` gagal dikompilasi,
 *  coba salah satu alternatif ini (lokasinya berbeda antar versi):
 *      import com.lagradost.cloudstream3.APIHolder.extractorApis
 *      import com.lagradost.cloudstream3.extractorApis
 *  Kalau ketiganya gagal, laporkan — itu sendiri sudah informasi berharga.
 */

object OppaDiag {

    const val TAG = "OppaDiag"

    /** Matikan bila tidak ingin ada request tambahan sama sekali. */
    private const val ENABLE_PLAYLIST_PROBE = true

    /** Batas jumlah playlist yang diunduh untuk diperiksa, per sesi loadLinks. */
    private const val MAX_PLAYLIST_PROBE = 2

    private val sessionSeq = AtomicInteger(0)
    private val probeDone = AtomicBoolean(false)
    private val registryDumped = AtomicBoolean(false)

    /** Domain yang relevan dengan investigasi ini. */
    private val DOMAINS = listOf(
        "emturbovid", "turbosplayer", "turboviplay",
        "buzzheavier", "buzz",
        "vidhide", "smoothpre", "earnvids",
        "minochinos", "abyss", "acek-cdn"
    )

    // ───────────────────────────────────────────────────────────────────
    //  Utilitas log (logcat memotong pesan di ~4000 byte, jadi dipotong)
    // ───────────────────────────────────────────────────────────────────

    fun log(section: String, msg: String) {
        try {
            val prefix = "[$section]"
            if (msg.length <= 3000) {
                Log.i(TAG, "$prefix $msg")
            } else {
                msg.chunked(3000).forEachIndexed { i, part ->
                    Log.i(TAG, "$prefix (${i + 1}) $part")
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun err(section: String, msg: String, t: Throwable? = null) {
        try {
            Log.e(TAG, "[$section] $msg" + (t?.let { " :: ${it.javaClass.name}: ${it.message}" } ?: ""))
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────────────────────────────────────────────────
    //  BAGIAN A — Registrasi (dipanggil dari Plugin.load())
    // ───────────────────────────────────────────────────────────────────

    fun registrySize(): Int = try {
        extractorApis.size
    } catch (t: Throwable) {
        err("REG", "extractorApis tidak terbaca", t)
        -1
    }

    /** Dipanggil SEBELUM tiap registerExtractorAPI. */
    fun beforeRegister(label: String) {
        log("REG", "AKAN daftar $label | ukuran extractorApis sekarang = ${registrySize()}")
    }

    /** Dipanggil SESUDAH tiap registerExtractorAPI yang berhasil. */
    fun afterRegister(label: String) {
        log("REG", "OK   daftar $label | ukuran extractorApis sekarang = ${registrySize()}")
    }

    /**
     * Dump isi extractorApis untuk domain yang relevan, LENGKAP DENGAN INDEKS.
     * Indeks inilah yang menentukan siapa menang saat loadExtractor menelusuri.
     */
    fun dumpRegistry(momen: String) {
        try {
            val list = extractorApis
            log("DUMP", "=== isi extractorApis @ $momen | total = ${list.size} ===")

            list.forEachIndexed { index, api ->
                val mu = api.mainUrl.lowercase()
                if (DOMAINS.any { mu.contains(it) }) {
                    log(
                        "DUMP",
                        "  idx=$index | kelas=${api.javaClass.name} | name='${api.name}' | mainUrl='${api.mainUrl}'"
                    )
                }
            }

            // 12 entri terakhir memperlihatkan di mana extractor plugin mendarat
            val tail = list.size - 12
            log("DUMP", "--- 12 entri terakhir (posisi extractor plugin) ---")
            list.forEachIndexed { index, api ->
                if (index >= tail) {
                    log("DUMP", "  idx=$index | kelas=${api.javaClass.simpleName} | mainUrl='${api.mainUrl}'")
                }
            }
            log("DUMP", "=== akhir dump ===")
        } catch (t: Throwable) {
            err("DUMP", "gagal membaca extractorApis", t)
        }
    }

    fun dumpRegistryOnce(momen: String) {
        if (registryDumped.compareAndSet(false, true)) dumpRegistry(momen)
    }

    // ───────────────────────────────────────────────────────────────────
    //  BAGIAN B — Replikasi pencocokan loadExtractor()
    // ───────────────────────────────────────────────────────────────────

    private val schemaStrip = Regex("""^(https?://)?(www\.)?""")

    private fun norm(u: String) = u.lowercase().trim().replace(schemaStrip, "")

    /**
     * Menirukan pencocokan awalan yang dipakai loadExtractor(), lalu mencatat
     * SEMUA kandidat yang cocok beserta indeksnya.
     *
     * PENTING: ini APROKSIMASI. Ia menjawab "siapa saja yang berhak", bukan
     * "siapa yang dipilih". Yang memilih diungkap oleh uji PROBE di Bagian C.
     */
    fun logCandidates(embedUrl: String) {
        try {
            val target = norm(embedUrl)
            val hits = mutableListOf<String>()

            extractorApis.forEachIndexed { index, api ->
                if (target.startsWith(norm(api.mainUrl))) {
                    hits += "idx=$index ${api.javaClass.simpleName}(mainUrl='${api.mainUrl}')"
                }
            }

            if (hits.isEmpty()) {
                log("MATCH", "TIDAK ADA kandidat cocok untuk: $embedUrl")
            } else {
                log("MATCH", "${hits.size} kandidat untuk $embedUrl")
                hits.forEach { log("MATCH", "    $it") }
                log("MATCH", "    -> indeks TERKECIL = ${hits.first()}")
                log("MATCH", "    -> indeks TERBESAR = ${hits.last()}")
            }
        } catch (t: Throwable) {
            err("MATCH", "gagal mencocokkan $embedUrl", t)
        }
    }

    // ───────────────────────────────────────────────────────────────────
    //  BAGIAN C — Uji arah iterasi (INI YANG PALING MENENTUKAN)
    // ───────────────────────────────────────────────────────────────────

    /**
     * Dua extractor tiruan dengan mainUrl IDENTIK, didaftarkan berurutan.
     * Memanggil loadExtractor() pada domain tiruan itu akan mengaktifkan
     * tepat satu di antaranya — dan mana yang aktif membuktikan arah
     * penelusuran extractorApis di APK yang benar-benar terpasang.
     *
     * Domain ".invalid" dijamin RFC 2606 tidak pernah dapat diresolusi,
     * sehingga mustahil bentrok dengan URL embed nyata.
     */
    suspend fun runProbeOnce() {
        if (!probeDone.compareAndSet(false, true)) return
        try {
            log("PROBE", "menjalankan uji arah iterasi …")
            val matched = loadExtractor(
                url = "https://oppadiag-probe.invalid/uji",
                referer = null,
                subtitleCallback = {},
                callback = {}
            )
            log("PROBE", "loadExtractor mengembalikan $matched")
            if (!matched) {
                log("PROBE", "HASIL: tidak ada yang cocok — probe tidak terdaftar, atau pencocokan gagal")
            }
        } catch (t: Throwable) {
            err("PROBE", "uji probe gagal", t)
        }
    }

    // ───────────────────────────────────────────────────────────────────
    //  BAGIAN D — Jejak embed (menjawab: benarkah URL-nya identik?)
    // ───────────────────────────────────────────────────────────────────

    fun newSession(dataUrl: String): Int {
        val id = sessionSeq.incrementAndGet()
        log("SESI", "########## SESI #$id | loadLinks(data=$dataUrl) ##########")
        return id
    }

    fun selectors(sesi: Int, iframe: Int, mirror: Int, dlbox: Int, eplister: Int) {
        log(
            "SESI",
            "#$sesi jumlah selector -> player-embed=$iframe | mirror=$mirror | dlbox=$dlbox | eplister=$eplister"
        )
    }

    /**
     * Dicatat SEBELUM embed diproses. `asal` menandai selector sumbernya,
     * mis. "IFRAME", "MIRROR#0", "DLBOX#3".
     *
     * `len` dan `hash` sengaja dicetak agar perbedaan tak kasat mata
     * (spasi tak terlihat, karakter zero-width) tetap ketahuan.
     */
    fun embed(sesi: Int, asal: String, mentah: String, sesudahHttpsify: String) {
        log("EMBED", "#$sesi $asal mentah   = [$mentah]")
        log("EMBED", "#$sesi $asal httpsify = [$sesudahHttpsify]")
        log(
            "EMBED",
            "#$sesi $asal len=${sesudahHttpsify.length} hash=${sesudahHttpsify.hashCode()}"
        )
    }

    // ───────────────────────────────────────────────────────────────────
    //  BAGIAN E — Jejak callback (menjawab: siapa yang menghasilkan link?)
    // ───────────────────────────────────────────────────────────────────

    /**
     * Pembungkus callback yang mencatat SETIAP link beserta source DAN name
     * secara terpisah. Ini yang akhirnya menjawab apakah UI menampilkan
     * `name` atau `source`, dan apakah label kualitas ditambahkan UI.
     *
     * Meneruskan link apa adanya — tidak menyaring, tidak mengubah urutan.
     */
    class DiagCallback(
        private val sesi: Int,
        private val asal: String,
        private val delegate: (ExtractorLink) -> Unit,
    ) : (ExtractorLink) -> Unit {

        private val n = AtomicInteger(0)
        val count: Int get() = n.get()

        override fun invoke(link: ExtractorLink) {
            try {
                val i = n.incrementAndGet()
                log(
                    "LINK",
                    "#$sesi $asal [$i] source='${link.source}' name='${link.name}' " +
                            "quality=${link.quality} type=${link.type} referer='${link.referer}'"
                )
                log("LINK", "#$sesi $asal [$i] url=${link.url}")
                collectForPlaylistProbe(link.url)
            } catch (_: Throwable) {
            }
            delegate(link)   // WAJIB: tanpa syarat, tanpa perubahan
        }
    }

    class DiagSubtitle(
        private val sesi: Int,
        private val asal: String,
        private val delegate: (SubtitleFile) -> Unit,
    ) : (SubtitleFile) -> Unit {
        override fun invoke(sub: SubtitleFile) {
            try {
                log("SUB", "#$sesi $asal lang='${sub.lang}' url=${sub.url}")
            } catch (_: Throwable) {
            }
            delegate(sub)
        }
    }

    fun embedResult(sesi: Int, asal: String, jumlah: Int, sumberInfo: String) {
        log("HASIL", "#$sesi $asal menghasilkan $jumlah link ($sumberInfo)")
    }

    // ───────────────────────────────────────────────────────────────────
    //  BAGIAN F — Pemeriksa playlist (menjawab Poin 4 & Poin 5 sekaligus)
    // ───────────────────────────────────────────────────────────────────

    private val playlistQueue = mutableListOf<String>()
    private val playlistProbed = mutableSetOf<String>()

    private fun collectForPlaylistProbe(url: String) {
        if (!ENABLE_PLAYLIST_PROBE) return
        val u = url.lowercase()
        val menarik = u.contains("master.m3u8") || u.contains(".urlset")
        if (!menarik) return
        synchronized(playlistQueue) {
            if (playlistQueue.size < MAX_PLAYLIST_PROBE && playlistQueue.none { it == url }) {
                playlistQueue += url
            }
        }
    }

    /**
     * Unduh playlist yang sudah dikumpulkan lalu cetak hanya baris yang
     * penting. Dipanggil SEKALI di akhir loadLinks, setelah semua link keluar.
     *
     * Menjawab dua hal sekaligus:
     *   - Poin 5: apakah anak dari master Emturbovid juga berupa master?
     *   - Poin 4: apakah resolusi Minochinos bisa dibaca akurat?
     */
    suspend fun flushPlaylistProbe(sesi: Int) {
        if (!ENABLE_PLAYLIST_PROBE) return
        val antrian = synchronized(playlistQueue) {
            val salinan = playlistQueue.toList()
            playlistQueue.clear()
            salinan
        }
        for (url in antrian) {
            if (!playlistProbed.add(url)) continue
            try {
                log("PLAYLIST", "#$sesi mengunduh: $url")
                val body = app.get(url, timeout = 15L).text
                val penting = body.lines()
                    .map { it.trim() }
                    .filter {
                        it.startsWith("#EXT-X-STREAM-INF") ||
                                it.startsWith("#EXT-X-MEDIA") ||
                                (it.isNotBlank() && !it.startsWith("#"))
                    }
                log("PLAYLIST", "#$sesi jumlah baris penting = ${penting.size}")
                penting.take(40).forEachIndexed { i, baris ->
                    log("PLAYLIST", "#$sesi   [$i] $baris")
                }
                log(
                    "PLAYLIST",
                    "#$sesi VONIS: " + when {
                        penting.any { it.contains("master.m3u8", true) } ->
                            "MASTER BERSARANG (anaknya master lagi)"
                        penting.any { it.startsWith("#EXT-X-STREAM-INF") } ->
                            "MASTER BIASA (anaknya rendition sungguhan)"
                        else -> "BUKAN MASTER (kemungkinan playlist media langsung)"
                    }
                )
            } catch (t: Throwable) {
                err("PLAYLIST", "#$sesi gagal mengunduh $url", t)
            }
        }
    }
}

/* ═══════════════════════════════════════════════════════════════════════════
 *  EXTRACTOR PROBE — hanya untuk uji arah iterasi. Hapus bersama OppaDiag.kt.
 *  Keduanya memakai mainUrl yang SAMA PERSIS. Bedanya cuma urutan registrasi.
 * ═══════════════════════════════════════════════════════════════════════════ */

class OppaProbeAlpha : ExtractorApi() {
    override val name = "OppaProbeAlpha"
    override val mainUrl = "https://oppadiag-probe.invalid"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        OppaDiag.log(
            "PROBE",
            "PEMENANG = ALPHA (didaftarkan PERTAMA / indeks LEBIH KECIL) " +
                    "=> iterasi MAJU => extractor CORE selalu menang"
        )
    }
}

class OppaProbeBeta : ExtractorApi() {
    override val name = "OppaProbeBeta"
    override val mainUrl = "https://oppadiag-probe.invalid"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        OppaDiag.log(
            "PROBE",
            "PEMENANG = BETA (didaftarkan TERAKHIR / indeks LEBIH BESAR) " +
                    "=> iterasi MUNDUR => extractor PLUGIN berhak menang"
        )
    }
}

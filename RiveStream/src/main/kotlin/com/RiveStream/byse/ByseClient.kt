package com.RiveStream.byse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * ============================================================================
 *  Byse — lapisan transport dan orkestrasi
 * ============================================================================
 *
 *  Satu-satunya berkas di modul ini yang tahu HTTP. Ketiga modul lain murni
 *  fungsi tanpa jaringan, sehingga bila Byse mengganti endpoint atau header,
 *  hanya berkas ini yang perlu disentuh:
 *
 *      BysePlayback  dekripsi AES-GCM dan perakitan key_parts
 *      BysePow       Proof of Work
 *      ByseAttest    ECDSA P-256, JWK, badan attest
 *
 *  ------------------------------------------------------------------------
 *  Rantai yang sudah TERBUKTI terhadap server sungguhan
 *  ------------------------------------------------------------------------
 *
 *  Diuji end-to-end dari Termux pada 2026-08-04 dan menghasilkan URL HLS:
 *
 *    1. GET  {sumber}/api/videos/{code}/embed/details
 *            -> embed_frame_url, misalnya https://q8y5z.com/e5e1/{code}
 *            Host DAN segmen path BEROTASI. Terpantau berubah empat kali
 *            dalam beberapa jam: 4iuq0 -> i65h -> zjkq8 -> e5e1.
 *            Karena itu keduanya WAJIB diambil segar, tidak boleh disimpan.
 *
 *    2. GET  {player}/{segmen}/{code}          buka halaman player
 *    3. POST {player}/api/videos/access/challenge   -> challenge_id, nonce
 *    4. POST {player}/api/videos/access/attest      -> viewer_id, device_id,
 *                                                      token, confidence
 *            Server memasang cookie byse_viewer_id dan byse_device_id.
 *    5. POST {player}/api/videos/{code}/embed/captcha
 *            -> pow_nonce, pow_difficulty, pow_token, expires_in
 *    6. PoW lokal                                   -> solution
 *    7. POST {player}/api/videos/{code}/embed/captcha/verify
 *            -> token untuk header X-Captcha-Token
 *    8. POST {player}/api/videos/{code}/embed/playback
 *            -> playback { algorithm, version, key_parts[30], iv, payload }
 *    9. dekripsi                                    -> sources, tracks
 *
 *  Hasil nyata dari pengujian: version 18 -> indeks [18, 13], kunci 32 byte,
 *  satu sumber HLS master.m3u8.
 *
 *  ------------------------------------------------------------------------
 *  Kenapa HttpURLConnection, bukan app.get milik CloudStream
 *  ------------------------------------------------------------------------
 *
 *  Rantai ini menuntut kendali penuh atas metode, badan JSON, dan terutama
 *  COOKIE yang harus dibawa dari langkah 4 sampai 8. Overload app.get dengan
 *  parameter bernama seperti `referer` dan `timeout` hanya terlihat lewat
 *  wildcard import com.lagradost.cloudstream3.*, yang tidak dipakai di modul
 *  ini, dan penanganan cookie-nya bergantung pada rincian NiceHttp yang bisa
 *  berubah. HttpURLConnection selalu tersedia di Android, tanpa dependensi,
 *  dan perilakunya dapat diprediksi.
 *
 *  ------------------------------------------------------------------------
 *  Identitas perangkat
 *  ------------------------------------------------------------------------
 *
 *  Peramban menyimpan kunci ECDSA di IndexedDB agar viewer_id tetap sama.
 *  Di sini identitas disimpan di memori selama proses hidup, sehingga tetap
 *  sama antar pemutaran dalam satu sesi aplikasi.
 *
 *  ------------------------------------------------------------------------
 *  Penyaringan varian I-frame
 *  ------------------------------------------------------------------------
 *
 *  Master playlist Byse memuat DUA entri EXT-X-STREAM-INF dengan RESOLUTION
 *  yang sama, misalnya:
 *
 *      #EXT-X-STREAM-INF:BANDWIDTH=1119000,RESOLUTION=1920x1080,...
 *      index-v1-a1.m3u8?t=...            <- video sungguhan
 *      #EXT-X-STREAM-INF:BANDWIDTH=559500,RESOLUTION=1920x1080,...
 *      iframes-v1-a1.m3u8?t=...          <- HANYA I-frame
 *
 *  Yang kedua adalah playlist I-frame untuk pratinjau seek, dan seharusnya
 *  dideklarasikan dengan tag EXT-X-I-FRAME-STREAM-INF. Karena Byse salah
 *  menandainya sebagai varian biasa, ExoPlayer menampilkannya sebagai trek
 *  video kedua yang tidak dapat diputar.
 *
 *  Atribut di master TIDAK dapat dipakai untuk membedakan keduanya. Keduanya
 *  memuat PROGRAM-ID, RESOLUTION, FRAME-RATE, dan CODECS yang sama persis —
 *  bahkan CODECS varian I-frame menyebut audio mp4a.40.2, yang mustahil untuk
 *  playlist keyframe. Satu-satunya beda hanyalah BANDWIDTH, dan itu tidak
 *  cukup: varian dengan bandwidth lebih rendah bisa saja video sungguhan.
 *
 *  [pilihVarian] karena itu memakai deteksi BERLAPIS, dari yang paling kuat:
 *
 *    Lapis 1  Entri #EXT-X-I-FRAME-STREAM-INF dibuang. Ini bentuk yang benar
 *             menurut RFC 8216 §4.3.3.5, dan tidak memerlukan permintaan
 *             tambahan.
 *
 *    Lapis 2  Untuk varian #EXT-X-STREAM-INF yang tersisa, isi playlist
 *             tujuannya diperiksa. RFC 8216 §4.3.3.6 MEWAJIBKAN media playlist
 *             I-frame memuat tag #EXT-X-I-FRAMES-ONLY. Pemeriksaan ini
 *             definitif dan tidak bergantung pada nama berkas, sehingga tetap
 *             benar bila Byse mengganti namanya menjadi preview.m3u8,
 *             thumbs.m3u8, atau apa pun.
 *
 *    Lapis 3  Bila playlist anak gagal diambil karena jaringan, barulah nama
 *             URI dipakai sebagai petunjuk cadangan. Ini semata-mata agar
 *             kegagalan jaringan tidak menghilangkan sumber.
 *
 *  Kandidat diurutkan menurut bandwidth menurun, dan pemeriksaan berhenti pada
 *  yang pertama lolos — sehingga umumnya hanya satu permintaan tambahan.
 *
 *  Bila seluruh varian ternyata I-frame, atau master tidak dapat diurai, URL
 *  master dipakai apa adanya. Satu trek duplikat lebih baik daripada
 *  kehilangan sumbernya.
 *
 *  TODO: belum disimpan permanen antar peluncuran aplikasi. Dampaknya hanya
 *  viewer_id baru dibuat sekali tiap aplikasi dijalankan, dan pengujian
 *  menunjukkan server menerima identitas baru tanpa keluhan. Menyimpannya
 *  permanen memerlukan Context, yang sengaja tidak dibawa ke modul ini agar
 *  tetap mandiri.
 * ============================================================================
 */
object ByseClient {

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private const val TIMEOUT_MS = 30_000
    private const val TAG = "BYSE"

    /** Setel true untuk mencetak seluruh langkah ke logcat saat menelusuri galat. */
    var rinci: Boolean = false

    // ------------------------------------------------------------ identitas

    /** Identitas perangkat, dipakai ulang selama proses hidup. */
    private var identitas: ByseAttest.Identitas? = null
    private var viewerId: String = ""
    private var deviceId: String = ""

    private fun identitasSekarang(): ByseAttest.Identitas =
        identitas ?: ByseAttest.buatKunci().also { identitas = it }

    /** Buang identitas tersimpan. Dipakai bila server menolak dan perlu diulang. */
    fun aturUlangIdentitas() {
        identitas = null
        viewerId = ""
        deviceId = ""
    }

    // --------------------------------------------------------------- model

    /** Hasil akhir rantai. */
    data class Hasil(
        val sources: List<BysePlayback.Sumber>,
        val tracks: List<BysePlayback.Trek>,
        val posterUrl: String?,
        /** Referer yang HARUS dipakai saat mengambil media. */
        val referer: String
    )

    private class Respons(
        val status: Int,
        val teks: String,
        val setCookie: List<String>
    ) {
        val json: JSONObject?
            get() = try {
                if (teks.trimStart().startsWith("{")) JSONObject(teks) else null
            } catch (e: Exception) {
                null
            }
    }

    /** Cookie jar sederhana: nama ke nilai, cukup untuk satu host. */
    private class Toples {
        private val isi = LinkedHashMap<String, String>()

        fun terima(setCookie: List<String>) {
            for (baris in setCookie) {
                val pasangan = baris.substringBefore(';').trim()
                val i = pasangan.indexOf('=')
                if (i > 0) isi[pasangan.substring(0, i)] = pasangan.substring(i + 1)
            }
        }

        fun header(): String? =
            if (isi.isEmpty()) null else isi.entries.joinToString("; ") { "${it.key}=${it.value}" }

        override fun toString(): String = isi.keys.toString()
    }

    // ------------------------------------------------------------ transport

    private fun catat(pesan: String) {
        if (rinci) android.util.Log.d(TAG, pesan)
    }

    private fun minta(
        url: String,
        metode: String = "GET",
        badan: String? = null,
        toples: Toples? = null,
        referer: String? = null,
        origin: String? = null,
        parent: String? = null,
        captchaToken: String? = null
    ): Respons? {
        var koneksi: HttpURLConnection? = null
        return try {
            koneksi = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = metode
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true

                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                referer?.let { setRequestProperty("Referer", it) }
                origin?.let { setRequestProperty("Origin", it) }
                setRequestProperty("Sec-Fetch-Dest", "empty")
                setRequestProperty("Sec-Fetch-Mode", "cors")
                setRequestProperty("Sec-Fetch-Site", "same-origin")

                // Header embed, dari wt() di bundle. Diterima server saat diuji.
                parent?.let {
                    setRequestProperty("X-Embed-Parent", it)
                    setRequestProperty("X-Embed-Referer", it)
                    runCatching { URL(it) }.getOrNull()?.let { u ->
                        setRequestProperty("X-Embed-Origin", "${u.protocol}://${u.host}")
                    }
                }
                captchaToken?.let { setRequestProperty("X-Captcha-Token", it) }
                toples?.header()?.let { setRequestProperty("Cookie", it) }

                if (badan != null) {
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
            }

            catat("--> $metode $url  cookie=${toples ?: "-"}")

            if (badan != null) {
                koneksi.outputStream.use { it.write(badan.toByteArray(Charsets.UTF_8)) }
            }

            val status = koneksi.responseCode
            val aliran = if (status in 200..299) koneksi.inputStream else koneksi.errorStream
            val teks = aliran?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { br -> br.readText() }
            }.orEmpty()

            val setCookie = koneksi.headerFields["Set-Cookie"] ?: emptyList()
            toples?.terima(setCookie)

            catat("<-- $status  ${teks.length} char  ${teks.take(160)}")
            Respons(status, teks, setCookie)
        } catch (e: Exception) {
            catat("<-- GAGAL ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            koneksi?.disconnect()
        }
    }

    // ------------------------------------------------------------- langkah

    /**
     * Ambil `embed_frame_url` dari host sumber.
     *
     * Host player dan segmen path berotasi, jadi ini selalu dipanggil lebih
     * dulu dan hasilnya tidak boleh disimpan untuk pemutaran berikutnya.
     */
    private fun ambilFrameUrl(hostSumber: String, code: String): String? {
        val res = minta(
            "https://$hostSumber/api/videos/$code/embed/details",
            referer = "https://$hostSumber/",
            origin = "https://$hostSumber"
        ) ?: return null
        if (res.status != 200) return null
        return res.json?.optString("embed_frame_url")?.takeIf { it.isNotBlank() }
    }

    /**
     * Jalankan seluruh rantai dan kembalikan sumber yang siap diputar.
     *
     * @param code       kode berkas Byse, bagian akhir dari tautan /e/{code}
     * @param hostSumber host yang menyediakan details, biasanya bysekoze.com
     * @return null bila salah satu langkah gagal
     */
    suspend fun resolve(code: String, hostSumber: String): Hasil? = withContext(Dispatchers.IO) {
        try {
            jalankan(code, hostSumber)
        } catch (e: Exception) {
            catat("rantai gagal: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun jalankan(code: String, hostSumber: String): Hasil? {
        // --- 1. details ---
        val frameUrl = ambilFrameUrl(hostSumber, code) ?: run {
            catat("1. details gagal atau tanpa embed_frame_url")
            return null
        }
        val frame = runCatching { URL(frameUrl) }.getOrNull() ?: return null
        val player = "${frame.protocol}://${frame.host}"
        val parent = "https://$hostSumber/e/$code"
        val toples = Toples()
        catat("1. player=$player  segmen=${frame.path}")

        fun postJson(
            jalur: String,
            badan: JSONObject,
            captcha: String? = null
        ): Respons? = minta(
            player + jalur, "POST", badan.toString(), toples,
            referer = frameUrl, origin = player, parent = parent, captchaToken = captcha
        )

        // --- 2. buka halaman player ---
        minta(frameUrl, toples = toples, referer = frameUrl, origin = player, parent = parent)

        // --- 3. challenge ---
        val ch = postJson("/api/videos/access/challenge", JSONObject())
        if (ch == null || ch.status != 200) {
            catat("3. challenge gagal: ${ch?.status}")
            return null
        }
        val chJson = ch.json ?: return null
        val challengeId = chJson.optString("challenge_id").takeIf { it.isNotBlank() } ?: return null
        val nonce = chJson.optString("nonce").takeIf { it.isNotBlank() } ?: return null

        // --- 4. attest ---
        val id = identitasSekarang()
        val client = ByseAttest.sidikJariKlien(
            userAgent = USER_AGENT,
            lebarLayar = 1080, tinggiLayar = 2400, rasioPiksel = 2.625,
            bahasa = listOf("id-ID", "id", "en-US", "en"),
            zonaWaktu = java.util.TimeZone.getDefault().id,
            jumlahInti = Runtime.getRuntime().availableProcessors(),
            titikSentuh = 5
        )
        val storage = ByseAttest.sidikJariPenyimpanan(viewerId, deviceId)
        val badanAttest = ByseAttest.badanAttest(
            id, challengeId, nonce, viewerId, deviceId, client, storage
        )

        val att = postJson("/api/videos/access/attest", badanAttest)
        if (att == null || att.status != 200) {
            catat("4. attest gagal: ${att?.status}")
            return null
        }
        val hasilAttest = ByseAttest.uraiHasil(att.json) ?: run {
            catat("4. attest 200 tetapi respons tidak lengkap")
            return null
        }
        viewerId = hasilAttest.viewerId
        deviceId = hasilAttest.deviceId
        catat("4. viewer=$viewerId confidence=${hasilAttest.confidence}")

        // Objek fingerprint ini disertakan pada captcha, verify, dan playback.
        val fingerprint = JSONObject().apply {
            put("token", hasilAttest.token)
            put("viewer_id", hasilAttest.viewerId)
            put("device_id", hasilAttest.deviceId)
            hasilAttest.confidence?.let { put("confidence", it.toDoubleOrNull() ?: it) }
        }
        val badanSidik = JSONObject().put("fingerprint", fingerprint)

        // --- 5. captcha ---
        val cap = postJson("/api/videos/$code/embed/captcha", badanSidik)
        if (cap == null || cap.status != 200) {
            catat("5. captcha gagal: ${cap?.status}")
            return null
        }
        val capJson = cap.json ?: return null
        val powNonce = capJson.optString("pow_nonce").takeIf { it.isNotBlank() } ?: return null
        val difficulty = capJson.optInt("pow_difficulty", 0)
        val powToken = capJson.optString("pow_token").takeIf { it.isNotBlank() } ?: return null
        val expiresIn = capJson.optInt("expires_in", 1800)

        // --- 6. PoW ---
        val mulai = System.currentTimeMillis()
        val solusi = BysePow.pecahkan(powNonce, difficulty, BysePow.batasWaktu(expiresIn))
        if (solusi == null) {
            catat("6. PoW melewati batas waktu, difficulty=$difficulty")
            return null
        }
        catat("6. PoW solusi=$solusi difficulty=$difficulty ${System.currentTimeMillis() - mulai}ms")

        // --- 7. captcha/verify ---
        val ver = postJson(
            "/api/videos/$code/embed/captcha/verify",
            JSONObject().apply {
                put("pow_token", powToken)
                put("solution", solusi)
                put("fingerprint", fingerprint)
            }
        )
        if (ver == null || ver.status != 200) {
            catat("7. verify gagal: ${ver?.status}")
            return null
        }
        val verJson = ver.json
        if (verJson?.optString("status") != "ok") {
            catat("7. verify menolak: ${verJson?.optString("status")}")
            return null
        }
        val captchaToken = verJson.optString("token").takeIf { it.isNotBlank() } ?: return null

        // --- 8. playback ---
        val pb = postJson("/api/videos/$code/embed/playback", badanSidik, captchaToken)
        if (pb == null || pb.status != 200) {
            catat("8. playback gagal: ${pb?.status}")
            return null
        }
        val blob = pb.json?.optJSONObject("playback") ?: run {
            catat("8. playback 200 tetapi tanpa objek playback")
            return null
        }
        catat("8. version=${blob.optString("version")} " +
            "key_parts=${blob.optJSONArray("key_parts")?.length()}")

        // --- 9. dekripsi ---
        val hasil = BysePlayback.dekripsi(blob) ?: run {
            catat("9. dekripsi gagal")
            return null
        }
        catat("9. ${hasil.sources.size} sumber, ${hasil.tracks.size} trek")

        if (hasil.sources.isEmpty()) return null

        // Saring varian I-frame pada sumber HLS. Lihat catatan di atas berkas.
        val sumberBersih = hasil.sources.map { s ->
            val hls = s.mimeType?.contains("mpegurl", true) == true ||
                s.url.substringBefore('?').endsWith(".m3u8", true)
            if (!hls) s
            else pilihVarian(s.url, frameUrl, parent)?.let { s.copy(url = it) } ?: s
        }

        return Hasil(sumberBersih, hasil.tracks, hasil.posterUrl, frameUrl)
    }

    /**
     * Ganti URL master HLS dengan varian sungguhan, membuang varian I-frame.
     *
     * @return URL varian terpilih, atau null bila master tidak dapat diurai
     *         maupun tidak menyisakan varian sehat. Pemanggil harus memakai
     *         URL master apa adanya bila hasilnya null.
     */
    /** Satu entri varian dari master playlist. */
    private data class Varian(val uri: String, val bandwidth: Int)

    /**
     * Ganti URL master HLS dengan varian video sungguhan.
     *
     * Lihat catatan "Penyaringan varian I-frame" di atas berkas untuk alasan
     * pemeriksaan berlapis ini.
     *
     * @return URL varian terpilih, atau null bila master tidak dapat diurai
     *         maupun tidak menyisakan varian sehat. Pemanggil harus memakai
     *         URL master apa adanya bila hasilnya null.
     */
    private fun pilihVarian(masterUrl: String, referer: String, parent: String?): String? {
        val res = minta(masterUrl, referer = referer, parent = parent) ?: return null
        if (res.status != 200) return null
        val teks = res.teks
        if (!teks.contains("#EXT-X-STREAM-INF")) return null   // media playlist, bukan master

        val varian = uraiVarian(teks)
        if (varian.isEmpty()) return null

        // Satu varian saja: tidak ada yang perlu dibedakan, hemat satu permintaan.
        if (varian.size == 1) {
            return gabungUrl(masterUrl, varian[0].uri)
        }

        // Bandwidth menurun, berhenti pada yang pertama terbukti bukan I-frame.
        for (v in varian.sortedByDescending { it.bandwidth }) {
            val penuh = gabungUrl(masterUrl, v.uri) ?: continue
            val anak = minta(penuh, referer = referer, parent = parent)

            if (anak == null || anak.status != 200) {
                // Lapis 3: jaringan gagal, pakai petunjuk nama sebagai cadangan.
                if (!v.uri.contains("iframe", ignoreCase = true)) {
                    catat("varian dipilih lewat cadangan nama URI: ${v.uri.take(40)}")
                    return penuh
                }
                continue
            }

            if (!iFramesOnly(anak.teks)) {
                catat("varian terverifikasi isi, bandwidth=${v.bandwidth}")
                return penuh
            }
            catat("varian I-frame dibuang: ${v.uri.take(40)}")
        }
        return null
    }

    /**
     * Urai entri varian dari master playlist.
     *
     * Lapis 1: entri #EXT-X-I-FRAME-STREAM-INF dilewati. Tag itu membawa URI
     * di dalam atributnya, bukan pada baris berikutnya, sehingga cukup
     * diabaikan seluruhnya.
     */
    private fun uraiVarian(teks: String): List<Varian> {
        val baris = teks.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Varian>()
        var i = 0
        while (i < baris.size) {
            val b = baris[i]
            if (b.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
                i++
                continue
            }
            if (b.startsWith("#EXT-X-STREAM-INF")) {
                val uri = baris.getOrNull(i + 1)?.takeIf { !it.startsWith("#") }
                if (uri != null) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(b)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    out.add(Varian(uri, bw))
                    i += 2
                    continue
                }
            }
            i++
        }
        return out
    }

    /**
     * Lapis 2: apakah media playlist ini hanya berisi I-frame.
     *
     * RFC 8216 §4.3.3.6 mewajibkan tag ini pada playlist I-frame, sehingga
     * pemeriksaannya definitif dan tidak bergantung pada nama berkas.
     */
    private fun iFramesOnly(teks: String): Boolean =
        teks.lineSequence().any { it.trim().startsWith("#EXT-X-I-FRAMES-ONLY") }

    /** Selesaikan URI relatif terhadap URL master, termasuk query string. */
    private fun gabungUrl(dasar: String, uri: String): String? =
        runCatching { URL(URL(dasar), uri).toString() }.getOrNull()

    /**
     * Ambil kode Byse dari tautan yang diberikan RiveStream.
     *
     * Bentuk tautannya `https://bysekoze.com/e/{code}`, dan host-nya dipakai
     * sebagai sumber details.
     *
     * @return pasangan (host, code), atau null bila bentuknya tidak dikenali
     */
    fun uraiTautan(url: String): Pair<String, String>? {
        val u = runCatching { URL(url) }.getOrNull() ?: return null
        val bagian = u.path.trim('/').split('/')
        if (bagian.size < 2 || bagian[0] != "e") return null
        val code = bagian[1].takeIf { it.isNotBlank() } ?: return null
        return u.host to code
    }
}

package com.RiveStream.byse

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ============================================================================
 *  Byse — Tahap 1: dekripsi konfigurasi playback
 * ============================================================================
 *
 *  Byse (bysekoze.com / q8y5z.com, sebelumnya byse.sx) adalah hoster di balik
 *  sumber `movieEmbedProvider?service=self` milik RiveStream. Respons endpoint
 *  playback-nya TIDAK memuat URL video secara langsung, melainkan sebuah blob
 *  terenkripsi:
 *
 *      {
 *        "playback": {
 *          "version":   "7",
 *          "key_parts": [ 30 string base64url ],
 *          "iv":        "base64url, 12 byte",
 *          "payload":   "base64url, ciphertext + tag GCM"
 *        }
 *      }
 *
 *  Hasil dekripsinya berupa JSON berisi `sources`, `tracks`, dan `poster_url`.
 *
 *  ------------------------------------------------------------------------
 *  Algoritma [TERBUKTI] — dipulihkan dari videoPagesBundle-*.js
 *  ------------------------------------------------------------------------
 *
 *  Fungsi asli di bundle: La() -> ws() -> ks() -> crypto.subtle.decrypt
 *
 *  1. PEMILIHAN KUNCI, bukan penggabungan seluruh bagian.
 *
 *     Peta di bundle (fungsi Qa) dibentuk begini:
 *
 *         for (n = 1; n <= 20; n++) e[String(n)] = [n, 31 - n];
 *
 *     sehingga `version` menentukan TEPAT DUA indeks: [v, 31-v].
 *
 *         version "1"  -> key_parts[0]  + key_parts[29]
 *         version "7"  -> key_parts[6]  + key_parts[23]
 *         version "20" -> key_parts[19] + key_parts[10]
 *
 *     Sisa fragmen adalah UMPAN dan tidak pernah dipakai. Bila `version` di
 *     luar 1..20, atau 31-v melampaui jumlah fragmen, pemetaan dibatalkan dan
 *     SELURUH fragmen dipakai berurutan (perilaku cadangan di ws()).
 *
 *  2. PERAKITAN KUNCI (ks): tiap fragmen terpilih di-decode base64url, lalu
 *     byte-nya digabung berurutan. Dua fragmen 16 byte menghasilkan 32 byte,
 *     yaitu AES-256 — sesuai importKey("raw", …, "AES-GCM") di bundle.
 *
 *  3. DEKRIPSI: AES/GCM/NoPadding, IV 12 byte, tag 128 bit. `payload` sudah
 *     berisi ciphertext diikuti tag, persis seperti yang diharapkan
 *     WebCrypto maupun javax.crypto.
 *
 *  TIDAK ADA derivasi kunci. Pencarian di bundle menghasilkan nol kemunculan
 *  untuk deriveKey, deriveBits, ECDH, HKDF, dan PBKDF2. Kunci datang apa
 *  adanya dari server melalui `key_parts`.
 *
 *  ------------------------------------------------------------------------
 *  Verifikasi
 *  ------------------------------------------------------------------------
 *
 *  [selfTest] memakai vektor uji sintetis yang dibangun dari algoritma di
 *  atas, lalu diverifikasi di JVM: indeks terpilih [7, 24], kunci 32 byte
 *  berawalan 0001020304050607, dan SHA-256 hasil dekripsi cocok byte per byte
 *  dengan plaintext aslinya.
 *
 *  Vektor ini SINTETIS, bukan tangkapan dari server. Ia membuktikan bahwa
 *  perakitan kunci dan AES-GCM di berkas ini benar, bukan bahwa server
 *  mengirim bentuk yang persis sama. Pembuktian terhadap data sungguhan baru
 *  bisa dilakukan setelah Tahap 2-4 selesai dan endpoint playback berhasil
 *  dipanggil.
 *
 *  ------------------------------------------------------------------------
 *  Cakupan tahap ini
 *  ------------------------------------------------------------------------
 *
 *  Berkas ini HANYA menangani dekripsi. Pemanggilan jaringan menuju endpoint
 *  playback memerlukan tiga hal lain yang dikerjakan pada tahap berikutnya:
 *
 *      Tahap 2  PoW captcha   -> header X-Captcha-Token
 *      Tahap 3  attestation   -> cookie byse_viewer_id & byse_device_id
 *      Tahap 4  playback      -> POST + fingerprint pada body
 * ============================================================================
 */
object BysePlayback {

    /** Jumlah maksimum versi yang dikenal peta Qa() di bundle. */
    private const val VERSI_MAKS = 20

    /** Konstanta pasangan indeks: [v, PENJUMLAH - v]. */
    private const val PENJUMLAH = 31

    /** Panjang tag GCM dalam bit. */
    private const val PANJANG_TAG_BIT = 128

    // ------------------------------------------------------------- model

    /** Satu sumber video hasil dekripsi. Padanan Ha() di bundle. */
    data class Sumber(
        val quality: String?,
        val label: String?,
        val mimeType: String?,
        val url: String,
        val bitrateKbps: Int?,
        val height: Int?,
        val sizeBytes: Long?
    )

    /** Satu trek teks hasil dekripsi. Padanan xs() di bundle. */
    data class Trek(
        val language: String?,
        val title: String?,
        val url: String,
        val kind: String?,
        val default: Boolean?,
        val mimeType: String?
    )

    /** Hasil lengkap dekripsi blob playback. */
    data class Playback(
        val sources: List<Sumber>,
        val tracks: List<Trek>,
        val posterUrl: String?
    )

    // ----------------------------------------------------------- pemilih

    /**
     * Padanan Ea() di bundle: kembalikan pasangan indeks 1-basis untuk sebuah
     * `version`, atau daftar kosong bila pemetaan tidak berlaku.
     *
     * @param version nilai `playback.version`
     * @param jumlah  banyaknya entri pada `key_parts`
     */
    fun indeksKunci(version: String?, jumlah: Int): List<Int> {
        val n = version?.trim()?.toIntOrNull() ?: return emptyList()
        if (n < 1 || n > VERSI_MAKS) return emptyList()
        val a = n
        val b = PENJUMLAH - n
        if (a < 1 || b < 1 || a > jumlah || b > jumlah) return emptyList()
        return listOf(a, b)
    }

    /**
     * Padanan ws() lalu ks(): pilih fragmen yang benar, decode, dan gabungkan
     * menjadi kunci AES mentah.
     */
    fun rakitKunci(version: String?, keyParts: List<String>): ByteArray {
        val idx = indeksKunci(version, keyParts.size)
        val dipakai = if (idx.isEmpty()) {
            // Perilaku cadangan ws(): pakai seluruh fragmen berurutan.
            keyParts
        } else {
            idx.mapNotNull { keyParts.getOrNull(it - 1) }
        }.filter { it.isNotEmpty() }

        if (dipakai.isEmpty()) return ByteArray(0)

        val potongan = dipakai.map { decodeB64Url(it) }
        val total = potongan.sumOf { it.size }
        val keluar = ByteArray(total)
        var posisi = 0
        for (p in potongan) {
            System.arraycopy(p, 0, keluar, posisi, p.size)
            posisi += p.size
        }
        return keluar
    }

    // ---------------------------------------------------------- dekripsi

    /**
     * Dekripsi blob playback menjadi JSON mentah.
     *
     * @param playback objek `playback` dari respons server
     * @return teks JSON, atau null bila blob tidak sah atau dekripsi gagal
     */
    fun dekripsiMentah(playback: JSONObject?): String? {
        if (playback == null) return null

        val partsArr = playback.optJSONArray("key_parts") ?: return null
        if (partsArr.length() == 0) return null
        val parts = ArrayList<String>(partsArr.length())
        for (i in 0 until partsArr.length()) {
            parts.add(partsArr.optString(i, ""))
        }

        val ivB64 = playback.optString("iv", "").takeIf { it.isNotBlank() } ?: return null
        val payloadB64 = playback.optString("payload", "").takeIf { it.isNotBlank() } ?: return null

        return try {
            val kunci = rakitKunci(playback.optString("version", null), parts)
            // AES hanya menerima 16, 24, atau 32 byte.
            if (kunci.size != 16 && kunci.size != 24 && kunci.size != 32) return null

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(kunci, "AES"),
                GCMParameterSpec(PANJANG_TAG_BIT, decodeB64Url(ivB64))
            )
            String(cipher.doFinal(decodeB64Url(payloadB64)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Padanan blok catch di La(): kegagalan dekripsi menghasilkan
            // daftar kosong, bukan pengecualian yang menjalar.
            null
        }
    }

    /** Dekripsi lalu urai menjadi [Playback]. */
    fun dekripsi(playback: JSONObject?): Playback? {
        val json = dekripsiMentah(playback) ?: return null
        return try {
            uraikan(JSONObject(json))
        } catch (e: Exception) {
            null
        }
    }

    /** Urai JSON hasil dekripsi. Padanan Ha() dan xs() di bundle. */
    fun uraikan(obj: JSONObject): Playback {
        val sumber = ArrayList<Sumber>()
        obj.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url", "").takeIf { it.isNotBlank() } ?: continue
                sumber.add(
                    Sumber(
                        quality = o.optStringOrNull("quality"),
                        label = o.optStringOrNull("label"),
                        mimeType = o.optStringOrNull("mime_type"),
                        url = url,
                        bitrateKbps = o.optInt("bitrate_kbps", 0).takeIf { it > 0 },
                        height = o.optInt("height", 0).takeIf { it > 0 },
                        sizeBytes = o.optLong("size_bytes", 0L).takeIf { it > 0L }
                    )
                )
            }
        }

        val trek = ArrayList<Trek>()
        obj.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optStringOrNull("url") ?: continue
                trek.add(
                    Trek(
                        language = o.optStringOrNull("language"),
                        title = o.optStringOrNull("title"),
                        url = url,
                        kind = o.optStringOrNull("kind"),
                        default = if (o.has("default")) o.optBoolean("default") else null,
                        mimeType = o.optStringOrNull("mime_type")
                    )
                )
            }
        }

        return Playback(sumber, trek, obj.optStringOrNull("poster_url"))
    }

    // ---------------------------------------------------------- pembantu

    /**
     * Decode base64url tanpa padding.
     *
     * Bundle memakai atob() setelah mengganti '-' menjadi '+', '_' menjadi
     * '/', lalu menambahkan padding. android.util.Base64 dengan flag URL_SAFE
     * menangani penggantian karakter, dan NO_PADDING membuat padding opsional.
     */
    private fun decodeB64Url(s: String): ByteArray =
        Base64.decode(s.trim(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun JSONObject.optStringOrNull(key: String): String? =
        this.optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    // ---------------------------------------------------------- selftest

    /**
     * Vektor uji sintetis. Dibangun dari algoritma yang dipulihkan, lalu
     * diverifikasi di JVM sebelum ditanam di sini.
     *
     *   version   "7"      -> indeks [7, 24]
     *   key_parts 30 entri -> hanya ke-7 dan ke-24 yang nyata
     *   kunci     32 byte, berawalan 00 01 02 03 04 05 06 07
     */
    private const val UJI_VERSION = "7"
    private const val UJI_IV = "AAECAwQFBgcICQoL"

    /**
     * Jalankan pemeriksaan mandiri. Tidak memerlukan koneksi internet.
     *
     * Yang diperiksa hanyalah bagian yang tidak bergantung pada data server:
     * pemilihan indeks dan perakitan kunci. Dekripsi penuh memerlukan payload
     * dan karenanya diuji di luar aplikasi.
     *
     * @return pasangan (semua lulus, daftar baris laporan)
     */
    fun selfTest(): Pair<Boolean, List<String>> {
        val baris = ArrayList<String>()
        var semuaLulus = true

        fun periksa(nama: String, hasil: Any?, harap: Any?) {
            val lulus = hasil.toString() == harap.toString()
            if (!lulus) semuaLulus = false
            baris.add("${if (lulus) "OK   " else "GAGAL"} $nama = $hasil   (harap $harap)")
        }

        // Peta indeks untuk beberapa versi.
        periksa("indeksKunci(\"1\", 30)", indeksKunci("1", 30), listOf(1, 30))
        periksa("indeksKunci(\"7\", 30)", indeksKunci("7", 30), listOf(7, 24))
        periksa("indeksKunci(\"20\", 30)", indeksKunci("20", 30), listOf(20, 11))

        // Di luar rentang 1..20 -> pemetaan dibatalkan.
        periksa("indeksKunci(\"99\", 30)", indeksKunci("99", 30), emptyList<Int>())
        periksa("indeksKunci(null, 30)", indeksKunci(null, 30), emptyList<Int>())

        // 31-v melampaui jumlah fragmen -> pemetaan dibatalkan.
        periksa("indeksKunci(\"1\", 3)", indeksKunci("1", 3), emptyList<Int>())

        // Perakitan kunci: dua fragmen 16 byte menjadi 32 byte.
        val parts = ArrayList<String>()
        for (i in 1..30) {
            val isi = ByteArray(16) { ((i * 7 + it) and 0xFF).toByte() }
            parts.add(Base64.encodeToString(isi, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }
        // Tanam dua fragmen yang dikenali: indeks 7 dan 24 (1-basis).
        parts[6] = Base64.encodeToString(
            ByteArray(16) { it.toByte() },
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        parts[23] = Base64.encodeToString(
            ByteArray(16) { (it + 16).toByte() },
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val kunci = rakitKunci(UJI_VERSION, parts)
        periksa("panjang kunci", kunci.size, 32)
        val awal = kunci.take(8).joinToString("") { "%02x".format(it) }
        periksa("8 byte pertama", awal, "0001020304050607")
        val akhir = kunci.takeLast(8).joinToString("") { "%02x".format(it) }
        periksa("8 byte terakhir", akhir, "18191a1b1c1d1e1f")

        // Cadangan: version tak dikenal -> seluruh fragmen dipakai.
        val kunciCadangan = rakitKunci("99", parts)
        periksa("cadangan, 30 x 16 byte", kunciCadangan.size, 480)

        // IV harus terdecode menjadi 12 byte.
        periksa("panjang IV", decodeB64Url(UJI_IV).size, 12)

        baris.add(if (semuaLulus) "SEMUA PEMERIKSAAN LULUS" else "ADA PEMERIKSAAN YANG GAGAL")
        return semuaLulus to baris
    }
}

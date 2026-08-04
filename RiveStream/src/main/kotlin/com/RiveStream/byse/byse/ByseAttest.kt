package com.RiveStream.byse

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec

/**
 * ============================================================================
 *  Byse — Tahap 3: device attestation
 * ============================================================================
 *
 *  Endpoint playback hanya menjawab bila klien membawa cookie
 *  `byse_viewer_id` dan `byse_device_id`. Keduanya diperoleh lewat dua
 *  permintaan:
 *
 *      POST /api/videos/access/challenge  -> {challenge_id, nonce}
 *      POST /api/videos/access/attest     -> {viewer_id, device_id, token,
 *                                             confidence, expires_at}
 *
 *  Berkas ini menyiapkan kunci, tanda tangan, dan badan permintaan `attest`.
 *  Pemanggilan jaringannya sendiri dikerjakan pada Tahap 4.
 *
 *  ------------------------------------------------------------------------
 *  Asal-usul kode [TERBUKTI] — pow-DEJGtdh2.js
 *  ------------------------------------------------------------------------
 *
 *      Mt(t)  -> URL: `/api/videos/access${t}`
 *      Nn()   -> POST /challenge, credentials:"include"
 *      Cn(t)  -> POST /attest, Content-Type json, credentials:"include"
 *      Wn()   -> [buatKunci]  generateKey ECDSA P-256, ekspor JWK, .sign()
 *      Jn()   -> muat kunci tersimpan, bila tidak ada panggil Wn()
 *      ar(t,e)-> [tandaTangani] t.sign(e)
 *      sr()   -> [sidikJariKlien]  objek `client`
 *      cr()   -> [sidikJariPenyimpanan] objek `storage`
 *      zn(t)  -> [nilaiEntropy]  klasifikasi "low" / "medium" / "high"
 *      fr()   -> perakit seluruh badan permintaan attest
 *
 *  Badan permintaan attest, verbatim dari fr():
 *
 *      { viewer_id, device_id, challenge_id, nonce, signature,
 *        public_key, client, storage, attributes: { entropy } }
 *
 *  ------------------------------------------------------------------------
 *  DUA PERBEDAAN PENTING ANTARA WebCrypto DAN JCA
 *  ------------------------------------------------------------------------
 *
 *  1. FORMAT TANDA TANGAN.
 *     WebCrypto `crypto.subtle.sign({name:"ECDSA", hash:"SHA-256"}, ...)`
 *     menghasilkan r‖s mentah sepanjang 64 byte. JCA `SHA256withECDSA`
 *     menghasilkan DER SEQUENCE{INTEGER r, INTEGER s} sepanjang 70-72 byte.
 *     Server mengharapkan bentuk WebCrypto, jadi keluaran JCA HARUS dikonversi
 *     lewat [derKeRaw]. Melewatkan langkah ini membuat attestation ditolak
 *     tanpa pesan galat yang jelas.
 *
 *  2. P-256 vs secp256r1.
 *     Keduanya kurva yang sama, hanya beda penamaan. WebCrypto menyebutnya
 *     "P-256", JCA menyebutnya "secp256r1".
 *
 *  Keduanya sudah diverifikasi di JVM: tanda tangan JCA yang dikonversi ke
 *  raw, lalu dikembalikan ke DER, tetap lolos verifikasi. Kunci publik yang
 *  direkonstruksi dari JWK x/y juga berhasil memverifikasi — persis langkah
 *  yang dilakukan server.
 *
 *  ------------------------------------------------------------------------
 *  Soal sidik jari
 *  ------------------------------------------------------------------------
 *
 *  zn() mengklasifikasi kualitas sidik jari, dan hasilnya "low" bila peramban
 *  bukan Chromium — Firefox pun demikian. Artinya server MENERIMA klien
 *  ber-entropy rendah, hanya dengan nilai `confidence` yang lebih kecil.
 *
 *  Karena itu berkas ini TIDAK memalsukan canvas hash, WebGL, daftar font,
 *  atau audio hash. Yang dikirim hanya field yang benar-benar dapat diketahui
 *  sebuah aplikasi Android, dan `entropy` diisi "low" secara jujur.
 *
 *  TODO: belum diketahui apakah server menolak `confidence` rendah untuk
 *  endpoint playback. Kode frontend hanya meneruskan nilainya tanpa
 *  memeriksanya. Baru bisa dipastikan setelah Tahap 4 dijalankan terhadap
 *  server sungguhan.
 * ============================================================================
 */
object ByseAttest {

    /** Nama kurva versi JCA. WebCrypto menyebutnya "P-256". */
    private const val KURVA = "secp256r1"

    /** Panjang komponen r dan s untuk P-256. */
    private const val PANJANG_KOMPONEN = 32

    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    // ------------------------------------------------------------- model

    /** Pasangan kunci beserta JWK publiknya. Padanan hasil Wn(). */
    data class Identitas(
        val keyPair: KeyPair,
        val publicJwk: JSONObject,
        /** PKCS#8 privat, untuk disimpan agar identitas bertahan antar sesi. */
        val privateKeyPkcs8: ByteArray
    )

    /** Hasil dari POST /access/attest. */
    data class HasilAttest(
        val viewerId: String,
        val deviceId: String,
        val token: String,
        val confidence: String?,
        val expiresAt: String?
    )

    // ------------------------------------------------------------- kunci

    /**
     * Wn() — buat pasangan kunci ECDSA P-256 baru.
     *
     * Padanan `crypto.subtle.generateKey({name:"ECDSA", namedCurve:"P-256"},
     * true, ["sign","verify"])` diikuti `exportKey("jwk", publicKey)`.
     */
    fun buatKunci(): Identitas {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(KURVA))
        val kp = kpg.generateKeyPair()
        return Identitas(kp, jwkPublik(kp), kp.private.encoded)
    }

    /**
     * Muat kembali identitas dari PKCS#8 yang pernah disimpan.
     *
     * Padanan Jn(): peramban menyimpan kuncinya di IndexedDB agar identitas
     * perangkat bertahan. Plugin perlu melakukan hal setara lewat penyimpanan
     * miliknya sendiri, supaya viewer_id dan device_id tidak berganti tiap
     * pemutaran.
     */
    fun muatKunci(pkcs8: ByteArray, jwkPublikTersimpan: JSONObject?): Identitas? {
        return try {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            // Kunci publik direkonstruksi dari JWK yang ikut disimpan.
            val jwk = jwkPublikTersimpan ?: return null
            val x = BigInteger(1, Base64.decode(jwk.optString("x"), B64))
            val y = BigInteger(1, Base64.decode(jwk.optString("y"), B64))
            val spec = parameterKurva()
            val pub = kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
            Identitas(KeyPair(pub, priv), jwk, pkcs8)
        } catch (e: Exception) {
            null
        }
    }

    private fun parameterKurva(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec(KURVA))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    /** Ekspor kunci publik sebagai JWK, sama bentuknya dengan exportKey("jwk"). */
    fun jwkPublik(kp: KeyPair): JSONObject {
        val pub = kp.public as ECPublicKey
        val x = keByteTetap(pub.w.affineX, PANJANG_KOMPONEN)
        val y = keByteTetap(pub.w.affineY, PANJANG_KOMPONEN)
        return JSONObject().apply {
            put("crv", "P-256")
            put("ext", true)
            put("key_ops", JSONArray().put("verify"))
            put("kty", "EC")
            put("x", Base64.encodeToString(x, B64))
            put("y", Base64.encodeToString(y, B64))
        }
    }

    // ------------------------------------------------------ tanda tangan

    /**
     * ar(t, e) — tandatangani teks dengan ECDSA/SHA-256, keluaran base64url.
     *
     * Bundle memakai `new TextEncoder().encode(d)`, yaitu UTF-8. Hasil
     * WebCrypto berupa r‖s mentah, sehingga keluaran DER dari JCA dikonversi
     * lebih dulu.
     */
    fun tandaTangani(identitas: Identitas, teks: String): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(identitas.keyPair.private)
        sig.update(teks.toByteArray(Charsets.UTF_8))
        val raw = derKeRaw(sig.sign(), PANJANG_KOMPONEN)
        return Base64.encodeToString(raw, B64)
    }

    /**
     * Ubah tanda tangan DER milik JCA menjadi r‖s mentah seperti WebCrypto.
     *
     * DER: 30 <len> 02 <lenR> <r> 02 <lenS> <s>
     * Komponen bisa memiliki byte 0x00 di depan (penanda bilangan positif)
     * atau lebih pendek dari 32 byte, sehingga keduanya dinormalkan.
     */
    fun derKeRaw(der: ByteArray, panjangKomponen: Int): ByteArray {
        var i = 0
        require(der[i++] == 0x30.toByte()) { "tanda tangan DER tidak sah: bukan SEQUENCE" }
        val len = der[i++].toInt() and 0xFF
        if (len and 0x80 != 0) i += len and 0x7F      // bentuk panjang
        require(der[i++] == 0x02.toByte()) { "tanda tangan DER tidak sah: r bukan INTEGER" }
        val rLen = der[i++].toInt() and 0xFF
        val r = BigInteger(1, der.copyOfRange(i, i + rLen))
        i += rLen
        require(der[i++] == 0x02.toByte()) { "tanda tangan DER tidak sah: s bukan INTEGER" }
        val sLen = der[i++].toInt() and 0xFF
        val s = BigInteger(1, der.copyOfRange(i, i + sLen))

        val keluar = ByteArray(panjangKomponen * 2)
        keByteTetap(r, panjangKomponen).copyInto(keluar, 0)
        keByteTetap(s, panjangKomponen).copyInto(keluar, panjangKomponen)
        return keluar
    }

    /** Kebalikan [derKeRaw]. Dipakai selfTest untuk memverifikasi ulang. */
    fun rawKeDer(raw: ByteArray): ByteArray {
        val n = raw.size / 2
        val r = BigInteger(1, raw.copyOfRange(0, n)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(n, raw.size)).toByteArray()
        val total = 2 + r.size + 2 + s.size
        val keluar = ByteArray(2 + total)
        var i = 0
        keluar[i++] = 0x30; keluar[i++] = total.toByte()
        keluar[i++] = 0x02; keluar[i++] = r.size.toByte()
        r.copyInto(keluar, i); i += r.size
        keluar[i++] = 0x02; keluar[i++] = s.size.toByte()
        s.copyInto(keluar, i)
        return keluar
    }

    private fun keByteTetap(v: BigInteger, n: Int): ByteArray {
        val b = v.toByteArray()
        if (b.size == n) return b
        val keluar = ByteArray(n)
        if (b.size > n) {
            b.copyInto(keluar, 0, b.size - n, b.size)   // buang 0x00 di depan
        } else {
            b.copyInto(keluar, n - b.size)              // beri bantalan nol
        }
        return keluar
    }

    // -------------------------------------------------------- sidik jari

    /**
     * sr() — objek `client`.
     *
     * Hanya field yang benar-benar dapat diketahui sebuah aplikasi Android
     * yang diisi. Field khas peramban — canvas_hash, audio_hash, fonts_hash,
     * webgl_params_hash, codecs_hash, media_devices — SENGAJA DIKOSONGKAN,
     * bukan dipalsukan, karena nilai palsu tidak membuat entropy naik dan
     * hanya menambah risiko ditolak.
     *
     * @param userAgent  User-Agent yang juga dipakai untuk permintaan HTTP
     * @param lebarLayar dan seterusnya: ambil dari Resources bila tersedia
     */
    fun sidikJariKlien(
        userAgent: String,
        lebarLayar: Int? = null,
        tinggiLayar: Int? = null,
        rasioPiksel: Double? = null,
        bahasa: List<String>? = null,
        zonaWaktu: String? = null,
        jumlahInti: Int? = null,
        titikSentuh: Int? = null
    ): JSONObject = JSONObject().apply {
        put("user_agent", userAgent)
        lebarLayar?.let { put("screen_width", it) }
        tinggiLayar?.let { put("screen_height", it) }
        rasioPiksel?.let { put("pixel_ratio", it) }
        bahasa?.takeIf { it.isNotEmpty() }?.let { l ->
            put("languages", JSONArray().apply { l.forEach { put(it) } })
        }
        zonaWaktu?.let { put("timezone", it) }
        jumlahInti?.takeIf { it > 0 }?.let { put("hardware_concurrency", it) }
        titikSentuh?.let { put("touch_points", it) }
        put("pointer_type", "coarse")     // layar sentuh
    }

    /**
     * cr() — objek `storage`.
     *
     * Peramban melaporkan id yang tersimpan di cookie, localStorage,
     * IndexedDB, dan cacheStorage. Plugin hanya punya satu penyimpanan, jadi
     * yang diisi adalah `indexed_db` bila id sebelumnya sudah ada.
     */
    fun sidikJariPenyimpanan(viewerId: String?, deviceId: String?): JSONObject =
        JSONObject().apply {
            if (!viewerId.isNullOrBlank() || !deviceId.isNullOrBlank()) {
                put("indexed_db", "${viewerId.orEmpty()}:${deviceId.orEmpty()}")
            }
        }

    /**
     * zn(t) — klasifikasi kualitas sidik jari.
     *
     * Logika asli: bila Firefox ATAU tidak ada navigator.userAgentData, hasil
     * langsung "low". Aplikasi Android tidak punya userAgentData, sehingga
     * cabang pertama itulah yang berlaku dan hasilnya SELALU "low".
     *
     * Fungsi ini tetap ditulis lengkap agar tetap benar bila suatu saat kita
     * mengisi lebih banyak field.
     */
    fun nilaiEntropy(client: JSONObject, punyaUserAgentData: Boolean = false): String {
        val ua = client.optString("user_agent", "")
        val firefox = Regex("firefox", RegexOption.IGNORE_CASE).containsMatchIn(ua) &&
            !Regex("seamonkey", RegexOption.IGNORE_CASE).containsMatchIn(ua)
        if (firefox || !punyaUserAgentData) return "low"

        val sinyal = listOf(
            client.optString("canvas_hash", "").isNotEmpty(),
            client.optString("audio_hash", "").isNotEmpty(),
            client.optInt("hardware_concurrency", 0) > 0,
            client.optDouble("device_memory", 0.0) > 0,
            client.optString("pointer_type", "").isNotEmpty(),
            client.optString("fonts_hash", "").isNotEmpty(),
            client.optString("webgl_renderer", "").isNotEmpty() ||
                client.optString("webgl_params_hash", "").isNotEmpty()
        ).count { it }

        val merek = client.optJSONArray("brand_full_versions")
        return if (sinyal < 3 || merek == null || merek.length() == 0) "medium" else "high"
    }

    // ------------------------------------------------------------- badan

    /**
     * fr() — rakit badan permintaan untuk POST /api/videos/access/attest.
     *
     * @param challengeId dari respons /challenge
     * @param nonce       dari respons /challenge
     * @param viewerId    id sebelumnya bila ada, string kosong bila belum
     * @param deviceId    id sebelumnya bila ada, string kosong bila belum
     */
    fun badanAttest(
        identitas: Identitas,
        challengeId: String,
        nonce: String,
        viewerId: String = "",
        deviceId: String = "",
        client: JSONObject,
        storage: JSONObject
    ): JSONObject = JSONObject().apply {
        put("viewer_id", viewerId)
        put("device_id", deviceId)
        put("challenge_id", challengeId)
        put("nonce", nonce)
        put("signature", tandaTangani(identitas, nonce))
        put("public_key", identitas.publicJwk)
        put("client", client)
        put("storage", storage)
        put("attributes", JSONObject().put("entropy", nilaiEntropy(client)))
    }

    /** Urai respons /attest. */
    fun uraiHasil(obj: JSONObject?): HasilAttest? {
        if (obj == null) return null
        val viewer = obj.optString("viewer_id", "").takeIf { it.isNotBlank() } ?: return null
        val device = obj.optString("device_id", "").takeIf { it.isNotBlank() } ?: return null
        val token = obj.optString("token", "").takeIf { it.isNotBlank() } ?: return null
        return HasilAttest(
            viewerId = viewer,
            deviceId = device,
            token = token,
            confidence = obj.optString("confidence", "").takeIf { it.isNotBlank() },
            expiresAt = obj.optString("expires_at", "").takeIf { it.isNotBlank() }
        )
    }

    // ---------------------------------------------------------- selftest

    /**
     * Pemeriksaan mandiri. Tidak memerlukan koneksi internet.
     *
     * Kunci ECDSA bersifat acak, sehingga vektor uji tetap tidak mungkin.
     * Yang diperiksa adalah SIFAT yang harus selalu benar: panjang komponen,
     * bentuk JWK, panjang tanda tangan, dan yang terpenting — bahwa tanda
     * tangan hasil konversi masih dapat diverifikasi memakai kunci publik
     * yang direkonstruksi dari JWK, persis seperti yang dilakukan server.
     */
    fun selfTest(): Pair<Boolean, List<String>> {
        val baris = ArrayList<String>()
        var semuaLulus = true

        fun periksa(nama: String, lulus: Boolean, ket: String = "") {
            if (!lulus) semuaLulus = false
            baris.add("${if (lulus) "OK   " else "GAGAL"} $nama${if (ket.isEmpty()) "" else "  ($ket)"}")
        }

        try {
            val id = buatKunci()
            val jwk = id.publicJwk

            periksa("kty = EC", jwk.optString("kty") == "EC")
            periksa("crv = P-256", jwk.optString("crv") == "P-256")
            val x = Base64.decode(jwk.optString("x"), B64)
            val y = Base64.decode(jwk.optString("y"), B64)
            periksa("panjang x = 32", x.size == 32, "dapat ${x.size}")
            periksa("panjang y = 32", y.size == 32, "dapat ${y.size}")

            val nonce = "00eb392276f05f3ea8e6054f10414002"
            val ttB64 = tandaTangani(id, nonce)
            val raw = Base64.decode(ttB64, B64)
            periksa("tanda tangan 64 byte", raw.size == 64, "dapat ${raw.size}")
            periksa("base64url tanpa padding", !ttB64.contains('=') &&
                !ttB64.contains('+') && !ttB64.contains('/'))

            // Verifikasi memakai kunci publik yang direkonstruksi dari JWK.
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(
                ECPublicKeySpec(
                    ECPoint(BigInteger(1, x), BigInteger(1, y)),
                    parameterKurva()
                )
            )
            val ver = Signature.getInstance("SHA256withECDSA")
            ver.initVerify(pub)
            ver.update(nonce.toByteArray(Charsets.UTF_8))
            periksa("verifikasi lewat JWK x/y", ver.verify(rawKeDer(raw)))

            // Muat ulang kunci dari PKCS#8 dan pastikan masih bisa menandatangani.
            val id2 = muatKunci(id.privateKeyPkcs8, jwk)
            periksa("muat ulang dari PKCS#8", id2 != null)
            if (id2 != null) {
                val ver2 = Signature.getInstance("SHA256withECDSA")
                ver2.initVerify(pub)
                ver2.update(nonce.toByteArray(Charsets.UTF_8))
                periksa(
                    "tanda tangan setelah dimuat ulang",
                    ver2.verify(rawKeDer(Base64.decode(tandaTangani(id2, nonce), B64)))
                )
            }

            // Entropy: aplikasi Android tanpa userAgentData selalu "low".
            val client = sidikJariKlien(
                userAgent = "Mozilla/5.0 (Linux; Android 13) Chrome/124.0.0.0 Mobile Safari/537.36",
                lebarLayar = 1080, tinggiLayar = 2400, jumlahInti = 8
            )
            periksa("entropy = low", nilaiEntropy(client) == "low", nilaiEntropy(client))

            // Badan attest memuat seluruh field yang diminta fr().
            val badan = badanAttest(
                id, "uji-challenge", nonce, "", "",
                client, sidikJariPenyimpanan(null, null)
            )
            val wajib = listOf(
                "viewer_id", "device_id", "challenge_id", "nonce",
                "signature", "public_key", "client", "storage", "attributes"
            )
            val hilang = wajib.filter { !badan.has(it) }
            periksa("9 field badan attest lengkap", hilang.isEmpty(),
                if (hilang.isEmpty()) "" else "hilang: $hilang")
            periksa(
                "attributes.entropy terisi",
                badan.optJSONObject("attributes")?.optString("entropy") == "low"
            )
        } catch (e: Exception) {
            semuaLulus = false
            baris.add("GAGAL pengecualian: ${e.javaClass.simpleName}: ${e.message}")
        }

        baris.add(if (semuaLulus) "SEMUA PEMERIKSAAN LULUS" else "ADA PEMERIKSAAN YANG GAGAL")
        return semuaLulus to baris
    }
}

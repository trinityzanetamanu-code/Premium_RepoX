package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException

/** Mapper Jackson Kotlin tunggal untuk decoding JSON nested Map/List. */
private val abyssJson: JsonMapper = JsonMapper.builder()
    .addModule(kotlinModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .build()

/**
 * 1. Emturbovid Extractor (TurboVIP)
 * Ekstraksi varian kualitas HLS kini didelegasikan ke M3u8Helper.generateM3u8 —
 * mekanisme standar core untuk mengurai master playlist (menggantikan parsing
 * manual baris #EXT-X-STREAM-INF yang rapuh terhadap edge case URL relatif).
 */
open class EmturbovidExtractor : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            val firstRequest = app.get(url, headers = headers, allowRedirects = false)
            var finalUrl = url

            if (firstRequest.code == 301 || firstRequest.code == 302) {
                val location = firstRequest.headers["Location"] ?: firstRequest.headers["location"]
                if (!location.isNullOrBlank()) {
                    finalUrl = location
                }
            }

            val response = app.get(finalUrl, headers = headers)
            val html = response.text
            val document = response.document

            val masterUrl = document.select("div#video_player").attr("data-hash").trim()
                .takeIf { it.isNotBlank() }
                ?: Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+)['"]""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim()

            if (masterUrl.isNullOrBlank()) return

            /* ── UJI SATU VARIABEL: samakan header dengan browser ────────────
             *
             * DASAR BUKTI — turbovip.har, 26 Jul 2026, 28 request segmen:
             *
             *   Playlist media g280.turbosplayer.com berisi 1877 segmen,
             *   SEMUANYA di https://lh3.googleusercontent.com/d/{id}=d
             *   (Google Drive, disamarkan content-type image/png,
             *    content-disposition attachment;filename="file_N.png").
             *
             *   Header yang browser kirim ke setiap segmen Drive:
             *       Referer    : ''          <- ADA tapi KOSONG
             *       User-Agent : Mozilla/5.0 …
             *       Origin     : TIDAK DIKIRIM SAMA SEKALI
             *
             *   Header yang kita kirim sebelumnya ke URL yang sama:
             *       Origin  : https://emturbovid.com
             *       Referer : https://emturbovid.com/
             *
             * Keduanya ikut ke segmen karena generateM3u8 menyalin `headers`
             * ke tiap ExtractorLink dan menaruh `referer` di link.referer;
             * CS3IPlayer meneruskan keduanya ke seluruh request, termasuk
             * pengambilan segmen. Google Drive menyajikan halaman perantara
             * alih-alih berkas untuk request yang dianggap tidak wajar, dan
             * badan HTML seperti itu persis memicu error 3001 di TsExtractor
             * (byte pertama '<', bukan sync byte 0x47).
             *
             * BELUM TERBUKTI — ini uji, bukan perbaikan yang dipastikan.
             * Semua 28 segmen di HAR berstatus 200, jadi kuota Drive BUKAN
             * penyebabnya (hipotesis itu sudah digugurkan).
             */
            val browserLikeHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
            )

            var links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = masterUrl,
                referer = "",              // browser mengirim Referer kosong
                headers = browserLikeHeaders
            )
            Log.i("Emturbovid", "UJI header-browser: ${links.size} link")

            /* JARING PENGAMAN.
             *
             * Ke master playlist (g*.turbosplayer.com) browser justru MENGIRIM
             * `origin: https://turbovidhls.com`. generateM3u8 memakai satu peta
             * header yang sama untuk master dan segmen, jadi keduanya tidak bisa
             * dicocokkan sekaligus. Kalau menghapus Origin membuat pengambilan
             * master ditolak, kita kembali ke perilaku lama yang sudah terbukti
             * menghasilkan link — supaya kegagalan uji ini tidak lebih buruk
             * daripada keadaan sekarang.
             */
            if (links.isEmpty()) {
                Log.w("Emturbovid", "Tanpa Origin gagal, kembali ke header lama")
                links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = masterUrl,
                    referer = "$mainUrl/",
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                        "Origin" to mainUrl
                    )
                )
            }

            links.forEach(callback)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Emturbovid", "Ekstraksi gagal: ${e.message}")
        }
    }
}

/**
 * 4. Abyss / Hydrax Extractor
 * [FIX #8] Migrasi dari endpoint lama `/api/player/v2` (404) ke endpoint baru
 * `/info/{slug}` (200) yang mengembalikan JSON dengan field `media` terenkripsi.
 *
 * Skema dekripsi (dideobfuscasi dari `iamcdn.net/player-v2/core.bundle.js`):
 *  1. `secret = "${user_id}:${slug}:${md5_id}"`
 *  2. `keyHex = MD5(secret)` → 32 hex chars
 *  3. `keyBytes = UTF-8(keyHex)` → 32 bytes
 *  4. `iv = keyBytes[0..16)` → 16 bytes (AES-CTR IV)
 *  5. `aesKey = keyBytes` → 32 bytes (AES-256-CTR)
 *  6. `plaintext = AES-256-CTR-decrypt(iv, aesKey, media)` → JSON sumber video
 *
 * Respons terdekripsi berisi:
 *  - `mp4.sources[]` daftar kualitas (`label`, `path`, `url`, `size`, `codec`)
 *  - `mp4.domains[]` daftar subdomain CDN
 *  - `mp4.fristDatas[]` chunk pertama yang sudah di-prefetch
 *
 * URL playable yang dikembalikan ke CloudStream adalah `fristDatas[].url`
 * (chunk pertama 4-8MB yang sudah ter-cache). File utama di GCS dienkripsi
 * dengan skema berbeda yang ditangani oleh JWPlayer HLS di browser, sehingga
 * untuk plugin ini kita fallback ke chunk pertama.
 */
open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
    override val requiresReferer = true

    /** Representasi satu sumber video dari JSON terdekripsi. */
    private data class AbyssSource(
        @JsonProperty("label") val label: String?,
        @JsonProperty("res_id") val resId: Int? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("codec") val codec: String? = null,
        @JsonProperty("path") val path: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("sub") val sub: String? = null,
        @JsonProperty("partSize") val partSize: Long? = null,
        @JsonProperty("status") val status: Boolean? = null
    )

    /** fristData: chunk pre-fetched pertama untuk playback instan. */
    private data class AbyssFirstData(
        @JsonProperty("res_id") val resId: Int? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("codec") val codec: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("partSize") val partSize: Long? = null
    )

    private data class AbyssMp4(
        @JsonProperty("sources") val sources: List<AbyssSource>? = null,
        @JsonProperty("domains") val domains: List<String>? = null,
        @JsonProperty("fristDatas") val fristDatas: List<AbyssFirstData>? = null
    )

    /** Respons mentah dari /info/{slug} (sebelum dekripsi). */
    private data class AbyssInfoResponse(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("md5_id") val md5Id: Long? = null,
        @JsonProperty("user_id") val userId: Long? = null,
        @JsonProperty("media") val media: String? = null,
        @JsonProperty("mp4") val mp4: AbyssMp4? = null
    )

    /**
     * Dekripsi field `media` (string biner) menjadi JSON string.
     * Algoritma: MD5(user_id:slug:md5_id) → 32-char hex → UTF-8 bytes sebagai
     * kunci AES-256-CTR, dengan 16 byte pertama sebagai IV.
     */
    private fun decryptMedia(userId: Long, slug: String, md5Id: Long, media: String): String? {
        return try {
            // 1. Bentuk secret & MD5-hash → hex string
            val secret = "$userId:$slug:$md5Id"
            val md = MessageDigest.getInstance("MD5")
            val keyHex = md.digest(secret.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            // 2. UTF-8 encode hex string sebagai key bytes
            val keyBytes = keyHex.toByteArray(Charsets.UTF_8)
            require(keyBytes.size == 32) { "Key harus 32 byte UTF-8 dari MD5 hex, dapat ${keyBytes.size}" }

            // 3. IV = 16 byte pertama; key = 32 byte (AES-256)
            val iv = keyBytes.copyOfRange(0, 16)

            // 4. Konversi media (string biner) ke byte array
            val cipherBytes = ByteArray(media.length) { i -> (media[i].code and 0xFF).toByte() }

            // 5. AES-256-CTR decrypt
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(iv)
            )
            val plain = cipher.doFinal(cipherBytes)
            String(plain, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("Abyss", "DECRYPT gagal: ${e.message}")
            null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val host = URI(url).host.ifBlank { URI("https://$mainUrl/").host }
            val fallbackReferer = "$mainUrl/"

            val browserHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: fallbackReferer),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            // [FIX #8] Ambil slug dari URL `?v=...` ATAU dari path `/embed/...`
            // — lebih robust terhadap perubahan struktur halaman embed
            val slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.getOrNull(1)?.trim()
                ?: Regex("/([A-Za-z0-9_-]{7,17})(?:\\?|$)").find(url)?.groupValues?.getOrNull(1)?.trim()
                ?: run {
                    Log.w("Abyss", "DIAG BERHENTI: slug kosong dari URL $url")
                    return
                }
            Log.i("Abyss", "DIAG slug=$slug")

            // [FIX #8] Endpoint baru: GET /info/{slug} di host halaman embed.
            // Header WAJIB: x-client-screen + x-referer (diperlukan oleh Cloudflare
            // untuk melewati bot check — lihat debug core.bundle.js).
            val apiUrl = "https://$host/info/$slug"
            val apiRaw = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Referer" to url,
                    "Origin" to "https://$host",
                    "x-client-screen" to "1920x1080",
                    "x-referer" to url
                )
            )

            Log.i("Abyss", "DIAG API url=$apiUrl code=${apiRaw.code} len=${apiRaw.text.length}")
            if (apiRaw.code !in 200..299) {
                Log.w("Abyss", "DIAG API body[0] ${apiRaw.text.take(200)}")
                return
            }

            val infoResp = apiRaw.parsedSafe<AbyssInfoResponse>()
            val userId = infoResp?.userId
            val md5Id = infoResp?.md5Id
            val encryptedMedia = infoResp?.media

            Log.i("Abyss", "DIAG parsed userId=$userId md5Id=$md5Id mediaLen=${encryptedMedia?.length ?: 0} title=${infoResp?.title}")

            if (userId == null || md5Id == null || encryptedMedia.isNullOrBlank()) {
                Log.w("Abyss", "DIAG field wajib kosong — kemungkinan struktur JSON berubah")
                return
            }

            // [FIX #8] Dekripsi media → JSON string
            val decrypted = decryptMedia(userId, slug, md5Id, encryptedMedia)
            if (decrypted.isNullOrBlank()) {
                Log.w("Abyss", "DIAG DECRYPT menghasilkan string kosong")
                return
            }
            val mp4 = runCatching { abyssJson.readValue(decrypted, AbyssMp4::class.java) }
                .onFailure { Log.e("Abyss", "DIAG parse JSON decrypted gagal: ${it.message}") }
                .getOrNull()
            if (mp4 == null) {
                Log.w("Abyss", "DIAG decrypted JSON tidak bisa di-parse, cuplikan: ${decrypted.take(200)}")
                return
            }

            Log.i("Abyss", "DIAG decrypted sources=${mp4.sources?.size ?: 0} fristDatas=${mp4.fristDatas?.size ?: 0} domains=${mp4.domains?.size ?: 0}")

            // [FIX #8] Pilih URL playable:
            // 1) Prefers fristData (chunk pre-fetch, biasanya tanpa proteksi GCS)
            // 2) Fallback ke source.url + source.path
            val firstDataByResId = mp4.fristDatas.orEmpty().associateBy { it.resId }

            mp4.sources.orEmpty().forEach { source ->
                val resId = source.resId
                val labelText = source.label ?: "Unknown"
                val videoUrl = firstDataByResId[resId]?.url
                    ?: source.path?.let { p ->
                        val base = source.url
                            ?: mp4.domains?.firstOrNull()?.let { "https://$it" }
                            ?: "https://$host"
                        base.trimEnd('/') + "/" + p.trimStart('/')
                    }

                if (!videoUrl.isNullOrBlank()) {
                    val isHls = videoUrl.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $labelText",
                            url = videoUrl,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://$host/"
                            this.quality = getQualityFromName(labelText)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Abyss", "Ekstraksi gagal: ${e.message}")
        }
    }
}

/**
 * 4b. (Dihapus) Alias domain mirror Abyss (abyssplayer.com).
 * Tidak lagi didaftarkan — keluarga host Abyss di-handle via routing eksplisit
 * di `OppaDramaProvider.dispatchEmbed()` yang langsung instantiate
 * `AbyssExtractor()`. Plugin ini hanya daftarkan 3 server valid sesuai
 * hasil debug: TurboVIP, Hydrax, FileLions.
 */

/**
 * 5. Minochinos / VidHide Obfuscated Extractor
 */
class MinochinosExtractor : ExtractorApi() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            val unpackedHtml = getAndUnpack(html)

            val streamUrl = Regex("""https?://[^\s"'`<>]+?\.m3u8[^\s"'`<>]*""")
                .find(unpackedHtml)?.value
                ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                    .find(unpackedHtml)?.groupValues?.getOrNull(1)

            if (!streamUrl.isNullOrBlank()) {
                val isM3u8 = streamUrl.contains(".m3u8")

                // DIKEMBALIKAN ke bentuk semula.
                //
                // Bentuk inilah yang TERBUKTI memutar di logcat 26 Jul 10:47:28
                // (decoder RUNNING, lalu IsPlaying pada 10:47:30) — meskipun saat
                // itu CDN sedang mengembalikan 429. Tidak ada request tambahan ke
                // CDN sebelum player menyentuh URL-nya.
                //
                // Dua percobaan sebelumnya (probe resolusi, lalu pemecahan varian)
                // sama-sama dipasang tanpa bukti runtime dan sama-sama dicabut.
                // Jangan ubah blok ini lagi tanpa logcat pembanding.
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Minochinos", "Ekstraksi gagal: ${e.message}")
        }
    }


}

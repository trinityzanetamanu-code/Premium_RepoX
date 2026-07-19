package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URI
import android.util.Base64

/**
 * 1. EarnVids / Smoothpre Extractor
 * Alias backend extractor: smoothpre.com menggunakan arsitektur yang sama dengan vidhidepro.com.
 */
class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

/**
 * 2. BuzzServer Extractor (Local Plugin Overrider)
 *
 * AKAR MASALAH TERBUKTI (level protokol, bukan dugaan soal perubahan situs):
 * `hx-redirect` adalah mekanisme htmx: server HANYA mengirim header `HX-Redirect`
 * kalau request dikenali sebagai request yang diinisiasi htmx, yaitu saat client
 * mengirim header `HX-Request: true`. Tanpa header ini, server pada umumnya
 * memperlakukan request sebagai navigasi browser biasa (redirect HTTP normal via
 * `Location`, atau render ulang halaman penuh) - BUKAN mengirim `HX-Redirect`.
 *
 * Implementasi sebelumnya TIDAK PERNAH mengirim `HX-Request: true` pada request
 * `/download`, jadi bergantung sepenuhnya pada apakah server/CDN kebetulan tetap
 * mengirim header itu meski tanpa diminta - inilah kemungkinan besar penyebab
 * sifat "kadang jalan, kadang tidak" yang sebenarnya, bukan soal subdomain/startsWith
 * seperti dugaan saya di analisa sebelumnya (dicabut karena tidak terbukti).
 */
class BuzzServer : ExtractorApi() {
    override val name = "BuzzServer"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            // Bersihkan URL dari silsilah parameter /download ganda jika terlempar dari core
            val cleanUrl = if (url.endsWith("/download")) url.substringBeforeLast("/download") else url
            val host = URI(cleanUrl).let { "${it.scheme}://${it.host}" }

            val page = app.get(cleanUrl, referer = referer)
            // FIX: `documentLarge` tidak ditemukan bukti keberadaannya di ExtractorApi.kt/
            // MainAPI.kt manapun (sudah di-grep menyeluruh). Ganti ke Jsoup.parse(page.text),
            // satu-satunya pola parsing HTML yang terbukti valid & konsisten dipakai di
            // SELURUH file provider ini (OppaDramaProvider.kt memakainya di setiap fungsi).
            val pageDoc = Jsoup.parse(page.text)
            val qualityText = pageDoc.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)

            // FIX UTAMA: sertakan header htmx yang wajib ada (HX-Request: true) supaya
            // server benar-benar mengenali ini sebagai request htmx dan membalas dengan
            // header HX-Redirect, bukan redirect HTTP biasa/render ulang halaman.
            val response = app.get(
                "$cleanUrl/download",
                referer = cleanUrl,
                headers = mapOf(
                    "HX-Request" to "true",
                    "HX-Current-URL" to cleanUrl
                ),
                allowRedirects = false,
            )

            Log.d("BuzzServer", "GET $cleanUrl/download -> code=${response.code}, headers=${response.headers}")

            val redirectUrl = response.headers["hx-redirect"]
                ?: response.headers["location"]

            if (!redirectUrl.isNullOrBlank()) {
                // hx-redirect/Location dari buzzheavier.com kerap berupa PATH RELATIF
                // (mis. "/dl/abc123"), bukan URL absolut. Kalau langsung dipakai sebagai
                // `url` pada ExtractorLink tanpa di-prefix host, pemutar akan gagal karena
                // menerima path yang bukan URL valid.
                val finalUrl = if (redirectUrl.startsWith("http")) {
                    redirectUrl
                } else {
                    host + (if (redirectUrl.startsWith("/")) redirectUrl else "/$redirectUrl")
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "BuzzServer Direct",
                        url = finalUrl,
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/"
                    }
                )
            } else {
                Log.w(
                    "BuzzServer",
                    "Tidak ada header redirect (code=${response.code}). Kalau code=200 dan " +
                        "bukan 3xx, kemungkinan server tidak mengenali request ini sebagai " +
                        "request htmx meski sudah dikirim header HX-Request, atau endpoint " +
                        "/download sudah berubah - perlu dicek isi body respons langsung."
                )
            }
        } catch (e: Exception) {
            Log.e("BuzzServer", "Gagal resolve $url: ${e::class.simpleName} - ${e.message}")
        }
    }
}

/**
 * 3. Emturbovid Extractor
 *
 * AKAR MASALAH (root cause) yang ditemukan:
 * File ExtractorApi.kt inti Cloudstream versi terbaru TERNYATA sudah memiliki
 * `com.lagradost.cloudstream3.extractors.EmturbovidExtractor` bawaan (built-in),
 * yang otomatis terdaftar di `extractorApis` global saat aplikasi start.
 *
 * Plugin ini mendaftarkan LAGI kelas `EmturbovidExtractor` versi lokal (nama sama,
 * package beda: com.OppaDrama) lewat `registerExtractorAPI(EmturbovidExtractor())`.
 * Karena `loadExtractor()` mengiterasi daftar `extractorApis` secara REVERSE
 * ("Iterate in reverse order so the new registered ExtractorApi takes priority"),
 * versi lokal plugin ini SELALU dicoba lebih dulu dan menutupi (shadow) versi bawaan.
 *
 * Ini masalah klasik: begitu tim Cloudstream memperbaiki extractor bawaan mereka
 * mengikuti perubahan situs emturbovid.com, perbaikan itu TIDAK PERNAH kepakai
 * karena salinan lokal yang sudah usang selalu menang duluan. Ini sangat cocok
 * dengan gejala "kadang error" - tergantung apakah situs sedang memakai struktur
 * lama (masih cocok dengan regex lokal) atau struktur baru (sudah diperbaiki di
 * core, tapi core tidak pernah kepanggil).
 *
 * FIX YANG DIREKOMENDASIKAN (dipakai di bawah): jangan reimplementasi ulang logic
 * ekstraksi. Ikuti pola yang sudah benar dipakai `Smoothpre : VidHidePro()` -
 * cukup delegasikan/subclass ke extractor inti yang sudah dirawat oleh maintainer
 * Cloudstream, supaya otomatis ikut ter-update setiap kali core diperbarui.
 *
 * Alternatif paling aman: HAPUS SAJA class ini + baris
 * `registerExtractorAPI(EmturbovidExtractor())` di Plugin.kt, karena versi bawaan
 * sudah otomatis aktif tanpa perlu didaftarkan manual sama sekali.
 */
class Emturbovid : com.lagradost.cloudstream3.extractors.EmturbovidExtractor() {
    // Override hanya jika OppaDrama memang memakai domain mirror yang berbeda
    // dari default core. Jika domainnya identik dengan core, class ini bahkan
    // tidak perlu didaftarkan sama sekali (lihat catatan di Plugin.kt).
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
}

/**
 * 4. Abyss / Hydrax Extractor
 * Mengurai kemurnian data Base64 "datas" dari player-v2 core bundle untuk mengambil otentikasi multi-token.
 *
 * AKAR MASALAH TERBUKTI (ditemukan lewat cross-reference langsung dengan MainAPI.kt/ExtractorApi.kt):
 *
 * Cloudstream versi ini sudah memakai kotlinx.serialization sebagai mesin utama untuk
 * `parsedSafe<T>()`. Bukti: SETIAP data class di MainAPI.kt yang dipakai untuk parsing
 * JSON (misalnya `AniSearch`, dipakai persis dengan pola `app.post(...).parsedSafe<T>()`
 * yang sama seperti di sini) SELALU diberi `@Serializable` di level class DAN `@SerialName`
 * berdampingan dengan `@JsonProperty` di tiap properti. Tidak ada satu pun kelas di kedua
 * file referensi yang hanya pakai `@JsonProperty` tanpa `@Serializable`.
 *
 * `AbyssSource`/`AbyssResponse` versi sebelumnya HANYA memakai `@JsonProperty` (Jackson)
 * tanpa `@Serializable`/`@SerialName` sama sekali. Tanpa `@Serializable`, kotlinx tidak
 * bisa membuat serializer untuk class ini, sehingga `.parsedSafe<AbyssResponse>()` gagal
 * total (baik sebagai compile error atau exception runtime yang jatuh ke `catch` tanpa
 * jejak). Ini match persis dengan gejala "Abyss tidak muncul sama sekali" - bukan soal API
 * situs berubah, tapi parsing-nya sendiri yang dari awal tidak pernah bisa jalan di versi
 * Cloudstream ini.
 */
class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
    override val requiresReferer = true

    @Serializable
    private data class AbyssSource(
        @JsonProperty("file") @SerialName("file") val file: String? = null,
        @JsonProperty("label") @SerialName("label") val label: String? = null,
        @JsonProperty("type") @SerialName("type") val type: String? = null
    )

    @Serializable
    private data class AbyssResponse(
        @JsonProperty("sources") @SerialName("sources") val sources: List<AbyssSource>? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "http://45.11.57.192/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            
            var slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.getOrNull(1)?.trim()
            var md5Id = ""
            var userId = ""

            if (!datasRaw.isNullOrBlank()) {
                val decodedDatas = String(Base64.decode(datasRaw, Base64.DEFAULT), Charsets.UTF_8)
                
                // Menggunakan format String escaping standar Kotlin untuk akurasi pembacaan Regex
                if (slug.isNullOrBlank()) {
                    slug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim()
                }
                md5Id = Regex("\"md5_id\"\\s*:\\s*\"?(\\d+)\"?").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
                userId = Regex("\"user_id\"\\s*:\\s*\"?(\\d+)\"?").find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) {
                slug = Regex("""(?:v|slug)\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)?.trim()
            }
            if (userId.isNullOrBlank()) {
                userId = Regex("""userID\s*:\s*["']?(\d+)["']?""").find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) return

            val host = URI(url).host
            val apiUrl = "https://$host/api/player/v2"

            val apiResponse = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                    "Referer" to url,
                    "Origin" to "https://$host",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf<String, String>(
                    "slug" to slug,
                    "md5_id" to md5Id,
                    "user_id" to userId
                )
            ).parsedSafe<AbyssResponse>()

            if (apiResponse == null) {
                // Kalau parsedSafe balik null, itu bisa berarti: (a) response API bukan JSON
                // valid/berubah struktur, ATAU (b) exception saat deserialisasi tertelan oleh
                // parsedSafe itu sendiri. Baris ini memastikan kasus ini TIDAK senyap lagi.
                Log.w("Abyss", "parsedSafe<AbyssResponse>() mengembalikan null untuk POST ke $apiUrl (slug=$slug)")
            } else if (apiResponse.sources.isNullOrEmpty()) {
                Log.w("Abyss", "Field 'sources' kosong/null pada respons player/v2 untuk $url")
            }

            apiResponse?.sources?.forEach { source ->
                val videoUrl = source.file
                if (!videoUrl.isNullOrBlank()) {
                    val labelText = source.label ?: "Unknown"
                    val isM3u8 = videoUrl.contains(".m3u8")
                    
                    val qualityValue = when {
                        labelText.contains("1080") -> Qualities.P1080.value
                        labelText.contains("720") -> Qualities.P720.value
                        labelText.contains("480") -> Qualities.P480.value
                        labelText.contains("360") -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $labelText",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://$host/"
                            this.quality = qualityValue
                        }
                    )
                }
            }
        } catch (e: Exception) {
            // Sebelumnya `catch (_: Exception) {}` - senyap total. Ini titik paling penting
            // untuk Abyss, karena exception dari parsedSafe<AbyssResponse>() (mis. kegagalan
            // serialisasi kalau class belum @Serializable) akan tertangkap justru di sini.
            Log.e("Abyss", "Gagal resolve $url: ${e::class.simpleName} - ${e.message}")
        }
    }
}

/**
 * 5. Minochinos / VidHide Obfuscated Extractor
 *
 * AKAR MASALAH (root cause) yang ditemukan:
 * Nama aslinya sendiri ("VidHide Obfuscated Extractor") mengindikasikan minochinos.com
 * kemungkinan adalah whitelabel/rebrand dari player VidHide - pola yang persis sama
 * dengan `Smoothpre` (rebrand vidhidepro.com) di file ini, yang bekerja dengan baik
 * karena men-subclass `VidHidePro` (core), BUKAN reimplementasi manual.
 *
 * Core Cloudstream sudah punya `com.lagradost.cloudstream3.extractors.VidhideExtractor`
 * yang dirawat aktif dan menangani berbagai varian obfuscation VidHide (bukan cuma satu
 * pola eval-packer). Implementasi lama di sini malah reimplementasi total dari nol
 * pakai regex manual yang hanya menutup sebagian pola.
 *
 * FIX: jadikan `VidhideExtractor` (core) sebagai jalur utama - persis pola Smoothpre.
 * Regex manual (termasuk getAndUnpack) DIPERTAHANKAN, tapi hanya sebagai fallback
 * defensif kalau core tidak menghasilkan link sama sekali (berjaga-jaga andai
 * minochinos.com punya variasi kecil dari VidHide generik).
 */
class MinochinosExtractor : com.lagradost.cloudstream3.extractors.VidhideExtractor() {
    override var name = "Minochinos"
    override var mainUrl = "https://minochinos.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        var foundAny = false

        try {
            super.getUrl(url, referer, subtitleCallback) { link ->
                foundAny = true
                callback(link)
            }
        } catch (e: Exception) {
            Log.w("Minochinos", "VidhideExtractor(core) melempar exception: ${e::class.simpleName} - ${e.message}")
        }

        if (foundAny) return

        Log.d("Minochinos", "VidhideExtractor(core) tidak menghasilkan link untuk $url, coba fallback regex manual")
        legacyRegexFallback(url, referer, callback)
    }

    private suspend fun legacyRegexFallback(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text

            // getAndUnpack() no-op jika tidak ada blok eval-packer; pakai html asli
            // sebagai ruang pencarian tambahan supaya tidak kehilangan kecocokan.
            val unpackedHtml = getAndUnpack(html)
            val searchSpaces = listOf(unpackedHtml, html).distinct()

            var streamUrl: String? = null
            for (space in searchSpaces) {
                streamUrl = Regex("""https?://[^\s"'`<>]+?\.m3u8[^\s"'`<>]*""").find(space)?.value
                    ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(space)?.groupValues?.getOrNull(1)
                    ?: Regex("""file\s*:\s*["'](https?://[^"']+)["']""").find(space)?.groupValues?.getOrNull(1)
                if (!streamUrl.isNullOrBlank()) break
            }

            if (!streamUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.w("Minochinos", "Fallback regex juga gagal untuk $url - kemungkinan struktur player benar-benar berubah, perlu HTML mentah untuk investigasi lanjut.")
            }
        } catch (e: Exception) {
            Log.e("Minochinos", "Fallback gagal resolve $url: ${e::class.simpleName} - ${e.message}")
        }
    }
}

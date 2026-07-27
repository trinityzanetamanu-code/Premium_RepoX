package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/*
 * CATATAN PEMBERSIHAN — 27 Jul 2026
 *
 * Kelas Smoothpre dan BuzzServer DIHAPUS dari berkas ini.
 *
 * Dasarnya HAR 26 Jul 2026 (halaman /movie-project-hail-mary-2026-web-line/):
 * situs hanya menyediakan TIGA server streaming di select.mirror —
 *
 *     TurboVIP   -> emturbovid.com/t/{id}      -> EmturbovidExtractor
 *     Hydrax     -> abyssplayer.com/?v={id}    -> AbyssPlayer
 *     FileLions  -> minochinos.com/v/{id}      -> MinochinosExtractor
 *
 * Seluruh isi div.dlbox adalah tautan UNDUHAN, bukan pemutar:
 *     buzzheavier.com, datanodes.to, vidhidepro.com/d/, fpgo.xyz/file/
 * dan dlbox sudah tidak lagi dibaca oleh parseEmbeds. Karena itu BuzzServer
 * tidak akan pernah terpanggil lagi.
 *
 * Smoothpre menunjuk smoothpre.com yang tidak muncul sama sekali di HAR, dan
 * CloudStream core sudah punya kelas Smoothpre() sendiri di daftar
 * extractorApis, sehingga versi plugin ini murni redundan.
 *
 * Kalau suatu saat situs menambahkan server streaming baru, tambahkan kelas
 * baru di sini DAN daftarkan di OppaDramaProviderPlugin.load().
 */

/**
 * 3. Emturbovid Extractor
 * Mengirim master playlist apa adanya sebagai SATU sumber. ExoPlayer yang
 * mengurai variannya, sehingga daftar kualitas muncul di menu "Video Trek"
 * alih-alih memenuhi daftar sumber dengan empat entri.
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

            /* ── SATU SUMBER SAJA ───────────────────────────────────────────
             *
             * masterUrl adalah master playlist multivarian (cdn*.turboviplay.com,
             * ~535 byte — bandingkan playlist media yang ~169 KB untuk film 156
             * menit). Mengirimkannya apa adanya membuat ExoPlayer sendiri yang
             * mengurai variannya, sehingga 1080p/720p/480p muncul di menu "Video
             * Trek" — persis seperti JWPlayer di browser, yang juga hanya diberi
             * satu URL ini (terbukti dari ping jwpltx: mu=…6a00485009c43.m3u8,
             * vh=1080 vw=1920).
             *
             * Sebelumnya generateM3u8 dipakai, dan ia menghasilkan EMPAT entri:
             * tiga varian g*.turbosplayer.com plus master itu sendiri, karena
             * `returnThis` dipatok true di dalam M3u8Helper:
             *
             *     if (parsed == null || !anyFound || returnThis) {
             *         if (parsed != null || TS_EXTENSION_REGEX...) { list += m3u8 }
             *     }
             *
             * Efek samping menguntungkan: extractor tidak lagi mengunduh master
             * sendiri, jadi satu request ke CDN berkurang.
             *
             * CATATAN — ini TIDAK memperbaiki error 3001. Uji 27 Jul 03:29
             * membuktikan header bukan penyebabnya: dengan header identik browser
             * (tanpa Origin, Referer kosong) pemutaran tetap gagal setelah 78
             * detik. Perubahan ini murni merapikan daftar sumber.
             */
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
                    )
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Emturbovid", "Ekstraksi gagal: ${e.message}")
        }
    }
}

/**
 * 4. Abyss / Hydrax Extractor
 * Mengurai data Base64 "datas" dari player-v2 core bundle untuk mengambil
 * otentikasi multi-token. Decoding memakai helper crossplatform core
 * (base64DecodeArray + decodeToString/UTF-8), BUKAN android.util.Base64
 * yang mengikat plugin ke platform Android.
 */
open class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
    override val requiresReferer = true

    private data class AbyssSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?
    )

    private data class AbyssResponse(
        @JsonProperty("sources") val sources: List<AbyssSource>?
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

            // [DIAG] Rekam respons halaman embed apa adanya
            val pageResp = app.get(url, headers = headers)
            val html = pageResp.text
            Log.i("Abyss", "DIAG halaman code=${pageResp.code} len=${html.length}")

            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)

            // [DIAG] Bila penanda hilang, cetak cuplikan supaya perubahan
            // struktur halaman bisa dilihat langsung, bukan ditebak.
            if (datasRaw.isNullOrBlank()) {
                Log.w("Abyss", "DIAG 'const datas' TIDAK DITEMUKAN. Cuplikan HTML:")
                html.take(1500).chunked(700).forEachIndexed { i, c ->
                    Log.w("Abyss", "DIAG html[$i] $c")
                }
            }

            var slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.getOrNull(1)?.trim()
            var md5Id = ""
            var userId = ""

            if (!datasRaw.isNullOrBlank()) {
                // Helper crossplatform core; decodeToString() = UTF-8
                val decodedDatas = base64DecodeArray(datasRaw).decodeToString()

                if (slug.isNullOrBlank()) {
                    slug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"")
                        .find(decodedDatas)?.groupValues?.getOrNull(1)?.trim()
                }
                md5Id = Regex("\"md5_id\"\\s*:\\s*\"?(\\d+)\"?")
                    .find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
                userId = Regex("\"user_id\"\\s*:\\s*\"?(\\d+)\"?")
                    .find(decodedDatas)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            if (slug.isNullOrBlank()) {
                slug = Regex("""(?:v|slug)\s*:\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim()
            }
            if (userId.isNullOrBlank()) {
                userId = Regex("""userID\s*:\s*["']?(\d+)["']?""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }

            // [DIAG] Titik keluar senyap #1
            Log.i("Abyss", "DIAG datasRaw=${if (datasRaw.isNullOrBlank()) "TIDAK ADA" else "ada(${datasRaw.length} char)"} | slug='$slug' | md5Id='$md5Id' | userId='$userId'")

            if (slug.isNullOrBlank()) {
                Log.w("Abyss", "DIAG BERHENTI: slug kosong")
                return
            }

            val host = URI(url).host
            val apiUrl = "https://$host/api/player/v2"

            val apiRaw = app.post(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                    "Referer" to url,
                    "Origin" to "https://$host",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf(
                    "slug" to slug,
                    "md5_id" to md5Id,
                    "user_id" to userId
                )
            )

            // [DIAG] Inilah yang menjawab: API menolak, atau struktur JSON berubah?
            Log.i("Abyss", "DIAG API url=$apiUrl code=${apiRaw.code} len=${apiRaw.text.length}")
            apiRaw.text.take(1400).chunked(700).forEachIndexed { i, c ->
                Log.i("Abyss", "DIAG API body[$i] $c")
            }

            val apiResponse = apiRaw.parsedSafe<AbyssResponse>()

            // [DIAG] Titik keluar senyap #2 — inilah yang paling mungkin terjadi:
            // parsedSafe mengembalikan null tanpa jejak, lalu forEach tidak pernah
            // berjalan sehingga getUrl selesai tanpa satu pun callback.
            Log.i("Abyss", "DIAG apiResponse=${if (apiResponse == null) "NULL (parse gagal)" else "ok"} | sources=${apiResponse?.sources?.size ?: -1}")

            apiResponse?.sources?.forEach { source ->
                val videoUrl = source.file
                if (!videoUrl.isNullOrBlank()) {
                    val labelText = source.label ?: "Unknown"
                    val isHls = videoUrl.contains(".m3u8")

                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $labelText",
                            url = videoUrl,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://$host/"
                            // Helper standar core menggantikan mapping manual 1080/720/480/360
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
 * 4b. Alias domain mirror Abyss (abyssplayer.com).
 * Pola alias standar (sama seperti Smoothpre) menggantikan fallback instansiasi
 * manual di provider — loadExtractor() akan mencocokkannya secara otomatis.
 */
class AbyssPlayer : AbyssExtractor() {
    override val mainUrl = "https://abyssplayer.com"
}

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

/**
 * 6. WebView Sniffer Fallback (LAST RESORT)
 *
 * Dipanggil oleh provider HANYA ketika seluruh pipeline HTTP (semua extractor,
 * semua mirror) selesai dieksekusi tanpa menghasilkan satu pun ExtractorLink.
 * Bukan bagian dari alur normal — jalur sukses tidak pernah menyentuh kelas ini.
 *
 * Mekanisme: memuat halaman embed di WebView headless core (WebViewResolver),
 * membiarkan JS situs berjalan (deobfuscation/token asli), lalu meng-intercept
 * request media pertama yang cocok pola m3u8/mp4/mpd via shouldInterceptRequest.
 * Catatan penting dari pembacaan kode core: pengecekan interceptUrl dilakukan
 * SEBELUM evaluasi blacklistedFiles, sehingga pola media tetap tertangkap.
 *
 * Keterbatasan yang disadari (by design): host berbasis MSE/blob/DRM
 * (mis. Abyss/Hydrax) atau file-host tanpa autoplay (mis. BuzzHeavier)
 * kemungkinan tetap gagal — resolver hanya akan timeout tanpa efek samping.
 */
object WebViewFallback {
    private const val TAG = "WebViewFallback"

    /** Pola request media yang menghentikan WebView saat tertangkap. */
    private val mediaUrlPattern = Regex("""\.(m3u8|mp4|mpd)(\?|${'$'})""")

    /**
     * Timeout per percobaan sniffing. Sengaja jauh di bawah DEFAULT_TIMEOUT (60s)
     * core agar total durasi fallback tetap aman terhadap timeout loadLinks,
     * dan memperkecil jendela hidup WebView (mitigasi risiko leak saat
     * pembatalan coroutine di tengah polling resolver).
     */
    private const val SNIFF_TIMEOUT_MS = 15_000L

    /**
     * Hanya header identitas/sesi yang relevan untuk playback yang diteruskan
     * ke ExoPlayer. Header transport (Host, Connection, Range, dsb.) sengaja
     * dibuang karena akan di-generate ulang oleh datasource player.
     */
    private val forwardedHeaderNames = setOf(
        "user-agent", "referer", "origin", "cookie", "accept", "accept-language"
    )

    /**
     * Muat [embedUrl] di WebView dan sniff request media pertama.
     * @return true bila sebuah ExtractorLink berhasil dikirim ke [callback].
     */
    suspend fun sniff(
        embedUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            Log.i(TAG, "Fallback WebView aktif untuk: $embedUrl")

            val resolver = WebViewResolver(
                interceptUrl = mediaUrlPattern,
                additionalUrls = emptyList(),
                userAgent = null,       // JANGAN override UA: bisa merusak bypass Cloudflare (catatan core)
                useOkhttp = false,      // false = seluruh request lewat engine WebView (aman utk Cloudflare)
                script = null,
                scriptCallback = null,
                timeout = SNIFF_TIMEOUT_MS
            )

            val (mediaRequest, _) = resolver.resolveUsingWebView(
                url = embedUrl,
                referer = referer,
                method = "GET",
                requestCallBack = { false }
            )

            val request = mediaRequest ?: run {
                Log.w(TAG, "Sniffing timeout/gagal (tidak ada request media tertangkap): $embedUrl")
                return false
            }

            val mediaUrl = request.url.toString()

            // okhttp3.Headers adalah Iterable<Pair<String, String>>; saring
            // hanya header yang berguna bagi player, pertahankan nama aslinya.
            val sniffedHeaders = request.headers
                .filter { (key, _) -> key.lowercase() in forwardedHeaderNames }
                .toMap()

            val sniffedReferer = sniffedHeaders.entries
                .firstOrNull { it.key.equals("referer", ignoreCase = true) }
                ?.value
                ?: embedUrl

            // Tanpa parameter `type` => INFER_TYPE: m3u8/mpd/mp4 disimpulkan dari URL.
            callback.invoke(
                newExtractorLink(
                    source = "WebView",
                    name = "WebView (Fallback)",
                    url = mediaUrl,
                ) {
                    this.referer = sniffedReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = sniffedHeaders
                }
            )

            Log.i(TAG, "Sniffing berhasil: $mediaUrl")
            true
        } catch (e: Exception) {
            // WAJIB: jangan menelan CancellationException (mekanisme timeout core).
            if (e is CancellationException) throw e
            Log.e(TAG, "Fallback WebView gagal: ${e.message}")
            false
        }
    }
}

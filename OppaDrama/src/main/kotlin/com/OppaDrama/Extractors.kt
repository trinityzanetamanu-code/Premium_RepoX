package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.network.WebViewResolver
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * 1. EarnVids / Smoothpre Extractor
 * Alias backend extractor: smoothpre.com menggunakan arsitektur yang sama
 * dengan vidhidepro.com. (Pola alias resmi, sama seperti extractor bawaan core.)
 */
class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

/**
 * 2. BuzzServer Extractor (Local Plugin Overrider)
 * Memperbaiki kegagalan pembacaan hx-redirect statis huruf kecil pada core HubCloud.kt
 * dengan menerapkan metode multi-headers fallback (hx-redirect, HX-Redirect, location, Location).
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

            val page = app.get(cleanUrl)
            val qualityText = page.documentLarge.selectFirst("div.max-w-2xl > span")?.text()
            val quality = getQualityFromName(qualityText)

            // Ambil respons headers dengan mematikan auto-redirect
            val response = app.get(
                "$cleanUrl/download",
                referer = cleanUrl,
                allowRedirects = false,
            )

            // MULTI-HEADERS OVERRIDE: tangkap seluruh variasi nama header dari htmx engine.
            // hx-redirect diprioritaskan karena itulah header yang benar; location
            // hanya dipakai sebagai cadangan terakhir.
            val rawRedirect = response.headers["hx-redirect"]
                ?: response.headers["HX-Redirect"]
                ?: response.headers["location"]
                ?: response.headers["Location"]

            // Endpoint /download kadang memantulkan Location kembali ke halaman
            // asalnya. Nilai seperti itu bukan tautan media dan harus dibuang,
            // kalau tidak player menerima HTML dan gagal diam-diam.
            val redirectUrl = rawRedirect
                ?.trim()
                ?.let { if (it.startsWith("/")) "$mainUrl$it" else it }
                ?.takeIf { candidate ->
                    val a = candidate.trimEnd('/').substringBefore('?')
                    val b = cleanUrl.trimEnd('/').substringBefore('?')
                    !a.equals(b, ignoreCase = true) &&
                            !a.equals("$b/download", ignoreCase = true)
                }

            if (rawRedirect != null && redirectUrl == null) {
                Log.w("BuzzServer", "Redirect memantul ke halaman asal, diabaikan: $rawRedirect")
            }

            if (!redirectUrl.isNullOrBlank()) {
                callback.invoke(
                    // Tanpa parameter `type` => INFER_TYPE: tipe media otomatis
                    // disimpulkan dari URL (fallback ke VIDEO bila tak dikenali).
                    newExtractorLink(
                        source = name,
                        name = name, // konsisten dgn source (saran maintainer core)
                        url = redirectUrl,
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/"
                    }
                )
            } else {
                Log.w("BuzzServer", "Bypass Failed: No valid redirect token found in response headers.")
            }
        } catch (e: Exception) {
            // WAJIB: jangan menelan CancellationException agar mekanisme
            // timeout coroutine core tetap berfungsi (sesuai pola loadExtractor).
            if (e is CancellationException) throw e
            Log.e("BuzzServer", "Ekstraksi gagal: ${e.message}")
        }
    }
}

/**
 * 3. Emturbovid Extractor
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

            val streamHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Origin" to mainUrl
            )

            // Mekanisme standar: M3u8Helper mengurai master playlist, menghasilkan
            // satu ExtractorLink per varian kualitas dengan resolusi URL relatif
            // yang benar, dan otomatis memakai ExtractorLinkType.M3U8.
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = masterUrl,
                referer = "$mainUrl/",
                headers = streamHeaders
            ).forEach(callback)
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

            val html = app.get(url, headers = headers).text
            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)

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

            val apiResponse = app.post(
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
            ).parsedSafe<AbyssResponse>()

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

                // Kualitas DIUKUR, bukan ditebak.
                //
                // Sebelumnya nilainya dipatok Qualities.Unknown (400), sehingga
                // sumber ini selalu terlempar ke dasar daftar — padahal sortUrls()
                // di core mengurutkan murni berdasarkan `-quality`, dan entri
                // Emturbovid yang berlabel 1080p/720p/480p otomatis naik ke atas
                // meski sebagiannya gagal diputar.
                //
                // Segmen ",l,n,h," pada URL urlset hanya menandakan tiga rendition
                // (low/normal/high) — itu label RELATIF, bukan resolusi. Satu-satunya
                // cara jujur mendapat angkanya adalah membaca master playlist.
                val quality = if (isM3u8) {
                    probeMaxResolution(streamUrl, headers)
                } else {
                    Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Minochinos", "Ekstraksi gagal: ${e.message}")
        }
    }

    /**
     * Membaca master playlist sekali, lalu mengembalikan resolusi TERTINGGI
     * yang benar-benar tercantum di dalamnya.
     *
     * Link tetap satu buah dan tetap adaptif — ExoPlayer yang memilih rendition
     * saat pemutaran. Yang berubah hanya nilai `quality`, supaya posisinya di
     * daftar sumber mencerminkan kualitas maksimum yang sungguh tersedia.
     * Memecah master jadi satu entri per rendition sengaja TIDAK dilakukan
     * karena itu justru memperpanjang daftar.
     *
     * Gagal apa pun -> kembali ke Qualities.Unknown, yaitu perilaku lama.
     * Jadi perubahan ini tidak pernah lebih buruk dari sebelumnya.
     */
    private suspend fun probeMaxResolution(
        masterUrl: String,
        headers: Map<String, String>,
    ): Int {
        return try {
            val body = app.get(masterUrl, headers = headers, timeout = 10L).text

            // Master playlist wajib memuat #EXT-X-STREAM-INF. Kalau tidak ada,
            // yang kita pegang adalah playlist media biasa (rendition tunggal)
            // dan resolusinya memang tidak tercantum di sana.
            if (!body.contains("#EXT-X-STREAM-INF")) {
                Log.i("Minochinos", "Bukan master playlist, kualitas tidak dapat diukur")
                return Qualities.Unknown.value
            }

            // [DIAG] Cetak baris varian apa adanya. Ini yang akan memastikan apakah
            // duplikasi berasal dari dua jalur (hls2/hls3) dan mana yang mati.
            body.lines()
                .map { it.trim() }
                .filter { it.startsWith("#EXT-X-STREAM-INF") || (it.isNotBlank() && !it.startsWith("#")) }
                .take(20)
                .forEachIndexed { i, baris -> Log.i("Minochinos", "DIAG playlist[$i] $baris") }

            val heights = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
                .findAll(body)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()

            if (heights.isEmpty()) {
                Log.i("Minochinos", "Master tanpa atribut RESOLUTION, kualitas tidak dapat diukur")
                return Qualities.Unknown.value
            }

            val max = heights.max()
            Log.i("Minochinos", "Resolusi terukur: ${heights.sorted().reversed()} -> pakai ${max}p")
            max
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("Minochinos", "Gagal mengukur resolusi (${e.message}), pakai Unknown")
            Qualities.Unknown.value
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

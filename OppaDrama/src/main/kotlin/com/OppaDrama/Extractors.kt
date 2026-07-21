package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.extractors.VidHidePro
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

            // MULTI-HEADERS OVERRIDE: tangkap seluruh variasi nama header dari htmx engine
            val redirectUrl = response.headers["hx-redirect"]
                ?: response.headers["HX-Redirect"]
                ?: response.headers["location"]
                ?: response.headers["Location"]

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
                data = mapOf(
                    "slug" to slug,
                    "md5_id" to md5Id,
                    "user_id" to userId
                )
            ).parsedSafe<AbyssResponse>()

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
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Minochinos", "Ekstraksi gagal: ${e.message}")
        }
    }
}

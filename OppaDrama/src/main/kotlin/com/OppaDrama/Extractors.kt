package com.OppaDrama

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI

/* ------------------------------------------------------------------------- *
 *  Catatan kompatibilitas API (Cloudstream terbaru, MainApi/ExtractorApi):
 *  - newExtractorLink(source, name, url, type) { ... } adalah suspend builder.
 *    referer / quality / headers WAJIB di-set di dalam blok lambda, bukan
 *    sebagai named-parameter (PR #1632 - deprecating ExtractorLink konstruktor).
 *  - ExtractorLinkType.{M3U8, VIDEO} atau INFER_TYPE untuk deteksi otomatis.
 *  - Jangan pernah menelan exception secara diam-diam (catch (_) {}). Untuk
 *    source yang "kadang error", log adalah satu-satunya cara menemukan akar
 *    masalah di runtime. Semua catch di bawah menulis Log.e/Log.w.
 * ------------------------------------------------------------------------- */

private const val UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

/** Resolusi URL relatif (mis. "/dl/xxx" atau "path") terhadap host absolut. */
private fun resolveUrl(raw: String, host: String): String = when {
    raw.startsWith("http") -> raw
    raw.startsWith("//")   -> "https:$raw"
    raw.startsWith("/")    -> host.trimEnd('/') + raw
    else                   -> host.trimEnd('/') + "/" + raw
}

/** "https://host" dari sebuah URL apa pun. Aman untuk domain mirror. */
private fun originOf(url: String): String {
    val u = URI(url)
    val scheme = u.scheme ?: "https"
    return "$scheme://${u.host}"
}

/**
 * 1. EarnVids / Smoothpre Extractor
 * Alias backend: smoothpre.com berbagi arsitektur dengan vidhidepro.com.
 * (Tidak diubah — sudah berfungsi.)
 */
class Smoothpre : VidHidePro() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}

/**
 * 2. BuzzServer Extractor
 *
 * AKAR MASALAH (intermittent):
 *   a) hx-redirect buzzheavier sering berupa PATH RELATIF ("/dl/...") atau URL
 *      di host mirror lain. Kode lama mengirim nilai mentah ke player → URL
 *      tidak valid → gagal putar.
 *   b) Endpoint /download adalah htmx. Tanpa header "HX-Request: true" dan
 *      TANPA meneruskan cookie sesi dari GET pertama, server sesekali membalas
 *      HTML biasa (bukan header redirect) → "Bypass Failed". Ini penyebab
 *      utama sifat "kadang berhasil, kadang gagal".
 *   c) Host di-hardcode. Sebaiknya diturunkan dari url masuk agar tahan mirror.
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
            val host = originOf(url)                                   // (c)
            val cleanUrl = url.substringBefore("/download").trimEnd('/')

            // GET pertama: ambil quality + cookie sesi (dipakai di request htmx)
            val landing = app.get(cleanUrl, referer = referer)
            val sessionCookies = landing.cookies
            val quality = getQualityFromName(
                landing.document.selectFirst("div.max-w-2xl > span")?.text()
            )

            // Request htmx dengan header + cookie yang benar, tanpa auto-redirect
            val response = app.get(
                "$cleanUrl/download",
                referer = cleanUrl,
                cookies = sessionCookies,                              // (b)
                headers = mapOf(
                    "HX-Request" to "true",                           // (b)
                    "HX-Current-URL" to cleanUrl,
                    "User-Agent" to UA,
                ),
                allowRedirects = false,
            )

            // Tangkap semua variasi nama header dari engine htmx
            val raw = response.headers["hx-redirect"]
                ?: response.headers["HX-Redirect"]
                ?: response.headers["location"]
                ?: response.headers["Location"]

            if (raw.isNullOrBlank()) {
                Log.w("BuzzServer", "No redirect header (status=${response.code}) for $cleanUrl")
                return
            }

            val redirectUrl = resolveUrl(raw.trim(), host)            // (a)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "BuzzServer Direct",
                    url = redirectUrl,
                ) {
                    this.quality = quality
                    this.referer = "$host/"
                }
            )
        } catch (e: Exception) {
            Log.e("BuzzServer", "Failed to resolve $url: ${e.message}")
        }
    }
}

/**
 * 3. Emturbovid Extractor
 *
 * AKAR MASALAH (intermittent):
 *   a) Domain sering berotasi. Referer/Origin di-hardcode ke mainUrl; jika url
 *      masuk berbeda host, CDN membalas 403 pada master.m3u8. Turunkan host
 *      dari url yang sebenarnya.
 *   b) Hanya menangani SATU hop redirect. Rantai redirect memutus alur.
 *   c) catch (_) {} lama menelan error → nol tautan tanpa jejak. Sekarang di-log.
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
            val pageHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            // Ikuti rantai redirect (bukan hanya 1 hop) — biarkan klien mengikuti,
            // lalu pakai url final untuk menentukan host yang benar.
            val page = app.get(url, headers = pageHeaders)            // (b)
            val finalUrl = page.url.ifBlank { url }
            val html = page.text
            val document = Jsoup.parse(html)

            val embedOrigin = originOf(finalUrl)                      // (a)

            val masterUrl = document.selectFirst("div#video_player")
                ?.attr("data-hash")?.trim()?.takeIf { it.isNotBlank() }
                ?: Regex("""var\s+urlPlay\s*=\s*['"]([^'"]+)['"]""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim()

            if (masterUrl.isNullOrBlank()) {
                Log.w("Emturbovid", "No master URL found on $finalUrl")
                return
            }
            val resolvedMaster = resolveUrl(masterUrl, embedOrigin)

            val streamHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to "$embedOrigin/",
                "Origin" to embedOrigin
            )

            val masterText = app.get(resolvedMaster, headers = streamHeaders).text
            val lines = masterText.lines()
            var variantsFound = false

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue

                val height = Regex("RESOLUTION=\\d+x(\\d+)")
                    .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()

                val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (nextLine.isBlank() || nextLine.startsWith("#")) continue

                val variantUrl = when {
                    nextLine.startsWith("//") -> "https:$nextLine"
                    nextLine.startsWith("/")  -> originOf(resolvedMaster) + nextLine
                    nextLine.startsWith("http") -> nextLine
                    else -> resolvedMaster.substringBeforeLast("/") + "/" + nextLine
                }

                variantsFound = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ${height ?: ""}p".trim(),
                        url = variantUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$embedOrigin/"
                        this.headers = streamHeaders
                        this.quality = height ?: Qualities.Unknown.value
                    }
                )
            }

            if (!variantsFound) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = resolvedMaster,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$embedOrigin/"
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {                                      // (c)
            Log.e("Emturbovid", "Failed to resolve $url: ${e.message}")
        }
    }
}

/**
 * 4. Abyss / Hydrax Extractor
 *
 * AKAR MASALAH (sumber paling rapuh):
 *   Format lama {"sources":[{"file":...}]} lewat POST /api/player/v2 sudah
 *   USANG untuk Hydrax terbaru. Player modern menaruh blob base64 di dalam
 *   `atob("...")` yang mendekode ke JSON {"domain":..., "id":...}; file
 *   progresif diakses di https://{domain}/{prefix}{id} dengan prefix kualitas
 *   ("" = 360p, "www" = 720p, "whw" = 1080p) dan WAJIB Referer abysscdn.com.
 *
 *   Catatan jujur: sebagian video Hydrax kini memakai segmen terenkripsi
 *   (AES-CTR, token turunan MD5) yang TIDAK bisa direkonstruksi ringan di
 *   sisi plugin. Extractor ini mencoba, berurutan:
 *     (1) pendekatan atob {domain,id}  → tautan CDN langsung (paling relevan),
 *     (2) fallback POST /api/player/v2 (untuk edge/varian lama).
 *   Bila keduanya gagal, itu memang batas wajar Abyss saat ini.
 */
class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    // PENTING (dibuktikan audit): embed nyata memakai host `abyssplayer.com`,
    // BUKAN `abyss.to`. loadExtractor() mencocokkan via startsWith(mainUrl)
    // (schema di-strip), jadi mainUrl HARUS berupa prefix host embed yang asli,
    // kalau tidak extractor ini tidak akan pernah dipilih oleh loadExtractor().
    // Varian lain (abyss.to / short.icu) tetap ditangani manual di parseEmbeds.
    override val mainUrl = "https://abyssplayer.com"
    override val requiresReferer = true

    private data class AbyssContent(
        @JsonProperty("domain") val domain: String?,
        @JsonProperty("id") val id: String?
    )

    private data class AbyssSource(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("type") val type: String?
    )

    private data class AbyssResponse(
        @JsonProperty("sources") val sources: List<AbyssSource>?
    )

    private fun qualityOf(label: String?): Int = when {
        label == null -> Qualities.Unknown.value
        label.contains("1080") -> Qualities.P1080.value
        label.contains("720")  -> Qualities.P720.value
        label.contains("480")  -> Qualities.P480.value
        label.contains("360")  -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cdnReferer = "https://abysscdn.com/"
        try {
            val host = URI(url).host
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: "http://45.11.57.192/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text

            // ---- (1) Pendekatan modern: atob("...") -> {domain, id} ----------
            val atobB64 = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")
                .find(html)?.groupValues?.getOrNull(1)
            if (!atobB64.isNullOrBlank()) {
                runCatching {
                    val json = base64Decode(atobB64)
                    val content = com.lagradost.cloudstream3.utils.AppUtils
                        .parseJson<AbyssContent>(json)
                    val domain = content.domain?.trim()
                    val id = content.id?.trim()
                    if (!domain.isNullOrBlank() && !id.isNullOrBlank()) {
                        // prefix kualitas Hydrax
                        val variants = listOf(
                            "" to Qualities.P360.value,
                            "www" to Qualities.P720.value,
                            "whw" to Qualities.P1080.value
                        )
                        for ((prefix, q) in variants) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name ${Qualities.getStringByInt(q)}",
                                    url = "https://$domain/$prefix$id",
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = cdnReferer
                                    this.quality = q
                                    this.headers = mapOf(
                                        "Referer" to cdnReferer,
                                        "User-Agent" to UA
                                    )
                                }
                            )
                        }
                        return  // sukses jalur modern
                    }
                }.onFailure { Log.w("Abyss", "atob path failed: ${it.message}") }
            }

            // ---- (2) Fallback: format lama POST /api/player/v2 ---------------
            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""")
                .find(html)?.groupValues?.getOrNull(1)

            var slug = Regex("[?&]v=([^&#]+)").find(url)?.groupValues?.getOrNull(1)?.trim()
            var md5Id = ""
            var userId = ""

            if (!datasRaw.isNullOrBlank()) {
                val decoded = base64Decode(datasRaw)
                if (slug.isNullOrBlank()) {
                    slug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"")
                        .find(decoded)?.groupValues?.getOrNull(1)?.trim()
                }
                md5Id = Regex("\"md5_id\"\\s*:\\s*\"?(\\d+)\"?")
                    .find(decoded)?.groupValues?.getOrNull(1)?.trim() ?: ""
                userId = Regex("\"user_id\"\\s*:\\s*\"?(\\d+)\"?")
                    .find(decoded)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }
            if (slug.isNullOrBlank()) {
                slug = Regex("""(?:v|slug)\s*:\s*["']([^"']+)["']""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim()
            }
            if (userId.isBlank()) {
                userId = Regex("""userID\s*:\s*["']?(\d+)["']?""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim() ?: ""
            }
            if (slug.isNullOrBlank()) {
                Log.w("Abyss", "No slug/domain resolvable for $url")
                return
            }

            val apiResponse = app.post(
                "https://$host/api/player/v2",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to url,
                    "Origin" to "https://$host",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("slug" to slug, "md5_id" to md5Id, "user_id" to userId)
            ).parsedSafe<AbyssResponse>()

            apiResponse?.sources?.forEach { source ->
                val videoUrl = source.file
                if (!videoUrl.isNullOrBlank()) {
                    val isM3u8 = videoUrl.contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - ${source.label ?: "Unknown"}",
                            url = videoUrl,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "https://$host/"
                            this.quality = qualityOf(source.label)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("Abyss", "Failed to resolve $url: ${e.message}")
        }
    }
}

/**
 * 5. Minochinos / VidHide Obfuscated Extractor
 *
 * AKAR MASALAH (intermittent):
 *   a) getAndUnpack mengasumsikan JS ter-"pack" (eval(function(p,a,c,k,e,d))).
 *      Bila halaman tidak dipack / berbeda obfuscation, hasilnya = input apa
 *      adanya → regex m3u8/mp4 meleset → nol tautan.
 *   b) Hanya mencari .m3u8/.mp4. Varian VidHide juga menaruh sumber di
 *      "sources":[{"file":"..."}] atau file:"...". Ditambahkan.
 *   c) Cari di HTML mentah DAN hasil unpack (gabungan), bukan salah satu saja.
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
            val host = originOf(url)
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to (referer ?: "$mainUrl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            // (a) getAndUnpack aman meski tak ter-pack (mengembalikan input).
            val unpacked = runCatching { getAndUnpack(html) }.getOrDefault("")
            // (c) cari di kedua sumber
            val haystack = html + "\n" + unpacked

            val streamUrl =
                Regex("""https?://[^\s"'`<>]+?\.m3u8[^\s"'`<>]*""")
                    .find(haystack)?.value
                    ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                        .find(haystack)?.groupValues?.getOrNull(1)
                    // (b) pola VidHide "sources":[{"file":"..."}] / file:"..."
                    ?: Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+)["']""")
                        .find(haystack)?.groupValues?.getOrNull(1)

            if (streamUrl.isNullOrBlank()) {
                Log.w("Minochinos", "No stream URL found on $url")
                return
            }

            val streamHeaders = mapOf(
                "User-Agent" to UA,
                "Referer" to "$mainUrl/",
                "Origin" to host
            )

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (streamUrl.contains(".m3u8"))
                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = streamHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e("Minochinos", "Failed to resolve $url: ${e.message}")
        }
    }
}

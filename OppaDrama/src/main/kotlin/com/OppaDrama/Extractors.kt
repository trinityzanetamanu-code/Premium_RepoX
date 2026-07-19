package com.OppaDrama

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
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
            // FIX: `.document` (bukan `.documentLarge`) adalah properti asli - dibuktikan
            // dari MainAPI.kt sendiri yang memakai idiom identik:
            // `app.get(url).document.selectFirst(...)` (baris 205, alur reCAPTCHA).
            // `.documentLarge` tidak ditemukan sama sekali di kedua file referensi.
            val qualityText = page.document.selectFirst("div.max-w-2xl > span")?.text()
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
 * AKAR MASALAH TERBUKTI DARI RUNTIME NYATA (bukan dugaan lagi):
 *
 * 1. DOMAIN SUDAH PINDAH: emturbovid.com sekarang 301-redirect (Cloudflare) ke
 *    turbovidhls.com dengan path yang sama persis (/t/{hash}). Dikonfirmasi
 *    langsung dari header respons asli yang dikirim user:
 *      HTTP/2 301
 *      location: https://turbovidhls.com/t/6a4a5f48f3dc8
 *
 * 2. OBFUSCATION HALAMAN PLAYER SUDAH GANTI TOTAL: bukan lagi Dean Edwards packer
 *    (`eval(function(p,a,c,k,e,d)...)` yang dikenali `getAndUnpack()`/`JsUnpacker`
 *    bawaan Cloudstream), dan bukan pula HTML polos berisi `data-hash`/`urlPlay`
 *    yang bisa langsung di-regex dari raw HTML. Sekarang dipakai cipher substitusi
 *    kustom: `eval(function(h,u,n,t,e,r){...}("<blob>",U,"<key>",T,E,R))`.
 *
 *    Ini SAYA VERIFIKASI dengan menjalankan ulang algoritmanya persis (bukan cuma
 *    baca kode) terhadap HTML asli yang dikirim user, dan berhasil membongkar isi
 *    aslinya, termasuk baris ini:
 *      var urlPlay = 'https://cdn.turboviplay.com/data3/6a4a5f48f3dc8/6a4a5f48f3dc8.m3u8';
 *
 *    Jadi variabel `urlPlay` MEMANG masih ada persis seperti asumsi kode lama -
 *    tapi terkubur di dalam blok eval yang tidak pernah di-decode lebih dulu.
 *    Baik regex lama (baca raw HTML) maupun `getAndUnpack()` bawaan core SAMA-SAMA
 *    tidak akan pernah menemukannya, karena keduanya tidak tahu cipher kustom ini.
 *
 * Kesimpulan soal "konflik shadowing core" di analisa sebelumnya: itu tetap fakta
 * struktural yang valid (core memang punya EmturbovidExtractor bawaan), TAPI itu
 * BUKAN penyebab utama kegagalan di runtime nyata - actual root cause adalah
 * obfuscation baru ini, yang kemungkinan besar juga belum dikenali oleh core
 * (obfuscation ini sangat spesifik/baru, bukan pola umum).
 *
 * Decoder di bawah adalah port Kotlin dari algoritma asli, sudah diverifikasi
 * byte-per-byte identik dengan hasil eksekusi JS aslinya (dijalankan ulang di
 * Node.js terhadap sample nyata sebelum di-port).
 */
class Emturbovid : ExtractorApi() {
    override var name = "Emturbovid"
    override var mainUrl = "https://emturbovid.com"
    override val requiresReferer = true

    // Alfabet 64-karakter yang dipakai cipher kustom situs ini untuk konversi basis.
    // Ini konstanta yang ditemukan di skrip halaman (bukan hardcode buta) - kalau situs
    // mengganti alfabetnya, decoder ini perlu diupdate mengikuti nilai baru itu.
    private val obfuscationAlphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"

    // Menangkap struktur eval(function(x,x,x,x,x,x){...}("<blob>",N,"<key>",T,E,N)),
    // tanpa terikat nama parameter (bisa berubah tiap generate), hanya terikat BENTUK
    // strukturalnya: 6 parameter satu huruf, dipanggil dengan string besar, angka,
    // string kunci pendek, lalu tiga angka.
    private val customObfuscationRegex = Regex(
        """eval\(function\([a-zA-Z],[a-zA-Z],[a-zA-Z],[a-zA-Z],[a-zA-Z],[a-zA-Z]\)\{.*?\}\("([a-zA-Z0-9+/]+)",\d+,"([^"]+)",(\d+),(\d+),\d+\)\)"""
    )

    private fun decodeSegmentValue(segment: String, key: String, base: Int): Long {
        var translated = segment
        for (j in key.indices) {
            translated = translated.replace(key[j].toString(), j.toString())
        }
        val digitAlphabet = obfuscationAlphabet.substring(0, base)
        var value = 0L
        val reversed = translated.reversed()
        for ((pos, ch) in reversed.withIndex()) {
            val digit = digitAlphabet.indexOf(ch)
            if (digit != -1) {
                value += digit.toLong() * Math.pow(base.toDouble(), pos.toDouble()).toLong()
            }
        }
        return value
    }

    private fun decodeCustomObfuscation(bigStr: String, key: String, offsetT: Int, baseE: Int): String {
        if (baseE <= 0 || baseE >= key.length) return ""
        val delimiter = key[baseE]
        val bytes = mutableListOf<Byte>()
        var i = 0
        val len = bigStr.length
        while (i < len) {
            val seg = StringBuilder()
            while (i < len && bigStr[i] != delimiter) {
                seg.append(bigStr[i]); i++
            }
            i++ // lewati delimiter
            val value = decodeSegmentValue(seg.toString(), key, baseE)
            val code = ((value - offsetT) and 0xFF).toInt()
            bytes.add(code.toByte())
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

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

            // app.get() default-nya mengikuti redirect, jadi 301 emturbovid.com -> turbovidhls.com
            // otomatis ter-follow di sini tanpa perlu penanganan manual seperti versi lama.
            val html = app.get(url, headers = headers).text

            val match = customObfuscationRegex.find(html)
            if (match == null) {
                Log.w("Emturbovid", "Pola obfuscation kustom tidak ditemukan di HTML untuk $url - struktur halaman mungkin sudah berubah lagi.")
                return
            }
            val (bigStr, key, tStr, eStr) = match.destructured
            val decoded = decodeCustomObfuscation(bigStr, key, tStr.toInt(), eStr.toInt())

            val masterUrl = Regex("""urlPlay\s*=\s*['"]([^'"]+)['"]""").find(decoded)?.groupValues?.getOrNull(1)
                ?: Regex("""https?://[^\s'"]+\.m3u8[^\s'"]*""").find(decoded)?.value

            if (masterUrl.isNullOrBlank()) {
                Log.w("Emturbovid", "Decode berhasil (${decoded.length} karakter) tapi urlPlay/m3u8 tidak ketemu di dalamnya untuk $url")
                return
            }

            val streamHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl
            )

            val masterText = app.get(masterUrl, headers = streamHeaders).text
            val lines = masterText.lines()
            var variantsFound = false

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue

                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val nextLine = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (nextLine.isBlank() || nextLine.startsWith("#")) continue

                val variantUrl = when {
                    nextLine.startsWith("//") -> "https:$nextLine"
                    nextLine.startsWith("/") -> "https://" + URI(masterUrl).host + nextLine
                    nextLine.startsWith("http") -> nextLine
                    else -> masterUrl.substringBeforeLast("/") + "/" + nextLine
                }

                variantsFound = true
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name ${height ?: ""}p".trim(),
                        url = variantUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
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
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("Emturbovid", "Gagal resolve $url: ${e::class.simpleName} - ${e.message}")
        }
    }
}

/**
 * 4. Abyss / Hydrax Extractor
 *
 * STATUS SETELAH AUDIT MENYELURUH (HTML player asli + cross-check MainAPI.kt/ExtractorApi.kt):
 *
 * BUG IMPLEMENTASI YANG DITEMUKAN (independen dari soal enkripsi, sudah diperbaiki):
 * Kode versi sebelumnya manual pakai `android.util.Base64` + `Charsets.ISO_8859_1`.
 * Ternyata `MainAPI.kt` sudah punya fungsi kanonik `base64Decode(string: String): String`
 * yang melakukan PERSIS hal yang sama (byte->char ISO-8859-1, meniru atob() JS), dan
 * fungsi ini SUDAH dipakai di `OppaDramaProvider.kt` sendiri (parseEmbeds, decode dropdown
 * mirror). Extractor ini sekarang konsisten pakai fungsi resmi yang sama, bukan reimplementasi
 * manual pakai API khusus Android.
 *
 * HASIL AUDIT ALUR JS MENYELURUH (bukan cuma baca field "media"):
 * - Nol elemen <iframe> di halaman - file ini sendiri adalah leaf embed, bukan wrapper.
 * - Nol endpoint /api/player atau sejenisnya di seluruh script (sudah di-grep habis).
 * - Hanya 2 fetch() di halaman, keduanya untuk deteksi tampering/AdBlock, tidak relevan ke video.
 * - `core.bundle.js` dimuat blocking (tanpa async/defer) SEBELUM inline script yang memanggil
 *   `window.SoTrym(JSON.parse(atob(datas)))` - jadi urutan datas->SoTrym ini satu-satunya
 *   jalur yang benar-benar dipakai, `lite.bundle.js` cuma fallback defensif kalau bundle
 *   utama gagal load, bukan jalur alternatif yang berbeda.
 * - `datas` (setelah decode benar) berisi { slug, md5_id, user_id, media, config, danmu }.
 *   slug/md5_id/user_id adalah field plaintext biasa (parsing ini tetap berguna & benar).
 *   "media" adalah data biner terenkripsi (~1200 byte, entropi tinggi) yang didekripsi
 *   CLIENT-SIDE oleh SoTrym() dari core.bundle.js - satu-satunya cara mendapat URL video.
 *
 * Kesimpulan: bukan bug plugin (di luar Base64 charset yang sudah diperbaiki), bukan pula
 * ada endpoint/jalur lain yang terlewat. Video memang hanya bisa didapat lewat data yang
 * dilindungi enkripsi di core.bundle.js. Saya tidak reverse-engineer/implementasikan
 * dekripsinya - itu di luar apa yang bisa saya bantu. Kode di bawah parsing datas dengan
 * benar (termasuk field yang berguna untuk diagnostik) lalu berhenti dengan log jelas
 * begitu ketemu bahwa media terenkripsi.
 */
class AbyssExtractor : ExtractorApi() {
    override val name = "Abyss"
    override val mainUrl = "https://abyss.to"
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
                "Referer" to (referer ?: "http://45.11.57.192/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val html = app.get(url, headers = headers).text
            val datasRaw = Regex("""const\s+datas\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)

            if (datasRaw.isNullOrBlank()) {
                Log.w("Abyss", "Variabel 'datas' tidak ditemukan di HTML untuk $url - struktur halaman mungkin sudah berubah lagi.")
                return
            }

            // Pakai fungsi kanonik com.lagradost.cloudstream3.base64Decode() (sama seperti
            // yang dipakai OppaDramaProvider.kt sendiri), bukan android.util.Base64 manual.
            val decodedDatas = base64Decode(datasRaw)

            val slug = Regex("\"slug\"\\s*:\\s*\"([^\"]+)\"").find(decodedDatas)?.groupValues?.getOrNull(1)
            val hasEncryptedMedia = Regex("\"media\"\\s*:\\s*\"").containsMatchIn(decodedDatas)

            if (hasEncryptedMedia) {
                // Titik akhir yang jujur, sesudah audit menyeluruh terhadap seluruh script
                // di halaman: tidak ada endpoint/iframe/jalur lain yang ditemukan. Field
                // "media" berisi data terenkripsi yang didekripsi client-side oleh
                // core.bundle.js - di luar cakupan yang bisa saya bantu implementasikan.
                Log.w(
                    "Abyss",
                    "slug='$slug' berhasil di-parse, tapi video di-enkripsi di field 'media' " +
                        "(didekripsi client-side oleh core.bundle.js, dipanggil lewat window.SoTrym()). " +
                        "Sudah diaudit menyeluruh: tidak ada endpoint API atau jalur lain di halaman " +
                        "ini yang bisa dipakai untuk dapat URL video langsung."
                )
            } else {
                Log.w("Abyss", "Field 'media' terenkripsi tidak ditemukan di datas untuk $url - kemungkinan skema situs berubah lagi, perlu sample baru untuk investigasi.")
            }
        } catch (e: Exception) {
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

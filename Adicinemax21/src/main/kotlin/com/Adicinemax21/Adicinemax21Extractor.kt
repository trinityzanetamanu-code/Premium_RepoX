package com.Adicinemax21

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object Adicinemax21Extractor : Adicinemax21() {

    // ================== KISSKH SOURCE ==================
    suspend fun invokeKisskh(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val mainUrl = "https://kisskh.ovh"
        val KISSKH_API = "https://script.google.com/macros/s/AKfycbzn8B31PuDxzaMa9_CQ0VGEDasFqfzI5bXvjaIZH4DM8DNq9q6xj1ALvZNz_JT3jF0suA/exec?id="
        val KISSKH_SUB_API = "https://script.google.com/macros/s/AKfycbyq6hTj0ZhlinYC6xbggtgo166tp6XaDKBCGtnYk8uOfYBUFwwxBui0sGXiu_zIFmA/exec?id="

        suspend fun searchAndMatch(query: String): KisskhMedia? {
            try {
                val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$query&type=0").text
                val searchList = tryParseJson<ArrayList<KisskhMedia>>(searchRes) ?: return null

                val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()

                return searchList.find {
                    val cleanItemTitle = it.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
                    cleanItemTitle.contains(cleanQuery)
                } ?: searchList.firstOrNull {
                    val cleanItemTitle = it.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase() ?: ""
                    cleanItemTitle.contains(cleanQuery)
                }
            } catch (e: Exception) {
                return null
            }
        }

        var matched = searchAndMatch(title)
        if (matched == null && orgTitle != null) {
            matched = searchAndMatch(orgTitle)
        }
        if (matched == null && altTitle != null) {
            matched = searchAndMatch(altTitle)
        }
        if (matched == null) return

        val dramaId = matched.id ?: return
        val detailRes = app.get("$mainUrl/api/DramaList/Drama/$dramaId?isq=false").parsedSafe<KisskhDetail>() ?: return
        val episodes = detailRes.episodes ?: return
        val targetEp = if (season == null) episodes.lastOrNull() else episodes.find { it.number?.toInt() == episode }
        val epsId = targetEp?.id ?: return

        val kkeyVideo = app.get("$KISSKH_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
        val videoUrl = "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=null&time=null&kkey=$kkeyVideo"
        val sources = app.get(videoUrl).parsedSafe<KisskhSources>()

        listOfNotNull(sources?.video, sources?.thirdParty).forEach { link ->
            if (link.contains(".m3u8")) M3u8Helper.generateM3u8("Kisskh", link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
            else if (link.contains(".mp4")) callback.invoke(newExtractorLink("Kisskh", "Kisskh", link, ExtractorLinkType.VIDEO) { this.referer = mainUrl })
        }
        val kkeySub = app.get("$KISSKH_SUB_API$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
        val subJson = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkeySub").text
        tryParseJson<List<KisskhSubtitle>>(subJson)?.forEach { sub ->
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: "Unknown", sub.src ?: return@forEach))
        }
    }

    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(@JsonProperty("key") val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    // ================== MOVIEBOX SOURCE ==================
    // Menggantikan invokeAdimoviebox() dan invokeAdimoviebox2().
    // Engine diambil dari MovieBoxProvider, TANPA membawa mainPage/search/
    // quickSearch/load/detail/recommendation miliknya, karena semua itu sudah
    // ditangani TMDB di Adicinemax21.
    //
    // Alur: TMDB load() -> LinkData -> loadLinks() -> invokeMoviebox()
    //       -> search/v2 -> season-info -> play-info -> ExtractorLink (+ Cookie)
    //
    // DIAGNOSTIK: filter logcat dengan tag "Adicinemax21MB". Setiap tahap diberi
    // nomor urut [00]..[10] sehingga tahap tempat MovieBox berhenti langsung
    // terbaca, termasuk ALASAN setiap kandidat search ditolak.
    private const val MB_TAG = "Adicinemax21MB"

    suspend fun invokeMoviebox(
        title: String,
        orgTitle: String? = null,
        altTitle: String? = null,
        year: Int?,
        airedYear: Int? = null,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // LinkData.year untuk TV = tahun SEASON, sedangkan releaseDate Moviebox
        // = tahun SERIES. Pakai airedYear (tahun rilis series) supaya season >= 2
        // tidak gagal match.
        val matchYear = if (season != null) (airedYear ?: year) else year
        val wantedType = if (season != null) 2 else 1

        Log.d(
            MB_TAG,
            "[00-INPUT] title='$title' orgTitle='$orgTitle' altTitle='$altTitle' " +
                "year=$year airedYear=$airedYear season=$season episode=$episode " +
                "=> matchYear=$matchYear wantedType=$wantedType"
        )

        val bearer = MovieboxHelper.getBearerToken()
        if (bearer == null) {
            Log.e(MB_TAG, "[01-AUTH] STOP: bearer token null (ranking-list / header x-user gagal)")
            return
        }
        Log.d(MB_TAG, "[01-AUTH] ok (len=${bearer.length})")

        suspend fun searchSubject(query: String): MovieboxSubject? {
            val cleanQuery = query.replace(Regex("[^A-Za-z0-9]"), "").lowercase()
            if (cleanQuery.isEmpty()) {
                Log.w(MB_TAG, "[02-SEARCH] skip: query '$query' kosong setelah normalisasi")
                return null
            }

            val body = JSONObject()
                .put("page", 1)
                .put("perPage", 10)
                .put("keyword", query)
                .put("tabId", "")
                .toString()

            Log.d(MB_TAG, "[02-SEARCH] keyword='$query' normalized='$cleanQuery'")

            val raw = MovieboxHelper.postSigned(
                "/wefeed-mobile-bff/subject-api/search/v2",
                body,
                bearer
            )
            if (raw == null) {
                Log.e(MB_TAG, "[02-SEARCH] STOP: HTTP gagal / bukan 200 untuk keyword='$query'")
                return null
            }

            val parsed = tryParseJson<MovieboxSearchResponse>(raw)
            if (parsed == null) {
                Log.e(MB_TAG, "[02-SEARCH] STOP: JSON gagal di-parse. head=${raw.take(300)}")
                return null
            }

            val groups = parsed.data?.results.orEmpty()
            val subjects = groups
                .flatMap { it.subjects ?: emptyList() }
                .filter { !it.subjectId.isNullOrBlank() }

            Log.d(
                MB_TAG,
                "[02-SEARCH] code=${parsed.code} groups=${groups.size} subjects=${subjects.size}"
            )
            subjects.forEachIndexed { i, s ->
                Log.d(
                    MB_TAG,
                    "[03-CAND] #$i id=${s.subjectId} type=${s.subjectType} " +
                        "releaseDate=${s.releaseDate} title='${s.title}'"
                )
            }
            if (subjects.isEmpty()) {
                Log.w(MB_TAG, "[03-CAND] response terbaca tapi 0 subject untuk keyword='$query'")
            }

            fun matches(subject: MovieboxSubject, exact: Boolean): Boolean {
                val pass = if (exact) "EXACT" else "PARTIAL"

                // [FIX-2] MovieBoxProvider asli memakai optInt("subjectType", 1), artinya
                // field ini BOLEH tidak ada. Versi lama menolak null secara keras sehingga
                // seluruh kandidat bisa terbuang. Sekarang null hanya ditoleransi pada pass
                // PARTIAL, jadi pass EXACT tetap seketat sebelumnya.
                val st = subject.subjectType
                if (st == null) {
                    if (exact) {
                        Log.d(MB_TAG, "[04-REJECT/$pass] id=${subject.subjectId} alasan=subjectType null")
                        return false
                    }
                } else if (st != wantedType) {
                    Log.d(MB_TAG, "[04-REJECT/$pass] id=${subject.subjectId} alasan=subjectType $st != $wantedType")
                    return false
                }

                val cleanTitle = subject.title?.replace(Regex("[^A-Za-z0-9]"), "")?.lowercase().orEmpty()
                if (cleanTitle.isEmpty()) {
                    Log.d(MB_TAG, "[04-REJECT/$pass] id=${subject.subjectId} alasan=title kosong")
                    return false
                }

                val titleOk = if (exact) {
                    cleanTitle == cleanQuery
                } else {
                    cleanTitle.contains(cleanQuery) || cleanQuery.contains(cleanTitle)
                }
                if (!titleOk) {
                    Log.d(MB_TAG, "[04-REJECT/$pass] id=${subject.subjectId} alasan=title '$cleanTitle' vs '$cleanQuery'")
                    return false
                }

                val subjectYear = subject.releaseDate?.split("-")?.firstOrNull()?.toIntOrNull()
                // [FIX-3] MovieBox kadang memecah serial menjadi subject per-season, sehingga
                // releaseDate subject = tahun SEASON (mis. 2024) sedangkan matchYear = tahun
                // SERIES (mis. 2022). Toleransi +/-1 menolak subject yang justru benar.
                // Sebuah season tidak mungkin tayang sebelum series-nya mulai, jadi untuk TV
                // batas bawah saja yang divalidasi. Movie tetap +/-1 seperti semula.
                val yearOk = when {
                    matchYear == null || subjectYear == null -> true
                    wantedType == 2 -> subjectYear >= matchYear - 1
                    else -> abs(subjectYear - matchYear) <= 1
                }
                if (!yearOk) {
                    Log.d(MB_TAG, "[04-REJECT/$pass] id=${subject.subjectId} alasan=year $subjectYear vs $matchYear")
                    return false
                }
                return true
            }

            val hit = subjects.firstOrNull { matches(it, true) }
                ?: subjects.firstOrNull { matches(it, false) }

            if (hit == null) {
                Log.w(MB_TAG, "[05-MATCH] tidak ada subject cocok untuk keyword='$query'")
            } else {
                Log.d(MB_TAG, "[05-MATCH] terpilih id=${hit.subjectId} type=${hit.subjectType} title='${hit.title}'")
            }
            return hit
        }

        // [FIX-1] Rantai fallback lama (title -> orgTitle -> altTitle) semuanya memakai
        // substringBefore(":"). Untuk judul berbahasa Inggris title == orgTitle dan altTitle
        // sering null, sehingga "3 fallback" sebenarnya mengirim keyword yang SAMA berkali-kali
        // (kasus House of the Dragon). Selain itu substringBefore(":") merusak judul seperti
        // "Mission: Impossible" menjadi "Mission". Sekarang judul utuh dicoba lebih dulu, lalu
        // varian tanpa subtitle, dan daftarnya di-distinct. Aturan matches() TIDAK dilonggarkan.
        val queries = listOfNotNull(
            title,
            title.substringBefore(":"),
            orgTitle,
            orgTitle?.substringBefore(":"),
            altTitle,
            altTitle?.substringBefore(":")
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()

        Log.d(MB_TAG, "[02-SEARCH] daftar keyword yang akan dicoba = $queries")

        var matched: MovieboxSubject? = null
        for (q in queries) {
            matched = searchSubject(q)
            if (matched != null) break
        }
        if (matched == null) {
            Log.e(MB_TAG, "[05-MATCH] STOP: tidak ada subject setelah ${queries.size} keyword: $queries")
            return
        }

        val subjectId = matched.subjectId
        if (subjectId.isNullOrBlank()) {
            Log.e(MB_TAG, "[05-MATCH] STOP: subjectId kosong pada subject terpilih")
            return
        }

        // [FIX-4] Indexing season TIDAK ditebak. Server ditanya lewat season-info
        // (endpoint + struktur sama persis dengan MovieBoxProvider). Pergeseran
        // season-1 hanya dilakukan bila daftar season milik server memang dimulai
        // dari 0. Bila season-info kosong / gagal, perilaku lama dipakai apa adanya.
        var resolvedSe: Int? = null
        if (season != null) {
            val rawSeason = MovieboxHelper.getSigned(
                "/wefeed-mobile-bff/subject-api/season-info",
                "subjectId=$subjectId",
                bearer
            )
            val seasonList = rawSeason
                ?.let { tryParseJson<MovieboxSeasonInfoResponse>(it) }
                ?.data?.seasons.orEmpty()
            val available = seasonList.mapNotNull { it.se }.sorted()

            val seasonDump = seasonList
                .joinToString { "se=" + it.se + "/maxEp=" + it.maxEp }
                .ifBlank { "(kosong)" }
            Log.d(MB_TAG, "[06-SEASON] subjectId=$subjectId server=$seasonDump")

            if (available.isNotEmpty()) {
                resolvedSe = when {
                    available.contains(season) -> season
                    available.first() == 0 && available.contains(season - 1) -> season - 1
                    else -> null
                }
                Log.d(MB_TAG, "[06-SEASON] tmdbSeason=$season available=$available => resolvedSe=$resolvedSe")
                if (resolvedSe == null) {
                    // Indikasi kuat MovieBox memecah serial menjadi subject terpisah
                    // per season: subject terpilih tidak memuat season yang diminta.
                    // TIDAK di-remap paksa (bisa memutar episode dari season lain);
                    // baris ini yang membuktikannya di logcat.
                    Log.w(
                        MB_TAG,
                        "[06-SEASON] season $season TIDAK ada pada subject $subjectId " +
                            "(server hanya punya $available) -> kemungkinan subject per-season"
                    )
                }
            } else {
                Log.d(MB_TAG, "[06-SEASON] season-info tidak tersedia, pakai candidatePairs lama")
            }
        }

        // Movie mengikuti candidatePairs asli MovieBoxProvider.
        // Untuk TV, fallback "1 to 1 / 0 to 0" milik source asli tetap TIDAK dipakai:
        // di konteks TMDB itu akan memutar episode yang salah (S03E07 -> S01E01).
        // Nomor episode SELALU dipertahankan di semua cabang di bawah ini.
        val ep = episode ?: 1
        val resolved = resolvedSe
        val candidatePairs: List<Pair<Int, Int>> = if (season == null) {
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
        } else if (resolved != null) {
            listOf(resolved to ep)
        } else if (season == 1) {
            listOf(1 to ep, 0 to ep)
        } else {
            listOf(season to ep)
        }
        Log.d(MB_TAG, "[07-PAIRS] candidatePairs=$candidatePairs")

        var foundStreams: List<MovieboxStreamItem>? = null
        for ((se, epNum) in candidatePairs) {
            // URUTAN QUERY WAJIB ALFABETIS (ep, se, subjectId) - ikut ditandatangani.
            val raw = MovieboxHelper.getSigned(
                "/wefeed-mobile-bff/subject-api/play-info",
                "ep=$epNum&se=$se&subjectId=$subjectId",
                bearer
            )
            if (raw == null) {
                Log.e(MB_TAG, "[08-PLAY] se=$se ep=$epNum HTTP gagal / exception")
                continue
            }

            val play = tryParseJson<MovieboxPlayInfoResponse>(raw)
            if (play == null) {
                Log.e(MB_TAG, "[08-PLAY] se=$se ep=$epNum JSON gagal di-parse. head=${raw.take(300)}")
                continue
            }

            val all = play.data?.streams.orEmpty()
            val usable = all
                .filter { !it.url.isNullOrBlank() && !it.signCookie.isNullOrBlank() }
                .distinctBy { it.url }

            val noUrl = all.count { it.url.isNullOrBlank() }
            val noCookie = all.count { it.signCookie.isNullOrBlank() }
            Log.d(
                MB_TAG,
                "[08-PLAY] se=$se ep=$epNum code=${play.code} msg=${play.message} " +
                    "streams=${all.size} usable=${usable.size} " +
                    "tanpaUrl=$noUrl tanpaSignCookie=$noCookie"
            )

            if (usable.isNotEmpty()) {
                foundStreams = usable
                break
            }
        }

        val streams = foundStreams
        if (streams == null) {
            Log.e(
                MB_TAG,
                "[08-PLAY] STOP: tidak ada stream layak. subjectId=$subjectId pairs=$candidatePairs"
            )
            return
        }

        // ---------- SUBTITLE ----------
        val streamId = streams.firstOrNull()?.id
        if (!streamId.isNullOrBlank()) {
            // URUTAN QUERY WAJIB ALFABETIS (streamId, subjectId).
            val rawSub = MovieboxHelper.getSigned(
                "/wefeed-mobile-bff/subject-api/get-stream-captions",
                "streamId=$streamId&subjectId=$subjectId",
                bearer
            )
            if (rawSub == null) {
                Log.w(MB_TAG, "[09-SUB] get-stream-captions gagal (stream tetap dilanjutkan)")
            } else {
                val caps = tryParseJson<MovieboxCaptionResponse>(rawSub)?.data?.extCaptions.orEmpty()
                var sent = 0
                caps.forEach { cap ->
                    val subUrl = cap.url ?: return@forEach
                    val label = cap.lanName ?: cap.lan ?: cap.language ?: "Unknown"
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                    sent++
                }
                Log.d(MB_TAG, "[09-SUB] extCaptions=${caps.size} terkirim=$sent")
            }
        } else {
            Log.w(MB_TAG, "[09-SUB] streamId kosong, subtitle dilewati")
        }

        // ---------- STREAM ----------
        var emitted = 0
        streams.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val cleanCookie = (stream.signCookie ?: return@forEach).trimEnd(';')

            val linkType = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH
            val quality = getQualityFromName(stream.resolutions)
                .takeIf { it != Qualities.Unknown.value } ?: Qualities.P1080.value
            val label = stream.resolutions?.let { "MovieBox ${it}p" } ?: "MovieBox (DASH)"

            callback.invoke(
                newExtractorLink("MovieBox", label, streamUrl, linkType) {
                    this.referer = MovieboxHelper.API_URL
                    this.quality = quality
                    // Cookie di sini dibaca lagi oleh Adicinemax21.getVideoInterceptor()
                    // supaya ExoPlayer ikut mengirimnya ke CDN.
                    this.headers = mapOf(
                        "User-Agent" to MovieboxHelper.USER_AGENT,
                        "Cookie" to cleanCookie,
                        "Referer" to MovieboxHelper.API_URL
                    )
                }
            )
            emitted++
            Log.d(MB_TAG, "[10-LINK] dibuat: $label type=$linkType q=$quality")
        }
        Log.d(MB_TAG, "[10-LINK] SELESAI, total ExtractorLink=$emitted")
    }

    // ================== MOVIEBOX ENGINE (AUTH + SIGNED REQUEST) ==================
    // Dipindahkan apa adanya dari MovieBoxProvider. JANGAN mengubah apa pun di
    // dalam buildCanonical/generateSignature/headersFor: server memvalidasi
    // signature byte per byte.
    private object MovieboxHelper {

        const val API_URL = "https://api3.aoneroom.com"
        const val USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"

        private const val CLIENT_INFO = """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","device_id":"71e0f7746936dc98","install_store":"ps","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":""}"""

        // Double base64 decode, persis MovieBoxProvider.
        private val SECRET_BYTES: ByteArray by lazy {
            val step1 = String(
                Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", Base64.DEFAULT),
                Charsets.UTF_8
            )
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /**
         * Canonical string mengikuti GatewaySignManager.doSign pada APK resmi.
         * Tujuh baris dipisah "\n":
         *   1. HTTP method (huruf besar)
         *   2. accept
         *   3. content-type
         *   4. panjang body   -> kosong bila tanpa body
         *   5. timestamp
         *   6. md5 hex body   -> kosong bila tanpa body
         *   7. path (+query)
         *
         * Tanpa body baris 4 dan 6 kosong, sehingga fungsi ini aman untuk GET
         * maupun POST. Baris 4 dan 6 HARUS diisi bersamaan atau kosong bersamaan.
         */
        private fun buildCanonical(method: String, pathWithQuery: String, ts: String, body: String): String {
            val length = if (body.isEmpty()) "" else body.length.toString()
            val digest = if (body.isEmpty()) "" else md5(body)
            return listOf(
                method.uppercase(),
                "application/json",
                "application/json",
                length,
                ts,
                digest,
                pathWithQuery
            ).joinToString("\n")
        }

        // NO_WRAP wajib. Base64.DEFAULT menambahkan newline dan merusak header.
        private fun generateSignature(method: String, pathWithQuery: String, ts: String, body: String = ""): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes = mac.doFinal(buildCanonical(method, pathWithQuery, ts, body).toByteArray(Charsets.UTF_8))
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

        private fun headersFor(ts: String, signature: String, bearer: String?): Map<String, String> {
            val h = mutableMapOf(
                "user-agent" to USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to signature,
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0"
            )
            if (!bearer.isNullOrBlank()) h["authorization"] = "Bearer $bearer"
            return h
        }

        suspend fun getSigned(path: String, query: String, bearer: String?): String? {
            val ts = System.currentTimeMillis().toString()
            val pathWithQuery = if (query.isBlank()) path else "$path?$query"
            return try {
                app.get(
                    "$API_URL$pathWithQuery",
                    headers = headersFor(ts, generateSignature("GET", pathWithQuery, ts), bearer)
                ).text
            } catch (e: Exception) {
                null
            }
        }

        /**
         * POST ber-signature.
         *
         * PENTING: RequestBody dibuat dari ByteArray, BUKAN String. Overload
         * String pada OkHttp menambahkan "; charset=utf-8" ke media type, lalu
         * BridgeInterceptor menimpa header Content-Type. Akibatnya yang dikirim
         * "application/json; charset=utf-8" sedangkan yang ditandatangani
         * "application/json" -> server menolak dengan 407.
         */
        suspend fun postSigned(path: String, body: String, bearer: String?): String? {
            val ts = System.currentTimeMillis().toString()
            val sig = generateSignature("POST", path, ts, body)
            return try {
                val res = app.post(
                    "$API_URL$path",
                    headers = headersFor(ts, sig, bearer),
                    requestBody = body.toByteArray(Charsets.UTF_8)
                        .toRequestBody("application/json".toMediaTypeOrNull())
                )
                if (res.code == 200) res.text else null
            } catch (e: Exception) {
                null
            }
        }

        // Token guest diambil dari header response "x-user" pada endpoint ranking-list.
        suspend fun getBearerToken(): String? {
            return try {
                val ts = System.currentTimeMillis().toString()
                val path = "/wefeed-mobile-bff/tab/ranking-list"
                val query = "page=1&perPage=1&tabId=0"

                val response = app.get(
                    "$API_URL$path?$query",
                    headers = headersFor(ts, generateSignature("GET", "$path?$query", ts), null)
                )

                val xUserHeader = response.headers["x-user"] ?: return null
                Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(xUserHeader)?.groupValues?.get(1)
            } catch (e: Exception) {
                null
            }
        }
    }
}

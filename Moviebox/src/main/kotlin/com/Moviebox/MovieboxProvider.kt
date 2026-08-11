package com.Moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.net.URLEncoder
import java.net.URLDecoder

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    override val mainPage = mainPageOf(
        "872031290915189720" to "Trending",
        "8821254238245470240" to "Film",
        "6528093688173053896" to "Indo Film",
        "4380734070238626200" to "K-Drama",
        "5283462032510044280" to "Indo Drama",
        "8617025562613270856" to "Anime",
        "1469286917119311888" to "Hollywood",
        "8624142774394406504" to "C-Drama",
        "5848753831881965888" to "Horror",
        "1164329479448281992" to "Thai-Drama"
    )

    companion object {
        private const val CS_USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val CLIENT_INFO = """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","device_id":"71e0f7746936dc98","install_store":"ps","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":""}"""

        private val SECRET_BYTES: ByteArray by lazy {
            val step1 = String(Base64.decode("NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==", Base64.DEFAULT), Charsets.UTF_8)
            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        private fun md5Base64(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return Base64.encodeToString(md.digest(input.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }

        private fun signCanonical(canonical: String, ts: String): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        // GET: bentuk ini TERBUKTI bekerja (main page, detail, season, play-info).
        // JANGAN diubah.
        private fun generateSignature(pathWithQuery: String, ts: String): String =
            signCanonical("GET\napplication/json\napplication/json\n\n$ts\n\n$pathWithQuery", ts)

        // POST: bentuk canonical-nya belum terbukti dari DEX. Kandidat dicoba
        // berurutan saat runtime; yang diterima server di-cache supaya
        // request berikutnya langsung memakai yang benar.
        private fun postCanonical(variant: Int, path: String, ts: String, body: String): String =
            when (variant) {
                0 -> "POST\napplication/json\napplication/json\n${md5Base64(body)}\n$ts\n\n$path"
                1 -> "POST\napplication/json\napplication/json\n\n$ts\n\n$path"
                2 -> "POST\napplication/json\napplication/json\n${md5(body)}\n$ts\n\n$path"
                3 -> "GET\napplication/json\napplication/json\n\n$ts\n\n$path"
                4 -> "POST\napplication/json\napplication/json\n\n$ts\n\n$path\n$body"
                else -> "POST\napplication/json\napplication/json\n\n$ts\n${md5Base64(body)}\n$path"
            }

        private const val POST_VARIANT_COUNT = 6
        @Volatile private var knownPostVariant: Int? = null

        private fun generateGuestToken(ts: String): String = "$ts,${md5(ts.reversed())}"

        private fun enc(str: String?): String =
            if (str.isNullOrBlank()) "" else URLEncoder.encode(str, "UTF-8")

        private fun dec(str: String?): String =
            if (str.isNullOrBlank()) "" else URLDecoder.decode(str, "UTF-8")
    }

    private fun baseHeaders(ts: String, signature: String, bearer: String?): Map<String, String> {
        val h = mutableMapOf(
            "user-agent" to CS_USER_AGENT,
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

    /**
     * POST ber-signature. Mengembalikan body response mentah, atau null.
     * Mencoba tiap varian canonical sampai server menjawab code 0, lalu
     * mengingat varian tersebut.
     */
    private suspend fun postSigned(path: String, body: String, bearer: String?): String? {
        val order = knownPostVariant?.let { listOf(it) } ?: (0 until POST_VARIANT_COUNT).toList()
        val mediaType = "application/json".toMediaTypeOrNull()

        for (variant in order) {
            val ts = System.currentTimeMillis().toString()
            val sig = signCanonical(postCanonical(variant, path, ts, body), ts)
            val res = try {
                app.post(
                    "$mainUrl$path",
                    headers = baseHeaders(ts, sig, bearer),
                    requestBody = body.toRequestBody(mediaType)
                )
            } catch (e: Exception) {
                continue
            }
            val text = res.text
            if (res.code == 200 && text.contains("\"code\":0")) {
                knownPostVariant = variant
                return text
            }
        }
        return null
    }

    private suspend fun getBearerToken(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"

        val response = app.get(
            "$mainUrl$path?$query",
            headers = baseHeaders(ts, generateSignature("$path?$query", ts), null)
        )

        val xUserHeader = response.headers["x-user"] ?: return null
        return """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)?.groupValues?.get(1)
    }

    // ---------------------------------------------------------------
    // Pemungut hasil yang tahan perubahan bentuk response.
    // Nama field yang dipakai (subjectId / title / cover.url / subjectType)
    // semuanya TERBUKTI dari dump JSON server dan dari DTO di APK
    // (SearchSubject mewarisi Subject). Yang TIDAK diasumsikan adalah
    // di kedalaman mana objek itu berada, karena bentuk pembungkusnya
    // (results/pager/tabs) belum dibuktikan field demi field.
    // ---------------------------------------------------------------
    private fun collectSubjects(node: Any?, out: MutableList<SearchResponse>, seen: MutableSet<String>) {
        when (node) {
            is JSONObject -> {
                val subjectId = node.optString("subjectId", "")
                val title = node.optString("title", "")
                if (subjectId.isNotBlank() && title.isNotBlank() && seen.add(subjectId)) {
                    val poster = node.optJSONObject("cover")?.optString("url").orEmpty()
                    val isSeries = node.optInt("subjectType", 1) == 2
                    val detailUrl = "$mainUrl/detail?id=$subjectId"
                    out.add(
                        if (isSeries) {
                            newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        } else {
                            newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                                this.posterUrl = poster
                            }
                        }
                    )
                }
                for (key in node.keys()) collectSubjects(node.opt(key), out, seen)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) collectSubjects(node.opt(i), out, seen)
            }
        }
    }

    private fun parseSubjects(rawJson: String?): List<SearchResponse> {
        if (rawJson.isNullOrBlank()) return emptyList()
        val out = mutableListOf<SearchResponse>()
        try {
            collectSubjects(JSONObject(rawJson), out, mutableSetOf())
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }

    // 1. MAIN PAGE  (tidak diubah - sudah bekerja)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val bearerToken = getBearerToken() ?: return null
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "categoryType=${request.data}&page=$page&perPage=10&tabId=0"

        val response = app.get(
            "$mainUrl$path?$query",
            headers = baseHeaders(ts, generateSignature("$path?$query", ts), bearerToken)
        )

        val jsonRes = response.parsedSafe<RankingResponse>() ?: return null
        val dataObj = jsonRes.data ?: return null

        val homeItems = dataObj.subjects?.mapNotNull { item ->
            val subjectId = item.subjectId ?: return@mapNotNull null
            val title = item.title ?: "Unknown"
            val posterUrl = item.cover?.url ?: ""
            val detailUrl = "$mainUrl/detail?id=$subjectId"

            if ((item.subjectType ?: 1) == 2) {
                newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems)
    }

    // 2. SEARCH  -- endpoint resmi
    //    POST /wefeed-mobile-bff/subject-api/search/v2
    //    body {"page":1,"perPage":10,"keyword":<q>,"tabId":""}
    //    page=1 dan perPage=10 keduanya terbukti dari bytecode aplikasi.
    override suspend fun search(query: String): List<SearchResponse> {
        val bearerToken = getBearerToken() ?: return emptyList()
        val body = JSONObject()
            .put("page", 1)
            .put("perPage", 10)
            .put("keyword", query)
            .put("tabId", "")
            .toString()

        val text = postSigned("/wefeed-mobile-bff/subject-api/search/v2", body, bearerToken)
            ?: return emptyList()
        return parseSubjects(text)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    data class EpData(
        val subjectId: String,
        val se: Int,
        val ep: Int,
        val subjectType: Int = 1
    )

    // Rekomendasi: POST /wefeed-mobile-bff/subject-api/detail-rec
    // body {"subjectId":<id>,"page":1,"perPage":6}
    private suspend fun fetchRecommendations(subjectId: String, bearer: String?): List<SearchResponse> {
        val body = JSONObject()
            .put("subjectId", subjectId)
            .put("page", 1)
            .put("perPage", 6)
            .toString()
        val text = postSigned("/wefeed-mobile-bff/subject-api/detail-rec", body, bearer)
        return parseSubjects(text).filterNot {
            it.url.substringAfter("id=").substringBefore("&") == subjectId
        }
    }

    // 3. LOAD
    override suspend fun load(url: String): LoadResponse? {
        val cleanId = when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/") -> url.substringAfterLast("/").substringBefore("?")
            else -> url.trim()
        }

        val bearerToken = getBearerToken() ?: return null

        val ts = System.currentTimeMillis().toString()
        val pathGet = "/wefeed-mobile-bff/subject-api/get"
        val queryGet = "subjectId=$cleanId"

        val responseGet = app.get(
            "$mainUrl$pathGet?$queryGet",
            headers = baseHeaders(ts, generateSignature("$pathGet?$queryGet", ts), bearerToken)
        )

        val detailRes = responseGet.parsedSafe<SubjectDetailResponse>()
        val subject = detailRes?.data ?: return null

        val displayTitle = subject.title ?: "MovieBox Content"
        val poster = subject.cover?.url
        val typeInt = subject.subjectType ?: 1
        val description = subject.description
        val yearInt = subject.releaseDate?.take(4)?.toIntOrNull()
        val ratingStr = subject.imdbRatingValue ?: subject.imdbRate

        // Server mengirim "VideoAddress" (huruf besar). DTO lama memetakan
        // "videoAddress" sehingga parsedSafe mengisi null tanpa error dan
        // trailer tidak pernah muncul. Sekarang kedua ejaan diterima.
        val trailerUrl = subject.trailer?.let { it.videoAddressUpper ?: it.videoAddressLower }?.url

        val genreTags = subject.genre?.split(",")?.map { it.trim() } ?: emptyList()

        val castActors = subject.staffList?.mapNotNull { staff ->
            val staffName = staff.name ?: return@mapNotNull null
            ActorData(
                actor = Actor(staffName, staff.avatarUrl),
                roleString = staff.character
            )
        } ?: emptyList()

        val recs = try {
            fetchRecommendations(cleanId, bearerToken)
        } catch (e: Exception) {
            emptyList()
        }

        val tsSeason = System.currentTimeMillis().toString()
        val pathSeason = "/wefeed-mobile-bff/subject-api/season-info"
        val querySeason = "subjectId=$cleanId"

        val responseSeason = app.get(
            "$mainUrl$pathSeason?$querySeason",
            headers = baseHeaders(tsSeason, generateSignature("$pathSeason?$querySeason", tsSeason), bearerToken)
        )

        val seasonRes = responseSeason.parsedSafe<SeasonInfoResponse>()
        val seasons = seasonRes?.data?.seasons

        val episodesList = mutableListOf<Episode>()

        seasons?.forEach { seasonItem ->
            val seNum = seasonItem.se ?: 1
            val maxEp = seasonItem.maxEp ?: 1

            for (epNum in 1..maxEp) {
                episodesList.add(
                    newEpisode(EpData(cleanId, seNum, epNum, typeInt)) {
                        this.name = "Episode $epNum"
                        this.season = seNum
                        this.episode = epNum
                    }
                )
            }
        }

        val isSeries = typeInt == 2 || episodesList.size > 1

        return if (isSeries) {
            if (episodesList.isEmpty()) {
                episodesList.add(
                    newEpisode(EpData(cleanId, 1, 1, 2)) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = description
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
                this.actors = castActors
                this.tags = genreTags
                this.recommendations = recs
                if (!trailerUrl.isNullOrBlank()) {
                    this.trailers.add(TrailerData(trailerUrl, mainUrl, true))
                }
            }
        } else {
            newMovieLoadResponse(displayTitle, url, TvType.Movie, EpData(cleanId, 0, 0, 1)) {
                this.posterUrl = poster
                this.plot = description
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
                this.actors = castActors
                this.tags = genreTags
                this.recommendations = recs
                if (!trailerUrl.isNullOrBlank()) {
                    this.trailers.add(TrailerData(trailerUrl, mainUrl, true))
                }
            }
        }
    }

    // 4. INTERCEPTOR COOKIE EXOPLAYER  (tidak diubah)
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val cookie = extractorLink.headers["Cookie"]
            if (!cookie.isNullOrBlank()) {
                val newRequest = request.newBuilder()
                    .header("Cookie", cookie)
                    .header("User-Agent", CS_USER_AGENT)
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
    }

    // 5. LOAD LINKS  (tidak diubah)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = AppUtils.tryParseJson<EpData>(data) ?: return false
        val bearerToken = getBearerToken() ?: return false

        val candidatePairs = if (epData.subjectType == 1 || (epData.se == 0 && epData.ep == 0)) {
            listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
        } else {
            listOf(epData.se to epData.ep, 1 to 1, 0 to 0)
        }

        var foundStream: StreamItem? = null

        for ((se, ep) in candidatePairs) {
            val ts = System.currentTimeMillis().toString()
            val path = "/wefeed-mobile-bff/subject-api/play-info"
            val query = "ep=$ep&se=$se&subjectId=${epData.subjectId}"

            val response = app.get(
                "$mainUrl$path?$query",
                headers = baseHeaders(ts, generateSignature("$path?$query", ts), bearerToken)
            )

            val playData = response.parsedSafe<PlayInfoResponse>()
            val stream = playData?.data?.streams?.firstOrNull()

            if (stream?.url != null && !stream.signCookie.isNullOrBlank()) {
                foundStream = stream
                break
            }
        }

        val targetStream = foundStream ?: return false
        val mpdUrl = targetStream.url ?: return false
        val cleanCookie = (targetStream.signCookie ?: return false).trimEnd(';')

        callback(
            newExtractorLink(
                source = name,
                name = "MovieBox (DASH HEVC)",
                url = mpdUrl,
                type = ExtractorLinkType.DASH
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "User-Agent" to CS_USER_AGENT,
                    "Cookie" to cleanCookie,
                    "Referer" to mainUrl
                )
            }
        )

        return true
    }

    // MODELS
    data class RankingResponse(val code: Int?, val data: RankingData?)
    data class RankingData(
        val categoryList: List<CategoryItem>?,
        val subjects: List<SubjectItem>?
    )
    data class CategoryItem(val name: String?, val type: String?)

    data class SubjectDetailResponse(val code: Int?, val data: SubjectDetailItem?)
    data class SubjectDetailItem(
        val subjectId: String?,
        val title: String?,
        val cover: CoverItem?,
        val subjectType: Int?,
        val description: String?,
        val releaseDate: String?,
        val imdbRatingValue: String?,
        val imdbRate: String?,
        val genre: String?,
        val staffList: List<StaffItem>?,
        val trailer: TrailerItem?
    )

    data class SubjectItem(
        val subjectId: String?,
        val title: String?,
        val cover: CoverItem?,
        val subjectType: Int?
    )
    data class CoverItem(val url: String?)

    data class StaffItem(
        val staffId: String?,
        val name: String?,
        val character: String?,
        val avatarUrl: String?
    )

    // Server mengirim "VideoAddress"; DTO resmi APK menulis "videoAddress".
    // Kedua-duanya diterima supaya tidak bergantung pada versi respons.
    data class TrailerItem(
        @JsonProperty("VideoAddress") val videoAddressUpper: VideoAddressItem? = null,
        @JsonProperty("videoAddress") val videoAddressLower: VideoAddressItem? = null
    )
    data class VideoAddressItem(
        val url: String?,
        val definition: String? = null,
        val duration: Int? = null
    )

    data class SeasonInfoResponse(val code: Int?, val data: SeasonInfoData?)
    data class SeasonInfoData(
        val subjectId: String?,
        val subjectType: Int?,
        val seasons: List<SeasonItem>?
    )
    data class SeasonItem(
        val se: Int?,
        val maxEp: Int?
    )

    data class PlayInfoResponse(val code: Int?, val message: String?, val data: PlayData?)
    data class PlayData(val streams: List<StreamItem>?)
    data class StreamItem(
        val format: String?,
        val id: String?,
        val url: String?,
        val resolutions: String?,
        val size: String?,
        val duration: Long?,
        val codecName: String?,
        val signCookie: String?
    )
}

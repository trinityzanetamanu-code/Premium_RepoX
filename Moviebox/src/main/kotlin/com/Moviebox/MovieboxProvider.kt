package com.Moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
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

        private fun generateSignature(pathWithQuery: String, ts: String): String {
            val canonical = "GET\napplication/json\napplication/json\n\n$ts\n\n$pathWithQuery"
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val hmacBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            val sigB64 = Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
            return "$ts|2|$sigB64"
        }

        private fun generateGuestToken(ts: String): String {
            val revTs = ts.reversed()
            return "$ts,${md5(revTs)}"
        }

        private fun enc(str: String?): String {
            return if (str.isNullOrBlank()) "" else URLEncoder.encode(str, "UTF-8")
        }

        private fun dec(str: String?): String {
            return if (str.isNullOrBlank()) "" else URLDecoder.decode(str, "UTF-8")
        }
    }

    private suspend fun getBearerToken(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "page=1&perPage=1&tabId=0"
        val fullUrl = "$mainUrl$path?$query"

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "user-agent" to CS_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to generateSignature("$path?$query", ts),
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0"
            )
        )

        val xUserHeader = response.headers["x-user"] ?: return null
        val tokenMatch = """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)
        return tokenMatch?.groupValues?.get(1)
    }

    // 1. MAIN PAGE (HOMEPAGE & CATEGORIES)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val bearerToken = getBearerToken() ?: return null
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "categoryType=${request.data}&page=$page&perPage=10&tabId=0"
        val fullUrl = "$mainUrl$path?$query"

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "authorization" to "Bearer $bearerToken",
                "user-agent" to CS_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to generateSignature("$path?$query", ts),
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0"
            )
        )

        val jsonRes = response.parsedSafe<RankingResponse>() ?: return null
        val dataObj = jsonRes.data ?: return null

        val homeItems = dataObj.subjects?.mapNotNull { item ->
            val subjectId = item.subjectId ?: return@mapNotNull null
            val title = item.title ?: "Unknown"
            val posterUrl = item.cover?.url ?: ""
            val subjectType = item.subjectType ?: 1
            val description = item.description ?: ""
            val year = item.releaseYear ?: item.releaseDate?.take(4)?.toIntOrNull()
            val rating = item.imdbRatingValue ?: item.imdbRate

            val detailUrl = "$mainUrl/detail?id=$subjectId&title=${enc(title)}&poster=${enc(posterUrl)}&type=$subjectType&desc=${enc(description)}&year=${year ?: ""}&rate=${enc(rating)}"

            if (subjectType == 2) {
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

    // 2. SEARCH
    override suspend fun search(query: String): List<SearchResponse>? {
        val bearerToken = getBearerToken() ?: return null
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/search/search-list"
        val queryStr = "keyword=${enc(query)}&page=1&perPage=20"
        val fullUrl = "$mainUrl$path?$queryStr"

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "authorization" to "Bearer $bearerToken",
                "user-agent" to CS_USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to generateSignature("$path?$queryStr", ts),
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0"
            )
        )

        val searchRes = response.parsedSafe<SearchResponseData>()
        val items = searchRes?.data?.list ?: searchRes?.data?.subjects

        return items?.mapNotNull { item ->
            val subjectId = item.subjectId ?: return@mapNotNull null
            val title = item.title ?: "Unknown"
            val posterUrl = item.cover?.url ?: ""
            val subjectType = item.subjectType ?: 1
            val description = item.description ?: ""
            val year = item.releaseYear ?: item.releaseDate?.take(4)?.toIntOrNull()
            val rating = item.imdbRatingValue ?: item.imdbRate

            val detailUrl = "$mainUrl/detail?id=$subjectId&title=${enc(title)}&poster=${enc(posterUrl)}&type=$subjectType&desc=${enc(description)}&year=${year ?: ""}&rate=${enc(rating)}"

            if (subjectType == 2) {
                newTvSeriesSearchResponse(title, detailUrl, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, detailUrl, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    data class EpData(
        val subjectId: String,
        val se: Int,
        val ep: Int,
        val subjectType: Int = 1
    )

    // 3. LOAD (HALAMAN DETAIL & EPISODE)
    override suspend fun load(url: String): LoadResponse? {
        val cleanId = when {
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            url.contains("/") -> url.substringAfterLast("/").substringBefore("?")
            else -> url.trim()
        }

        val params = try {
            if (url.contains("?")) {
                url.substringAfter("?").split("&").associate {
                    val pair = it.split("=")
                    if (pair.size == 2) pair[0] to dec(pair[1]) else "" to ""
                }
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }

        val rawTitle = params["title"]?.takeIf { it.isNotBlank() }
        val poster = params["poster"]?.takeIf { it.isNotBlank() }
        val typeInt = params["type"]?.toIntOrNull() ?: 1
        val descStr = params["desc"]?.takeIf { it.isNotBlank() }
        val yearInt = params["year"]?.toIntOrNull()
        val ratingStr = params["rate"]?.takeIf { it.isNotBlank() }

        val bearerToken = getBearerToken()

        var seasons: List<SeasonItem>? = null
        if (bearerToken != null) {
            val ts = System.currentTimeMillis().toString()
            val path = "/wefeed-mobile-bff/subject-api/season-info"
            val query = "subjectId=$cleanId"
            val fullUrl = "$mainUrl$path?$query"

            val response = app.get(
                fullUrl,
                headers = mapOf(
                    "authorization" to "Bearer $bearerToken",
                    "user-agent" to CS_USER_AGENT,
                    "accept" to "application/json",
                    "content-type" to "application/json",
                    "x-client-token" to generateGuestToken(ts),
                    "x-tr-signature" to generateSignature("$path?$query", ts),
                    "x-client-info" to CLIENT_INFO,
                    "x-client-status" to "0"
                )
            )

            val seasonRes = response.parsedSafe<SeasonInfoResponse>()
            seasons = seasonRes?.data?.seasons
        }

        val displayTitle = if (!rawTitle.isNullOrBlank()) rawTitle else "MovieBox Content"
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
                this.plot = descStr ?: "Saksikan $displayTitle di MovieBox."
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
            }
        } else {
            newMovieLoadResponse(displayTitle, url, TvType.Movie, EpData(cleanId, 0, 0, 1)) {
                this.posterUrl = poster
                this.plot = descStr ?: "Saksikan $displayTitle di MovieBox."
                this.year = yearInt
                this.score = Score.from(ratingStr, 10)
            }
        }
    }

    // 4. INTERCEPTOR COOKIE EXOPLAYER
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

    // 5. LOAD LINKS
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
            val fullUrl = "$mainUrl$path?$query"

            val response = app.get(
                fullUrl,
                headers = mapOf(
                    "authorization" to "Bearer $bearerToken",
                    "user-agent" to CS_USER_AGENT,
                    "accept" to "application/json",
                    "content-type" to "application/json",
                    "x-client-token" to generateGuestToken(ts),
                    "x-tr-signature" to generateSignature("$path?$query", ts),
                    "x-client-info" to CLIENT_INFO,
                    "x-client-status" to "0"
                )
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
        val rawCookie = targetStream.signCookie ?: return false
        val cleanCookie = rawCookie.trimEnd(';')

        callback(
            ExtractorLink(
                source = name,
                name = "MovieBox (DASH HEVC)",
                url = mpdUrl,
                referer = mainUrl,
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.DASH,
                headers = mapOf(
                    "User-Agent" to CS_USER_AGENT,
                    "Cookie" to cleanCookie,
                    "Referer" to mainUrl
                )
            )
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
    data class SubjectItem(
        val subjectId: String?,
        val title: String?,
        val cover: CoverItem?,
        val subjectType: Int?,
        val description: String?,
        val releaseDate: String?,
        val releaseYear: Int?,
        val imdbRatingValue: String?,
        val imdbRate: String?
    )
    data class CoverItem(val url: String?)

    data class SearchResponseData(val code: Int?, val data: SearchInnerData?)
    data class SearchInnerData(
        val list: List<SubjectItem>?,
        val subjects: List<SubjectItem>?
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

package com.Moviebox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class MovieboxProvider : MainAPI() {
    override var name = "Moviebox"

    // Domain Utama (Digunakan untuk Origin dan Referer)
    override var mainUrl = "https://moviebox.ph"

    // Domain Khusus API (Terpusat)
    private val apiBaseUrl = "https://h5-api.aoneroom.com/wefeed-h5api-bff"

    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    /*
     * ===================================================================
     * CATATAN PENTING — hasil investigasi 2026-08-06
     * ===================================================================
     * API ini TIDAK KONSISTEN soal Authorization. Sudah dibuktikan lewat
     * pengujian langsung per-endpoint:
     *
     *   /subject/search  -> WAJIB token. Tanpa token: HTTP 400 "invalid token"
     *   /home, /detail   -> token opsional (jalan dengan maupun tanpa)
     *   /subject/play    -> HARUS TANPA token
     *
     * Sebab /subject/play menolak token: uid 6544730643964516232 sudah
     * kehabisan kuota tonton gratis. Server membalas HTTP 200 code=0 "ok"
     * tetapi mengosongkan streams:
     *
     *   dengan token   -> {"limited":true,  "freeNum":3,   "streams":[]}
     *   tanpa token    -> {"limited":false, "freeNum":999, "streams":[...]}
     *
     * Karena itu header dipisah jadi dua fungsi di bawah. JANGAN disatukan.
     *
     * MASA BERLAKU TOKEN: exp = 2026-09-25 04:43:22 UTC.
     * Setelah tanggal itu search & home akan mati dengan "invalid token".
     * ===================================================================
     */
    private val bearerToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjY1NDQ3MzA2NDM5NjQ1MTYyMzIsImF0cCI6MywiZXh0IjoiMTc4MjUzNTQwMiIsImV4cCI6MTc5Mzk5MzM1NSwiaWF0IjoxNzgyNTM1MTAyfQ.d2WpLFeF0erMdSlaaM1RMgnpyB4j1R1s2xVcY6a2Ut8"

    /** Header untuk home / search / detail / rec — DENGAN token. */
    private fun getApiHeaders(customReferer: String = "$mainUrl/"): Map<String, String> {
        return mapOf(
            "Accept" to "application/json",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "Origin" to mainUrl,
            "Referer" to customReferer,
            "Authorization" to "Bearer $bearerToken"
        )
    }

    /** Header khusus playback — TANPA token, kalau tidak streams akan kosong. */
    private fun getPlayHeaders(customReferer: String): Map<String, String> {
        return mapOf(
            "Accept" to "application/json",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "Origin" to mainUrl,
            "Referer" to customReferer
        )
    }

    // --- DATA CLASSES ---
    data class HomeResponse(@param:JsonProperty("data") val data: HomeData?)
    data class HomeData(@param:JsonProperty("operatingList") val operatingList: List<OperatingList>?)
    data class OperatingList(@param:JsonProperty("title") val title: String?, @param:JsonProperty("subjects") val subjects: List<Subject>?, @param:JsonProperty("banner") val banner: Banner?)
    data class Banner(@param:JsonProperty("items") val items: List<BannerItem>?)
    data class BannerItem(@param:JsonProperty("subject") val subject: Subject?)

    data class SearchApiResponse(@param:JsonProperty("data") val data: SearchData?)
    data class SearchData(@param:JsonProperty("subjectList") val subjectList: List<Subject>?, @param:JsonProperty("items") val items: List<Subject>?, @param:JsonProperty("list") val list: List<Subject>?)
    data class Subject(@param:JsonProperty("title") val title: String?, @param:JsonProperty("subjectId") val subjectId: String?, @param:JsonProperty("subjectType") val subjectType: Int?, @param:JsonProperty("detailPath") val detailPath: String?, @param:JsonProperty("releaseDate") val releaseDate: String?, @param:JsonProperty("cover") val cover: ImageInfo?)
    data class ImageInfo(@param:JsonProperty("url") val url: String?)

    data class DetailResponse(@param:JsonProperty("data") val data: DetailDataWrapper?)
    data class DetailDataWrapper(@param:JsonProperty("subject") val subject: DetailData?, @param:JsonProperty("stars") val stars: List<Star>?, @param:JsonProperty("resource") val resource: ResourceData?)
    data class DetailData(@param:JsonProperty("subjectId") val subjectId: String?, @param:JsonProperty("title") val title: String?, @param:JsonProperty("description") val description: String?, @param:JsonProperty("releaseDate") val releaseDate: String?, @param:JsonProperty("cover") val cover: ImageInfo?, @param:JsonProperty("imdbRatingValue") val imdbRatingValue: String?, @param:JsonProperty("subjectType") val subjectType: Int?, @param:JsonProperty("episodes") val episodes: List<EpisodeInfo>?)
    data class Star(@param:JsonProperty("name") val name: String?, @param:JsonProperty("avatarUrl") val avatarUrl: String?, @param:JsonProperty("character") val character: String?)
    data class ResourceData(@param:JsonProperty("seasons") val seasons: List<SeasonDataApi>?)
    data class SeasonDataApi(@param:JsonProperty("se") val se: Int?, @param:JsonProperty("maxEp") val maxEp: Int?)
    data class EpisodeInfo(@param:JsonProperty("episodeId") val episodeId: String?, @param:JsonProperty("title") val title: String?, @param:JsonProperty("episodeNum") val episodeNum: Int?, @param:JsonProperty("seasonNum") val seasonNum: Int?)

    data class RecResponse(@param:JsonProperty("data") val data: RecData?)
    data class RecData(@param:JsonProperty("items") val items: List<Subject>?)

    data class LinkData(
        @param:JsonProperty("subjectId") val subjectId: String,
        @param:JsonProperty("detailPath") val detailPath: String,
        @param:JsonProperty("season") val season: Int = 0,
        @param:JsonProperty("episode") val episode: Int = 0,
        @param:JsonProperty("isTv") val isTv: Boolean = false
    )

    data class PlayResponse(
        @param:JsonProperty("code") val code: Int?,
        @param:JsonProperty("message") val message: String?,
        @param:JsonProperty("data") val data: PlayData?
    )

    data class PlayData(
        @param:JsonProperty("streams") val streams: List<StreamItem>?,
        @param:JsonProperty("dash") val dash: List<StreamItem>?,
        @param:JsonProperty("hls") val hls: List<StreamItem>?,
        @param:JsonProperty("limited") val limited: Boolean?,
        @param:JsonProperty("freeNum") val freeNum: Int?,
        @param:JsonProperty("hasResource") val hasResource: Boolean?,
        @param:JsonProperty("vipLocked") val vipLocked: Boolean?
    )

    data class StreamItem(
        @param:JsonProperty("id") val id: String?,
        @param:JsonProperty("url") val url: String?,
        @param:JsonProperty("resolutions") val resolutions: String?,
        @param:JsonProperty("format") val format: String?,
        @param:JsonProperty("codecName") val codecName: String?,
        @param:JsonProperty("size") val size: String?,
        @param:JsonProperty("duration") val duration: Long?,
        @param:JsonProperty("signCookie") val signCookie: String?,
        @param:JsonProperty("signHeaderKey") val signHeaderKey: String?,
        @param:JsonProperty("vipLocked") val vipLocked: Boolean?
    )

    data class CaptionResponse(@param:JsonProperty("data") val data: CaptionData?)
    data class CaptionData(@param:JsonProperty("captions") val captions: List<CaptionItem>?)
    data class CaptionItem(@param:JsonProperty("lanName") val lanName: String?, @param:JsonProperty("url") val url: String?)

    // --- FUNGSI UTAMA ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiUrl = "$apiBaseUrl/home?host=moviebox.ph"
        val response = app.get(apiUrl, headers = getApiHeaders()).parsedSafe<HomeResponse>()

        val homeItems = mutableListOf<HomePageList>()
        response?.data?.operatingList?.forEach { section ->
            val searchResponses = mutableListOf<SearchResponse>()
            section.subjects?.forEach { it.toSearchResponse()?.let { res -> searchResponses.add(res) } }
            section.banner?.items?.forEach { it.subject?.toSearchResponse()?.let { res -> searchResponses.add(res) } }
            if (searchResponses.isNotEmpty()) homeItems.add(HomePageList(section.title ?: "", searchResponses))
        }
        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$apiBaseUrl/subject/search"
        val payload = mapOf(
            "keyword" to query,
            "page" to 1,
            "perPage" to 28,
            "subjectType" to 0
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val response = app.post(
            apiUrl,
            headers = getApiHeaders(),
            requestBody = payload
        ).parsedSafe<SearchApiResponse>()

        val list = response?.data?.items ?: response?.data?.subjectList ?: emptyList()
        return list.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringAfterLast("/")
        val detailUrl = "$apiBaseUrl/detail?detailPath=$slug"

        val wrapper = app.get(detailUrl, headers = getApiHeaders()).parsedSafe<DetailResponse>()?.data ?: return null
        val res = wrapper.subject ?: return null

        val recUrl = "$apiBaseUrl/subject/detail-rec?subjectId=${res.subjectId}&page=1&perPage=12"
        val recs = app.get(recUrl, headers = getApiHeaders()).parsedSafe<RecResponse>()?.data?.items?.mapNotNull { it.toSearchResponse() }

        val castList = wrapper.stars?.mapNotNull { star ->
            if (star.name != null) ActorData(actor = Actor(star.name, star.avatarUrl), roleString = star.character) else null
        }

        val seasonsData = wrapper.resource?.seasons.orEmpty()
        val legacyEpisodes = res.episodes.orEmpty()

        // Item tunggal: satu musim dengan se=0 DAN maxEp=0.
        val isSingleItem = seasonsData.size == 1 &&
                (seasonsData[0].se ?: 0) == 0 &&
                (seasonsData[0].maxEp ?: 0) == 0

        val episodesList = mutableListOf<Episode>()

        /*
         * =============================================================
         * PENENTUAN EPISODE (Diperbaiki)
         * =============================================================
         * 1. Utamakan array `episodes` eksplisit dari API jika ada.
         *    Ini menyelesaikan kasus seperti "The Manager 2026" yang
         *    episodenya tidak mulai dari 1 (contoh: 379..409).
         * 2. Jika array `episodes` kosong, gunakan fallback loop `1..maxEp`
         *    dari `seasonsData`.
         * =============================================================
         */
        if (legacyEpisodes.isNotEmpty()) {
            val fallbackSeason = seasonsData.firstOrNull()?.se?.takeIf { it > 0 } ?: 1

            legacyEpisodes.forEach { ep ->
                val epNum = ep.episodeNum ?: 1
                val sNum = ep.seasonNum?.takeIf { it > 0 } ?: fallbackSeason

                episodesList.add(
                    newEpisode(LinkData(res.subjectId ?: "", slug, sNum, epNum, true).toJson()) {
                        this.name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                        this.season = sNum
                        this.episode = epNum
                    }
                )
            }
        } else if (seasonsData.isNotEmpty()) {
            if (!isSingleItem) {
                seasonsData.forEach { season ->
                    val sNum = season.se ?: 0
                    val epCount = (season.maxEp ?: 0).coerceAtLeast(1)
                    for (eNum in 1..epCount) {
                        episodesList.add(
                            newEpisode(LinkData(res.subjectId ?: "", slug, sNum, eNum, true).toJson()) {
                                this.name = "Episode $eNum"
                                this.season = if (sNum > 0) sNum else 1
                                this.episode = eNum
                            }
                        )
                    }
                }
            }
        }

        return if (episodesList.isEmpty()) {
            newMovieLoadResponse(res.title ?: "", url, TvType.Movie, LinkData(res.subjectId ?: "", slug, 0, 0, false).toJson()) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                this.actors = castList
                this.score = Score.from10(res.imdbRatingValue)
            }
        } else {
            newTvSeriesLoadResponse(res.title ?: "", url, TvType.TvSeries, episodesList) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                this.actors = castList
                this.score = Score.from10(res.imdbRatingValue)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = tryParseJson<LinkData>(data) ?: return false

        val playUrl = "$apiBaseUrl/subject/play" +
                "?subjectId=${linkData.subjectId}" +
                "&se=${linkData.season}" +
                "&ep=${linkData.episode}" +
                "&detailPath=${linkData.detailPath}" +
                "&streamSignType=1"

        val specificReferer = if (linkData.isTv || linkData.season > 0) {
            "$mainUrl/spa/videoPlayPage/tv/${linkData.detailPath}" +
                    "?id=${linkData.subjectId}&type=/tv/detail" +
                    "&detailSe=${linkData.season}&detailEp=${linkData.episode}&lang=en"
        } else {
            "$mainUrl/spa/videoPlayPage/movies/${linkData.detailPath}" +
                    "?id=${linkData.subjectId}&type=/movie/detail" +
                    "&detailSe=&detailEp=&lang=en"
        }
        val playHeaders = getPlayHeaders(specificReferer)

        val response = app.get(playUrl, headers = playHeaders)
        val playRes = tryParseJson<PlayResponse>(response.text)
        val playData = playRes?.data

        val allStreams = buildList {
            playData?.streams?.let { addAll(it) }
            playData?.dash?.let { addAll(it) }
            playData?.hls?.let { addAll(it) }
        }.filter { !it.url.isNullOrBlank() }

        if (allStreams.isEmpty()) {
            val reason = when {
                playData?.limited == true ->
                    "Kuota tonton gratis habis (freeNum=${playData.freeNum}). Server menolak memberikan stream untuk sesi ini."
                playData?.hasResource == false ->
                    "Server melaporkan hasResource=false. Referer kemungkinan tidak diterima."
                playData?.vipLocked == true ->
                    "Konten terkunci VIP."
                playRes?.code != null && playRes.code != 0 ->
                    "API menolak: code=${playRes.code} message=${playRes.message}"
                else ->
                    "Tidak ada stream. code=${playRes?.code} message=${playRes?.message}"
            }
            throw ErrorLoadingException("Moviebox: $reason")
        }

        allStreams.forEach { stream ->
            if (stream.vipLocked == true) return@forEach

            val streamUrl = stream.url ?: return@forEach
            val isHls = stream.format?.contains("M3U8", true) == true ||
                    streamUrl.contains(".m3u8", true)

            val cdnHeaders = mutableMapOf("Referer" to "$mainUrl/")
            if (!stream.signCookie.isNullOrBlank()) {
                cdnHeaders["Cookie"] = stream.signCookie
            }
            if (!stream.signHeaderKey.isNullOrBlank()) {
                cdnHeaders["X-Sign-Key"] = stream.signHeaderKey
            }

            val label = buildString {
                append(this@MovieboxProvider.name)
                append(" ")
                append(stream.resolutions ?: "?")
                append("p")
                stream.codecName?.let { append(" ").append(it) }
            }

            callback(
                newExtractorLink(
                    source = this.name,
                    name = label,
                    url = streamUrl,
                    type = if (isHls) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = getQuality(stream.resolutions)
                    this.referer = "$mainUrl/"
                    this.headers = cdnHeaders
                }
            )
        }

        allStreams.firstOrNull { !it.id.isNullOrBlank() }?.let { first ->
            val captionUrl = "$apiBaseUrl/subject/caption" +
                    "?format=${first.format}" +
                    "&id=${first.id}" +
                    "&subjectId=${linkData.subjectId}" +
                    "&detailPath=${linkData.detailPath}"

            val captions = app.get(captionUrl, headers = playHeaders)
                .parsedSafe<CaptionResponse>()?.data?.captions
                ?.takeIf { it.isNotEmpty() }
                ?: app.get(captionUrl, headers = getApiHeaders(specificReferer))
                    .parsedSafe<CaptionResponse>()?.data?.captions

            captions?.forEach { cap ->
                if (!cap.url.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(cap.lanName ?: "Unknown", cap.url)
                    )
                }
            }
        }

        return true
    }

    private fun Subject.toSearchResponse(): SearchResponse? {
        val titleStr = title ?: return null
        val pathStr = detailPath ?: return null
        val yearInt = releaseDate?.take(4)?.toIntOrNull()
        val poster = cover?.url

        return if (subjectType == 1) {
            newMovieSearchResponse(titleStr, pathStr) {
                this.posterUrl = poster
                this.year = yearInt
            }
        } else {
            newTvSeriesSearchResponse(titleStr, pathStr) {
                this.posterUrl = poster
                this.year = yearInt
            }
        }
    }

    private fun getQuality(res: String?): Int {
        return when {
            res?.contains("2160") == true -> Qualities.P2160.value
            res?.contains("1440") == true -> Qualities.P1440.value
            res?.contains("1080") == true -> Qualities.P1080.value
            res?.contains("720") == true -> Qualities.P720.value
            res?.contains("480") == true -> Qualities.P480.value
            res?.contains("360") == true -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

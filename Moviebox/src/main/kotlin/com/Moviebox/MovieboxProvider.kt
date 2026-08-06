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
     * CATATAN PERUBAHAN — hasil investigasi 2026-08-06
     * ===================================================================
     * Header "Authorization: Bearer <JWT>" SUDAH DIHAPUS.
     *
     * Bukti: token lama terikat pada uid 6544730643964516232 yang sudah
     * kehabisan kuota tonton gratis. Server tetap membalas HTTP 200 dengan
     * code=0 message="ok", tetapi mengosongkan streams:
     *
     *   dengan token   -> {"limited":true,  "freeNum":3,   "streams":[]}
     *   tanpa token    -> {"limited":false, "freeNum":999, "streams":[...]}
     *
     * Jadi header inilah satu-satunya penyebab playback gagal. Endpoint,
     * format JSON, host API, dan CDN semuanya tidak berubah.
     *
     * JANGAN menambahkan kembali Authorization tanpa token yang benar-benar
     * baru dan belum kena kuota.
     * ===================================================================
     */
    private fun getApiHeaders(customReferer: String = "$mainUrl/"): Map<String, String> {
        return mapOf(
            "Accept" to "application/json",
            "x-client-info" to """{"timezone":"Asia/Jakarta"}""",
            "x-request-lang" to "en",
            "x-source" to "",
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

    // PlayResponse diperluas: code/message/limited/freeNum kini DIBACA,
    // supaya kegagalan berikutnya bisa didiagnosis tanpa curl.
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

    // Field baru sesuai respons asli server
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

        return if (res.subjectType == 1) {
            newMovieLoadResponse(res.title ?: "", url, TvType.Movie, LinkData(res.subjectId ?: "", slug, 0, 0, false).toJson()) {
                this.posterUrl = res.cover?.url
                this.plot = res.description
                this.year = res.releaseDate?.take(4)?.toIntOrNull()
                this.recommendations = recs
                this.actors = castList
                this.score = Score.from10(res.imdbRatingValue)
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            val seasonsData = wrapper.resource?.seasons

            if (!seasonsData.isNullOrEmpty()) {
                seasonsData.forEach { season ->
                    val sNum = season.se ?: 1
                    val maxEp = season.maxEp ?: 0
                    if (maxEp > 0) {
                        for (eNum in 1..maxEp) {
                            episodesList.add(
                                newEpisode(LinkData(res.subjectId ?: "", slug, sNum, eNum, true).toJson()) {
                                    this.name = "Episode $eNum"
                                    this.season = sNum
                                    this.episode = eNum
                                }
                            )
                        }
                    }
                }
            } else if (!res.episodes.isNullOrEmpty()) {
                res.episodes.forEach { ep ->
                    episodesList.add(
                        newEpisode(LinkData(res.subjectId ?: "", slug, ep.seasonNum ?: 1, ep.episodeNum ?: 1, true).toJson()) {
                            this.name = ep.title
                            this.season = ep.seasonNum
                            this.episode = ep.episodeNum
                        }
                    )
                }
            }

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

        /*
         * streamSignType=1 mengikuti klien web resmi. Tidak terbukti
         * mengubah hasil, tapi dipertahankan demi kesamaan dengan klien asli.
         */
        val playUrl = "$apiBaseUrl/subject/play" +
                "?subjectId=${linkData.subjectId}" +
                "&se=${linkData.season}" +
                "&ep=${linkData.episode}" +
                "&detailPath=${linkData.detailPath}" +
                "&streamSignType=1"

        /*
         * Referer WAJIB berupa URL halaman videoPlayPage.
         * Terbukti: dengan referer halaman detail / root / tanpa referer,
         * server membalas hasResource=false dan streams kosong.
         * Jangan disederhanakan.
         */
        val specificReferer = if (linkData.isTv || linkData.season > 0) {
            "$mainUrl/spa/videoPlayPage/tv/${linkData.detailPath}" +
                    "?id=${linkData.subjectId}&type=/tv/detail" +
                    "&detailSe=${linkData.season}&detailEp=${linkData.episode}&lang=en"
        } else {
            "$mainUrl/spa/videoPlayPage/movies/${linkData.detailPath}" +
                    "?id=${linkData.subjectId}&type=/movie/detail" +
                    "&detailSe=&detailEp=&lang=en"
        }
        val reqHeaders = getApiHeaders(specificReferer)

        val response = app.get(playUrl, headers = reqHeaders)
        val playRes = tryParseJson<PlayResponse>(response.text)
        val playData = playRes?.data

        // Gabungkan ketiga daftar. Saat ini hanya "streams" yang terisi,
        // tapi dash/hls sudah ada di skema server.
        val allStreams = buildList {
            playData?.streams?.let { addAll(it) }
            playData?.dash?.let { addAll(it) }
            playData?.hls?.let { addAll(it) }
        }.filter { !it.url.isNullOrBlank() }

        if (allStreams.isEmpty()) {
            // Diagnostik eksplisit: server membalas 200 "ok" walau menolak.
            val reason = when {
                playData?.limited == true ->
                    "Kuota tonton gratis habis (freeNum=${playData.freeNum}). " +
                    "Server menolak memberikan stream untuk sesi ini."
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

            /*
             * CDN (hakunaymatata.com) WAJIB menerima Referer.
             * Terbukti: tanpa Referer -> HTTP 429; dengan Referer -> HTTP 206.
             * User-Agent tidak berpengaruh.
             *
             * signCookie / signHeaderKey saat ini kosong, tetapi disertakan
             * kalau suatu saat server mulai mengisinya.
             */
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

        // Subtitle diambil sekali saja, dari stream pertama yang valid.
        allStreams.firstOrNull { !it.id.isNullOrBlank() }?.let { first ->
            val captionUrl = "$apiBaseUrl/subject/caption" +
                    "?format=${first.format}" +
                    "&id=${first.id}" +
                    "&subjectId=${linkData.subjectId}" +
                    "&detailPath=${linkData.detailPath}"
            app.get(captionUrl, headers = reqHeaders)
                .parsedSafe<CaptionResponse>()?.data?.captions?.forEach { cap ->
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

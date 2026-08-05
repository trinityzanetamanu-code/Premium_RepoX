package com.RiveStream

import com.RiveStream.api.RiveApi
import com.RiveStream.byse.ByseClient
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

class RiveStreamProvider : MainAPI() {

    override var mainUrl = RiveApi.MAIN_URL
    override var name = "RiveStream"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val skippedServices = setOf("primevids")
    private val proxyMarkers = listOf("valhallastream", "/proxy?")

    override val mainPage = mainPageOf(
        "trending:multi" to "Trending",
        "trendingMovie:movie" to "Trending Movies",
        "trendingTv:tv" to "Trending TV Shows",
        "latestMovie:movie" to "Latest Movies",
        "latestTv:tv" to "Latest TV Shows",
        "popularMovie:movie" to "Popular Movies",
        "popularTv:tv" to "Popular TV Shows",
        "topRatedMovie:movie" to "Top Rated Movies",
        "topRatedTv:tv" to "Top Rated TV Shows",
        "onTheAirTv:tv" to "On The Air"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val parts = request.data.split(":")
        val requestID = parts.getOrElse(0) { "trending" }
        val typeHint = parts.getOrElse(1) { "multi" }

        val results = RiveApi.catalog(requestID, page)
        val items = results.toSearchResponses(typeHint)

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return RiveApi.searchMulti(query).toSearchResponses("multi")
    }

    override suspend fun load(url: String): LoadResponse? {
        val type = url.queryParam("type") ?: return null
        val id = url.queryParam("id")?.takeIf { it.isNotBlank() } ?: return null

        return if (type.equals("tv", true)) loadTv(id, url) else loadMovie(id, url)
    }

    private suspend fun loadMovie(id: String, url: String): LoadResponse? {
        val data = RiveApi.movieData(id) ?: return null

        val title = data.optStringOrNull("title")
            ?: data.optStringOrNull("original_title")
            ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, dataUrl = url) {
            this.posterUrl = RiveApi.posterUrl(data.optStringOrNull("poster_path"))
            this.backgroundPosterUrl = RiveApi.backdropUrl(data.optStringOrNull("backdrop_path"))
            this.plot = data.optStringOrNull("overview")
            this.year = data.optStringOrNull("release_date")?.take(4)?.toIntOrNull()
            this.tags = data.genreNames()
            this.duration = data.optInt("runtime", 0).takeIf { it > 0 }

            data.voteAverage()?.let { addScore(it, 10) }
            addImdbId(data.optStringOrNull("imdb_id"))
        }
    }

    private suspend fun loadTv(id: String, url: String): LoadResponse? {
        val data = RiveApi.tvData(id) ?: return null

        val title = data.optStringOrNull("name")
            ?: data.optStringOrNull("original_name")
            ?: return null

        val episodes = loadTvEpisodes(id, data)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = RiveApi.posterUrl(data.optStringOrNull("poster_path"))
            this.backgroundPosterUrl = RiveApi.backdropUrl(data.optStringOrNull("backdrop_path"))
            this.plot = data.optStringOrNull("overview")
            this.year = data.optStringOrNull("first_air_date")?.take(4)?.toIntOrNull()
            this.tags = data.genreNames()

            this.duration = data.optJSONArray("episode_run_time")
                ?.takeIf { it.length() > 0 }
                ?.optInt(0, 0)
                ?.takeIf { it > 0 }

            this.showStatus = when {
                data.optBoolean("in_production", false) -> ShowStatus.Ongoing
                data.optStringOrNull("status")?.equals("Ended", true) == true -> ShowStatus.Completed
                data.optStringOrNull("status")?.equals("Canceled", true) == true -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }

            data.voteAverage()?.let { addScore(it, 10) }
            addImdbId(data.optJSONObject("external_ids")?.optStringOrNull("imdb_id"))
        }
    }

    private suspend fun loadTvEpisodes(id: String, data: JSONObject): List<Episode> {
        val seasons = data.optJSONArray("seasons") ?: return emptyList()
        val out = ArrayList<Episode>()

        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNumber = season.optInt("season_number", -1)
            if (seasonNumber < 0) continue

            val detail = RiveApi.tvEpisodes(id, seasonNumber) ?: continue
            val list = detail.optJSONArray("episodes") ?: continue

            for (j in 0 until list.length()) {
                val ep = list.optJSONObject(j) ?: continue
                val episodeNumber = ep.optInt("episode_number", -1)
                if (episodeNumber < 0) continue

                val epSeason = ep.optInt("season_number", seasonNumber)

                out.add(
                    newEpisode(episodeUrl(id, epSeason, episodeNumber)) {
                        this.name = ep.optStringOrNull("name")
                        this.season = epSeason
                        this.episode = episodeNumber
                        this.description = ep.optStringOrNull("overview")
                        this.posterUrl = RiveApi.posterUrl(ep.optStringOrNull("still_path"))
                        this.runTime = ep.optInt("runtime", 0).takeIf { it > 0 }?.let { it * 60 }
                        addDate(ep.optStringOrNull("air_date"))
                    }
                )
            }
        }

        out.sortWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = data.queryParam("type") ?: return false
        val id = data.queryParam("id")?.takeIf { it.isNotBlank() } ?: return false
        val isTv = type.equals("tv", true)

        val season = data.queryParam("season")?.toIntOrNull()
        val episode = data.queryParam("episode")?.toIntOrNull()
        if (isTv && (season == null || episode == null)) return false

        val services = RiveApi.videoProviderServices()
        val seenLinks = HashSet<String>()
        val seenSubs = HashSet<String>()
        var emitted = 0

        for (service in services) {
            if (service.lowercase() in skippedServices) continue

            val result = try {
                if (isTv) {
                    RiveApi.tvVideoProvider(id, service, season!!, episode!!)
                } else {
                    RiveApi.movieVideoProvider(id, service)
                }
            } catch (e: Exception) {
                null
            } ?: continue

            emitted += emitSources(result, service, seenLinks, callback)
            emitCaptions(result, seenSubs, subtitleCallback)
        }

        emitted += try {
            emitEmbed(id, isTv, season, episode, seenLinks, callback)
        } catch (e: Exception) {
            0
        }

        return emitted > 0
    }

    private suspend fun emitEmbed(
        id: String,
        isTv: Boolean,
        season: Int?,
        episode: Int?,
        seen: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        for (service in RiveApi.embedProviderServices()) {
            val data = try {
                if (isTv) {
                    RiveApi.tvEmbedProvider(id, service, season ?: 1, episode ?: 1)
                } else {
                    RiveApi.movieEmbedProvider(id, service)
                }
            } catch (e: Exception) {
                null
            } ?: continue

            val sources = data.optJSONArray("sources") ?: continue
            for (i in 0 until sources.length()) {
                val item = sources.optJSONObject(i) ?: continue
                val link = item.optStringOrNull("link") ?: continue

                val hostTag = item.optStringOrNull("host").orEmpty()
                val labelKualitas = hostTag.split("-").getOrNull(2)

                val target = ByseClient.uraiTautan(link) ?: continue
                val hasil = ByseClient.resolve(target.second, target.first) ?: continue

                for (sumber in hasil.sources) {
                    if (!seen.add(sumber.url)) continue

                    val tipe = when {
                        sumber.mimeType?.contains("mpegurl", true) == true -> ExtractorLinkType.M3U8
                        sumber.url.substringBefore('?').endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
                        sumber.mimeType?.contains("mp4", true) == true -> ExtractorLinkType.VIDEO
                        else -> null
                    }

                    val kualitas = sumber.height ?: getQualityFromName(sumber.label ?: labelKualitas)
                    val playerHost = runCatching { URL(hasil.referer).host }.getOrNull() ?: "q8y5z.com"

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "Byse",
                            url = sumber.url,
                            type = tipe
                        ) {
                            this.quality = kualitas
                            this.referer = hasil.referer
                            this.headers = mapOf(
                                "User-Agent" to ByseClient.USER_AGENT,
                                "Origin" to "https://$playerHost"
                            )
                        }
                    )
                    count++
                }
            }
        }
        return count
    }

    private suspend fun emitSources(
        result: JSONObject,
        service: String,
        seen: HashSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val sources = result.optJSONArray("sources") ?: return 0
        var count = 0

        for (i in 0 until sources.length()) {
            val item = sources.optJSONObject(i) ?: continue
            val url = item.optStringOrNull("url") ?: continue
            if (!seen.add(url)) continue

            // AUTO-RESOLVE BYSE PADA MOVIE/TV PROVIDER
            val byseTarget = ByseClient.uraiTautan(url)
            if (byseTarget != null) {
                val hasil = ByseClient.resolve(byseTarget.second, byseTarget.first)
                if (hasil != null) {
                    for (sumber in hasil.sources) {
                        if (!seen.add(sumber.url)) continue
                        val tipe = if (sumber.mimeType?.contains("mpegurl", true) == true ||
                            sumber.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        val playerHost = runCatching { URL(hasil.referer).host }.getOrNull() ?: "q8y5z.com"
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "Byse ($service)",
                                url = sumber.url,
                                type = tipe
                            ) {
                                this.quality = sumber.height ?: getQualityFromName(sumber.label)
                                this.referer = hasil.referer
                                this.headers = mapOf(
                                    "User-Agent" to ByseClient.USER_AGENT,
                                    "Origin" to "https://$playerHost"
                                )
                            }
                        )
                        count++
                    }
                    continue
                }
            }

            val (finalUrl, finalHeaders) = resolveLink(url)
            if (!isReachable(finalUrl, finalHeaders)) continue

            val label = item.optStringOrNull("source") ?: service
            val quality = getQualityFromName(item.optStringOrNull("quality"))

            val linkType = when (item.optStringOrNull("format")?.lowercase()) {
                "hls" -> ExtractorLinkType.M3U8
                "mp4" -> ExtractorLinkType.VIDEO
                else -> null
            }

            callback(
                newExtractorLink(
                    source = this.name,
                    name = label,
                    url = finalUrl,
                    type = linkType
                ) {
                    this.quality = quality
                    this.referer = finalHeaders["Referer"] ?: RiveApi.REFERER
                    this.headers = finalHeaders.filterKeys { it != "Referer" }
                }
            )
            count++
        }
        return count
    }

    private suspend fun isReachable(url: String, headers: Map<String, String>): Boolean {
        return try {
            withTimeoutOrNull(8000L) {
                val res = app.get(url, headers = headers + ("Range" to "bytes=0-1"))
                res.code in 200..299
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveLink(url: String): Pair<String, Map<String, String>> {
        val siteHeaders = mapOf(
            "User-Agent" to RiveApi.USER_AGENT,
            "Referer" to RiveApi.REFERER
        )
        if (proxyMarkers.none { url.contains(it) }) return url to siteHeaders

        val inner = url.queryParam("url")?.takeIf { it.startsWith("http") }
            ?: return url to siteHeaders

        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = RiveApi.USER_AGENT

        url.queryParam("headers")?.let { raw ->
            runCatching {
                val obj = JSONObject(raw)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) headers[k] = v
                }
            }
        }
        if (!headers.containsKey("Referer")) headers["Referer"] = RiveApi.REFERER

        return inner to headers
    }

    private suspend fun emitCaptions(
        result: JSONObject,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val captions = result.optJSONArray("captions") ?: return

        for (i in 0 until captions.length()) {
            val item = captions.optJSONObject(i) ?: continue
            val file = item.optStringOrNull("file") ?: continue
            if (!seen.add(file)) continue

            val raw = item.optStringOrNull("label") ?: "Unknown"
            val lang = raw.substringBefore(" - ").trim().ifBlank { raw }

            subtitleCallback(newSubtitleFile(lang, file))
        }
    }

    private fun JSONArray?.toSearchResponses(typeHint: String): List<SearchResponse> {
        if (this == null) return emptyList()
        val out = ArrayList<SearchResponse>(this.length())
        for (i in 0 until this.length()) {
            val item = this.optJSONObject(i) ?: continue
            toSearchResponse(item, typeHint)?.let { out.add(it) }
        }
        return out
    }

    private fun toSearchResponse(item: JSONObject, typeHint: String): SearchResponse? {
        val id = item.optInt("id", 0).takeIf { it > 0 } ?: return null

        val mediaType = item.optString("media_type", "").ifBlank { null }
        if (mediaType == "person") return null

        val isTv = when {
            mediaType == "tv" -> true
            mediaType == "movie" -> false
            typeHint == "tv" -> true
            typeHint == "movie" -> false
            else -> item.has("name") && !item.has("title")
        }

        val title = (if (isTv) {
            item.optStringOrNull("name") ?: item.optStringOrNull("original_name")
        } else {
            item.optStringOrNull("title") ?: item.optStringOrNull("original_title")
        }) ?: return null

        if (item.optBoolean("adult", false)) return null

        val poster = RiveApi.posterUrl(item.optStringOrNull("poster_path"))
        val year = (if (isTv) item.optStringOrNull("first_air_date")
        else item.optStringOrNull("release_date"))
            ?.take(4)?.toIntOrNull()

        val url = buildUrl(if (isTv) "tv" else "movie", id)

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    private fun buildUrl(type: String, id: Int): String =
        "$mainUrl/watch?type=$type&id=$id"

    private fun episodeUrl(id: String, season: Int, episode: Int): String =
        "$mainUrl/watch?type=tv&id=$id&season=$season&episode=$episode"

    private fun JSONObject.optStringOrNull(key: String): String? =
        this.optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.genreNames(): List<String>? {
        val arr = this.optJSONArray("genres") ?: return null
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it) }
        }
        return out.ifEmpty { null }
    }

    private fun JSONObject.voteAverage(): String? {
        val v = this.optDouble("vote_average", -1.0)
        return if (v > 0.0) v.toString() else null
    }

    private fun String.queryParam(key: String): String? {
        val q = this.substringAfter('?', "").substringBefore('#')
        if (q.isBlank()) return null
        for (pair in q.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) == key) {
                return java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
        }
        return null
    }
}

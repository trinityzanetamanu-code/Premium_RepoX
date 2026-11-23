package com.fourKHDHub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addSimklId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Locale
import java.util.MissingResourceException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class FourKHDHub : MainAPI() {
    override var mainUrl: String = runBlocking {
        FourKHDHubProvider.getDomains()?.n4khdhub ?: "https://4khdhub.dad"
    }
    override var name                 = "4K HDHUB"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime,TvType.TvSeries)


    companion object
    {
        const val TMDBAPI = "https://api.themoviedb.org/3"
        const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val SIMKL = "https://api.simkl.com"
        private const val SIMKL_CLIENT_ID = "39f470a9f2ec1aa2383269ca831bc7be0e47da48d6d708ccad9bed4e1a60993e"
        const val TMDBIMAGEBASEURL = "https://image.tmdb.org/t/p/original"
        private const val AUDIO_FILTER_TAG = "fourkhdhub-original-audio:"
        private const val MAX_PLAYLIST_SIZE = 512L * 1024
        private const val MEDIA_TAG = "#EXT-X-MEDIA:"
        private const val STREAM_TAG = "#EXT-X-STREAM-INF:"
        private val HLS_ATTRIBUTE = Regex("""(?:^|,)([A-Z0-9-]+)=("[^"]*"|[^",]+)""")
        private val LANGUAGE_CODE = Regex("[a-z]{2,3}")
        private val ORIGINAL_LABEL = Regex("""\boriginal\b""", RegexOption.IGNORE_CASE)
        private val ALTERNATE_AUDIO = Regex(
            """\b(?:dub(?:bed|bing)?|commentary|descriptive|audio[ -]+description|describes-video|(?:non|not)[ -]+original)\b""",
            RegexOption.IGNORE_CASE
        )

    }

    override val mainPage = mainPageOf(
        "" to "Home",
        "category/movies" to "Latest Movies",
        "category/series" to "Latest Episodes",
        "category/korean-series" to "Korean Series",
        "category/netflix" to "Netflix",
        "category/amazon_prime_video" to "Amazon Prime Video",
        "category/jiohotstar" to "JioHotstar",
        "category/disney" to "Disney+",
        "category/Apple_TV" to "Apple TV+",
        "category/anime" to "Anime",
        "category/2160p-HDR" to "4K HDR",
        "category/imdb" to "Top IMDb",
        "category/hindi-movies" to "Hindi Movies",
        "category/english-movies" to "English Movies"
    )


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = mainUrl + (if (request.data.isBlank()) "" else "/${request.data}") +
            (if (page > 1) "/page/$page" else "")
        val document = app.get(url).documentLarge
        val results = document.select("div.card-grid a").mapNotNull {
                it.toSearchResult()
        }
        return newHomePageResponse(request.name, results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.text() ?: return null
        val href = this.attr("href")
        val posterUrl = this.select("img").attr("src")
        
        // Extract quality badges directly from the website's badges section
        val tags = select("span.movie-card-format").map { it.text() }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getSearchQuality(tags)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").documentLarge
        val results = document.select("div.card-grid a").mapNotNull {
            it.toSearchResult()
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val title = document.selectFirst("h1.page-title")?.text()?.substringBefore("(")?.trim().toString()
        val poster = document.select("meta[property=og:image]").attr("content")
        val tags = document.select("div.mt-2 span.badge").map { it.text() }
        val year = document.selectFirst("div.mt-2 span")?.text()?.toIntOrNull()
        val tvType = if ("Movies" in tags) TvType.Movie else TvType.TvSeries
        val isMovie = tvType == TvType.Movie
        val tmdbId = runCatching { fetchtmdb(title,isMovie) }.getOrNull()

        val hrefs: List<String> = document.select("div.download-item a").eachAttr("href")

        val description = document.selectFirst("div.content-section p.mt-4")?.text()?.trim()
        val trailer = document.selectFirst("#trailer-btn")?.attr("data-trailer-url")

        val recommendations = document.select("div.card-grid-small a").mapNotNull {
            it.toSearchResult()
        }

        var tmdbTitle: String? = null
        var tmdbOverview: String? = null
        var tmdbYear: Int? = null
        var tmdbRating: Double? = null
        var tmdbPoster: String? = null
        var tmdbBackdrop: String? = null
        var tmdbActors: List<ActorData> = emptyList()
        var originalLanguage: String? = null

        if (tmdbId != null) {
            val type = if (tvType == TvType.Movie) "movie" else "tv"
            val tmdbJson = runCatching {
                JSONObject(
                    app.get("$TMDBAPI/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits")
                        .textLarge
                )
            }.getOrNull()

            if (tmdbJson != null) {
                tmdbTitle = tmdbJson.optString("title").ifBlank { tmdbJson.optString("name").ifBlank { null } }
                tmdbOverview = tmdbJson.optString("overview").takeIf { it.isNotBlank() }
                val date = tmdbJson.optString("release_date").ifBlank { tmdbJson.optString("first_air_date") }
                tmdbYear = date.takeIf { it.isNotBlank() }?.substringBefore("-")?.toIntOrNull()
                val originalTitle = tmdbJson.optString(if (isMovie) "original_title" else "original_name")
                val titleKey = title.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                if (year != null && year == tmdbYear && titleKey.isNotEmpty() &&
                    listOfNotNull(tmdbTitle, originalTitle).any {
                        it.lowercase(Locale.ROOT).filter { character -> character.isLetterOrDigit() } == titleKey
                    }
                ) {
                    originalLanguage = languageCode(tmdbJson.optString("original_language"))
                }
                tmdbRating = tmdbJson.optDouble("vote_average").takeIf { it != 0.0 }
                tmdbPoster = tmdbJson.optString("poster_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                tmdbBackdrop = tmdbJson.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }

                tmdbActors = buildList {
                    tmdbJson.optJSONObject("credits")
                        ?.optJSONArray("cast")
                        ?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val name = obj.optString("name").takeIf { it.isNotBlank() } ?: obj.optString("original_name").takeIf { it.isNotBlank() }
                                if (name.isNullOrBlank()) continue
                                val profile = obj.optString("profile_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                                val character = obj.optString("character").takeIf { it.isNotBlank() }
                                add(ActorData(Actor(name, profile), roleString = character))
                            }
                        }
                }
            }
        }

        val fixedTitle = tmdbTitle ?: title
        val fixedPoster = tmdbPoster ?: poster
        val fixedBackdrop = tmdbBackdrop ?: poster
        val fixedPlot = tmdbOverview ?: description
        val fixedYear = tmdbYear ?: year
        val finalActorsFromTmdb = tmdbActors

        return if (tvType == TvType.TvSeries) {
            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap = mutableMapOf<Pair<Int, Int>, MutableList<String>>()
            val maxEpisodePerSeason = mutableMapOf<Int, Int>()
            val imdbIdFromSeries = tmdbId?.let { id ->
                runCatching {
                    val url = "$TMDBAPI/tv/$id/external_ids?api_key=$TMDB_API_KEY"
                    JSONObject(app.get(url).textLarge).optString("imdb_id").takeIf { it.isNotBlank() }
                }.getOrNull()
            }

            val logoPath = imdbIdFromSeries?.let {
                "https://live.metahub.space/logo/medium/$it/img"
            }


            val simklIdseries = imdbIdFromSeries?.let { imdb ->
                runCatching {
                        JSONObject(app.get("$SIMKL/tv/$imdb?client_id=$SIMKL_CLIENT_ID").text)
                        .optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
                }.getOrNull()
            }

            document.select("div.episodes-list div.season-item").forEach { seasonElement ->
                val seasonText = seasonElement.select("div.episode-number").text()
                val season = Regex("""S?([1-9][0-9]*)""").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                seasonElement.select("div.episode-download-item").forEach { episodeItem ->
                    val episodeText = episodeItem.select("div.episode-file-info span.badge-psa").text()
                    val episode = Regex("""Episode-0*([1-9][0-9]*)""").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@forEach

                    val hrefsForEp = episodeItem.select("a").mapNotNull { it.attr("href").takeIf { it.isNotBlank() } }
                    if (hrefsForEp.isNotEmpty()) {
                        val key = season to episode
                        episodesMap.getOrPut(key) { mutableListOf() }.addAll(hrefsForEp)
                        maxEpisodePerSeason[season] = maxOf(maxEpisodePerSeason.getOrDefault(season, 0), episode)
                    }
                }
            }

            val tmdbSeasonCache = mutableMapOf<Int, JSONObject?>()
            if (tmdbId != null) {
                val seasonsToFetch = episodesMap.keys.map { it.first }.distinct()
                for (s in seasonsToFetch) {
                    tmdbSeasonCache[s] = runCatching {
                        JSONObject(app.get("$TMDBAPI/tv/$tmdbId/season/$s?api_key=$TMDB_API_KEY").text)
                    }.getOrNull()
                }
            }


            episodesMap.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (seasonEpisode, hrefsList) ->
                val (season, episode) = seasonEpisode

                var epName: String? = null
                var epOverview: String? = null
                var epThumb: String? = null
                var epAir: String? = null
                var epRating: Double? = null

                tmdbSeasonCache[season]?.optJSONArray("episodes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val epObj = arr.optJSONObject(i) ?: continue
                        if (epObj.optInt("episode_number") == episode) {
                            epName = epObj.optString("name").takeIf { it.isNotBlank() }
                            epOverview = epObj.optString("overview").takeIf { it.isNotBlank() }
                            epThumb = epObj.optString("still_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                            epAir = epObj.optString("air_date").takeIf { it.isNotBlank() }
                            epRating = epObj.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
                            break
                        }
                    }
                }

                tvSeriesEpisodes += newEpisode(playbackData(hrefsList.distinct(), originalLanguage)) {
                    this.season = season
                    this.episode = episode
                    this.name = epName ?: "Episode $episode"
                    this.posterUrl = epThumb
                    this.description = epOverview
                    addDate(epAir)
                    this.score = safeScoreFrom10(epRating)
                }
            }

            document.select("div.download-item").forEach { item ->
                val headerText = item.select("div.flex-1.text-left.font-semibold").text()

                val season = Regex("""S([0-9]+)""").find(headerText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                val size = Regex("""(\d+(?:\.\d+)?\s*GB)""").find(headerText)?.groupValues?.get(1) ?: "Unknown Size"
                val quality = Regex("""(\d{3,4}p)""").find(headerText)?.groupValues?.get(1) ?: "Unknown Quality"

                val hrefList = item.select("a").mapNotNull { it.attr("href").takeIf { it.isNotBlank() } }

                val fileTitle = item.select("div.file-title").text()
                    .replace(Regex("""\[[^]]*]"""), "")
                    .replace(Regex("""\(.+?\)"""), "")

                if (hrefList.isNotEmpty()) {
                    var nextEpisode = maxEpisodePerSeason.getOrDefault(season, 0) + 1

                    var epName: String? = null
                    var epOverview: String? = null
                    var epThumb: String? = null
                    var epAir: String? = null
                    var epRating: Double? = null

                    tmdbSeasonCache[season]?.optJSONArray("episodes")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val epObj = arr.optJSONObject(i) ?: continue
                            if (epObj.optInt("episode_number") == nextEpisode) {
                                epName = epObj.optString("name").takeIf { it.isNotBlank() }
                                epOverview = epObj.optString("overview").takeIf { it.isNotBlank() }
                                epThumb = epObj.optString("still_path").takeIf { it.isNotBlank() }?.let { TMDBIMAGEBASEURL + it }
                                epAir = epObj.optString("air_date").takeIf { it.isNotBlank() }
                                epRating = epObj.optDouble("vote_average").takeIf { !it.isNaN() && it > 0.0 }
                                break
                            }
                        }
                    }

                    tvSeriesEpisodes += newEpisode(playbackData(hrefList.distinct(), originalLanguage)) {
                        this.season = season
                        this.episode = nextEpisode
                        this.name = epName ?: "S${season.toString().padStart(2, '0')} – $fileTitle [$quality, $size]".trim()
                        this.posterUrl = epThumb
                        this.description = epOverview
                        addDate(epAir)
                        this.score = safeScoreFrom10(epRating)

                    }

                    nextEpisode++
                    maxEpisodePerSeason[season] = nextEpisode - 1
                }
            }

            newTvSeriesLoadResponse(fixedTitle, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = fixedPoster
                this.backgroundPosterUrl = fixedBackdrop
                // try { this.logoUrl = logoPath } catch(_:Throwable){}
                this.year = fixedYear
                this.plot = fixedPlot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = finalActorsFromTmdb
                this.score = safeScoreFrom10(tmdbRating)
                addTrailer(trailer)
                addSimklId(simklIdseries)
            }
        } else {
            val imdbIdFromMovie = tmdbId?.let { id ->
                runCatching {
                    val url = "$TMDBAPI/movie/$id/external_ids?api_key=$TMDB_API_KEY"
                    JSONObject(app.get(url).textLarge).optString("imdb_id").takeIf { it.isNotBlank() }
                }.getOrNull()
            }

            val logoPath = imdbIdFromMovie?.let {
                "https://live.metahub.space/logo/medium/$it/img"
            }

            val simklIdMovie = imdbIdFromMovie?.let { imdb ->
                runCatching {
                    JSONObject(app.get("$SIMKL/movies/$imdb?client_id=$SIMKL_CLIENT_ID").text)
                        .optJSONObject("ids")?.optInt("simkl")?.takeIf { it != 0 }
                }.getOrNull()
            }

            val movieCreditsJsonText = tmdbId?.let { id ->
                runCatching {
                    app.get("${TMDBAPI}/movie/$id/credits?api_key=$TMDB_API_KEY&language=en-US").textLarge
                }.getOrNull()
            }
            val movieCastList = parseCredits(movieCreditsJsonText)
            val finalMovieActors = tmdbActors.ifEmpty { movieCastList }

            newMovieLoadResponse(fixedTitle, url, TvType.Movie, playbackData(hrefs, originalLanguage)) {
                this.posterUrl = fixedPoster
                this.backgroundPosterUrl = fixedBackdrop
                // try { this.logoUrl = logoPath } catch(_:Throwable){}
                this.year = fixedYear
                this.plot = fixedPlot
                this.tags = tags
                this.recommendations = recommendations
                this.actors = finalMovieActors
                this.score = safeScoreFrom10(tmdbRating)
                addTrailer(trailer)
                addSimklId(simklIdMovie)
            }
        }
    }

    private val LINK_REGEX =
        Regex("""https?://[^\s'",\]\[]+""", RegexOption.IGNORE_CASE)

    private data class PlaybackData(val links: List<String>, val originalLanguage: String)

    private fun playbackData(links: List<String>, originalLanguage: String?): Any =
        if (originalLanguage == null) links else PlaybackData(links, originalLanguage)

    private fun languageCode(value: String?): String? {
        val tag = value?.trim()?.substringBefore('-')?.lowercase(Locale.ROOT) ?: return null
        if (!LANGUAGE_CODE.matches(tag) || tag in setOf("und", "mul", "zxx")) return null
        return try {
            Locale.forLanguageTag(tag).getISO3Language().takeIf { it.isNotEmpty() }
        } catch (_: MissingResourceException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun linkWithOriginalAudio(link: ExtractorLink, originalLanguage: String?): ExtractorLink {
        if (link.type != ExtractorLinkType.M3U8 || link.javaClass != ExtractorLink::class.java ||
            link.extractorData != null || link.audioTracks.isNotEmpty() ||
            (link.source != name && getApiFromNameNull(link.source) != null)
        ) return link

        // The player finds getVideoInterceptor() by ExtractorLink.source.
        return ExtractorLink(
            source = name,
            name = link.name,
            url = link.url,
            referer = link.referer,
            quality = link.quality,
            headers = link.headers,
            extractorData = AUDIO_FILTER_TAG + originalLanguage.orEmpty(),
            type = link.type,
        )
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val data = extractorLink.extractorData?.takeIf { it.startsWith(AUDIO_FILTER_TAG) } ?: return null
        val playlistUrl = extractorLink.url.toHttpUrlOrNull() ?: return null
        val originalLanguage = data.removePrefix(AUDIO_FILTER_TAG).ifEmpty { null }

        return Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.url != playlistUrl || response.code != 200) return@Interceptor response
            val body = response.body ?: return@Interceptor response
            val encoding = response.header("Content-Encoding")
            if (body.contentLength() > MAX_PLAYLIST_SIZE ||
                (encoding != null && !encoding.equals("identity", ignoreCase = true))
            ) return@Interceptor response

            val filtered = try {
                val preview = response.peekBody(MAX_PLAYLIST_SIZE + 1)
                if (preview.contentLength() > MAX_PLAYLIST_SIZE) null
                else filterHlsAudio(preview.string(), originalLanguage)
            } catch (_: IOException) {
                null
            } ?: return@Interceptor response

            val replacement = filtered.toResponseBody(body.contentType())
            body.close()
            response.newBuilder()
                .header("Content-Length", replacement.contentLength().toString())
                .body(replacement)
                .build()
        }
    }

    private fun filterHlsAudio(playlist: String, originalLanguage: String?): String? {
        val lines = playlist.lines()
        if (lines.firstOrNull()?.trim() != "#EXTM3U") return null

        val media = lines.indices.filter { lines[it].startsWith(MEDIA_TAG) }
            .associateWith { index -> hlsAttributes(lines[index]) }
            .filterValues { it["TYPE"] == "AUDIO" }
        if (media.isEmpty()) return null

        val groups = media.entries.groupBy { it.value["GROUP-ID"] }
        val variants = lines.filter { it.startsWith(STREAM_TAG) }
        if (variants.isEmpty() || groups.containsKey(null) || variants.any {
                val audioGroup = hlsAttributes(it)["AUDIO"]
                audioGroup.isNullOrBlank() || audioGroup !in groups
            }
        ) return null

        val kept = mutableSetOf<Int>()
        for (group in groups.values) {
            val mainAudio = group.filter { (_, attributes) ->
                !ALTERNATE_AUDIO.containsMatchIn(attributes["NAME"].orEmpty()) &&
                    !ALTERNATE_AUDIO.containsMatchIn(attributes["CHARACTERISTICS"].orEmpty())
            }
            val original = mainAudio.filter { ORIGINAL_LABEL.containsMatchIn(it.value["NAME"].orEmpty()) }
            val selected = if (original.isNotEmpty()) {
                original.singleOrNull()?.takeIf {
                    val language = languageCode(it.value["LANGUAGE"])
                    originalLanguage == null || language == null || language == originalLanguage
                }
            } else if (originalLanguage != null && mainAudio.all { languageCode(it.value["LANGUAGE"]) != null }) {
                mainAudio.singleOrNull { languageCode(it.value["LANGUAGE"]) == originalLanguage }
            } else null
            if (selected == null || selected.value["URI"].isNullOrBlank()) return null
            kept += selected.key
        }

        if (kept.size == media.size) return null
        return lines.mapIndexedNotNull { index, line ->
            when {
                index in kept -> defaultHlsAudio(line, media.getValue(index))
                index in media -> null
                else -> line
            }
        }.joinToString("\n")
    }

    private fun hlsAttributes(line: String): Map<String, String> =
        HLS_ATTRIBUTE.findAll(line.substringAfter(':')).associate {
            it.groupValues[1] to it.groupValues[2].removeSurrounding("\"")
        }

    private fun defaultHlsAudio(line: String, attributes: Map<String, String>): String {
        val flags = listOf("DEFAULT", "AUTOSELECT")
        val updated = HLS_ATTRIBUTE.replace(line.removePrefix(MEDIA_TAG)) { match ->
            if (match.groupValues[1] in flags) match.value.substringBefore('=') + "=YES"
            else match.value
        }
        return MEDIA_TAG + updated + flags.filterNot(attributes::containsKey).joinToString("") { ",$it=YES" }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playback = if (data.startsWith('{')) AppUtils.tryParseJson<PlaybackData>(data) else null
        val links = (playback?.links ?: AppUtils.tryParseJson<List<String>>(data))
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            ?: return false

        val emit: (ExtractorLink) -> Unit = { link ->
            callback(if (isCasting) link else linkWithOriginalAudio(link, playback?.originalLanguage))
        }
        coroutineScope {
            links.map { raw -> async {
            try {
                val resolved = if (raw.contains("id=")) getRedirectLinks(raw) else raw
                if (resolved.isBlank()) return@async
                when {
                    resolved.contains("hubcloud", ignoreCase = true) ->
                        HubCloud().getUrl(resolved, null, subtitleCallback, emit)
                    resolved.contains("hblinks", ignoreCase = true) ->
                        Hblinks().getUrl(resolved, null, subtitleCallback, emit)
                    else -> loadExtractor(resolved, subtitleCallback, emit)
                }
            } catch (e: Exception) {
                Log.e("Extractor", "Extractor failed: $raw — ${e.message}")
            }
            } }.awaitAll()
        }
        return true
    }
}

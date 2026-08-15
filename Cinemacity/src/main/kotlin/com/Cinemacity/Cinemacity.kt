package com.Cinemacity

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import org.json.JSONArray
import org.json.JSONObject
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class Cinemacity : MainAPI() {

    override var mainUrl = "https://cinemacity.cc"
    override var name = "CinemaCity"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val loginCookie = "" 
        private val seasonRegex = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val episodeRegex = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val imdbRegex = Regex("""tt\d+""")
        private val subtitleRegex = Regex("""\[(.+?)](https?://.+)""")
        private val cfMarkers = listOf(
            "<title>just a moment",
            "id=\"challenge-form\"",
            "cf-browser-verification",
            "checking your browser before accessing",
            "attention required"
        )
        private const val TAG = "Phisher"
    }

    private suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
        var response = app.get(url, headers = headers, interceptor = CinemacityCFBypassInterceptor)
        
        if (isCloudflareBlocked(response.code, response.text)) {
            Log.d(TAG, "CF Challenge detected, attempting bypass...")
            val success = showCinemacityCFBypassDialogAndWait()
            if (success) {
                Log.d(TAG, "CF Bypass success, retrying request...")
                response = app.get(url, headers = headers, interceptor = CinemacityCFBypassInterceptor)
            } else {
                if (ActivityHelper.currentActivity == null) {
                    throw ErrorLoadingException("Cloudflare Aktif! Tutup paksa aplikasi CloudStream (Clear Recent Apps) lalu buka kembali.")
                } else {
                    throw ErrorLoadingException("Bypass Cloudflare dibatalkan/gagal. Coba muat ulang.")
                }
            }
        }
        
        if (isCloudflareBlocked(response.code, response.text)) {
            throw ErrorLoadingException("CinemaCity: Terhalang Cloudflare. Coba muat ulang.")
        }
        
        return response
    }

    private fun isCloudflareBlocked(code: Int, text: String): Boolean {
        val lower = text.lowercase()
        return cfMarkers.any { lower.contains(it) }
    }

    private fun siteCookieHeader(): Map<String, String> = mapOf("Cookie" to buildCookieValue())

    private fun buildCookieValue(): String {
        val cf = CinemacityPlugin.cfCookies
        return if (cf.isEmpty()) loginCookie else if (loginCookie.isEmpty()) cf else "$loginCookie; $cf"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/tv-series/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}page/$page/"
        val doc = appGet(url, siteCookieHeader()).document

        val items = doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        // FIX: Bidik elemen a.e-nowrap (URL Film Asli) atau URL yang berakhiran .html
        val linkElement = this.selectFirst("a.e-nowrap") 
            ?: this.select("a").firstOrNull { it.attr("href").contains(".html") }
            ?: return null
            
        val href = linkElement.attr("href")
        
        // FIX: Ekstrak judul dari elemen yang sama, bersihkan angka tahun di akhirnya
        val rawTitle = linkElement.text().trim()
        val title = rawTitle.replace(Regex("""\s*\(\d{4}(?:–\d{4}|–)?\)$"""), "").trim()

        val poster = this.selectFirst("img.poster")?.attr("src") 
            ?: this.selectFirst("img")?.attr("src")

        val cfHeaders = CinemacityPlugin.getCfHeaders()

        return if (href.contains("/tv-series/")) {
            newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.posterHeaders = cfHeaders
            }
        } else {
            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.posterHeaders = cfHeaders
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // FIX: Gunakan GET HTTP standar agar lolos Cloudflare Bypass, ajax.php terlalu rentan diblokir
        val searchUrl = "$mainUrl/index.php?do=search&subaction=search&story=$query"
        val doc = appGet(searchUrl, siteCookieHeader()).document
        return doc.select("div.dar-short_item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = appGet(url, siteCookieHeader())
        val doc = res.document

        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { doc.title() } ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        
        val background = doc.selectFirst("div.dar-full_bg a")?.attr("data-vbg")
            ?: doc.selectFirst("div.dar-full_bg.e-cover > div")?.attr("data-vbg")
            ?: poster

        val plot = doc.select("#about div.ta-full_text1").text().trim().takeIf { it.isNotBlank() }
        val tvType = if (url.contains("/tv-series/")) TvType.TvSeries else TvType.Movie

        val imdbId = doc.select("div.ta-full_rating1 > div").firstOrNull()?.attr("onclick")?.let { imdbRegex.find(it)?.value }

        val scriptElements = doc.select("script:containsData(atob)")
        var playerRoot: JSONObject? = null

        for (script in scriptElements) {
            val scriptData = script.data()
            if (scriptData.contains("atob(\"")) {
                val decoded = base64Decode(scriptData.substringAfter("atob(\"").substringBefore("\")"))
                if (decoded.contains("new Playerjs(")) {
                    val raw = decoded.substringAfter("new Playerjs(").substringBeforeLast(");")
                    try {
                        val tempRoot = tryParseJson<JSONObject>(raw) ?: JSONObject(raw)
                        if (tempRoot.has("file") && tempRoot.optString("file").isNotBlank()) {
                            playerRoot = tempRoot
                            break
                        }
                    } catch (e: Exception) { Log.d(TAG, "Failed parsing PlayerJS block") }
                }
            }
        }

        var movieData: String? = null

        if (playerRoot != null) {
            val fileValue = playerRoot.opt("file")
            if (fileValue != null && fileValue.toString().isNotBlank()) {
                val fileArray = normalizeFile(fileValue)
                movieData = if (tvType != TvType.TvSeries) buildMovieData(playerRoot, fileArray) else null
            }
        }
        
        if (playerRoot == null || (tvType != TvType.TvSeries && movieData == null)) {
            val iframeSrc = doc.selectFirst("iframe")?.attr("src")
            if (iframeSrc != null && iframeSrc.isNotBlank()) {
                movieData = JSONObject().put("streamUrl", iframeSrc).toString()
            } else if (tvType != TvType.TvSeries) {
                throw ErrorLoadingException("PlayerJS/IFrame not found; only torrent links available")
            }
        }

        val cfHeaders = CinemacityPlugin.getCfHeaders()

        return if (tvType != TvType.TvSeries) {
            newMovieLoadResponse(title, url, TvType.Movie, movieData ?: "") {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.backgroundPosterUrl = background?.let { fixUrl(it) }
                this.plot = plot
                // FIX: PosterHeaders untuk Backdrop Detail
                this.posterHeaders = cfHeaders 
                addImdbId(imdbId)
            }
        } else {
            val episodes = if (playerRoot != null) buildEpisodes(normalizeFile(playerRoot.opt("file")!!)) else emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster?.let { fixUrl(it) }
                this.backgroundPosterUrl = background?.let { fixUrl(it) }
                this.plot = plot
                // FIX: PosterHeaders untuk Backdrop Detail
                this.posterHeaders = cfHeaders
                addImdbId(imdbId)
            }
        }
    }

    private fun normalizeFile(fileValue: Any): JSONArray {
        if (fileValue is JSONArray) return fileValue
        if (fileValue is String) {
            val s = fileValue.trim()
            if (s.isBlank()) throw ErrorLoadingException("PlayerJS: empty file string")
            if (s.startsWith("[") && s.endsWith("]")) return tryParseJson<JSONArray>(s) ?: JSONArray(s)
            if (s.startsWith("{") && s.endsWith("}")) return JSONArray().put(tryParseJson<JSONObject>(s) ?: JSONObject(s))
            return JSONArray().put(JSONObject().put("file", s))
        }
        throw ErrorLoadingException("PlayerJS: unsupported file type")
    }

    private fun buildMovieData(playerRoot: JSONObject, arr: JSONArray): String? {
        val first = arr.optJSONObject(0) ?: return null
        if (first.has("folder")) return null
        val streamUrl = first.optString("file").takeIf { it.isNotBlank() } ?: return null
        val rootSub = playerRoot.opt("subtitle") as? String
        val subtitleSource = rootSub ?: (arr.optJSONObject(0)?.opt("subtitle") as? String)
        return JSONObject()
            .put("streamUrl", streamUrl)
            .put("subtitleTracks", parseSubtitles(subtitleSource))
            .toString()
    }

    private fun buildEpisodes(arr: JSONArray): List<Episode> {
        val episodes = mutableListOf<Episode>()
        for (i in 0 until arr.length()) {
            val season = arr.getJSONObject(i)
            val seasonNo = seasonRegex.find(season.optString("title"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            val folder = season.optJSONArray("folder") ?: continue
            for (j in 0 until folder.length()) {
                val ep = folder.getJSONObject(j)
                val epNo = episodeRegex.find(ep.optString("title"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                val urls = mutableListOf<String>()
                ep.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                ep.optJSONArray("folder")?.let { nested ->
                    for (k in 0 until nested.length()) {
                        val src = nested.optJSONObject(k) ?: continue
                        src.optString("file").takeIf { it.isNotBlank() }?.let { urls.add(it) }
                    }
                }
                if (urls.isEmpty()) continue
                val data = JSONObject()
                    .put("streams", JSONArray(urls))
                    .put("subtitleTracks", parseSubtitles(ep.optString("subtitle")))
                    .toString()
                episodes.add(
                    newEpisode(data) {
                        this.name = ep.optString("title").takeIf { it.isNotBlank() }
                        this.season = seasonNo
                        this.episode = epNo
                    }
                )
            }
        }
        return episodes
    }

    private fun parseSubtitles(source: String?): JSONArray {
        val out = JSONArray()
        if (source.isNullOrBlank()) return out
        source.split(",").forEach { part ->
            val m = subtitleRegex.find(part.trim()) ?: return@forEach
            val lang = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val subUrl = m.groupValues.getOrNull(2)?.trim().orEmpty()
            if (lang.isNotEmpty() && subUrl.isNotEmpty()) {
                out.put(JSONObject().put("language", lang).put("subtitleUrl", subUrl))
            }
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val obj = JSONObject(data)
        obj.optJSONArray("subtitleTracks")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                subtitleCallback(newSubtitleFile(s.getString("language"), s.getString("subtitleUrl")))
            }
        }
        val urls = mutableListOf<String>()
        obj.optJSONArray("streams")?.let { streams ->
            for (i in 0 until streams.length()) {
                streams.optString(i).takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }
        }
        if (urls.isEmpty()) {
            obj.optString("streamUrl").takeIf { it.isNotBlank() }?.let { urls.add(it) }
        }
        if (urls.isEmpty()) return false
        
        val linkHeaders = mapOf("Cookie" to buildCookieValue())
        urls.forEach { streamUrl ->
            if (streamUrl.contains(".m3u8") || streamUrl.contains(".mp4") || streamUrl.contains("/public_files/")) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name • HLS • Master ",
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = linkHeaders
                    }
                )
            } else {
                loadExtractor(streamUrl, subtitleCallback, callback)
            }
        }
        return true
    }
}

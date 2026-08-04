package com.RiveStream.api

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * ============================================================================
 *  RiveStream — klien API
 * ============================================================================
 */
object RiveApi {

    const val MAIN_URL = "https://www.rivestream.app"
    private const val ENDPOINT = "$MAIN_URL/api/backendfetch"

    /** Referer untuk permintaan API maupun untuk URL stream FlowCast. */
    const val REFERER = "$MAIN_URL/"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Basis gambar TMDB. Poster dan backdrop hanya berisi path relatif. */
    const val IMG_POSTER = "https://image.tmdb.org/t/p/w500"
    const val IMG_BACKDROP = "https://image.tmdb.org/t/p/original"

    val FALLBACK_SERVICES = listOf(
        "primevids", "flowcast", "asiacloud", "hindicast", "guru", "ophim"
    )

    val FALLBACK_EMBED_SERVICES = listOf("self", "prime")

    // --------------------------------------------------------------- limiter

    private val rateMutex = Mutex()
    private var lastRequestAt = 0L

    private const val MIN_INTERVAL_MS = 350L

    private suspend fun rateLimit() {
        rateMutex.withLock {
            val now = System.currentTimeMillis()
            val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------ inti fetch

    private fun buildUrl(requestID: String, params: Map<String, String?>, key: String): String {
        val sb = StringBuilder(ENDPOINT).append("?requestID=").append(enc(requestID))
        for ((k, v) in params) {
            if (v.isNullOrBlank()) continue
            sb.append('&').append(enc(k)).append('=').append(enc(v))
        }
        sb.append("&secretKey=").append(enc(key))
        return sb.toString()
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    suspend fun raw(
        requestID: String,
        params: Map<String, String?> = emptyMap(),
        keySource: String? = null
    ): String? {
        val key = SecretKey.of(keySource)
        val url = buildUrl(requestID, params, key)
        return try {
            rateLimit()
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to REFERER,
                    "Accept" to "application/json, text/plain, */*"
                )
            )
            val body = res.text
            if (body.isBlank() || !(body.startsWith("{") || body.startsWith("["))) null
            else body
        } catch (e: Exception) {
            null
        }
    }

    suspend fun obj(
        requestID: String,
        params: Map<String, String?> = emptyMap(),
        keySource: String? = null
    ): JSONObject? {
        val text = raw(requestID, params, keySource) ?: return null
        return try {
            if (text.startsWith("[")) JSONObject().put("data", JSONArray(text))
            else JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun results(
        requestID: String,
        params: Map<String, String?> = emptyMap(),
        keySource: String? = null
    ): JSONArray? = obj(requestID, params, keySource)?.optJSONArray("results")

    // ------------------------------------------------------------- katalog

    suspend fun catalog(
        requestID: String,
        page: Int = 1,
        language: String = "en-US",
        sortBy: String? = null
    ): JSONArray? = results(
        requestID,
        mapOf(
            "language" to language,
            "page" to page.toString(),
            "sortBy" to sortBy
        )
    )

    object Catalog {
        const val TRENDING = "trending"
        const val TRENDING_MOVIE = "trendingMovie"
        const val TRENDING_TV = "trendingTv"
        const val TRENDING_MOVIE_DAY = "trendingMovieDay"
        const val TRENDING_TV_DAY = "trendingTvDay"
        const val LATEST_MOVIE = "latestMovie"
        const val LATEST_TV = "latestTv"
        const val POPULAR_MOVIE = "popularMovie"
        const val POPULAR_TV = "popularTv"
        const val TOP_RATED_MOVIE = "topRatedMovie"
        const val TOP_RATED_TV = "topRatedTv"
        const val ON_THE_AIR_TV = "onTheAirTv"
    }

    // ------------------------------------------------------------ pencarian

    suspend fun searchMulti(
        query: String,
        page: Int = 1,
        language: String = "en-US"
    ): JSONArray? = results(
        "searchMulti",
        mapOf("query" to query, "language" to language, "page" to page.toString()),
        keySource = query
    )

    // --------------------------------------------------------------- detail

    suspend fun movieData(id: String, language: String = "en-US"): JSONObject? =
        obj("movieData", mapOf("id" to id, "language" to language), keySource = id)

    suspend fun tvData(id: String, language: String = "en-US"): JSONObject? =
        obj("tvData", mapOf("id" to id, "language" to language), keySource = id)

    suspend fun tvEpisodes(id: String, season: Int): JSONObject? =
        obj("tvEpisodes", mapOf("id" to id, "season" to season.toString()), keySource = id)

    suspend fun tvEpisodeDetail(id: String, season: Int, episode: Int): JSONObject? =
        obj(
            "tvEpisodeDetail",
            mapOf("id" to id, "season" to season.toString(), "episode" to episode.toString()),
            keySource = id
        )

    // --------------------------------------------------------------- stream

    suspend fun videoProviderServices(): List<String> {
        val arr = obj("VideoProviderServices")?.optJSONArray("data")
            ?: return FALLBACK_SERVICES
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out.ifEmpty { FALLBACK_SERVICES }
    }

    suspend fun movieVideoProvider(id: String, service: String): JSONObject? =
        obj(
            "movieVideoProvider",
            mapOf("id" to id, "service" to service),
            keySource = id
        )?.optJSONObject("data")

    suspend fun tvVideoProvider(
        id: String,
        service: String,
        season: Int,
        episode: Int
    ): JSONObject? = obj(
        "tvVideoProvider",
        mapOf(
            "id" to id,
            "service" to service,
            "season" to season.toString(),
            "episode" to episode.toString()
        ),
        keySource = id
    )?.optJSONObject("data")

    // ---------------------------------------------------------- embed

    suspend fun embedProviderServices(): List<String> {
        val arr = obj("EmbedProviderServices")?.optJSONArray("data")
            ?: return FALLBACK_EMBED_SERVICES
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out.ifEmpty { FALLBACK_EMBED_SERVICES }
    }

    suspend fun movieEmbedProvider(id: String, service: String): JSONObject? {
        val res = obj("movieEmbedProvider", mapOf("id" to id, "service" to service), keySource = id) ?: return null
        return wrapEmbedData(res)
    }

    suspend fun tvEmbedProvider(
        id: String,
        service: String,
        season: Int,
        episode: Int
    ): JSONObject? {
        val res = obj(
            "tvEmbedProvider",
            mapOf("id" to id, "service" to service, "season" to season.toString(), "episode" to episode.toString()),
            keySource = id
        ) ?: return null
        return wrapEmbedData(res)
    }

    /** Helper untuk menormalisasi struktur data embed. */
    private fun wrapEmbedData(res: JSONObject): JSONObject? {
        if (res.has("sources")) return res
        val dataObj = res.optJSONObject("data")
        if (dataObj != null) return dataObj

        val dataArr = res.optJSONArray("data")
        if (dataArr != null) return JSONObject().put("sources", dataArr)

        return null
    }

    // ------------------------------------------------------------- pembantu

    fun posterUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG_POSTER + it }

    fun backdropUrl(path: String?): String? =
        path?.takeIf { it.isNotBlank() && it != "null" }?.let { IMG_BACKDROP + it }
}

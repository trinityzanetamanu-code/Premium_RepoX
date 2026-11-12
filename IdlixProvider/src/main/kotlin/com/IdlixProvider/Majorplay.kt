package com.IdlixProvider

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class Majorplay : ExtractorApi() {
    override var name = "Majorplay"
    override var mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parsed = url.toHttpUrlOrNull() ?: return
        val claim = parsed.queryParameter("claim")?.takeIf { it.isNotBlank() } ?: return
        resolveClaim(SessionClaimResponse(claim = claim,
            redeemUrl = parsed.queryParameter("redeemUrl") ?: "/watch/session/redeem"),
            referer ?: "https://z2.idlixku.com/", subtitleCallback, callback)
    }

    suspend fun resolveClaim(
        session: SessionClaimResponse,
        effectiveReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        sessionCookies: Map<String, String> = emptyMap()
    ): Boolean {
        val claimToken = session.claim?.takeIf { it.isNotBlank() } ?: return false
        val page = effectiveReferer.toHttpUrlOrNull() ?: return false
        val effectiveOrigin = "${page.scheme}://${page.host}"
        val target = IdlixPlayback.redeemUrl(session.redeemUrl, effectiveOrigin) ?: return false
        val safeHeaders = mapOf(
            "Origin" to effectiveOrigin,
            "Referer" to effectiveReferer,
            "User-Agent" to IdlixPlayback.USER_AGENT
        )
        val jsonMediaType = RequestBodyTypes.JSON.toMediaTypeOrNull()
        val requestBodyData = mapOf("claim" to claimToken, "mode" to "browser").toJson().toRequestBody(jsonMediaType)
        val redeemCookies = if (target.toHttpUrlOrNull()?.host == page.host) sessionCookies else emptyMap()
        val redeem = app.post(target, headers = safeHeaders, cookies = redeemCookies, requestBody = requestBodyData,
            allowRedirects = false, timeout = 15)
        if (redeem.code != 200) {
            Log.d("IdlixProvider", "Playback redeem HTTP=${redeem.code}")
            return false
        }
        val response = AppUtils.parseJson<NewMajorplayResponse>(redeem.text)
        if (response.code != "ok" || response.mode != "browser") return false
        val master = response.url?.toHttpUrlOrNull() ?: return false
        val videoId = IdlixPlayback.videoId(master) ?: return false
        if (session.videoId != null && session.videoId != videoId) return false
        if (response.videoId != null && response.videoId != videoId) return false
        if (master.queryParameter("t").isNullOrBlank()) return false
        val expiresAt = response.expiresAt ?: return false
        val serverTime = redeem.headers["Date"]?.let {
            runCatching { java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(it)?.time }.getOrNull()
        } ?: System.currentTimeMillis()
        if (expiresAt <= serverTime / 1000L + 30L || expiresAt > serverTime / 1000L + 86_400L) return false

        val masterConfigUrl = IdlixPlayback.authorize(master, master).toString()
        val manifest = app.get(masterConfigUrl, headers = safeHeaders, allowRedirects = false, timeout = 15)
        if (manifest.code != 200) return false
        val manifestText = manifest.text.trimStart('\uFEFF', ' ', '\r', '\n')
        if (manifestText.length > 262_144 || !manifestText.startsWith("#EXTM3U") ||
            !(manifestText.contains("#EXT-X-STREAM-INF:") || manifestText.contains("#EXTINF:"))) return false

        response.subtitles?.forEach { sub ->
            val subUrl = sub.path?.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() && IdlixPlayback.mediaHost(it.host)
            }?.newBuilder()?.host(master.host)?.build()?.toString() ?: return@forEach
            subtitleCallback.invoke(newSubtitleFile(sub.label ?: sub.lang ?: "Indo", subUrl))
        }
        callback.invoke(newExtractorLink(name, "Majorplay Auto Quality", masterConfigUrl, ExtractorLinkType.M3U8) {
            this.headers = safeHeaders
            this.referer = effectiveReferer
            this.quality = Qualities.Unknown.value
        })
        return true
    }
}

// FIX #5: Data class dipindah ke luar class agar lebih sesuai konvensi Cloudstream
@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorplayResponse(
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("mode") val mode: String? = null,
    @JsonProperty("videoId") val videoId: String? = null,
    @JsonProperty("expiresAt") val expiresAt: Long? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("subtitles") val subtitles: List<NewMajorSubtitle>? = null,
    @JsonProperty("label") val label: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NewMajorSubtitle(
    @JsonProperty("lang") val lang: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("path") val path: String? = null
)

/** Idlix's served browser player rewrites t/pm on every request for the selected video. */
internal object IdlixPlayback {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // From the media-host contract in Idlix's served 2znv4-qfvxuz6.js (not ad hosts).
    private val cdnDomains = setOf(
        "academiclearning.site", "akademivo.website", "aspireheightsacademy.digital",
        "careerskills.site", "classroomlearning.space", "distancelearning.space",
        "e-learningtips.site", "edubambu.store", "educationalskills.space",
        "educationlearning.store", "educationonlinelearning.xyz", "edusparkonline.quest",
        "eldrinlorekeeper.store", "exampreparation.space", "highereducation.online",
        "homeworkhelp.site", "horizonacademy.site", "knowledgequest.store",
        "languagelearning.space", "learnleapinstitute.site", "mindbloomcourses.website",
        "onlineeducation.store", "onlinestudy.online", "scholarshipeducation.online",
        "skilldevelopment.space", "skillsprouthub.space", "stemeducation.site",
        "studentsuccess.space", "studycourses.site", "tutoringskills.site"
    )
    private val z4Domains = setOf(
        "academiclearning.site", "aspireheightsacademy.digital", "classroomlearning.space",
        "edubambu.store", "edusparkonline.quest", "eldrinlorekeeper.store",
        "horizonacademy.site", "knowledgequest.store", "learnleapinstitute.site",
        "mindbloomcourses.website", "onlinestudy.online", "skillsprouthub.space"
    )

    private fun secure(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null

    fun mediaHost(host: String): Boolean {
        if (host == "e2e.majorplay.net") return true
        val prefix = host.substringBefore('.')
        val domain = host.substringAfter('.', "")
        return domain in cdnDomains && (prefix in setOf("g1", "g2", "g3", "g5", "g6", "g7", "g8") ||
            prefix == if (domain in z4Domains) "z4" else "g4")
    }

    fun videoId(url: HttpUrl): String? = url.pathSegments.takeIf {
        secure(url) && mediaHost(url.host) && it.size >= 4 && it[0] == "v"
    }?.getOrNull(2)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }

    fun authorize(url: HttpUrl, master: HttpUrl): HttpUrl {
        val id = videoId(master) ?: return url
        val token = master.queryParameter("t")?.takeIf { it.isNotBlank() } ?: return url
        if (videoId(url) != id) return url
        return url.newBuilder().setQueryParameter("t", token).setQueryParameter("pm", "browser").build()
    }

    fun redeemUrl(value: String?, origin: String): String? {
        val base = origin.toHttpUrlOrNull()?.takeIf(::secure) ?: return null
        if (value == "/watch/session/redeem") return base.resolve("/api/watch/session/redeem")?.toString()
        val url = value?.toHttpUrlOrNull()?.takeIf(::secure) ?: return null
        return url.takeIf {
            (it.host == "majorplay.net" || it.host.endsWith(".majorplay.net")) &&
                it.encodedPath.trimEnd('/') == "/api/play" && it.query == null
        }?.toString()
    }

    /** Reject excessive waits instead of claiming before the server's unlock time. */
    fun waitMillis(serverNow: Long?, unlockAt: Long?, countdownSec: Long?): Long? {
        val countdown = countdownSec ?: 7L
        if (countdown !in 0..29 || serverNow == null || unlockAt == null || serverNow < 0 || unlockAt < 0) return null
        val remaining = if (unlockAt > serverNow) unlockAt - serverNow else 0L
        if (remaining > 29_000L) return null
        return maxOf(countdown * 1000L, remaining) + 1000L
    }

    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun interceptor(masterUrl: String): Interceptor {
        val master = masterUrl.toHttpUrlOrNull()
        // An independent client keeps redirect handling inside the credential boundary.
        val scoped = client.newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val authorized = master?.let { authorize(request.url, it) } ?: request.url
            chain.proceed(request.newBuilder().url(authorized).build())
        }.build()
        return Interceptor { chain -> scoped.newCall(chain.request()).execute() }
    }
}

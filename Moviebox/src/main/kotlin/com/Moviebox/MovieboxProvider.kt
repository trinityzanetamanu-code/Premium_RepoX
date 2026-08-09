package com.Moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

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
    }

    private suspend fun getBearerToken(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "categoryType=4516404531735022304&page=1&perPage=1&tabId=0"
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

    // 1. Menggunakan Interceptor untuk Menjamin Header Cookie pada OkHttpDataSource
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

    // 2. Load Links Menggunakan Standards ExtractorLink
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val subjectId = data.trim()
        val bearerToken = getBearerToken() ?: return false

        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/subject-api/play-info"
        val query = "ep=0&se=0&subjectId=$subjectId"
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

        val playData = response.parsedSafe<PlayInfoResponse>() ?: return false
        val stream = playData.data?.streams?.firstOrNull() ?: return false

        val mpdUrl = stream.url ?: return false
        val rawCookie = stream.signCookie ?: return false
        val cleanCookie = rawCookie.trimEnd(';')

        callback(
            ExtractorLink(
                source = name,
                name = "MovieBox (DASH 1080p HEVC)",
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

    // Models
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

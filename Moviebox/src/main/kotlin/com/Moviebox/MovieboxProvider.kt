package com.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
import okhttp3.OkHttpClient
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
        private const val USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val CLIENT_INFO = """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","device_id":"71e0f7746936dc98","install_store":"ps","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":""}"""
        
        // Key Secret HMAC (Double Base64 Decoded Key: 76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O)
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

    // Helper Bootstrap Session untuk mendapatkan Bearer JWT Token
    private suspend fun getBearerToken(): String? {
        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/tab/ranking-list"
        val query = "categoryType=4516404531735022304&page=1&perPage=1&tabId=0"
        val fullUrl = "$mainUrl$path?$query"

        val guestToken = generateGuestToken(ts)
        val signature = generateSignature("$path?$query", ts)

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "user-agent" to USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to guestToken,
                "x-tr-signature" to signature,
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0"
            )
        )

        val xUserHeader = response.headers["x-user"] ?: return null
        val json = app.baseClient.newBuilder().build()
        // Extract JWT dari JSON String {"token":"eyJ...", "userId":"..."}
        val tokenMatch = """"token"\s*:\s*"([^"]+)"""".toRegex().find(xUserHeader)
        return tokenMatch?.groupValues?.get(1)
    }

    override suspend fun loadLinks(
        data: String, // subjectId, misal: "74738785354956752"
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val subjectId = data.trim()
        val bearerToken = getBearerToken() ?: return false

        val ts = System.currentTimeMillis().toString()
        val path = "/wefeed-mobile-bff/subject-api/play-info"
        // Query Wajib Terurut Alfabetis
        val query = "ep=0&se=0&subjectId=$subjectId"
        val fullUrl = "$mainUrl$path?$query"

        val guestToken = generateGuestToken(ts)
        val signature = generateSignature("$path?$query", ts)

        val response = app.get(
            fullUrl,
            headers = mapOf(
                "authorization" to "Bearer $bearerToken",
                "user-agent" to USER_AGENT,
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to guestToken,
                "x-tr-signature" to signature,
                "x-client-info" to CLIENT_INFO,
                "x-client-status" to "0"
            )
        )

        val playData = response.parsedSafe<PlayInfoResponse>() ?: return false
        val stream = playData.data?.streams?.firstOrNull() ?: return false

        val mpdUrl = stream.url ?: return false
        val rawCookie = stream.signCookie ?: return false
        val cleanCookie = rawCookie.trimEnd(';')

        // Rakit ExtractorLink khusus DASH
        callback(
            ExtractorLink(
                source = name,
                name = "MovieBox (DASH 1080p HEVC)",
                url = mpdUrl,
                referer = mainUrl,
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.DASH,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Cookie" to cleanCookie,
                    "Referer" to mainUrl
                )
            )
        )

        return true
    }

    // Data Classes untuk Response Parsing
    data class PlayInfoResponse(
        val code: Int?,
        val message: String?,
        val data: PlayData?
    )

    data class PlayData(
        val streams: List<StreamItem>?
    )

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

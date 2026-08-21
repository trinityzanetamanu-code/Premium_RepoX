package com.fourKHDHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor

// ============================================================================
// [RECONSTRUCTED FROM VERIFIED BEHAVIOR]
// ============================================================================
class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.cx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val hubResponse = app.get(url, referer = referer).text
            
            val gamerxytRegex = Regex("""href=["'](https://gamerxyt\.com/hubcloud\.php[^"']+)["']""")
            val gamerxytUrl = gamerxytRegex.find(hubResponse)?.groupValues?.get(1)?.replace("&amp;", "&")
            
            if (gamerxytUrl != null) {
                val gxResponse = app.get(gamerxytUrl, referer = url).text
                
                val finalRegex = Regex("""<a[^>]+href=["'](https?://[^"']+)["'][^>]*>""")
                val links = finalRegex.findAll(gxResponse).map { it.groupValues[1] }

                links.forEach { finalUrl ->
                    when {
                        finalUrl.contains("r2.cloudflarestorage.com") -> {
                            // [FIXED] Menggunakan traditional constructor
                            callback.invoke(
                                ExtractorLink(
                                    source = "4K HDHUB [R2 FSL]",
                                    name = "4K HDHUB [R2 FSL]",
                                    url = finalUrl,
                                    referer = gamerxytUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = false
                                )
                            )
                        }
                        finalUrl.contains("video-downloads.googleusercontent.com") -> {
                            // [FIXED] Menggunakan traditional constructor
                            callback.invoke(
                                ExtractorLink(
                                    source = "4K HDHUB [Google]",
                                    name = "4K HDHUB [10Gbps]",
                                    url = finalUrl,
                                    referer = gamerxytUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = false
                                )
                            )
                        }
                        finalUrl.contains("pixeldrain") -> {
                            loadExtractor(finalUrl, gamerxytUrl, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

// ============================================================================
// [RECONSTRUCTED FROM VERIFIED BEHAVIOR]
// ============================================================================
class PixelDrainDev : ExtractorApi() {
    override val name = "PixelDrainDev"
    override val mainUrl = "https://pixeldrain.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalUrl = if (!url.contains("?download")) "$url?download" else url
        
        // [FIXED] Menggunakan traditional constructor
        callback.invoke(
            ExtractorLink(
                source = "Pixeldrain",
                name = "Pixeldrain [Web-DL]",
                url = finalUrl,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8 = false
            )
        )
    }
}

// ============================================================================
// [UNRECOVERED] SECONDARY EXTRACTORS
// ============================================================================

class HdStream4u : ExtractorApi() {
    override val name = "HdStream4u"
    override val mainUrl = "https://hdstream4u.com" 
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Hubstream : ExtractorApi() {
    override val name = "Hubstream"
    override val mainUrl = "https://hubstream.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Hubstreamdad : ExtractorApi() {
    override val name = "Hubstreamdad"
    override val mainUrl = "https://hubstream.dad"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Hubcdnn : ExtractorApi() {
    override val name = "Hubcdnn"
    override val mainUrl = "https://hubcdnn.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

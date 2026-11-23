package com.fourKHDHub

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import java.net.URI


class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.*"
}

class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.*"
}

class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://hblinks.*"
}

open class Hblinks : ExtractorApi() {

    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to (referer ?: url)
        )
        val elements = runCatching {
            app.get(url, headers = headers).documentLarge
                .select("h3 a, h5 a, div.entry-content p a, div.entry-content a")
        }.getOrElse {
            Log.e(name, "Failed to load page: ${it.message}")
            return
        }

        for (el in elements) {
            val href = el.absUrl("href")
                .ifBlank { el.attr("href") }
                .trim()

            if (href.isEmpty() || href.startsWith("#") ||
                href.contains("telegram", ignoreCase = true) || href.contains("t.me", ignoreCase = true)
            ) continue

            val lower = href.lowercase()

            when {
                "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                "gdflix" in lower -> loadSourceNameExtractor(
                    name, href, "", Qualities.Unknown.value, subtitleCallback, callback
                )
                "gofile" in lower -> loadExtractor(href, subtitleCallback, callback)
                else ->
                    loadSourceNameExtractor(
                        name,
                        href,
                        "",
                        Qualities.Unknown.value,
                        subtitleCallback,
                        callback
                    )
            }
        }
    }
}


class Hubcdnn : ExtractorApi() {

    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url).documentLarge.toString()
        }.getOrElse {
            Log.e(name, "Failed to load page: ${it.message}")
            return
        }

        val encoded = Regex("r=([A-Za-z0-9+/=]+)")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        if (encoded.isEmpty()) {
            Log.e(name, "Encoded URL not found")
            return
        }

        val m3u8 = runCatching {
            base64Decode(encoded).substringAfterLast("link=")
        }.getOrElse {
            Log.e(name, "Failed to decode base64")
            return
        }

        callback(
            newExtractorLink(
                name,
                name,
                m3u8,
                ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}


class PixelDrainDev : PixelDrain(){
    override var mainUrl = "https://pixeldrain.dev"
}

class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href = app.get(url, timeout = 5000L)
            .documentLarge.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        if (href.contains("hubcloud", ignoreCase = true)) {
            HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
        } else {
            loadExtractor(href, "HubDrive", subtitleCallback, callback)
        }
    }
}


class HubCloud : ExtractorApi() {

    override val name = "Hub-Cloud"
    override var mainUrl: String = runBlocking {
        FourKHDHubProvider.getDomains()?.hubcloud ?: "https://hubcloud.foo"
    }
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val prefix = when {
            referer.isNullOrBlank() || referer.contains("4khdhub", ignoreCase = true) ||
                referer.contains("4k hdhub", ignoreCase = true) -> "HubCloud"
            referer.contains("HubCloud", ignoreCase = true) || referer.contains("Hub-Cloud", ignoreCase = true) -> referer.trim()
            else -> "${referer.trim()} HubCloud"
        }

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        var realUrl = uri.toString()
        var baseUrl = "${uri.scheme}://${uri.host}"

        FourKHDHubProvider.getDomains()?.hubcloud?.trim()?.trimEnd('/')?.let { latest ->
            if (latest.isNotBlank() && !baseUrl.equals(latest, ignoreCase = true)) {
                realUrl = realUrl.replace(baseUrl, latest)
                baseUrl = latest
            }
        }

        val href = runCatching {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl).document
                    .selectFirst("a[href*='hubcloud.php']")
                    ?.attr("href")
                    .orEmpty()

                if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: ${it.message}")
            ""
        }

        if (href.isBlank()) return

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text()?.substringBefore("|")?.trim().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append(headerDetails)
            if (size.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("[$size]")
            }
        }

        for (element in document.select("a.btn")) {
            val link = resolveButtonHref(element, document)
            val text = element.text().ifBlank { element.ownText() }
            val label = text.lowercase()

            when {
                "fsl server" in label -> {
                    callback(
                        newExtractorLink(
                            "$prefix [FSL Server]".trim(),
                            "$prefix [FSL Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "download file" in label || "instant download" in label || "instant" in label -> {
                    callback(
                        newExtractorLink(
                            "$prefix [Instant Download]".trim(),
                            "$prefix [Instant Download] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "buzzserver" in label || "buzz server" in label ||
                    "buzz" in label || "fuckingfast" in label -> {
                    val browserHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Sec-Ch-Ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
                        "Sec-Ch-Ua-Platform" to "\"Windows\"",
                        "Sec-Ch-Ua-Mobile" to "?0"
                    )
                    val firstResponse = runCatching { app.get(link, headers = browserHeaders) }.getOrNull()
                    val hxGet = firstResponse?.document?.selectFirst("a[hx-get*='download']")?.attr("hx-get")
                    val copiedLink = Regex("copyDownloadLink\\(['\"]([^'\"]+)['\"]\\)")
                        .find(firstResponse?.text.orEmpty())?.groupValues?.getOrNull(1)?.replace("\\/", "/")
                    val downloadPath = hxGet ?: copiedLink
                    val downloadUrl = when {
                        downloadPath.isNullOrBlank() && link.endsWith("/download") -> link
                        downloadPath.isNullOrBlank() -> link.trimEnd('/') + "/download"
                        downloadPath.startsWith("http", ignoreCase = true) -> downloadPath
                        else -> getBaseUrl(link).trimEnd('/') + "/" + downloadPath.trimStart('/')
                    }
                    val resp = runCatching {
                        app.get(downloadUrl, headers = browserHeaders + mapOf(
                            "Referer" to link, "HX-Request" to "true"
                        ), allowRedirects = false)
                    }.getOrNull()
                    val dlink = resp?.headers?.get("hx-redirect")
                        ?: resp?.headers?.get("HX-Redirect")
                        ?: resp?.headers?.get("Location")
                        ?: resp?.headers?.get("location")
                        ?: ""

                    if (dlink.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                "$prefix [BuzzServer]".trim(),
                                "$prefix [BuzzServer] $labelExtras",
                                dlink
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w(tag, "BuzzServer: No redirect")
                    }
                }

                "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                    val base = getBaseUrl(link)
                    val finalUrl =
                        if ("download" in link) link
                        else "$base/api/file/${link.substringAfterLast("/")}?download"

                    callback(
                        newExtractorLink(
                            "[Pixeldrain]",
                            "$prefix [Pixeldrain] $labelExtras",
                            finalUrl
                        ) { this.quality = quality }
                    )
                }

                "s3 server" in label -> {
                    callback(
                        newExtractorLink(
                            "[FSL Server 3]",
                            "$prefix [S3 Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "fslv2" in label -> {
                    callback(
                        newExtractorLink(
                            "[FSL Server 2]",
                            "$prefix [FSLv2] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                "mega server" in label -> {
                    callback(
                        newExtractorLink(
                            "[Mega Server]",
                            "$prefix [Mega Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )
                }

                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }

    private fun resolveButtonHref(
        element: org.jsoup.nodes.Element,
        document: org.jsoup.nodes.Document
    ): String {
        var href = element.attr("href")
        val id = element.id()
        if (id.isBlank()) return href

        val html = document.html()
        val assignment = Regex(
            "document\\.getElementById\\([\\\"']${Regex.escape(id)}[\\\"']\\)\\.href\\s*=\\s*([^;]+)"
        ).find(html)?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')

        if (!assignment.isNullOrBlank()) {
            href = if (assignment.startsWith("http", ignoreCase = true)) {
                assignment
            } else {
                Regex("(?:var|let|const)?\\s*${Regex.escape(assignment)}\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
                    .find(html)?.groupValues?.getOrNull(1) ?: href
            }
        }
        return href
    }

    private fun cleanTitle(title: String): String {

        val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")

        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
            .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")

        val parts = normalized.split(" ", "_", ".")

        val sourceTags = setOf(
            "WEB-DL", "WEBRIP", "BLURAY", "HDRIP",
            "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP"
        )

        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")

        val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")

        val audioExtras = setOf("ATMOS")

        val hdrTags = setOf("SDR","HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

        val filtered = parts.mapNotNull { part ->
            val p = part.uppercase()

            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } -> p
                audioExtras.contains(p) -> p
                hdrTags.contains(p) -> {
                    when (p) {
                        "DV", "DOLBYVISION" -> "DOLBYVISION"
                        else -> p
                    }
                }
                p == "NF" || p == "CR" -> p
                else -> null
            }
        }

        return filtered.distinct().joinToString(" ")
    }
}

class HubCloudWildcard : HubCloud() {
    override var mainUrl = "https://*.hubcloud.*"
}

class HubCloudApex : HubCloud() {
    override var mainUrl = "https://hubcloud.*"
}

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url).documentLarge
        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = Regex("reurl\\s*=\\s*\"([^\"]+)\"")
            .find(scriptText ?: "")
            ?.groupValues?.get(1)
            ?.substringAfter("?r=")

        val decodedUrl = encodedUrl?.let { base64Decode(it) }?.substringAfterLast("link=")


        if (decodedUrl != null) {
            callback(
                newExtractorLink(
                    this.name,
                    this.name,
                    decodedUrl,
                    INFER_TYPE,
                )
                {
                    this.quality=Qualities.Unknown.value
                }
            )
        }
    }
}

package com.kurakura21

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * Isolated CloudStream provider for KURAKURA21.
 *
 * Page routes and visible server labels were inspected on 2026-11-09.
 * Individual HTML attributes / media endpoints could not be verified in the
 * inspection environment; playback extraction below is intentionally generic,
 * never fabricates video URLs, and returns false when there is no valid source.
 * This is a BETA provider, not a verified working video extractor.
 */
class KuraKura21Provider(private val context: Context) : MainAPI() {
    override var name = "KURAKURA21"
    override var mainUrl = "https://kurakura21.com"
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val userAgent = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private val headers get() = mapOf(
        "User-Agent" to userAgent,
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8",
        "Referer" to "$mainUrl/"
    )

    // Category paths confirmed via the site's visible links on 2026-11-09.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/genre/bioskop/" to "Bioskop",
        "$mainUrl/genre/jav-sub-indo/" to "JAV Sub Indo",
        "$mainUrl/genre/jav-english-sub/" to "JAV English Sub",
        "$mainUrl/genre/indo-18/" to "Indonesia 18+",
        "$mainUrl/genre/korea-18/" to "Korea 18+",
        "$mainUrl/genre/barat-18/" to "Barat 18+",
        "$mainUrl/genre/cina-18/" to "China 18+"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (page <= 1) request.data else request.data.trimEnd('/') + "/page/$page/"
        val doc = getPage(url)
        val results = parseListing(doc)
        return newHomePageResponse(
            request.name,
            results,
            hasNext = doc.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next, .wp-pagenavi a.nextpostslink") != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return parseListing(getPage("$mainUrl/?s=$encoded"))
    }

    override suspend fun load(url: String): LoadResponse? {
        val safePage = pageUrl(url) ?: return null
        val doc = getPage(safePage)
        val title = doc.selectFirst("h1.entry-title, h1[itemprop=name], .sheader h1, h1")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val poster = findImage(doc.selectFirst(".poster img, .sheader img, .mvic-desc img, article img, .entry-content img"), doc)
            ?: resolveUrl(doc.selectFirst("meta[property=og:image]")?.attr("content"), doc.baseUri())
        val summary = doc.selectFirst(".synopsis, .wp-content > p, .entry-content > p, .description > p")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val tags = doc.select("a[href*='/genre/']")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct().take(20)
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.value?.toIntOrNull()
        val recommendations = parseListing(doc).filter { it.url != safePage }.take(12)

        return newMovieLoadResponse(title, safePage, TvType.NSFW, safePage) {
            this.posterUrl = poster
            this.plot = summary
            this.tags = tags
            this.year = year
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailUrl = pageUrl(data) ?: return false
        return KuraKura21Playback(mainUrl, headers, context).load(detailUrl, subtitleCallback, callback)
    }

    private suspend fun getPage(url: String): Document {
        val response = app.get(url, headers = headers)
        if (response.code !in 200..299) {
            throw ErrorLoadingException("KURAKURA21 HTTP ${response.code}")
        }
        return response.document.apply { setBaseUri(response.url) }
    }

    private fun parseListing(doc: Document): List<SearchResponse> {
        val cards = doc.select(
            "article.item, article.post, .items .item, .movies .item, .movie-item, " +
            ".ml-item, .result-item, .latest-movie .item, .post-item, .movie"
        )
        val items = cards.mapNotNull { parseCard(it, doc) }.distinctBy { it.url }
        if (items.isNotEmpty()) return items

        // Fallback for layouts where cards have no stable container class.
        return doc.select("h2 > a[href], h3 > a[href], .entry-title a[href]")
            .mapNotNull { link ->
                val url = resolveUrl(link.attr("href"), doc.baseUri()) ?: return@mapNotNull null
                if (!isMovieDetailUrl(url)) return@mapNotNull null
                val title = link.text().trim().ifEmpty { return@mapNotNull null }
                val image = link.parent()?.parent()?.selectFirst("img")
                newMovieSearchResponse(title, url, TvType.NSFW) {
                    posterUrl = findImage(image, doc)
                }
            }.distinctBy { it.url }
    }

    private fun parseCard(card: Element, doc: Document): SearchResponse? {
        val link = card.selectFirst("h2 a[href], h3 a[href], h4 a[href], .entry-title a[href], .title a[href], a[title][href]")
            ?: return null
        val url = resolveUrl(link.attr("href"), doc.baseUri()) ?: return null
        if (!isMovieDetailUrl(url)) return null
        val image = card.selectFirst("img")
        val title = link.text().trim().ifEmpty {
            link.attr("title").trim().ifEmpty { image?.attr("alt")?.trim().orEmpty() }
        }.takeIf { it.isNotBlank() } ?: return null
        return newMovieSearchResponse(title, url, TvType.NSFW) {
            posterUrl = findImage(image, doc)
        }
    }

    private fun findImage(image: Element?, doc: Document): String? {
        if (image == null) return null
        val attributes = listOf("data-src", "data-original", "data-lazy-src", "data-wpfc-original-src", "src")
        for (attribute in attributes) {
            resolveUrl(image.attr(attribute), doc.baseUri())?.let { return it }
        }
        return null
    }

    private fun pageUrl(raw: String): String? = resolveUrl(raw, mainUrl)?.takeIf(::isMovieDetailUrl)

    private fun isMovieDetailUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val validHost = uri.host?.lowercase() in setOf("kurakura21.com", "www.kurakura21.com")
            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
            validHost && segments.size == 1 && segments[0] !in setOf(
                "genre", "page", "search", "tag", "category", "wp-admin", "wp-content"
            )
        } catch (_: Exception) { false }
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.takeIf {
            it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("javascript:", true) && !it.startsWith("data:", true)
        } ?: return null
        return try {
            val uri = URI(base).resolve(value)
            if (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) uri.toString() else null
        } catch (_: Exception) { null }
    }

}

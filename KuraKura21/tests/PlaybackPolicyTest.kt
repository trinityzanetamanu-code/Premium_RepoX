package com.kurakura21

import org.json.JSONObject

/**
 * URL identities/paths come from the user's Chrome capture. HTML/JSON/manifests
 * below are intentionally synthetic unit fixtures, NOT claimed server responses.
 */
fun main() {
    var count = 0
    fun verify(name: String, result: Boolean) {
        check(result) { name }
        count++
    }
    val id = "a4g5v3xj6ltr"
    val frame = "https://kr21.click/e/$id/ibu-kos-kurakura21"
    val nested = "https://n1mwq.org/pn7/$id"
    val policy = KuraKura21MediaPolicy(id)
    val master = "https://rotating-cdn.example.org/hls2/05/12480/${id}_,n,h,.urlset/master.m3u8?token=TEST_ONLY&expires=123"
    val variant = "https://rotating-cdn.example.org/hls2/05/12480/${id}_n/index-v1-a1.m3u8?token=TEST_ONLY"
    val observed = setOf("https://kr21.click", "https://n1mwq.org")
    verify("entry identity", KuraKura21MediaPolicy.identity(frame) == id)
    verify("nested identity", KuraKura21MediaPolicy.identity(nested) == id)
    verify("unknown domain is not a player", KuraKura21MediaPolicy.identity("https://unknown.example.org/e/$id") == null)
    verify("wrong video iframe", !policy.playerDocument("https://n1mwq.org/pn7/different123"))
    verify("metadata can declare a rotated player", policy.authorizeFrame("https://new-player.example.org/pn8/$id"))
    verify("declared player accepted", policy.playerDocument("https://new-player.example.org/pn8/$id"))
    verify("metadata rejects different identity", !policy.authorizeFrame("https://new-player.example.org/pn8/wrongvideo123"))
    verify("metadata rejects ad player", !policy.authorizeFrame("https://tsvideo.sacdnssedge.com/pn7/$id"))
    verify("HLS master identity", policy.mediaPath(master))
    verify("HLS variant identity", policy.mediaPath(variant))
    verify("MP4 recognition", policy.mediaPath("https://cdn.example.org/$id.mp4"))
    verify("segments are not playable callbacks", !policy.mediaPath("https://cdn.example.org/${id}_n/seg-1-v1-a1.ts"))
    verify("whole player chain required", !policy.accepts(master, nested, null, emptySet()))
    verify("full player referrer accepted", policy.accepts(master, nested, "https://n1mwq.org", observed))
    verify("origin-only referrer accepted after frame", policy.accepts(master, "https://n1mwq.org/", null, observed))
    verify("unobserved referrer rejected", !policy.accepts(master, "https://new-player.example.org/", null, observed))
    verify("different referrer video rejected", !policy.accepts(master, "https://n1mwq.org/pn7/wrongvideo123", null, observed))
    verify("mismatched Origin rejected", !policy.accepts(master, nested, "https://kr21.click", observed))
    verify("movie detail is not a player referrer", !policy.accepts(master, "https://kurakura21.com/", null, observed))
    verify("query identity is not path identity", !policy.mediaPath("https://cdn.example.org/advert.m3u8?video=$id"))
    verify("known CDN is not proof", !policy.mediaPath("https://edge.owphbf24.com/unrelated/master.m3u8"))
    verify("case-sensitive video identity", !policy.mediaPath(master.replace(id, id.uppercase())))
    verify("substring identity rejected", !policy.mediaPath(master.replace(id, "prefix$id")))
    verify("HTTP media rejected", !policy.mediaPath(master.replace("https:", "http:")))
    verify("URL credentials rejected", !policy.mediaPath(master.replace("https://", "https://user:pass@")))
    verify("path traversal rejected", !policy.mediaPath("https://cdn.example.org/${id}_n/../index.m3u8"))
    verify("encoded traversal rejected", !policy.mediaPath("https://cdn.example.org/${id}_n/%2e%2e/index.m3u8"))
    verify("loopback rejected", !policy.mediaPath("https://127.0.0.1/${id}_n/index.m3u8"))
    for (host in listOf("tsvideo.sacdnssedge.com", "xvacdn77.tsyndicate.com", "other.tsyndicate.com", "stripchat.com")) {
        verify("ad host $host", KuraKura21MediaPolicy.isAd("https://$host/media.mp4"))
        verify("ad cannot borrow movie ID $host", !policy.accepts("https://$host/${id}_n/index.m3u8", nested, null, observed))
    }
    val manifest = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000\n../${id}_n/index-v1-a1.m3u8\n"
    verify("identity-linked master", policy.validPlaylist(manifest, master))
    verify("variant resolves dynamically", policy.firstVariant(manifest, master)?.endsWith("${id}_n/index-v1-a1.m3u8") == true)
    val vod = "#EXTM3U\n#EXTINF:5.0,\nseg-1-v1-a1.ts\n#EXTINF:5.0,\nseg-2-v1-a1.ts\n#EXT-X-ENDLIST\n"
    verify("linked media playlist", policy.validPlaylist(vod, variant))
    verify("long duration is not proof", !policy.validPlaylist("#EXTM3U\n#EXTINF:563,\nhttps://cdn.example.org/wrong-video/segment.ts", variant))
    verify("ad segment is rejected", !policy.validPlaylist("#EXTM3U\n#EXTINF:563,\nhttps://tsvideo.sacdnssedge.com/${id}_n/seg.ts", variant))
    verify("HTML is not HLS", !policy.validPlaylist("<html>Site Unavailable</html>", master))
    verify("empty HLS rejected", !policy.validPlaylist("#EXTM3U", master))
    verify("master with different video rejected", !policy.validPlaylist(manifest.replace(id, "different123"), master))
    verify("fragment does not alter identity", KuraKura21MediaPolicy.sameDocument(frame, "$frame#p1"))
    val html = """<div id="muvipro_player_content_id" data-id="271180"><ul class="muvipro-player-tabs">
        <li><a href="#p1">Server 1</a></li><li><a href="#p2">Server 2</a></li>
        <li><a href="#p3">Server 3</a></li><li><a href="#p1">duplicate</a></li>
        <li><a href="#p1';alert(1)">invalid</a></li></ul></div>"""
    val player = KuraKura21PlayerData.parse(html)!!
    verify("sample ID parsed dynamically", player.postId == "271180")
    verify("different film ID parsed dynamically", KuraKura21PlayerData.parse(html.replace("271180", "999123"))?.postId == "999123")
    verify("three servers and no duplicates", player.tabs.map { it.first } == listOf("p1", "p2", "p3"))
    verify("AJAX action and selected server", KuraKura21PlayerData.ajaxData("p2", "999123") == mapOf("action" to "muvipro_player_content", "tab" to "p2", "post_id" to "999123"))
    verify("missing player fails", KuraKura21PlayerData.parse("<h1>Site Unavailable</h1>") == null)
    verify("invalid ID fails", KuraKura21PlayerData.parse(html.replace("271180", "abc")) == null)
    val fragment = """<iframe src="$frame"></iframe><script>var ad='https://ads.example.org/a.mp4';</script><iframe src="https://tsvideo.sacdnssedge.com/ads/"></iframe>"""
    verify("AJAX selects only actual iframe", KuraKura21PlayerData.frames(fragment, "https://kurakura21.com/") == listOf(frame))
    verify("JSON HTML string parses", KuraKura21PlayerData.frames(JSONObject.quote(fragment), "https://kurakura21.com/") == listOf(frame))
    verify("WordPress success envelope parses", KuraKura21PlayerData.frames(JSONObject().put("success", true).put("data", fragment).toString(), "https://kurakura21.com/") == listOf(frame))
    verify("WordPress error envelope fails", KuraKura21PlayerData.frames(JSONObject().put("success", false).put("data", fragment).toString(), "https://kurakura21.com/").isEmpty())
    verify("malformed JSON fails closed", KuraKura21PlayerData.frames("\"broken", "https://kurakura21.com/").isEmpty())
    verify("error response emits no iframe", KuraKura21PlayerData.frames("0", "https://kurakura21.com/").isEmpty())
    println("PASS $count offline parser/media-policy assertions (synthetic fixtures + capture-derived URLs)")
}

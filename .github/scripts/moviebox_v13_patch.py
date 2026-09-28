from pathlib import Path
import re

provider = Path("Moviebox/src/main/kotlin/com/Moviebox/MovieboxProvider.kt")
text = provider.read_text()

# The provider-level interceptor forces CS3IPlayer onto OkHttpDataSource.
# Remove it so the registered compatibility provider can use CloudStream's
# native media data source path while still carrying ExtractorLink headers.
text = text.replace("import okhttp3.Interceptor\n", "", 1)
text, removed = re.subn(
    r"\n    // 4\. INTERCEPTOR COOKIE EXOPLAYER\n"
    r"    override fun getVideoInterceptor\(extractorLink: ExtractorLink\): Interceptor \{.*?"
    r"\n    \}\n\n    // SUBTITLE",
    "\n    // SUBTITLE",
    text,
    count=1,
    flags=re.S,
)
if removed != 1:
    raise SystemExit(f"Expected one provider media interceptor, removed={removed}")

old_found = "        var foundStream: StreamItem? = null\n"
if old_found not in text:
    raise SystemExit("Expected foundStream declaration not found")
text = text.replace(old_found, "        var foundStreams: List<StreamItem> = emptyList()\n", 1)

old_pick = """            val playData = response.parsedSafe<PlayInfoResponse>()
            val stream = playData?.data?.streams?.firstOrNull()

            if (!stream?.url.isNullOrBlank() && !stream?.signCookie.isNullOrBlank()) {
                foundStream = stream
                break
            }
"""
new_pick = """            val playData = response.parsedSafe<PlayInfoResponse>()
            val streams = playData?.data?.streams.orEmpty()
                .filter { !it.url.isNullOrBlank() && !it.signCookie.isNullOrBlank() }
                .distinctBy { it.url }

            if (streams.isNotEmpty()) {
                foundStreams = streams
                Log.d(TAG, "[PLAYBACK] valid stream candidates=${streams.size}")
                break
            }
"""
if old_pick not in text:
    raise SystemExit("Expected firstOrNull stream picker not found")
text = text.replace(old_pick, new_pick, 1)

old_emit = """        val targetStream = foundStream ?: return false
        val mediaUrl = targetStream.url ?: return false
        val cleanCookie = (targetStream.signCookie ?: return false).trimEnd(';')

        // Subtitle failure must not block the already-resolved video source.
        try {
            loadSubtitles(epData.subjectId, targetStream.id, bearerToken, subtitleCallback)
        } catch (e: Exception) {
            Log.e(TAG, "[SUBTITLE] playback subtitle request gagal: ${e.javaClass.simpleName}: ${e.message}")
        }

        callback(
            newExtractorLink(
                source = name,
                name = "MovieBox",
                url = mediaUrl,
                type = INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "User-Agent" to CS_USER_AGENT,
                    "Cookie" to cleanCookie,
                    "Referer" to mainUrl
                )
            }
        )

        return true
"""
new_emit = """        if (foundStreams.isEmpty()) return false

        val primaryStream = foundStreams.first()

        // Subtitle failure must not block the already-resolved video sources.
        try {
            loadSubtitles(epData.subjectId, primaryStream.id, bearerToken, subtitleCallback)
        } catch (e: Exception) {
            Log.e(TAG, "[SUBTITLE] playback subtitle request gagal: ${e.javaClass.simpleName}: ${e.message}")
        }

        val emittedUrls = linkedSetOf<String>()
        var emitted = 0

        foundStreams.forEachIndexed { index, stream ->
            val mediaUrl = stream.url ?: return@forEachIndexed
            val signCookie = stream.signCookie ?: return@forEachIndexed
            if (!emittedUrls.add(mediaUrl)) return@forEachIndexed

            val cleanCookie = signCookie.trimEnd(';')
            callback(
                newExtractorLink(
                    source = name,
                    name = if (index == 0) "MovieBox" else "MovieBox Mirror ${index + 1}",
                    url = mediaUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "User-Agent" to CS_USER_AGENT,
                        "Cookie" to cleanCookie,
                        "Referer" to mainUrl
                    )
                }
            )
            emitted += 1
        }

        Log.d(TAG, "[PLAYBACK] emitted stream candidates=$emitted")
        return emitted > 0
"""
if old_emit not in text:
    raise SystemExit("Expected single-stream emitter not found")
text = text.replace(old_emit, new_emit, 1)
provider.write_text(text)

gradle = Path("Moviebox/build.gradle.kts")
gradle_text = gradle.read_text()
if "version = 12" not in gradle_text:
    raise SystemExit("Expected MovieBox version 12 not found")
gradle.write_text(gradle_text.replace("version = 12", "version = 13", 1))

print("MovieBox v13 playback patch applied")

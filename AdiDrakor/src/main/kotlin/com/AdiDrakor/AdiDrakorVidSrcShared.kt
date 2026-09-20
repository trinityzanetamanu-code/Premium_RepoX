package com.AdiDrakor

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Public facade for the VidSrc engine already used by AdiDrakor.
 * Other Adi providers can reuse the exact same resolver implementation
 * without duplicating the large VidSrc resolver source.
 */
object AdiDrakorVidSrcShared {
    fun attachContext(context: Context) {
        AdiDrakorVidSrc.attachContext(context)
    }

    suspend fun invokeVidSrc(
        tmdbId: Int,
        type: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        AdiDrakorVidSrc.invokeVidSrc(
            tmdbId = tmdbId,
            type = type,
            season = season,
            episode = episode,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}

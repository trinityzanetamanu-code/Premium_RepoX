package com.michat88

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Exact MovieBox runtime profile harus siap sebelum request playback pertama.
        AdiFilmSemiExtractor.attachContext(context)

        // Provider utama dengan recovery playback MovieBox terbaru.
        registerMainAPI(AdiFilmSemiPlaybackFixedProvider())
    }
}

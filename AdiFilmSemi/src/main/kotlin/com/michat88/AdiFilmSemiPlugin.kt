package com.michat88

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiFilmSemiPlugin : Plugin() {
    override fun load(context: Context) {
        // Exact MovieBox runtime profile harus siap sebelum request playback pertama.
        AdiFilmSemiExtractor.attachContext(context)

        // Attach local VidSrc/WebView/WASM engine.
        AdiFilmSemiVidSrcShared.attachContext(context)

        // Provider utama: MovieBox/VidSrc proven + Idlix prefetch/cache.
        registerMainAPI(AdiFilmSemiPrefetchProvider())
    }
}

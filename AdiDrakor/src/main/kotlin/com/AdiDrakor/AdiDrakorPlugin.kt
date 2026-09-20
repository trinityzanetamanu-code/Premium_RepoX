package com.AdiDrakor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AdiDrakorPlugin : Plugin() {
    override fun load(context: Context) {
        // Identity persisten MovieBox disiapkan sebelum request pertama.
        AdiDrakorExtractor.attachContext(context)

        // Attach local VidSrc/WebView/WASM engine.
        AdiDrakorVidSrcShared.attachContext(context)

        // Provider utama: MovieBox/VidSrc proven + Idlix prefetch/cache.
        registerMainAPI(AdiDrakorPrefetchProvider())
    }
}

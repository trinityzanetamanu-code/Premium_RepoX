package com.streamzy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamzyPlugin : Plugin() {

    override fun load(context: Context) {
        registerMainAPI(
            StreamzyProvider(
                context.applicationContext
            )
        )
    }
}

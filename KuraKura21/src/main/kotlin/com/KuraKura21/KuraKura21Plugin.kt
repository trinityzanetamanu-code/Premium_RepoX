package com.kurakura21

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KuraKura21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(KuraKura21Provider(context.applicationContext ?: context))
    }
}

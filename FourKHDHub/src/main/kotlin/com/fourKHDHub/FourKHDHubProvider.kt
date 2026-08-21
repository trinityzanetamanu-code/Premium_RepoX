package com.fourKHDHub

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class FourKHDHubProvider : BasePlugin() {
    
    // [VERIFIED RECONSTRUCTION] Extractor registration strictly follows DEX.
    // Hubdrive and Hblinks exist as classes but were NOT registered in the original load().
    override fun load(context: Context) {
        registerMainAPI(FourKHDHub())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Hubstreamdad())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(PixelDrainDev())
        registerExtractorAPI(HUBCDN())
    }

    companion object {
        data class Domains(
            @JsonProperty("4khdhub") val n4khdhub: String?,
            @JsonProperty("hubcloud") val hubcloud: String?
        )

        private var cachedDomains: Domains? = null

        // [RECONSTRUCTED FROM VERIFIED BEHAVIOR]
        // DEX explicitly references domains.json on GitHub and cachedDomains.
        suspend fun getDomains(forceRefresh: Boolean = false): Domains? {
            if (!forceRefresh && cachedDomains != null) return cachedDomains
            return try {
                val url = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
                val response = app.get(url).parsedSafe<Domains>()
                if (response != null) cachedDomains = response
                response
            } catch (e: Exception) {
                // Fallback to active domains verified in Termux
                Domains("https://4khdhub.one", "https://hubcloud.cx")
            }
        }
    }
}

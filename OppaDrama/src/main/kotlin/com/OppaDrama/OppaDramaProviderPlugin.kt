package com.OppaDrama

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class OppaDramaPlugin: Plugin() {
    override fun load(context: Context) {
        // 1. Mendaftarkan API utama OppaDrama agar muncul di beranda aplikasi[span_3](start_span)[span_3](end_span)
        registerMainAPI(OppaDramaProvider())

        // 2. Mendaftarkan seluruh Kluster Custom Extractors ke registry Cloudstream[span_4](start_span)[span_4](end_span)
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(BuzzServer())
        // NB: Emturbovid() BUKAN lagi subclass extractor core - situs sudah pakai obfuscation
        // JS kustom (bukan format packer standar) dan domain sudah pindah ke turbovidhls.com
        // (via 301 redirect dari emturbovid.com, terverifikasi dari header respons asli).
        // Class ini WAJIB didaftarkan karena berisi decoder khusus untuk obfuscation tersebut.
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(MinochinosExtractor())
    }
}

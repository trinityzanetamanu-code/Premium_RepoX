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
        // NB: EmturbovidExtractor() sekarang bernama Emturbovid() dan hanya men-subclass
        // extractor bawaan core (com.lagradost.cloudstream3.extractors.EmturbovidExtractor).
        // Bawaan core SUDAH otomatis aktif tanpa perlu didaftarkan sama sekali - baris ini
        // hanya diperlukan jika mainUrl OppaDrama berbeda dari default core. Jika sama persis,
        // baris ini boleh dihapus total.
        registerExtractorAPI(Emturbovid())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(MinochinosExtractor())
    }
}

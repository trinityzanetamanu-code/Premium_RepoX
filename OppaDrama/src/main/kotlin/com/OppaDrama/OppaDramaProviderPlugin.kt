package com.OppaDrama

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class OppaDramaPlugin : BasePlugin() {
    // Standar terbaru (pasca migrasi crossplatform PR #1527):
    // BasePlugin + load() TANPA Context. Kelas Plugin(context) hanya untuk
    // plugin yang benar-benar membutuhkan Android Context.
    override fun load() {
        // 1. Mendaftarkan API utama OppaDrama agar muncul di beranda aplikasi
        registerMainAPI(OppaDramaProvider())

        // 2. Mendaftarkan seluruh kluster custom extractor ke registry Cloudstream.
        //    Setelah terdaftar, loadExtractor() otomatis mencocokkan URL embed
        //    berdasarkan prefix mainUrl (plus Levenshtein untuk domain mirror),
        //    sehingga TIDAK perlu instansiasi manual di provider.
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(BuzzServer())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssPlayer())      // alias domain abyssplayer.com
        registerExtractorAPI(MinochinosExtractor())
    }
}

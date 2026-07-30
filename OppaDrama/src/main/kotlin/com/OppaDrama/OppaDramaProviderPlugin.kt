package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OppaDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // MILESTONE 1: dijalankan SEBELUM registrasi provider, supaya kalau
        // start() melempar, kegagalannya terisolasi dan provider produksi
        // tetap terdaftar. Provider utama tidak bergantung pada server ini
        // sama sekali pada tahap M1.
        val ok = runCatching { LocalServer.start() }.getOrElse { e ->
            LocalServer.obs("START-THROW", "${e.javaClass.simpleName}: ${e.message}")
            false
        }
        LocalServer.obs("PLUGIN-LOAD", "server_started=$ok port=${LocalServer.port}")

        registerMainAPI(OppaDramaProvider())

        // Hapus baris ini setelah M1 selesai diverifikasi.
        registerMainAPI(OppaDramaDiagProvider())
    }
}

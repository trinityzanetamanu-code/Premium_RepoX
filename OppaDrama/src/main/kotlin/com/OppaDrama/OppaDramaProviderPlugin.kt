package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OppaDramaPlugin : Plugin() {
    override fun load(context: Context) {
        // M2: LocalProxy MENGGANTIKAN LocalServer. Pastikan LocalServer.kt
        // sudah dihapus dari project, jangan sampai dua socket hidup bersamaan.
        //
        // start() dipanggil di dalam runCatching SEBELUM registrasi provider,
        // supaya kalau bind gagal, provider produksi tetap terdaftar dan Anda
        // tidak kehilangan fungsi yang sudah berjalan.
        val ok = runCatching { LocalProxy.start() }.getOrElse { e ->
            LocalProxy.obs("START-THROW", "${e.javaClass.simpleName}: ${e.message}")
            false
        }
        LocalProxy.obs("PLUGIN-LOAD", "started=$ok port=${LocalProxy.port}")

        registerMainAPI(OppaDramaProvider())

        // Hapus setelah M2 diverifikasi.
        registerMainAPI(OppaDramaDiagProvider())
    }
}

package com.RiveStream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Pendaftaran plugin RiveStream.
 *
 * Berkas ini hanya mendaftarkan provider ke CloudStream. Tidak ada logika
 * lain di sini, dan tidak perlu diubah pada tahap-tahap berikutnya.
 */
@CloudstreamPlugin
class RiveStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RiveStreamProvider())
    }
}

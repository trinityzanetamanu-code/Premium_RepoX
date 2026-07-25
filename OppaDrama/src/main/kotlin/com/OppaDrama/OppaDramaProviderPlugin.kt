package com.OppaDrama

import android.util.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/*  ── VERSI DIAGNOSTIK SEMENTARA ──────────────────────────────────────────
 *  Tujuan tunggal: memisahkan hipotesis (A) dari (B).
 *
 *  Sengaja TIDAK memakai OppaDiag.kt sama sekali, supaya tidak ada import
 *  baru yang bisa menggagalkan kompilasi. Hanya android.util.Log yang dipakai
 *  — dijamin tersedia.
 *
 *  Pola catch → log → rethrow membuat perilaku tetap identik: exception tetap
 *  dilempar ulang seperti sebelumnya, hanya penyebabnya sekarang tercatat.
 *
 *  Setelah investigasi: hapus baris Log dan fungsi reg(), kembalikan ke
 *  pemanggilan registerExtractorAPI biasa.
 *  ───────────────────────────────────────────────────────────────────────── */

@CloudstreamPlugin
class OppaDramaPlugin : BasePlugin() {

    override fun load() {
        Log.i(TAG, "A1 ===== load() MULAI =====")

        registerMainAPI(OppaDramaProvider())
        Log.i(TAG, "A2 OK   registerMainAPI(OppaDramaProvider)")

        reg("Smoothpre")           { registerExtractorAPI(Smoothpre()) }
        reg("BuzzServer")          { registerExtractorAPI(BuzzServer()) }
        reg("EmturbovidExtractor") { registerExtractorAPI(EmturbovidExtractor()) }
        reg("AbyssExtractor")      { registerExtractorAPI(AbyssExtractor()) }
        reg("AbyssPlayer")         { registerExtractorAPI(AbyssPlayer()) }   // hapus bila kelasnya belum ada
        reg("MinochinosExtractor") { registerExtractorAPI(MinochinosExtractor()) }

        Log.i(TAG, "A3 ===== load() SELESAI, semua registrasi lolos =====")
    }

    private inline fun reg(label: String, blok: () -> Unit) {
        Log.i(TAG, "A2 akan daftar $label")
        try {
            blok()
            Log.i(TAG, "A2 OK   $label")
        } catch (t: Throwable) {
            Log.e(TAG, "A2 GAGAL $label :: ${t.javaClass.name}: ${t.message}")
            throw t   // perilaku dipertahankan persis seperti semula
        }
    }

    private companion object {
        const val TAG = "OppaDiag"
    }
}

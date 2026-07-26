package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * # OppaDramaPlugin
 *
 * Entry point CloudStream plugin untuk situs OppaDrama.
 *
 * ## SDK Pattern (CloudStream 3+)
 *
 * Plugin modern HARUS menggunakan pola ini:
 *   1. Annotation `@CloudstreamPlugin` sebagai marker untuk loader
 *   2. Class yang `extends Plugin`
 *   3. Override `load(context: Context)` — BUKAN `load()` no-arg
 *   4. Panggil `registerMainAPI()` / `registerExtractorAPI()` secara eksplisit
 *
 * Loader menemukan class ini via classpath scan + reflection. Annotation
 * `@CloudstreamPlugin` diperlukan agar loader tahu ini plugin entry point
 * (bukan class Plugin biasa).
 *
 * ## Isi Plugin Ini
 *
 * **Provider (1):**
 *   - `OppaDramaProvider` — homepage 18 kategori, search, load series/movie,
 *     loadLinks dengan routing Abyss khusus + WebView fallback
 *
 * **Extractors (5 terdaftar, 1 opsional):**
 *   - `Smoothpre`         — alias EarnVids, extends core VidHidePro
 *   - `BuzzServer`        — overrider hx-redirect untuk BuzzHeavier
 *   - `EmturbovidExtractor` — M3U8 master playlist via M3u8Helper
 *   - `AbyssExtractor`    — JSON player v2 API untuk abyss.to
 *   - `MinochinosExtractor` — Regex dari JS-obfuscated page
 *
 *   - `AbyssPlayer` (TIDAK terdaftar, lihat catatan di bawah)
 *
 * **Helper (1, tidak di-register):**
 *   - `WebViewFallback` (object) — last-resort WebView sniffing via
 *     `WebViewResolver` internal core
 *
 * ## Catatan: Mengapa `AbyssPlayer` Tidak Didaftar?
 *
 * `AbyssPlayer` adalah alias `AbyssExtractor` dengan `mainUrl = "abyssplayer.com"`.
 * Tidak didaftarkan karena `OppaDramaProvider.loadLinks` sudah melakukan routing
 * eksplisit untuk semua host keluarga Abyss (`abyssHosts` list), sehingga
 * `abyssplayer.com` di-handle langsung oleh instance `AbyssExtractor()` tanpa
 * melalui registry loader.
 *
 * Konsekuensi: plugin LAIN yang ingin extract URL `abyssplayer.com` via
 * `loadExtractor()` TIDAK akan menemukan match. Tambahkan
 * `registerExtractorAPI(AbyssPlayer())` di sini bila plugin Anda akan
 * didistribusikan untuk ekosistem multi-plugin.
 *
 * ## Lifecycle Plugin
 *
 * ```
 *   PluginManager.loadPlugin(pluginClass)
 *     ├─→ instantiate OppaDramaPlugin (via class loader)
 *     └─→ plugin.load(context)
 *           ├─→ registerMainAPI(OppaDramaProvider())
 *           │     └─→ APIHolder.addPluginMapping(api)
 *           └─→ registerExtractorAPI(...) × 5
 *                 └─→ extractorApis.add(extractor)
 *
 *   [Plugin siap dipakai oleh CloudStream UI]
 * ```
 *
 * ## Patch History (lihat /workspace/fixed/CHANGES.md untuk detail)
 *
 *   - **#1** `mainUrl` HTTPS (bukan HTTP) — `OppaDramaProvider.kt`
 *   - **#2** `Jsoup` → `Ksoup` di-revert karena Ksoup belum di-expose di
 *     classpath plugin (lihat CHANGES.md untuk fix lanjutan)
 *   - **#3** `WebViewResolver` safety — `Extractors.kt`
 *   - **#4** Hardcoded IP referer di `AbyssExtractor` → `$mainUrl/`
 */
@CloudstreamPlugin
class OppaDramaPlugin : Plugin() {

    /**
     * Lifecycle hook. Dipanggil SEKALI saat plugin dimuat oleh CloudStream.
     *
     * [context] adalah Android `Context` yang bisa dipakai untuk:
     *   - Akses string resources (`context.getString(R.string.xxx)`)
     *   - Akses SharedPreferences (settings persistence)
     *   - Setup WebView (untuk plugin yang pakai WebView API)
     *
     * Method ini HARUS melakukan registrasi seluruh MainAPI/ExtractorApi
     * yang ingin dimuat ke CloudStream. Tidak ada auto-discovery di SDK
     * modern — semua harus eksplisit.
     */
    override fun load(context: Context) {
        // ── Provider ─────────────────────────────────────────────────────
        // Mendaftarkan provider agar muncul di homepage, search, dan
        // daftar provider CloudStream. Cukup satu provider per plugin.
        registerMainAPI(OppaDramaProvider())

        // ── Extractors ───────────────────────────────────────────────────
        // Mendaftarkan extractor untuk host video yang dipakai situs.
        // URUTAN TIDAK PENTING untuk fungsionalitas, tapi `extractorApis`
        // diiterasi dari belakang (last-registered diprioritaskan) oleh
        // `loadExtractor()` — lihat implementasi di ExtractorApi.kt.
        //
        // Catatan: `AbyssPlayer` TIDAK didaftarkan (lihat header KDoc).
        //         `WebViewFallback` BUKAN ExtractorApi, jadi tidak perlu
        //         register — dipakai internal oleh `OppaDramaProvider`.
        registerExtractorAPI(Smoothpre())
        registerExtractorAPI(BuzzServer())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(MinochinosExtractor())
    }
}

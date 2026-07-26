package com.OppaDrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * # OppaDramaPlugin
 *
 * Entry point CloudStream plugin untuk situs OppaDrama (drama/movie Asia).
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
 *     loadLinks dengan routing Abyss khusus
 *
 * **Extractors (3) — sesuai debug aktual website, hanya server streaming valid:**
 *   - `EmturbovidExtractor` — M3U8 master playlist via `M3u8Helper` (TurboVIP)
 *   - `AbyssExtractor`      — JSON player v2 API untuk abyss.to (Hydrax)
 *   - `MinochinosExtractor` — Regex dari JS-obfuscated page (FileLions)
 *
 * **Catatan penting:**
 *   - **HANYA 3 server di atas** yang didaftarkan. Server lain yang
 *     sebelumnya ada (EarnVids/Smoothpre, BuzzServer, AbyssPlayer) sudah
 *     dihapus dari `Extractors.kt` karena bukan server streaming —
 *     tautan-tautan itu di `div.dlbox` adalah download-only, bukan
 *     sumber video playable.
 *   - Keluarga host Abyss (`abyss.to`, `abyssplayer.com`, dll) di-handle
 *     via routing eksplisit di `OppaDramaProvider.dispatchEmbed()` yang
 *     langsung instantiate `AbyssExtractor()`.
 *   - **TIDAK ADA WEBVIEW**. Plugin ini 100% berbasis HTTP. Tidak ada
 *     fallback WebView sniffing. Kalau extractor core gagal pada semua
 *     mirror, plugin menyerah — user harus memilih server lain dari
 *     dropdown halaman versi.
 *
 * ## Lifecycle Plugin
 *
 * ```
 *   PluginManager.loadPlugin(pluginClass)
 *     ├─→ instantiate OppaDramaPlugin (via class loader)
 *     └─→ plugin.load(context)
 *           ├─→ registerMainAPI(OppaDramaProvider())
 *           │     └─→ APIHolder.addPluginMapping(api)
 *           └─→ registerExtractorAPI(...) × 3   // TurboVIP, Hydrax, FileLions
 *                 └─→ extractorApis.add(extractor)
 *
 *   [Plugin siap dipakai oleh CloudStream UI]
 * ```
 *
 * ## Patch History (lihat /workspace/fixed/CHANGES.md untuk detail)
 *
 *   - **#1** `mainUrl` HTTPS → di-revert ke HTTP (server tidak listen di 443)
 *   - **#2** `Jsoup` → `Ksoup` di-revert (Ksoup belum di-expose di classpath)
 *   - **#3** `WebViewResolver` safety → **DIHAPUS** (WebView tidak dipakai lagi)
 *   - **#4** Hardcoded IP referer di `AbyssExtractor` → `$mainUrl/`
 *   - **#5** Multi-version movie resolution di `loadMovie()`
 *   - **#6** **WebView fallback dihapus total** — tidak ada `WebViewFallback`
 *     object, tidak ada `WebViewResolver` import, tidak ada last-resort
 *     sniffing via WebView. Cleaner & lebih ringan.
 *   - **#7** **Whitelist 3 server valid** — `Smoothpre`, `BuzzServer`,
 *     `AbyssPlayer` dihapus dari `Extractors.kt` karena bukan streaming
 *     (host-nya cuma muncul di `div.dlbox` sebagai download link).
 */
@CloudstreamPlugin
class OppaDramaPlugin : Plugin() {

    /**
     * Lifecycle hook. Dipanggil SEKALI saat plugin dimuat oleh CloudStream.
     *
     * [context] adalah Android `Context` yang bisa dipakai untuk:
     *   - Akses string resources (`context.getString(R.string.xxx)`)
     *   - Akses SharedPreferences (settings persistence)
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
        // Plugin ini HANYA mendaftarkan 3 server streaming valid sesuai
        // debug aktual website OppaDrama (27 Jul 2026). Server lain yang
        // muncul di `div.dlbox` adalah download-only, BUKAN streaming.
        //
        //   1. TurboVIP   → EmturbovidExtractor (emturbovid.com)
        //   2. Hydrax     → AbyssExtractor      (abyss.to)
        //   3. FileLions  → MinochinosExtractor (minochinos.com)
        //
        // URUTAN TIDAK PENTING untuk fungsionalitas, tapi `extractorApis`
        // diiterasi dari belakang (last-registered diprioritaskan) oleh
        // `loadExtractor()` — lihat implementasi di ExtractorApi.kt.
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(MinochinosExtractor())
    }
}

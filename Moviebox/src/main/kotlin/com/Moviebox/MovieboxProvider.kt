package com.Moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.content.Context
import android.util.Base64
import android.util.Log
import android.os.Build
import java.util.UUID
import java.net.URLEncoder
import java.net.URLDecoder
import java.net.URI

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api3.aoneroom.com"
    override var name = "MovieBox"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var hasMainPage = true

    // Disusun dari daftar kategori yang server kirim (lihat mainPageEntries).
    override val mainPage = mainPageOf(*mainPageEntries())

    companion object {
        private const val TAG = "MovieBox"
        private const val CS_USER_AGENT = "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Samsung; Build/TQ3A.230901.001)"
        private const val PLAYBACK_API_BASE = "https://api6.aoneroom.com"
        private val PLAYBACK_HOSTS = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com"
        )
        private const val PLAYBACK_TOKEN_URL =
            "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        private const val PLAYBACK_USER_AGENT =
            "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
        private const val PLAYBACK_VERSION_NAME = "4.0.03.0920.03"
        private const val PLAYBACK_VERSION_CODE = 50020130L
        private const val PLAYBACK_ALT_SECRET_B64 =
            "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="
        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"

        private fun clientInfo(): String =
            "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"3.0.13.0325.03\"," +
            "\"version_code\":50020088,\"os\":\"android\",\"os_version\":\"13\"," +
            "\"device_id\":\"${deviceId()}\",\"install_store\":\"ps\"," +
            "\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\"," +
            "\"timezone\":\"Asia/Calcutta\",\"sp_code\":\"\"}"

        private const val CAT_KEY = "categorylist"

        private val SEED_CATEGORIES = listOf(
            "4809349160627587984" to "Semua",
            "4380734070238626200" to "K-Drama",
            "5283462032510044280" to "Indo Drama",
            "8617025562613270856" to "Anime",
            "5307082080063488480" to "Barat",
            "8624142774394406504" to "C-Drama",
            "1164329479448281992" to "Thai-Drama",
            "5720220657917522824" to "Reality"
        )

        @Volatile
        private var categories: List<Pair<String, String>> = SEED_CATEGORIES

        private const val FILTER_PREFIX = "filter:"

        private val EXTRA_ROWS = listOf(
            FILTER_PREFIX + "subjectType=1&genre=Horror&country=Indonesia" to "Horror Indonesia"
        )

        private fun prefs() =
            try {
                appContext?.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
            } catch (e: Exception) {
                null
            }

        private fun mainPageEntries(): Array<Pair<String, String>> =
            (categories + EXTRA_ROWS).toTypedArray()

        private fun loadCategories() {
            val parsed = prefs()?.getString(CAT_KEY, null)
                ?.split("\n")
                ?.mapNotNull { line ->
                    val p = line.split("\t")
                    if (p.size == 2 && p[0].isNotBlank() && p[1].isNotBlank()) {
                        p[0] to p[1]
                    } else {
                        null
                    }
                }
                ?.takeIf { it.isNotEmpty() }

            if (parsed != null) categories = parsed

            Log.d(
                TAG,
                "[CATEGORY] dimuat ${categories.size} kategori " +
                    "(${if (parsed != null) "tersimpan" else "seed"}): " +
                    categories.joinToString(", ") { it.second }
            )
        }

        private fun rememberCategories(fresh: List<CategoryItem>) {
            val list = fresh.mapNotNull { c ->
                val t = c.type
                val n = c.name
                if (t.isNullOrBlank() || n.isNullOrBlank()) null else t to n
            }

            if (list.isEmpty() || list == categories) return

            categories = list

            try {
                prefs()?.edit()?.putString(
                    CAT_KEY,
                    list.joinToString("\n") { "${it.first}\t${it.second}" }
                )?.apply()

                Log.d(
                    TAG,
                    "[CATEGORY] daftar server berubah -> disimpan ${list.size}: " +
                        list.joinToString(", ") { it.second }
                )
            } catch (e: Exception) {
                Log.e(TAG, "[CATEGORY] gagal menyimpan: ${e.message}")
            }
        }

        private const val ID_PREFS = "moviebox_identity"
        private const val ID_KEY = "apkdeviceid"
        private val ID_FORMAT = Regex("^[0-9a-f]{32}$")

        @Volatile
        private var appContext: Context? = null

        @Volatile
        private var cachedDeviceId: String? = null

        @Volatile
        private var persisted = false

        fun attachContext(context: Context) {
            appContext = context.applicationContext
            loadCategories()

            val id = deviceId()

            Log.d(
                TAG,
                "[IDENTITY] device_id=$id len=${id.length} " +
                    "valid=${ID_FORMAT.matches(id)} persisted=$persisted"
            )
        }

        private fun deviceId(): String {
            val cached = cachedDeviceId
            if (cached != null && persisted) return cached

            val ctx = appContext
            if (ctx != null) {
                try {
                    val sp = ctx.getSharedPreferences(ID_PREFS, Context.MODE_PRIVATE)
                    val existing = sp.getString(ID_KEY, null)

                    if (!existing.isNullOrBlank() && ID_FORMAT.matches(existing)) {
                        cachedDeviceId = existing
                        persisted = true
                        return existing
                    }

                    val fresh = cached ?: md5(UUID.randomUUID().toString())

                    sp.edit()
                        .putString(ID_KEY, fresh)
                        .apply()

                    cachedDeviceId = fresh
                    persisted = true
                    return fresh
                } catch (e: Exception) {
                    Log.e(TAG, "[IDENTITY] storage tidak tersedia: ${e.message}")
                }
            }

            return cached
                ?: md5(UUID.randomUUID().toString())
                    .also { cachedDeviceId = it }
        }

        private val SECRET_BYTES: ByteArray by lazy {
            val step1 = String(
                Base64.decode(
                    "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw==",
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )

            Base64.decode(step1, Base64.DEFAULT)
        }

        private val PLAYBACK_ALT_SECRET_BYTES: ByteArray by lazy {
            val step1 = String(
                Base64.decode(
                    PLAYBACK_ALT_SECRET_B64,
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )

            Base64.decode(step1, Base64.DEFAULT)
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        private fun buildCanonical(
            method: String,
            pathWithQuery: String,
            ts: String,
            body: String
        ): String {
            val length = if (body.isEmpty()) "" else body.length.toString()
            val digest = if (body.isEmpty()) "" else md5(body)

            return listOf(
                method.uppercase(),
                "application/json",
                "application/json",
                length,
                ts,
                digest,
                pathWithQuery
            ).joinToString("\n")
        }

        private fun generateSignature(
            method: String,
            pathWithQuery: String,
            ts: String,
            body: String = ""
        ): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))

            val hmacBytes = mac.doFinal(
                buildCanonical(
                    method,
                    pathWithQuery,
                    ts,
                    body
                ).toByteArray(Charsets.UTF_8)
            )

            return "$ts|2|${Base64.encodeToString(hmacBytes, Base64.NO_WRAP)}"
        }

        private fun generateGuestToken(ts: String): String =
            "$ts,${md5(ts.reversed())}"

        private const val ORACLE_BLOB_B64 =
            "DTIykuYnWieJjJgXTGK+QzvFGBfR7pw7CKX532Rg/rxEImeG4XFYNNeXjUMSJKoba4VVBda+0mtT8JXQYST0rlQ8cofmIFoniYzSREIn+R861RlRje/Zf1/zntQte/ymRD0x0LN7CGfWn9lFR3LpAX6OWkbB6oY+NKPOlDpgtO1UPHKJ6TpKZN/CtAVVfrlIft0WRcapxnAGr8LTbGD+vCVdfbO+eAZHkYLJGERl6Rd+qXFh4sS4GTSX7/BJYOi8GWMPluI7TWzcwMlMAyD9D3DFRlDS4oU8SfqE/0Rg6LwFYA+D6C1bJ4mM3kcRIPsPcMVHTMb/jz80rMfYZzel+RMyasLuJxwpkdqCG0RrpEM5xQ4X9PiDM0SKx89hMrHsFzJ8wvI6W3fsx49UGzP9H2XSAg2Gut5gXPiVgjh386tEMnzC8SxMdtrBhSlCfq9Ift0WDIyy02tS+Z+PIm7m6BNiI4noJ2Fr0sOOVBsz/wNsyQQHm7vSYVruloUiPw=="

        private const val ORACLE_KEY_DOMAIN = "MovieBoxOracleV1|"

        private data class OracleIdentity(
            val userId: String,
            val deviceId: String,
            val versionName: String,
            val versionCode: Long,
            val osVersion: String,
            val model: String,
            val installCh: String,
            val gaid: String,
            val net: String,
            val region: String,
            val timezone: String,
            val spCode: String,
            val installStore: String,
            val systemLanguage: String
        )

        private data class PlaybackSessionProfile(
            val identity: OracleIdentity,
            val clientInfo: String
        )

        private fun oracleDecryptJson(): JSONObject? {
            return try {
                val encrypted = Base64.decode(
                    ORACLE_BLOB_B64,
                    Base64.DEFAULT
                )

                val key = MessageDigest.getInstance("SHA-256")
                    .digest(
                        (ORACLE_KEY_DOMAIN + Build.FINGERPRINT)
                            .toByteArray(Charsets.UTF_8)
                    )

                val plain = ByteArray(encrypted.size)

                for (i in encrypted.indices) {
                    plain[i] =
                        (encrypted[i].toInt() xor key[i % key.size].toInt())
                            .toByte()
                }

                JSONObject(String(plain, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "[PLAYBACK] runtime identity decrypt gagal: ${e.javaClass.simpleName}"
                )
                null
            }
        }

        private fun oracleString(
            o: JSONObject,
            key: String
        ): String {
            val value = o.opt(key)
            return if (value == null || value == JSONObject.NULL) {
                ""
            } else {
                value.toString()
            }
        }

        private fun oracleIdentity(): OracleIdentity? {
            val o = oracleDecryptJson() ?: return null

            val versionCode =
                oracleString(o, "version_code")
                    .toLongOrNull()
                    ?: return null

            val identity = OracleIdentity(
                userId = oracleString(o, "user_id"),
                deviceId = oracleString(o, "device_id"),
                versionName = oracleString(o, "version_name"),
                versionCode = versionCode,
                osVersion = oracleString(o, "os_version"),
                model = oracleString(o, "model"),
                installCh = oracleString(o, "install_ch"),
                gaid = oracleString(o, "gaid"),
                net = oracleString(o, "net"),
                region = oracleString(o, "region"),
                timezone = oracleString(o, "timezone"),
                spCode = oracleString(o, "sp_code"),
                installStore = oracleString(o, "install_store"),
                systemLanguage = oracleString(o, "system_language")
            )

            if (
                identity.deviceId.isBlank() ||
                identity.versionName != "4.0.02.0831.03" ||
                identity.versionCode != 999999999L
            ) {
                Log.e(
                    TAG,
                    "[PLAYBACK] runtime identity tidak cocok dengan build target"
                )
                return null
            }

            return identity
        }

        private fun realUserIdIsGuest(value: String): Boolean =
            value.isBlank() ||
                value.equals("null", ignoreCase = true) ||
                value.equals("none", ignoreCase = true) ||
                value.equals("guest", ignoreCase = true)

        private fun buildPlaybackSessionProfile(): PlaybackSessionProfile? {
            val id = oracleIdentity() ?: return null

            val info = JSONObject()
                .put("package_name", "com.community.oneroom")
                .put("version_name", id.versionName)
                .put("version_code", id.versionCode)
                .put("os", "android")
                .put("os_version", id.osVersion)

            if (id.installCh.isNotBlank()) {
                info.put("install_ch", id.installCh)
            }

            info.put("device_id", id.deviceId)
                .put("install_store", id.installStore)

            if (id.gaid.isNotBlank()) {
                info.put("gaid", id.gaid)
            }

            info.put("brand", Build.BRAND ?: "")
                .put("model", id.model)
                .put("system_language", id.systemLanguage)
                .put("net", id.net)
                .put("region", id.region)
                .put("timezone", id.timezone)
                .put("sp_code", id.spCode)

            return PlaybackSessionProfile(
                identity = id,
                clientInfo = info.toString()
            )
        }

        private fun playbackGetCanonical(
            pathWithCanonicalQuery: String,
            ts: String
        ): String =
            listOf(
                "GET",
                "",
                "",
                "",
                ts,
                "",
                pathWithCanonicalQuery
            ).joinToString("\n")

        private fun playbackGetSignature(
            pathWithCanonicalQuery: String,
            ts: String
        ): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))

            val bytes = mac.doFinal(
                playbackGetCanonical(
                    pathWithCanonicalQuery,
                    ts
                ).toByteArray(Charsets.UTF_8)
            )

            return "$ts|2|${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        private fun playbackGuestHeaders(
            ts: String,
            signature: String,
            profile: PlaybackSessionProfile
        ): Map<String, String> =
            mapOf(
                "x-client-token" to generateGuestToken(ts),
                "x-tr-signature" to signature,
                "x-client-info" to profile.clientInfo,
                "x-client-status" to "1"
            )

        private fun playbackPlayInfoHeaders(
            signature: String,
            bearer: String,
            profile: PlaybackSessionProfile
        ): Map<String, String> =
            mapOf(
                "authorization" to "Bearer $bearer",
                "x-tr-signature" to signature,
                "x-client-info" to profile.clientInfo,
                "x-client-status" to "1"
            )

        private fun enc(str: String?): String =
            if (str.isNullOrBlank()) {
                ""
            } else {
                URLEncoder.encode(str, "UTF-8")
            }

        private fun dec(str: String?): String =
            if (str.isNullOrBlank()) {
                ""
            } else {
                URLDecoder.decode(str, "UTF-8")
            }
    }

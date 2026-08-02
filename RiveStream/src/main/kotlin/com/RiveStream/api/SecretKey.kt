package com.RiveStream.api

/**
 * ============================================================================
 *  RiveStream — secretKey
 * ============================================================================
 *
 *  Setiap panggilan ke `/api/backendfetch` wajib menyertakan `secretKey`.
 *  Algoritmanya dipulihkan dari bundle produksi RiveStream
 *  (`/_next/static/chunks/pages/_app-*.js`) dan diverifikasi silang di
 *  Node.js, Python, dan JVM.
 *
 *  CATATAN: gerbang ini TIDAK ada di repo publik `BytesGaming/rive`.
 *  Repo itu tertinggal dari situs live. Jangan mencarinya di sana.
 *
 *  ------------------------------------------------------------------------
 *  Rumus
 *  ------------------------------------------------------------------------
 *
 *      secretKey(e):
 *          e == null                 -> "rive"
 *          r   = String(e)
 *          idx = angka(e)                       bila e numerik
 *                jumlah charCode(r)             bila e teks
 *          t   = SALTS[idx % SALTS.size]
 *          n   = (idx % r.length) / 2           pembagian bulat
 *          i   = r[0 until n] + t + r[n until r.length]
 *          hasil = base64( hashB( hashA(i) ) )
 *
 *  ------------------------------------------------------------------------
 *  DUA JEBAKAN YANG WAJIB DIPERHATIKAN
 *  ------------------------------------------------------------------------
 *
 *  1. HEX BERTANDA.
 *     Di JavaScript, `(t ^= t ushr 16).toString(16)` menghasilkan int32
 *     BERTANDA. Nilai dengan bit tertinggi menyala dicetak diawali minus:
 *
 *         0xF9F26C25  ->  "-60d93db"      BUKAN  "f9f26c25"
 *
 *     String inilah yang menjadi masukan hashB, jadi tandanya mengubah hasil
 *     akhir sepenuhnya. Karena itu JANGAN memakai `Integer.toHexString()`:
 *     fungsi itu memperlakukan nilai sebagai unsigned dan hasilnya SALAH.
 *     Pakai [jsHex8] di bawah.
 *
 *  2. ARITMETIKA 32-BIT.
 *     `Int` Kotlin adalah int32 bertanda, dan jumlah geseran dihitung modulo
 *     32 — sama persis dengan JavaScript. Semua perkalian di sini boleh
 *     meluap; hasilnya tetap benar karena yang dipakai hanya 32 bit rendah.
 *     Jangan "memperbaiki" dengan Long: itu justru merusak hasilnya.
 *
 *  ------------------------------------------------------------------------
 *  Vektor uji (lihat [selfTest])
 *  ------------------------------------------------------------------------
 *
 *      969681  -> MTQ5OTgzZGI=      hashA("9zJ3nmt4OA69681") = -553470bf
 *      1396    -> Nzg3ZmU5YTI=      hashB("-553470bf")       = 149983db
 *      550     -> NDQzM2UwYzI=
 *      278     -> LTJhMzZhYTFi
 *      batman  -> LTY1MmZkNTky
 *      null    -> rive
 * ============================================================================
 */
object SecretKey {

    /** Nilai tetap untuk permintaan tanpa argumen, mis. `VideoProviderServices`. */
    const val LITERAL = "rive"

    /**
     * Tabel garam, disalin apa adanya dari bundle (offset 77811, 70 entri).
     *
     * Catatan hasil analisis: karena riwayat hash terhapus setiap `n % 5 == 0`
     * di [hashA], pada praktiknya hanya PANJANG entri yang memengaruhi hasil,
     * bukan isinya. Isinya tetap disimpan utuh supaya tetap setia pada sumber
     * dan tahan bila pola masukan berubah di kemudian hari.
     */
    private val SALTS = arrayOf(
        "4Z7lUo", "gwIVSMD", "PLmz2elE2v", "Z4OFV0", "SZ6RZq6Zc",
        "zhJEFYxrz8", "FOm7b0", "axHS3q4KDq", "o9zuXQ", "4Aebt",
        "wgjjWwKKx", "rY4VIxqSN", "kfjbnSo", "2DyrFA1M", "YUixDM9B",
        "JQvgEj0", "mcuFx6JIek", "eoTKe26gL", "qaI9EVO1rB", "0xl33btZL",
        "1fszuAU", "a7jnHzst6P", "wQuJkX", "cBNhTJlEOf", "KNcFWhDvgT",
        "XipDGjST", "PCZJlbHoyt", "2AYnMZkqd", "HIpJh", "KH0C3iztrG",
        "W81hjts92", "rJhAT", "NON7LKoMQ", "NMdY3nsKzI", "t4En5v",
        "Qq5cOQ9H", "Y9nwrp", "VX5FYVfsf", "cE5SJG", "x1vj1",
        "HegbLe", "zJ3nmt4OA", "gt7rxW57dq", "clIE9b", "jyJ9g",
        "B5jXjMCSx", "cOzZBZTV", "FTXGy", "Dfh1q1", "ny9jqZ2POI",
        "X2NnMn", "MBtoyD", "qz4Ilys7wB", "68lbOMye", "3YUJnmxp",
        "1fv5Imona", "PlfvvXD7mA", "ZarKfHCaPR", "owORnX", "dQP1YU",
        "dVdkx", "qgiK0E", "cx9wQ", "5F9bGa", "7UjkKrp",
        "Yvhrj", "wYXez5Dg3", "pG4GMU", "MwMAu", "rFRD5wlM"
    )

    /** Hanya angka desimal, dengan tanda dan eksponen opsional. */
    private val NUMERIC = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")

    // ------------------------------------------------------------------ util

    /**
     * Setara `value.toString(16).padStart(8, "0")` di JavaScript.
     *
     * Nilai negatif dicetak dengan tanda minus, dan padding tetap ditambahkan
     * di depan tanda — persis perilaku `String.prototype.padStart`.
     * Contoh: `-0xabc` menjadi `"0000-abc"`.
     */
    private fun jsHex8(value: Int): String {
        val s = if (value < 0) {
            "-" + java.lang.Long.toString(-(value.toLong()), 16)
        } else {
            java.lang.Integer.toString(value, 16)
        }
        return s.padStart(8, '0')
    }

    /** Setara `btoa()`: tiap karakter diperlakukan sebagai satu byte (latin-1). */
    private fun btoa(s: String): String = android.util.Base64.encodeToString(
        s.toByteArray(Charsets.ISO_8859_1),
        android.util.Base64.NO_WRAP
    )

    // ----------------------------------------------------------------- hashA

    /**
     * Hash tahap pertama. Keluarannya string hex 8 karakter, bisa bertanda minus.
     *
     * Perhatikan baris `t = t xor (i xor ...)`: ketika `n % 5 == 0`, nilai `i`
     * sama dengan `t`, sehingga seluruh riwayat hash terhapus dan `t` menjadi
     * nilai yang hanya berasal dari karakter saat itu. Ini perilaku asli kode
     * mereka, bukan kekeliruan port. Jangan "diperbaiki".
     */
    fun hashA(input: String): String {
        var t = 0
        for (n in input.indices) {
            val r = input[n].code
            t = r + (t shl 6) + (t shl 16) - t
            val i = (t shl (n % 5)) or (t ushr (32 - n % 5))
            t = t xor (i xor ((r shl (n % 7)) or (r ushr (8 - n % 7))))
            t += (t ushr 11) xor (t shl 3)
        }
        t = t xor (t ushr 15)
        t = (65535 and t) * 49842 + ((((t ushr 16) * 49842) and 65535) shl 16)
        t = t xor (t ushr 13)
        t = (65535 and t) * 40503 + ((((t ushr 16) * 40503) and 65535) shl 16)
        t = t xor (t ushr 16)
        return jsHex8(t)
    }

    // ----------------------------------------------------------------- hashB

    /**
     * Hash tahap kedua, dijalankan atas KELUARAN STRING dari [hashA].
     * Seed 0xDEADBEEF di-XOR dengan panjang masukan.
     */
    fun hashB(input: String): String {
        var n = -559038737 xor input.length          // 0xDEADBEEF sebagai Int
        for (e in input.indices) {
            var r = input[e].code
            r = r xor (((131 * e + 89) xor (r shl (e % 5))) and 255)
            n = ((n shl 7) or (n ushr 25)) xor r
            val i = (65535 and n) * 60205
            val o = ((n ushr 16) * 60205) shl 16
            n = i + o
            n = n xor (n ushr 11)
        }
        n = n xor (n ushr 15)
        n = (65535 and n) * 49842 + (((n ushr 16) * 49842) shl 16)
        n = n xor (n ushr 13)
        n = (65535 and n) * 40503 + (((n ushr 16) * 40503) shl 16)
        n = n xor (n ushr 16)
        n = (65535 and n) * 10196 + (((n ushr 16) * 10196) shl 16)
        n = n xor (n ushr 15)
        return jsHex8(n)
    }

    // ------------------------------------------------------------- secretKey

    /**
     * Bangun `secretKey` untuk sebuah nilai.
     *
     * @param value TMDB id (sebagai teks angka) atau kata kunci pencarian.
     *              `null` menghasilkan [LITERAL] seperti perilaku aslinya.
     */
    fun of(value: String?): String {
        if (value == null) return LITERAL
        val r = value
        if (r.isEmpty()) return LITERAL

        val trimmed = r.trim()
        val idx: Long = if (trimmed.isNotEmpty() && NUMERIC.matches(trimmed)) {
            // cabang ANGKA: dipakai untuk semua TMDB id
            trimmed.toDouble().toLong()
        } else {
            // cabang TEKS: dipakai untuk kata kunci pencarian
            var sum = 0L
            for (ch in r) sum += ch.code
            sum
        }

        val saltIdx = Math.floorMod(idx, SALTS.size.toLong()).toInt()
        var t = SALTS[saltIdx]
        if (t.isEmpty()) t = btoa(r)

        val n = (Math.floorMod(idx, r.length.toLong()) / 2).toInt()
        val salted = r.substring(0, n) + t + r.substring(n)

        return btoa(hashB(hashA(salted)))
    }

    /** Bentuk ringkas untuk TMDB id. */
    fun forId(tmdbId: Int): String = of(tmdbId.toString())

    /** Bentuk ringkas untuk kata kunci pencarian. */
    fun forQuery(query: String): String = of(query)

    // ------------------------------------------------------------- self test

    /**
     * Vektor uji dari hasil investigasi. Nilai harapan berasal dari traffic
     * asli dan dari verifikasi silang Node/Python/JVM.
     */
    val TEST_VECTORS: List<Pair<String?, String>> = listOf(
        "969681" to "MTQ5OTgzZGI=",
        "1396" to "Nzg3ZmU5YTI=",
        "550" to "NDQzM2UwYzI=",
        "278" to "LTJhMzZhYTFi",
        "batman" to "LTY1MmZkNTky",
        null to "rive"
    )

    /**
     * Jalankan seluruh vektor uji. Tidak memerlukan koneksi internet.
     *
     * @return pasangan (semua lulus, daftar baris laporan)
     */
    fun selfTest(): Pair<Boolean, List<String>> {
        val lines = ArrayList<String>()
        var allOk = true

        for ((input, expected) in TEST_VECTORS) {
            val got = runCatching { of(input) }.getOrElse { "ERROR: ${it.message}" }
            val ok = got == expected
            if (!ok) allOk = false
            lines.add("${if (ok) "OK   " else "GAGAL"} ${(input ?: "null").padEnd(10)} -> $got   (harap $expected)")
        }

        // nilai antara, berguna untuk menunjukkan di tahap mana kesalahan terjadi
        val hA = hashA("9zJ3nmt4OA69681")
        val hB = hashB("-553470bf")
        val okA = hA == "-553470bf"
        val okB = hB == "149983db"
        if (!okA || !okB) allOk = false
        lines.add("${if (okA) "OK   " else "GAGAL"} hashA(\"9zJ3nmt4OA69681\") = $hA   (harap -553470bf)")
        lines.add("${if (okB) "OK   " else "GAGAL"} hashB(\"-553470bf\")       = $hB   (harap 149983db)")
        lines.add("${if (hashA("969681") == "-60d93db") "OK   " else "GAGAL"} hashA(\"969681\") = ${hashA("969681")}   (harap -60d93db)")

        lines.add(if (allOk) "SEMUA VEKTOR UJI LULUS" else "ADA VEKTOR UJI YANG GAGAL")
        return allOk to lines
    }
}

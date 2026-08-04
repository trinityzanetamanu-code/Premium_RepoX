package com.RiveStream.byse

/**
 * ============================================================================
 *  Byse — Tahap 2: Proof of Work untuk gerbang captcha
 * ============================================================================
 *
 *  Sebelum endpoint playback mau menjawab, klien harus menyelesaikan tantangan
 *  PoW dan menukarnya dengan header `X-Captcha-Token`. Alurnya, dari komponen
 *  captcha di videoPagesBundle:
 *
 *      POST {mode}/captcha         -> {pow_nonce, pow_difficulty, pow_token,
 *                                      expires_in, algorithm}
 *      pecahkan PoW                -> solution (string desimal)
 *      POST {mode}/captcha/verify  -> {pow_token, solution, fingerprint?}
 *                                  -> {status:"ok", token, expires_in}
 *      token itulah yang dipasang sebagai header X-Captcha-Token.
 *
 *  ------------------------------------------------------------------------
 *  PERINGATAN: label `algorithm` dari server MENYESATKAN
 *  ------------------------------------------------------------------------
 *
 *  Respons server menyebut `"algorithm":"sha256-leading-zero-bits"`, tetapi
 *  implementasi sebenarnya BUKAN SHA-256. Fungsi hash-nya (gr di bundle)
 *  adalah hash memory-hard buatan sendiri: state 4 word dengan quarter-round
 *  gaya ChaCha (rotasi 16/12/8/7), buffer pencampuran 512 word, dua putaran,
 *  lalu pemadatan menjadi 8 word.
 *
 *  Nilai awal state memang IV SHA-256 (1779033703, 3144134277, 1013904242,
 *  2773480762) — itulah yang tampaknya membuat labelnya keliru. Jangan
 *  tertipu: memakai SHA-256 sungguhan akan menghasilkan solusi yang selalu
 *  ditolak server.
 *
 *  ------------------------------------------------------------------------
 *  Asal-usul kode [TERBUKTI] — pow-DEJGtdh2.js
 *  ------------------------------------------------------------------------
 *
 *      re(t,e)  -> [rotl]        putar kiri 32-bit
 *      ht(t,e)  -> [imul]        Math.imul, perkalian 32-bit
 *      ye(t)    -> [campur]      quarter-round gaya ChaCha
 *      gr(t)    -> [hash]        hash utama, mengembalikan 8 word
 *      wr(t)    -> [bitNolAwal]  hitung leading zero bits
 *      yr(t)    -> [keByte]      charCodeAt(i) & 255, yaitu LATIN-1 bukan UTF-8
 *      Er(t,e,r)-> [pecahkan]    cari solusi; diekspor sebagai `s`, dipakai
 *                                videoPagesBundle dengan alias `no`
 *
 *      Konstanta: be=512, lt=511, dr=2, lr=2654435761, hr=2246822519
 *
 *  Masukan yang di-hash: `"$nonce:$counter"`, counter mulai 0 dan naik satu.
 *  Nilai kembalian Er adalah `String(s)` — STRING DESIMAL, bukan angka
 *  maupun hex. Nilai itu dikirim apa adanya sebagai field `solution`.
 *
 *  ------------------------------------------------------------------------
 *  Verifikasi
 *  ------------------------------------------------------------------------
 *
 *  Kode asli dijalankan verbatim di Node untuk menghasilkan vektor acuan,
 *  lalu replikasi Python dan transliterasi JVM dibandingkan terhadapnya.
 *  Ketiganya sepakat: 5/5 vektor hash identik, dan solusi PoW untuk nonce
 *  yang sama menghasilkan 2 (difficulty 4), 71 (8), dan 1926 (12).
 *
 *  Biaya komputasi ringan: difficulty 12 selesai dalam ~83 ms di JVM.
 *  Difficulty yang terpantau dari server sejauh ini adalah 12.
 * ============================================================================
 */
object BysePow {

    private const val BE = 512          // be
    private const val LT = BE - 1       // lt
    private const val DR = 2            // dr
    private const val LR = -1640531527  // lr = 2654435761 sebagai Int bertanda
    private const val HR = -2048144777  // hr = 2246822519 sebagai Int bertanda

    /** Jumlah percobaan antar pemeriksaan batas waktu. Sama dengan bundle (a=1024). */
    private const val BLOK = 1024

    /** re(t,e) — putar kiri 32-bit. */
    private fun rotl(t: Int, e: Int): Int = (t shl e) or (t ushr (32 - e))

    /** ye(t) — quarter-round gaya ChaCha atas state 4 word. */
    private fun campur(e: IntArray) {
        e[0] = e[0] + e[1]; e[3] = rotl(e[3] xor e[0], 16)
        e[2] = e[2] + e[3]; e[1] = rotl(e[1] xor e[2], 12)
        e[0] = e[0] + e[1]; e[3] = rotl(e[3] xor e[0], 8)
        e[2] = e[2] + e[3]; e[1] = rotl(e[1] xor e[2], 7)
    }

    /**
     * gr(t) — hash utama. Mengembalikan 8 word 32-bit.
     *
     * Perkalian di sini memang meluap, dan itu benar: Math.imul di JavaScript
     * setara dengan perkalian Int yang membuang bit di atas 32.
     */
    fun hash(data: ByteArray): IntArray {
        val e = intArrayOf(1779033703, -1150833019, 1013904242, -1521486534)
        for (b in data) {
            e[0] = e[0] + (b.toInt() and 0xFF)
            e[0] = rotl(e[0], 7)
            campur(e)
        }
        repeat(8) { campur(e) }

        val r = IntArray(BE)
        for (i in 0 until BE) {
            campur(e)
            r[i] = e[0] xor e[2]
        }
        repeat(DR) {
            for (s in 0 until BE) {
                val a = r[s] and LT
                var c = r[s] + r[a]
                c = rotl(c, 13)
                c = c xor (r[(s + 1) and LT] * LR)
                r[s] = c
                e[0] = e[0] xor c
                campur(e)
            }
        }

        val n = IntArray(8)
        val o = BE / 8
        for (i in 0 until 8) {
            campur(e)
            var s = e[0]
            val a = i * o
            for (c in 0 until o) {
                val d = r[a + c]
                s += d
                s = rotl(s, 5)
                s = s xor (d * HR)
            }
            n[i] = s xor e[2]
        }
        return n
    }

    /** wr(t) — jumlah bit nol di depan, dihitung atas rangkaian word. */
    fun bitNolAwal(t: IntArray): Int {
        var e = 0
        for (n in t) {
            if (n == 0) {
                e += 32
                continue
            }
            return e + Integer.numberOfLeadingZeros(n)
        }
        return e
    }

    /**
     * yr(t) — ubah teks menjadi byte dengan `charCodeAt(i) and 255`.
     *
     * Ini LATIN-1, bukan UTF-8. Untuk nonce heksadesimal keduanya sama, tetapi
     * kesetiaan pada kode asli dijaga supaya tetap benar bila server suatu
     * saat mengirim nonce dengan karakter di luar ASCII.
     */
    fun keByte(s: String): ByteArray {
        val b = ByteArray(s.length)
        for (i in s.indices) b[i] = (s[i].code and 255).toByte()
        return b
    }

    /**
     * Er(t,e,r) — cari solusi PoW.
     *
     * @param nonce      `pow_nonce` dari respons captcha
     * @param difficulty `pow_difficulty` dari respons captcha
     * @param batasMs    batas waktu. Bundle menghitungnya sebagai
     *                   min(20000, max(4000, (expires_in - 3) * 1000)).
     * @return solusi sebagai STRING DESIMAL, atau null bila melewati batas
     *         waktu. difficulty <= 0 menghasilkan "0" tanpa perhitungan,
     *         persis seperti aslinya.
     */
    fun pecahkan(nonce: String, difficulty: Int, batasMs: Long = 20_000L): String? {
        if (difficulty <= 0) return "0"
        val awalan = "$nonce:"
        val mulai = System.currentTimeMillis()
        var s = 0L
        while (true) {
            for (c in 0 until BLOK) {
                if (bitNolAwal(hash(keByte(awalan + s))) >= difficulty) return s.toString()
                s++
            }
            if (System.currentTimeMillis() - mulai > batasMs) return null
        }
    }

    /**
     * Batas waktu yang dipakai bundle: min(20000, max(4000, (expiresIn-3)*1000)).
     * Untuk expires_in 1800 detik hasilnya 20000 ms.
     */
    fun batasWaktu(expiresInDetik: Int): Long =
        minOf(20_000L, maxOf(4_000L, (expiresInDetik - 3) * 1000L))

    // ---------------------------------------------------------- selftest

    /**
     * Vektor uji hasil menjalankan kode asli di Node, lalu dicocokkan ulang
     * dengan replikasi Python dan transliterasi JVM. Ketiganya identik.
     */
    private val VEKTOR_HASH = listOf(
        Triple("", "8703893e9260f0cf2bf5d1d7a35805bc9cd563e323d5283b42cd9b249f2fef93", 0),
        Triple("a", "943cfd8c92c17927a34d9eb5602fdae4517a8003380b53018337d0491a31f1ed", 0),
        Triple("abc", "f2758c8c405e74dae5cc830f7e93a27736deea6ec9dca24ac5b12c3047a63974", 0),
        Triple(
            "00eb392276f05f3ea8e6054f10414002:0",
            "3b247d380bde45d6f53b6c08c5360bb0f71e40647762f009cd60cf72817c64b2", 2
        ),
        Triple(
            "00eb392276f05f3ea8e6054f10414002:4095",
            "4b9e6f86690ec65edf3f60d392191b4613e7782863d01c4b96a875f3d4498ae4", 1
        )
    )

    /** Nonce nyata yang tertangkap dari server, beserta solusi acuannya. */
    private const val UJI_NONCE = "00eb392276f05f3ea8e6054f10414002"
    private val VEKTOR_SOLUSI = listOf(4 to "2", 8 to "71", 12 to "1926")

    private fun hex(h: IntArray): String =
        h.joinToString("") { "%08x".format(it) }

    /**
     * Jalankan pemeriksaan mandiri. Tidak memerlukan koneksi internet.
     * Perkiraan waktu: di bawah satu detik.
     */
    fun selfTest(): Pair<Boolean, List<String>> {
        val baris = ArrayList<String>()
        var semuaLulus = true

        for ((masukan, harapHex, harapLzb) in VEKTOR_HASH) {
            val h = hash(keByte(masukan))
            val gotHex = hex(h)
            val gotLzb = bitNolAwal(h)
            val lulus = gotHex == harapHex && gotLzb == harapLzb
            if (!lulus) semuaLulus = false
            val label = if (masukan.length > 34) masukan.take(34) + "…" else masukan
            baris.add("${if (lulus) "OK   " else "GAGAL"} hash(${"\"$label\""}) lzb=$gotLzb")
            if (!lulus) {
                baris.add("      dapat: $gotHex")
                baris.add("      harap: $harapHex")
            }
        }

        for ((difficulty, harap) in VEKTOR_SOLUSI) {
            val mulai = System.currentTimeMillis()
            val solusi = pecahkan(UJI_NONCE, difficulty, 20_000L)
            val durasi = System.currentTimeMillis() - mulai
            val lulus = solusi == harap
            if (!lulus) semuaLulus = false
            baris.add(
                "${if (lulus) "OK   " else "GAGAL"} pecahkan(difficulty=$difficulty) = $solusi " +
                    "(harap $harap, ${durasi}ms)"
            )
        }

        // difficulty <= 0 harus mengembalikan "0" tanpa perhitungan.
        val nol = pecahkan(UJI_NONCE, 0)
        if (nol != "0") semuaLulus = false
        baris.add("${if (nol == "0") "OK   " else "GAGAL"} pecahkan(difficulty=0) = $nol (harap 0)")

        // Batas waktu sesuai rumus bundle.
        val bw = batasWaktu(1800)
        if (bw != 20_000L) semuaLulus = false
        baris.add("${if (bw == 20_000L) "OK   " else "GAGAL"} batasWaktu(1800) = $bw (harap 20000)")
        val bw2 = batasWaktu(5)
        if (bw2 != 4_000L) semuaLulus = false
        baris.add("${if (bw2 == 4_000L) "OK   " else "GAGAL"} batasWaktu(5) = $bw2 (harap 4000)")

        baris.add(if (semuaLulus) "SEMUA PEMERIKSAAN LULUS" else "ADA PEMERIKSAAN YANG GAGAL")
        return semuaLulus to baris
    }
}

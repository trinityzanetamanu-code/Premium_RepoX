package com.RiveStream.byse

object BysePow {

    private const val BE = 512
    private const val LT = BE - 1
    private const val DR = 2
    private const val LR = -1640531535
    private const val HR = -2048144777
    private const val BLOK = 1024

    private fun rotl(t: Int, e: Int): Int = (t shl e) or (t ushr (32 - e))

    private fun campur(e: IntArray) {
        e[0] = e[0] + e[1]; e[3] = rotl(e[3] xor e[0], 16)
        e[2] = e[2] + e[3]; e[1] = rotl(e[1] xor e[2], 12)
        e[0] = e[0] + e[1]; e[3] = rotl(e[3] xor e[0], 8)
        e[2] = e[2] + e[3]; e[1] = rotl(e[1] xor e[2], 7)
    }

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

    fun keByte(s: String): ByteArray {
        val b = ByteArray(s.length)
        for (i in s.indices) b[i] = (s[i].code and 255).toByte()
        return b
    }

    fun pecahkan(nonce: String, difficulty: Int, batasMs: Long = 35_000L): String? {
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

    // Naikkan batas maksimum timeout ke 35.000 ms (35 detik)
    fun batasWaktu(expiresInDetik: Int): Long =
        minOf(35_000L, maxOf(5_000L, (expiresInDetik - 3) * 1000L))
}

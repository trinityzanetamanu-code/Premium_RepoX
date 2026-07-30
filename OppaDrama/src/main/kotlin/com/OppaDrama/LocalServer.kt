package com.OppaDrama

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * MILESTONE 1 — validasi ServerSocket di dalam plugin CloudStream.
 *
 * Ruang lingkup SENGAJA dibatasi. Tidak ada proxy, tidak ada scanner,
 * tidak ada strip. Hanya menjawab tiga risiko yang belum diketahui:
 *
 *   R1. apakah plugin bisa mem-bind ServerSocket sama sekali
 *   R2. apakah lifecycle plugin membiarkan server tetap hidup
 *   R3. apakah server melayani request berulang, bukan sekali lalu mati
 *
 * (Keterjangkauan 127.0.0.1 dari ExoPlayer TIDAK diuji di sini karena sudah
 *  terbukti pada arm B1 harness. Cleartext HTTP juga sudah terbukti karena
 *  provider ini memakai mainUrl http:// dan berjalan.)
 *
 * Keputusan desain yang dibawa dari pelajaran harness:
 *  - bind ke 127.0.0.1 saja, BUKAN 0.0.0.0. Harness bind ke 0.0.0.0 dan itu
 *    boleh untuk eksperimen, salah untuk kode yang berjalan di perangkat orang.
 *  - port 0 supaya OS memilih port bebas; tidak ada bentrok, tidak ada hardcode.
 *  - diskoneksi klien DITELAN diam-diam. Log harness terkubur ribuan
 *    ConnectionResetError karena ExoPlayer membuka/menutup koneksi agresif.
 *  - thread daemon, sehingga tidak menahan proses tetap hidup.
 */
object LocalServer {

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var startedAtMs: Long = 0L
        private set

    /** Jumlah koneksi yang berhasil dilayani. Bukti untuk R3. */
    val served = AtomicInteger(0)

    /** Jumlah error yang ditelan. Harus tetap kecil; kalau membengkak, catat. */
    val swallowed = AtomicInteger(0)

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var thread: Thread? = null

    val isRunning: Boolean
        get() = socket?.let { !it.isClosed } == true

    val uptimeSec: Long
        get() = if (startedAtMs == 0L) 0L else (System.currentTimeMillis() - startedAtMs) / 1000

    /**
     * Idempoten. Dipanggil berulang kali tidak membuat server kedua.
     * Ini penting: kalau lifecycle plugin memanggil load() lebih dari sekali,
     * kita tidak ingin kebocoran socket.
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
            ss.reuseAddress = true
            socket = ss
            port = ss.localPort
            startedAtMs = System.currentTimeMillis()
            served.set(0)
            swallowed.set(0)
            lastError = null

            val t = Thread({ acceptLoop(ss) }, "OppaDrama-LocalServer")
            t.isDaemon = true
            t.start()
            thread = t

            obs("START", "port=$port")
            true
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            obs("START-FAIL", lastError!!)
            port = -1
            false
        }
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        thread = null
        obs("STOP", "served=${served.get()}")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (e: Throwable) {
                if (ss.isClosed) return
                swallowed.incrementAndGet()
                lastError = "accept: ${e.javaClass.simpleName}"
                continue
            }
            // thread per koneksi: sengaja, karena ExoPlayer membuka beberapa
            // koneksi paralel dan M2 nanti butuh perilaku yang sama.
            val ct = Thread({ handle(client) }, "OppaDrama-conn")
            ct.isDaemon = true
            ct.start()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true

            val input = client.getInputStream()
            val line = StringBuilder()
            // baca request line saja; header sisanya di-skip sampai baris kosong
            var requestLine: String? = null
            var prevBlank = false
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val s = line.toString().trim()
                    line.setLength(0)
                    if (requestLine == null) {
                        requestLine = s
                    } else if (s.isEmpty()) {
                        prevBlank = true
                        break
                    }
                } else if (b != '\r'.code) {
                    line.append(b.toChar())
                }
            }
            if (requestLine == null) {
                swallowed.incrementAndGet()
                return
            }

            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val n = served.incrementAndGet()

            val body = buildString {
                append("ok\n")
                append("port=$port\n")
                append("served=$n\n")
                append("uptime_sec=$uptimeSec\n")
                append("started_at_ms=$startedAtMs\n")
                append("swallowed=${swallowed.get()}\n")
                append("path=$path\n")
                append("headers_terminated=$prevBlank\n")
            }.toByteArray()

            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray()

            val out = client.getOutputStream()
            out.write(head)
            out.write(body)
            out.flush()

            obs("SERVE", "path=$path served=$n uptime=${uptimeSec}s")
        } catch (_: SocketException) {
            swallowed.incrementAndGet()          // reset by peer: normal, diam
        } catch (_: IOException) {
            swallowed.incrementAndGet()          // broken pipe: normal, diam
        } catch (e: Throwable) {
            swallowed.incrementAndGet()
            lastError = "handle: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * MODE OBSERVASI. Satu baris per peristiwa, field tetap, tanpa dump byte.
     * Format ini bertahan sampai M5 dan akan diperluas dengan offset serta
     * Content-Length asli/baru pada M4 — supaya perubahan wrapper di masa
     * depan langsung terlihat tanpa mengulang investigasi forensik.
     */
    fun obs(event: String, detail: String) {
        println("[OppaDrama/OBS] $event $detail")
    }
}

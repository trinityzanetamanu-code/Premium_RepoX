package com.OppaDrama

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MILESTONE 2 — HTTP passthrough. MENGGANTIKAN LocalServer.kt (hapus file itu).
 *
 * Ruang lingkup: byte keluar WAJIB identik dengan byte upstream.
 * TIDAK ada scanner. TIDAK ada strip. Itu M3 dan M4.
 *
 * Perubahan desain dari M1, beserta alasannya:
 *
 *  - PORT DETERMINISTIK. M1 memakai port 0 sehingga OS memilih port acak tiap
 *    start. Untuk M2 itu berbahaya: kalau plugin dimuat ulang saat film sedang
 *    berjalan, port berganti dan URL yang sudah dipegang ExoPlayer jadi mati.
 *    Dengan port preferensi + fallback berurutan, nomornya stabil lintas reload
 *    dan pertanyaan lifecycle R2 tidak lagi memengaruhi arsitektur.
 *
 *  - ALLOWLIST BERBASIS SUFFIX DOMAIN, bukan host literal. F-34 membuktikan
 *    host CDN berbeda per judul (g282/g269 vs g266/g279) sementara wrapper
 *    tidak. Allowlist host literal akan pecah di judul kedua.
 *
 *  - REDIRECT DIIKUTI MANUAL, dengan pemeriksaan allowlist DI SETIAP HOP.
 *    instanceFollowRedirects=true akan mengikuti Location ke host mana pun dan
 *    itu melubangi allowlist-nya sendiri.
 *
 *  - MEMORI KONSTAN. Buffer tetap, tidak pernah menampung seluruh segmen.
 *
 *  - Range DITERUSKAN VERBATIM. Ini bukan pekerjaan M5, ini konsekuensi dari
 *    passthrough yang setia: menghapus header justru membuat byte tidak
 *    identik. Penerjemahan offset Range baru menjadi pekerjaan nyata di M4.
 */
object LocalProxy {

    private const val PREFERRED_PORT = 47821
    private const val PORT_FALLBACK_TRIES = 12
    private const val BUF = 64 * 1024
    private const val MAX_REDIRECTS = 5

    /** Suffix domain yang diizinkan. Awalan '.' berarti cocokkan sebagai suffix. */
    private val ALLOWED = listOf(
        ".turbosplayer.com",
        ".googleusercontent.com",
        "cdn.turboviplay.com",
        "turbovidhls.com",
        ".turboviplay.com"
    )

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var startedAtMs: Long = 0L
        private set

    @Volatile
    var lastError: String? = null
        private set

    val served = AtomicInteger(0)
    val swallowed = AtomicInteger(0)
    val proxied = AtomicInteger(0)
    val denied = AtomicInteger(0)
    val bytesOut = AtomicLong(0)

    @Volatile
    private var socket: ServerSocket? = null

    val isRunning: Boolean
        get() = socket?.let { !it.isClosed } == true

    val uptimeSec: Long
        get() = if (startedAtMs == 0L) 0L else (System.currentTimeMillis() - startedAtMs) / 1000

    // ------------------------------------------------------------- lifecycle

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val loopback = InetAddress.getByName("127.0.0.1")
        var bound: ServerSocket? = null
        var lastEx: Throwable? = null

        // port deterministik dulu, lalu fallback berurutan, terakhir port 0
        for (i in 0 until PORT_FALLBACK_TRIES) {
            bound = try {
                ServerSocket(PREFERRED_PORT + i, 64, loopback)
            } catch (e: Throwable) {
                lastEx = e; null
            }
            if (bound != null) break
        }
        if (bound == null) {
            bound = try {
                ServerSocket(0, 64, loopback)
            } catch (e: Throwable) {
                lastEx = e; null
            }
        }
        if (bound == null) {
            lastError = "bind: ${lastEx?.javaClass?.simpleName}: ${lastEx?.message}"
            obs("START-FAIL", lastError!!)
            port = -1
            return false
        }

        socket = bound
        port = bound.localPort
        startedAtMs = System.currentTimeMillis()
        served.set(0); swallowed.set(0); proxied.set(0); denied.set(0)
        bytesOut.set(0); lastError = null

        Thread({ acceptLoop(bound) }, "OppaDrama-proxy").apply {
            isDaemon = true
            start()
        }
        obs("START", "port=$port preferred=$PREFERRED_PORT")
        return true
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
        socket = null
        obs("STOP", "served=${served.get()} proxied=${proxied.get()}")
    }

    // ------------------------------------------------------------------- URL

    /** Bangun URL proxy. WAJIB dipanggil saat emit, jangan di-cache. */
    fun proxyUrl(upstream: String): String? {
        if (!isRunning || port <= 0) return null
        val enc = URLEncoder.encode(upstream, "UTF-8")
        return "http://127.0.0.1:$port/p?u=$enc"
    }

    fun isAllowed(rawUrl: String): Boolean {
        val host = try {
            URL(rawUrl).host?.lowercase() ?: return false
        } catch (_: Throwable) {
            return false
        }
        if (host.isEmpty()) return false
        return ALLOWED.any { entry ->
            if (entry.startsWith(".")) host == entry.substring(1) || host.endsWith(entry)
            else host == entry
        }
    }

    // ---------------------------------------------------------------- server

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val c = try {
                ss.accept()
            } catch (e: Throwable) {
                if (ss.isClosed) return
                swallowed.incrementAndGet()
                continue
            }
            Thread({ handle(c) }, "OppaDrama-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private class Req(val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Req? {
        val sb = StringBuilder()
        var requestLine: String? = null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (b == '\n'.code) {
                val line = sb.toString().trim()
                sb.setLength(0)
                if (requestLine == null) {
                    requestLine = line
                } else if (line.isEmpty()) {
                    break
                } else {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] =
                        line.substring(i + 1).trim()
                }
            } else if (b != '\r'.code) {
                sb.append(b.toChar())
            }
        }
        val rl = requestLine ?: return null
        val path = rl.split(" ").getOrNull(1) ?: return null
        return Req(path, headers)
    }

    private fun handle(client: Socket) {
        var upstreamUrl = "-"
        var status = -1
        var lenAsli = -1L
        var sent = 0L
        val t0 = System.currentTimeMillis()
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val req = readRequest(client.getInputStream()) ?: run {
                swallowed.incrementAndGet(); return
            }
            served.incrementAndGet()
            val out = client.getOutputStream()

            val qIdx = req.path.indexOf('?')
            val route = if (qIdx >= 0) req.path.substring(0, qIdx) else req.path
            val query = if (qIdx >= 0) req.path.substring(qIdx + 1) else ""

            if (route == "/ping" || route == "/") {
                val body = buildString {
                    append("ok\nport=$port\nserved=${served.get()}\n")
                    append("uptime_sec=$uptimeSec\nstarted_at_ms=$startedAtMs\n")
                    append("proxied=${proxied.get()}\ndenied=${denied.get()}\n")
                    append("swallowed=${swallowed.get()}\nbytes_out=${bytesOut.get()}\n")
                }.toByteArray()
                writeHead(out, 200, "text/plain; charset=utf-8", body.size.toLong(), null)
                out.write(body); out.flush()
                obs("PING", "served=${served.get()} uptime=${uptimeSec}s")
                return
            }

            if (route != "/p") {
                val b = "not found\n".toByteArray()
                writeHead(out, 404, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush()
                return
            }

            upstreamUrl = param(query, "u") ?: run {
                val b = "missing u\n".toByteArray()
                writeHead(out, 400, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush()
                return
            }

            if (!isAllowed(upstreamUrl)) {
                denied.incrementAndGet()
                val b = "host not allowed\n".toByteArray()
                writeHead(out, 403, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush()
                obs("DENY", "host=${hostOf(upstreamUrl)}")
                return
            }

            // ---- upstream, redirect diikuti manual + allowlist per hop ----
            var url = upstreamUrl
            var conn: HttpURLConnection? = null
            var hops = 0
            while (true) {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "*/*")
                    req.headers["range"]?.let { setRequestProperty("Range", it) }
                }
                val code = c.responseCode
                if (code in 301..308 && code != 304) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank() || ++hops > MAX_REDIRECTS) {
                        conn = null; break
                    }
                    val next = URL(URL(url), loc).toString()
                    if (!isAllowed(next)) {
                        denied.incrementAndGet()
                        obs("DENY-REDIRECT", "host=${hostOf(next)}")
                        conn = null; break
                    }
                    url = next
                    continue
                }
                conn = c
                break
            }

            if (conn == null) {
                val b = "upstream refused\n".toByteArray()
                writeHead(out, 502, "text/plain", b.size.toLong(), null)
                out.write(b); out.flush()
                obs("PROXY-FAIL", "url=${shortUrl(upstreamUrl)} hops=$hops")
                return
            }

            status = conn.responseCode
            lenAsli = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val ctype = conn.contentType ?: "application/octet-stream"
            val contentRange = conn.getHeaderField("Content-Range")

            // M2: Content-Length diteruskan APA ADANYA. Di M4 nilai ini
            // menjadi lenAsli - offset.
            writeHead(out, status, ctype, lenAsli, contentRange)

            val ins: InputStream = try {
                conn.inputStream
            } catch (_: Throwable) {
                conn.errorStream ?: return
            }
            sent = pump(ins, out)
            out.flush()
            proxied.incrementAndGet()
            bytesOut.addAndGet(sent)
            conn.disconnect()
        } catch (_: SocketException) {
            swallowed.incrementAndGet()
        } catch (_: IOException) {
            swallowed.incrementAndGet()
        } catch (e: Throwable) {
            swallowed.incrementAndGet()
            lastError = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            try {
                client.close()
            } catch (_: Throwable) {
            }
            if (upstreamUrl != "-") {
                // MODE OBSERVASI. Field tetap. offset=0 pada M2 karena belum ada
                // strip; M4 mengisinya dengan offset sync nyata. Format tidak
                // berubah supaya perbandingan lintas milestone tetap sah.
                obs(
                    "PROXY",
                    "url=${shortUrl(upstreamUrl)} status=$status offset=0 " +
                        "len_asli=$lenAsli len_kirim=$sent " +
                        "ms=${System.currentTimeMillis() - t0}"
                )
            }
        }
    }

    private fun pump(ins: InputStream, out: OutputStream): Long {
        val buf = ByteArray(BUF)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return total
    }

    private fun writeHead(
        out: OutputStream, code: Int, ctype: String, len: Long, contentRange: String?
    ) {
        val reason = when (code) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"
            403 -> "Forbidden"; 404 -> "Not Found"; 502 -> "Bad Gateway"
            else -> "Status"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $ctype\r\n")
        if (len >= 0) sb.append("Content-Length: $len\r\n")
        contentRange?.let { sb.append("Content-Range: $it\r\n") }
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun param(query: String, key: String): String? {
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i > 0 && pair.substring(0, i) == key) {
                return try {
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }

    private fun hostOf(u: String) = try {
        URL(u).host ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun shortUrl(u: String): String {
        val h = hostOf(u)
        val tail = u.takeLast(28)
        return "$h..$tail"
    }

    fun obs(event: String, detail: String) {
        println("[OppaDrama/OBS] $event $detail")
    }
}

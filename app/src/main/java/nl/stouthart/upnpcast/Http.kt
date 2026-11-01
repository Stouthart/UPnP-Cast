package nl.stouthart.upnpcast

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executor

/** Runs [work] on [e], or not at all once the screen that owns [e] has shut it down: never a crash. */
fun later(e: Executor, work: () -> Unit) {
    runCatching { e.execute(work) }
}

/** A device answered with an error; [upnp] is the SOAP fault's errorCode, or 0. */
class HttpException(val code: Int, val upnp: Int) :
    IOException(if (upnp > 0) "HTTP $code, UPnP error $upnp" else "HTTP $code")

/**
 * HTTP/1.1 over a plain socket, one request per connection: Sonos closes after every reply anyway,
 * and sending the request in one write keeps Nagle from holding its tail back for a round trip.
 */
object Http {
    private const val TIMEOUT = 3000 // ms, to connect and for each read.
    private const val DEADLINE = 15_000_000_000L // ns for a whole request: a device dripping a byte per read can't hold a thread.
    private const val MAX_BODY = 8 shl 20 // No sane reply is this big: a search of all 1,891 tracks is 2.4 MB.
    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 100

    fun get(url: String): ByteArray = send(URL(url), "GET", "", null)

    /** Calls [action] of [service] (e.g. "AVTransport") at [control]; [args] is the arguments' XML, escaped. */
    fun soap(control: String, service: String, action: String, args: String = ""): ByteArray {
        val type = "urn:schemas-upnp-org:service:$service:1"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/" +
            "envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"" +
            "$type\">$args</u:$action></s:Body></s:Envelope>"
        val headers = "Content-Type: text/xml; charset=\"utf-8\"\r\nSOAPACTION: \"$type#$action\"\r\n"
        return send(URL(control), "POST", headers, body.toByteArray())
    }

    private fun send(u: URL, method: String, headers: String, body: ByteArray?): ByteArray {
        if (u.protocol != "http") throw IOException("Not http: $u")
        if (Lan.wifi == null) throw IOException("No WiFi") // Not a 3 s wait for a LAN address over mobile data.
        val port = if (u.port < 0) 80 else u.port
        val head = ("$method ${u.file.ifEmpty { "/" }} HTTP/1.1\r\nHost: ${u.host}:$port\r\nConnection: close\r\n" +
            headers + (if (body == null) "" else "Content-Length: ${body.size}\r\n") + "\r\n").toByteArray()
        val end = System.nanoTime() + DEADLINE
        Socket().use { s ->
            s.tcpNoDelay = true
            s.soTimeout = TIMEOUT
            s.connect(InetSocketAddress(u.host, port), TIMEOUT)
            s.getOutputStream().write(if (body == null) head else head + body)

            val i = s.getInputStream().buffered(16384)
            val code = line(i, end).split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: throw IOException("Not HTTP")
            var length = -1L
            var chunked = false
            var headers = 0
            while (true) {
                val h = line(i, end)
                if (h.isEmpty()) break
                if (++headers > MAX_HEADERS) throw IOException("Too many headers")
                val c = h.indexOf(':')
                if (c < 0) continue
                val name = h.substring(0, c).trim()
                val value = h.substring(c + 1).trim()
                if (name.equals("Content-Length", true)) length = value.toLongOrNull() ?: -1
                else if (name.equals("Transfer-Encoding", true)) chunked = value.contains("chunked", true)
            }

            val out = ByteArrayOutputStream(if (length in 0..MAX_BODY) length.toInt() else 16384)
            val buf = ByteArray(16384)
            when {
                chunked -> while (true) {
                    val n = line(i, end).substringBefore(';').trim().toIntOrNull(16)
                    if (n == null || n < 0) throw IOException("Bad chunk") // A negative size would read to the end.
                    if (n == 0) break
                    copy(i, out, buf, n.toLong(), end)
                    line(i, end) // The CRLF that ends each chunk.
                }
                length >= 0 -> copy(i, out, buf, length, end)
                else -> copy(i, out, buf, -1, end) // Until the device closes the connection.
            }
            val b = out.toByteArray()
            if (code != 200) throw HttpException(code, upnpError(b))
            return b
        }
    }

    private fun late(end: Long) {
        if (System.nanoTime() > end) throw IOException("Reply too slow")
    }

    /** Copies [n] bytes, or everything up to the end when [n] is negative, before [end]. */
    private fun copy(i: InputStream, out: ByteArrayOutputStream, buf: ByteArray, n: Long, end: Long) {
        var left = if (n < 0) Long.MAX_VALUE else n
        while (left > 0) {
            val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) {
                if (n < 0) return
                throw IOException("Reply cut short")
            }
            out.write(buf, 0, r)
            if (out.size() > MAX_BODY) throw IOException("Reply too big")
            late(end)
            left -= r
        }
    }

    /** One header line without its line ending, before [end]; headers are ASCII. */
    private fun line(i: InputStream, end: Long): String {
        val sb = StringBuilder(64)
        while (true) {
            val c = i.read()
            if (c < 0 || c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > MAX_LINE) throw IOException("Header too long")
            late(end) // Per byte: these reads come from the buffer, so a clock read is all it costs.
        }
    }

    private fun upnpError(b: ByteArray): Int {
        val s = String(b)
        val i = s.indexOf("<errorCode>")
        if (i < 0) return 0
        val end = s.indexOf('<', i + 11)
        return if (end < 0) 0 else s.substring(i + 11, end).trim().toIntOrNull() ?: 0
    }
}

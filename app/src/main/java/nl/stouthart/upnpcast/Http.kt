package nl.stouthart.upnpcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
    const val TIMEOUT = 3000 // ms, to connect and for each read.
    private const val DEADLINE = 15_000_000_000L // ns for a whole request: a device dripping a byte per read can't hold a thread.
    private const val MAX_BODY = 8 shl 20 // No sane reply is this big: a search of all 1,891 tracks is 2.4 MB.
    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 100

    fun get(url: String): ByteArray = send(URL(url), "GET", "", null)

    /**
     * Calls [action] of [service] (e.g. "AVTransport") at [control]; [args] is the arguments' XML, escaped. [wait]: ms
     * for each read, longer only for an action the device answers once it's done.
     */
    fun soap(control: String, service: String, action: String, args: String = "", wait: Int = TIMEOUT): ByteArray {
        val type = "urn:schemas-upnp-org:service:$service:1"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/" +
            "envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"" +
            "$type\">$args</u:$action></s:Body></s:Envelope>"
        val headers = "Content-Type: text/xml; charset=\"utf-8\"\r\nSOAPACTION: \"$type#$action\"\r\n"
        val reply = send(URL(control), "POST", headers, body.toByteArray(), wait)
        if (!acknowledges(reply, action, service)) throw IOException("No $action response")
        return reply
    }

    /**
     * Whether [reply] is [action]'s own answer: one complete SOAP document, its Envelope's Body holding that action's
     * response alone, in [service]'s namespace at any version, or none, as a sloppy server may send it; no DTD. A web
     * page, an empty or unfinished reply, a fault sent with 200, or the answer's tag in a comment or CDATA isn't taken
     * as done, so no step that depends on it follows, as Play after SetAVTransportURI. Read whole: ~15 ms for the
     * largest reply, a search of the whole library, which its caller reads again anyway.
     */
    fun acknowledges(reply: ByteArray, action: String, service: String? = null): Boolean = runCatching {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(reply.inputStream(), null)
        val envelope = "http://schemas.xmlsoap.org/soap/envelope/"
        val type = service?.let { "urn:schemas-upnp-org:service:$it:" }
        var roots = 0
        var body = false
        var answer = false
        while (p.nextToken() != XmlPullParser.END_DOCUMENT) {
            val e = p.eventType
            // No DTD, nor an entity only one could define.
            require(e != XmlPullParser.DOCDECL && (e != XmlPullParser.ENTITY_REF || p.text != null))
            // Around the Envelope only whitespace, comments and processing instructions.
            require(p.depth != 0 || e !in XmlPullParser.TEXT..XmlPullParser.ENTITY_REF || e == XmlPullParser.TEXT && p.isWhitespace)
            if (e != XmlPullParser.START_TAG) continue
            when (p.depth) {
                1 -> require(++roots == 1 && p.name == "Envelope" && p.namespace == envelope)
                2 -> {
                    require(!body) // A Header may come first; nothing after the Body.
                    body = p.name == "Body" && p.namespace == envelope
                }
                3 -> if (body) {
                    // In the service's namespace, any version of it (a number), or in none, as some devices answer.
                    val ns = p.namespace
                    require(!answer && p.name == "${action}Response" && (type == null || ns.isEmpty() ||
                        ns.startsWith(type) && ns.removePrefix(type).let { v -> v.isNotEmpty() && v.all { it in '0'..'9' } }))
                    answer = true
                }
            }
        }
        answer && p.depth == 0
    }.getOrDefault(false)

    // Writes have no timeout of their own: at the deadline this closes the socket, which ends any I/O still on it.
    private val watchdog = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "http-deadline").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }

    private fun send(u: URL, method: String, headers: String, body: ByteArray?, wait: Int = TIMEOUT): ByteArray {
        if (u.protocol != "http") throw IOException("Not http: $u")
        val net = Lan.wifi ?: throw IOException("No WiFi") // Not a 3 s wait for a LAN address over mobile data.
        // An address, never a name: no DNS, which could take any time and point anywhere. The path goes on the request
        // line as it is, so a space is escaped and any other control character refused: it could add header lines.
        if (!literal(u.host)) throw IOException("Not an address: ${u.host}")
        val target = u.file.ifEmpty { "/" }.replace(" ", "%20")
        if (target.any { it < ' ' || it == '\u007f' }) throw IOException("Bad request target")
        val port = if (u.port < 0) 80 else u.port
        val head = ("$method $target HTTP/1.1\r\nHost: ${u.host}:$port\r\nConnection: close\r\n" +
            headers + (if (body == null) "" else "Content-Length: ${body.size}\r\n") + "\r\n").toByteArray()
        val end = System.nanoTime() + DEADLINE
        Socket().use { s ->
            // On the network checked above, whatever the process binding says by now (WiFi may have gone meanwhile).
            // Refused under a VPN that allows no bypass: the request then goes its way, as the process binding does.
            runCatching { net.bindSocket(s) }
            val kill = watchdog.schedule({ runCatching { s.close() } }, DEADLINE, TimeUnit.NANOSECONDS)
            try {
                s.tcpNoDelay = true
                s.soTimeout = wait
                s.connect(InetSocketAddress(InetAddress.getByName(u.host), port), TIMEOUT) // A literal: no lookup.
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
            } finally {
                kill.cancel(false)
            }
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

    /**
     * A SOAP fault's UPnP errorCode, with any prefix (<u:errorCode>, as 1028 or 803 may come), or 0. Namespace-aware, by
     * the factory's parser as acknowledges(), so it reads on the computer too.
     */
    internal fun upnpError(b: ByteArray): Int = runCatching {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(noDtd(b).inputStream(), null)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "errorCode") return@runCatching p.nextText().trim().toIntOrNull() ?: 0
        }
        0
    }.getOrDefault(0)
}

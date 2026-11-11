package nl.stouthart.upnpcast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Reading a device's reply off the socket: by Content-Length, chunked, or up to the close, within the limits. */
class HttpReplyTest {
    private fun read(raw: String) = Http.reply(raw.toByteArray(Charsets.ISO_8859_1).inputStream(), System.nanoTime() + 5_000_000_000)

    private fun refused(raw: String) = assertTrue(raw.take(40), runCatching { read(raw) }.exceptionOrNull() is IOException)

    @Test
    fun byLength() {
        val (code, body) = read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nServer: x\r\n\r\nhello and more")
        assertEquals(200, code)
        assertArrayEquals("hello".toByteArray(), body)
    }

    @Test
    fun chunked() {
        val (code, body) = read("HTTP/1.1 500 Internal\r\nTransfer-Encoding: chunked\r\n\r\n5;x=1\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")
        assertEquals(500, code)
        assertEquals("hello world", String(body))
    }

    @Test
    fun upToTheClose() {
        assertEquals("all of it", String(read("HTTP/1.0 200 OK\r\n\r\nall of it").second))
    }

    @Test
    fun outsideTheLimits() {
        refused("SSH-2.0-OpenSSH\r\n\r\n") // Not HTTP.
        refused("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nhello\r\n0\r\n\r\n") // A negative chunk.
        refused("HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\nshort") // Cut short.
        refused("HTTP/1.1 200 OK\r\nX: ${"a".repeat(9000)}\r\n\r\n") // A header too long.
        refused("HTTP/1.1 200 OK\r\n" + "X: 1\r\n".repeat(101) + "\r\n") // Too many headers.
        refused("HTTP/1.1 200 OK\r\nContent-Length: 9000000\r\n\r\n" + "x".repeat(9_000_000)) // Too big.
    }

    @Test
    fun framingAsTheMacAppHasIt() {
        refused("HTTP/1.1 200 OK\r\nContent-Length: abc\r\n\r\nhello") // Not a length.
        refused("HTTP/1.1 200 OK\r\nContent-Length: +5\r\n\r\nhello") // Not digits alone.
        refused("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\nhello!") // Two that differ.
        refused("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhelloXX0\r\n\r\n") // A chunk not ended by CRLF.
        refused("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n") // No CRLF after the last.
        refused("HTTP/1.1 200 OK\r\nContent-Len") // Headers cut short.
        assertEquals("hello", String(read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello").second))
        assertEquals("hi", String(read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhi\r\n0\r\nX-Trailer: 1\r\n\r\n").second))
    }

    @Test
    fun tooSlow() {
        val r = runCatching { Http.reply("HTTP/1.1 200 OK\r\n\r\nx".toByteArray().inputStream(), System.nanoTime() - 1) }
        assertTrue(r.exceptionOrNull() is IOException)
    }
}

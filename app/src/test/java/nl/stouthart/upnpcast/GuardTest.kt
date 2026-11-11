package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.Locale

/** The checks that keep requests on the LAN and in the speaker's format, and Now Playing within a small window. */
class GuardTest {
    @Test
    fun literalAddressesOnly() {
        assertTrue(literal("192.168.8.10"))
        assertTrue(literal("10.0.0.1"))
        assertTrue(literal("[fe80::1]"))
        assertTrue(literal("[fe80::1%wlan0]"))
        assertFalse(literal("sonos.local")) // A name would mean DNS.
        assertFalse(literal("evil.example.com"))
        assertFalse(literal("192.168.8"))
        assertFalse(literal("192.168.8.256"))
        assertFalse(literal("1.2.3.4.5"))
        assertFalse(literal("[fe80::1"))
        assertFalse(literal(""))
        // Brackets hold hex digits and colons, with a zone after %: not any word.
        assertFalse(literal("[abc]"))
        assertFalse(literal("[evil.example.com]"))
        assertFalse(literal("[fe80::1%]"))
        assertFalse(literal("[fe80::g]"))
    }

    private fun ip(s: String) = InetAddress.getByName(s).address

    @Test
    fun subnets() {
        val net = ip("192.168.8.0")
        assertTrue(inSubnet(ip("192.168.8.10"), net, 24))
        assertTrue(inSubnet(ip("192.168.8.255"), net, 24))
        assertFalse(inSubnet(ip("192.168.9.10"), net, 24))
        assertFalse(inSubnet(ip("8.8.8.8"), net, 24))
        assertTrue(inSubnet(ip("192.168.9.10"), net, 16))
        assertTrue(inSubnet(ip("1.2.3.4"), net, 0)) // A /0 holds everything.
        assertFalse(inSubnet(ip("192.168.8.10"), ip("fe80::"), 64)) // IPv4 against IPv6: never.
        assertTrue(inSubnet(ip("fe80::1"), ip("fe80::"), 64))
        assertFalse(inSubnet(ip("192.168.8.10"), net, 33)) // A prefix the address can't have.
    }

    @Test
    fun seekTargetInAsciiDigits() {
        val was = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG")) // Arabic-Indic digits by default.
            assertEquals("0:01:30", hms(90))
            assertEquals("1:02:03", hms(3723))
            assertEquals("+0:00:15", delta(15)) // Quick skip's relative Seek.
            assertEquals("-0:01:00", delta(-60))
        } finally {
            Locale.setDefault(was)
        }
    }

    @Test
    fun durationsWithinBounds() {
        assertEquals(340, seconds("0:05:40.000"))
        assertEquals(3723, seconds("01:02:03"))
        assertEquals(0, seconds("NOT_IMPLEMENTED"))
        assertEquals(0, seconds("1::03")) // A part missing.
        assertEquals(0, seconds("0:60:00")) // Minutes and seconds stop at 59.
        assertEquals(0, seconds("0:00:60"))
        assertEquals(0, seconds("-1:00:00"))
        assertEquals(596523 * 3600 + 14 * 60 + 7, seconds("596523:14:07")) // Int.MAX_VALUE itself.
        assertEquals(0, seconds("596523:14:08")) // One more: no wrap to a negative length.
        assertEquals(0, seconds("596524:00:00"))
        assertEquals(0, seconds("99999999999:00:00")) // Not even a number of hours an Int holds.
    }

    @Test
    fun coverInASmallWindow() {
        assertEquals(500, coverSide(500, 400, 900))
        assertEquals(400, coverSide(100, 400, 900)) // Not below the minimum while there's room.
        assertEquals(900, coverSide(2000, 400, 900))
        assertEquals(300, coverSide(100, 400, 300)) // The window is smaller than the minimum: the largest it can be.
        assertEquals(300, coverSide(500, 400, 300))
    }
}

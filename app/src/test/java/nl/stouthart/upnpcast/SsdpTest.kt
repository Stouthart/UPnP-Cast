package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A search's answers: taken only for what was asked, and only from a device that names its own address. */
class SsdpTest {
    private val targets = arrayOf(Ssdp.SONOS, Ssdp.SERVER)

    private fun answer(st: String, location: String) =
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age = 1800\r\nLOCATION: $location\r\nST: $st\r\nUSN: uuid:RINCON_1::$st\r\n\r\n"

    @Test
    fun itsOwnAddress() {
        val a = Ssdp.parse(answer(Ssdp.SONOS, "http://192.168.8.10:1400/xml/device_description.xml"), "192.168.8.10", targets)
        assertEquals(Ssdp.SONOS, a?.st)
        assertEquals("http://192.168.8.10:1400/xml/device_description.xml", a?.location)
        assertEquals("uuid:RINCON_1::${Ssdp.SONOS}", a?.usn)
        // Header names in any case.
        val lower = "HTTP/1.1 200 OK\r\nlocation: http://192.168.8.2:9790/desc.xml\r\nst: ${Ssdp.SERVER}\r\n\r\n"
        assertEquals("http://192.168.8.2:9790/desc.xml", Ssdp.parse(lower, "192.168.8.2", targets)?.location)
    }

    @Test
    fun refused() {
        // Another device's address: it would aim our requests at a third party.
        assertNull(Ssdp.parse(answer(Ssdp.SONOS, "http://192.168.8.99:1400/x.xml"), "192.168.8.10", targets))
        assertNull(Ssdp.parse(answer(Ssdp.SONOS, "https://192.168.8.10:1400/x.xml"), "192.168.8.10", targets)) // Not http.
        assertNull(Ssdp.parse(answer("upnp:rootdevice", "http://192.168.8.10:1400/x.xml"), "192.168.8.10", targets)) // Not asked.
        assertNull(Ssdp.parse("HTTP/1.1 200 OK\r\n\r\n", "192.168.8.10", targets))
    }
}

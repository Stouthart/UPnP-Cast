package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A server's entries and the Sonos favourites, as the rows and playback take them. */
class DidlTest {
    private val ns = "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""

    @Test
    fun aServersEntries() {
        val xml = "<DIDL-Lite $ns><container id=\"1\" childCount=\"12\"><dc:title>Albums</dc:title><upnp:class>object.container" +
            "</upnp:class></container><item id=\"2\"><dc:title>Festival</dc:title><upnp:artist>Seetu</upnp:artist>" +
            "<upnp:artist role=\"AlbumArtist\">Other</upnp:artist><upnp:originalTrackNumber>3</upnp:originalTrackNumber>" +
            "<upnp:albumArtURI dlna:profileID=\"JPEG_TN\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">http://192.168.8.2/tn.jpg" +
            "</upnp:albumArtURI><upnp:albumArtURI dlna:profileID=\"JPEG_LRG\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
            "http://192.168.8.2/lrg.jpg</upnp:albumArtURI><upnp:albumArtURI>http://evil.example.com/x.jpg</upnp:albumArtURI>" +
            "<res protocolInfo=\"http-get:*:audio/x-flac:*\" sampleFrequency=\"96000\">http://192.168.8.2/hi.flac</res>" +
            "<res protocolInfo=\"http-get:*:audio/x-flac:*\" sampleFrequency=\"48000\" bitsPerSample=\"24\">http://192.168.8.2/48.flac</res>" +
            "</item></DIDL-Lite>"
        val e = Didl.parse(xml, Didl.on("192.168.8.2"))
        assertEquals(2, e.size)
        assertTrue(e[0].container)
        assertEquals(12, e[0].count)
        val t = e[1]
        assertEquals("Seetu", t.artist) // The performer, not the album artist.
        assertEquals(3, t.track)
        assertEquals("http://192.168.8.2/lrg.jpg", t.art) // The largest labelled cover; another host's never.
        assertEquals("http://192.168.8.2/48.flac", t.res?.url) // The first Sonos can play.
        assertTrue(t.playable)
    }

    @Test
    fun aLinkElsewhereIsntPlayable() {
        val xml = "<DIDL-Lite $ns><item id=\"2\"><dc:title>X</dc:title><res protocolInfo=\"http-get:*:audio/mpeg:*\">" +
            "http://evil.example.com/x.mp3</res></item></DIDL-Lite>"
        val t = Didl.parse(xml, Didl.on("192.168.8.2")).single()
        assertFalse(t.playable)
    }

    @Test
    fun favourites() {
        val xml = "<DIDL-Lite $ns><item id=\"FV:2/1\"><dc:title>90s90s Radio</dc:title><r:type>instantPlay</r:type>" +
            "<r:description>TuneIn</r:description><res>x-sonosapi-stream:s123?sid=254</res><r:resMD>&lt;DIDL-Lite/&gt;</r:resMD>" +
            "<upnp:albumArtURI>/getaa?s=1&amp;u=x</upnp:albumArtURI></item><item id=\"FV:2/2\"><dc:title>Sonos Radio</dc:title>" +
            "<r:type>shortcut</r:type><res>x-rincon-cpcontainer:abc</res></item></DIDL-Lite>"
        val f = Didl.favorites(xml, "http://192.168.8.10:1400/MediaServer/ContentDirectory/Control")
        assertEquals(2, f.size)
        assertEquals("TuneIn", f[0].artist)
        assertEquals("x-sonosapi-stream:s123?sid=254", f[0].fav?.uri)
        assertEquals("<DIDL-Lite/>", f[0].fav?.meta)
        assertEquals("http://192.168.8.10:1400/getaa?s=1&u=x", f[0].art) // On the player.
        assertTrue(f[0].playable)
        assertEquals("", f[1].fav?.uri) // A shortcut: nothing UPnP can play.
        assertFalse(f[1].playable)
        assertNull(f[1].art)
    }
}

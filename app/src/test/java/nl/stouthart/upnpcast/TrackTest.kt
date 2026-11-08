package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TrackMetaData as a track: its format and cover, which a favourite of it keeps, from any valid spelling. */
class TrackTest {
    private val ns = "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
        "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""

    @Test
    fun formatAndCover() {
        val t = Sonos.track(
            "<DIDL-Lite $ns><item id=\"-1\"><dc:title>Festival</dc:title><dc:creator>Acoustic Mania</dc:creator>" +
                "<upnp:albumArtURI> http://10.0.0.5:9790/c.jpg?x=1&amp;y=2 </upnp:albumArtURI>" +
                "<res protocolInfo=\"http-get:*:audio/flac:DLNA.ORG_OP=01\" sampleFrequency=\"44100\" bitsPerSample=\"16\">" +
                "http://10.0.0.5:9790/a.flac</res></item></DIDL-Lite>",
        )!!
        assertEquals("Festival", t.title)
        assertEquals("http-get:*:audio/flac:DLNA.ORG_OP=01", t.protocol)
        assertEquals("http://10.0.0.5:9790/c.jpg?x=1&y=2", t.art)
    }

    @Test
    fun anySpelling() {
        // Single quotes and other prefixes are as valid: the same track.
        val t = Sonos.track(
            "<d:DIDL-Lite xmlns:t='http://purl.org/dc/elements/1.1/' xmlns:u='urn:schemas-upnp-org:metadata-1-0/upnp/' " +
                "xmlns:d='urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/'><d:item id='-1'><t:title>Festival</t:title>" +
                "<u:albumArtURI>http://10.0.0.5:9790/c.jpg</u:albumArtURI><d:res protocolInfo='http-get:*:audio/flac:*'>" +
                "http://10.0.0.5:9790/a.flac</d:res></d:item></d:DIDL-Lite>",
        )!!
        assertEquals("Festival", t.title)
        assertEquals("http-get:*:audio/flac:*", t.protocol)
        assertEquals("http://10.0.0.5:9790/c.jpg", t.art)
    }

    @Test
    fun none() {
        assertNull(Sonos.track(null))
        assertNull(Sonos.track("NOT_IMPLEMENTED"))
        assertEquals("", Sonos.track("<DIDL-Lite $ns><item><dc:title>Festival</dc:title></item></DIDL-Lite>")!!.protocol)
    }
}

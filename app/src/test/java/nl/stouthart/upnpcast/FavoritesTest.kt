package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What plays now, as the favourite the speaker saves: with Sonos's own ids where the Sonos app needs them to show it. */
class FavoritesTest {
    private fun has(xml: String?, part: String) = assertTrue("$part in $xml", xml?.contains(part) == true)

    @Test
    fun key() {
        // A station by itself: its link's flags change from play to play.
        assertEquals("x-sonosapi-stream:s123", Favorites.key("x-sonosapi-stream:s123?sid=511&flags=8224&sn=2"))
        assertEquals("x-sonosapi-stream:s123", Favorites.key("x-sonosapi-stream:s123?sid=511&flags=32&sn=3"))
        val track = "http://10.0.0.5:9790/minimserver/*/Music/a.flac?x=1"
        assertEquals(track, Favorites.key(track))
    }

    @Test
    fun station() {
        val d = Favorites.station("x-sonosapi-stream:s123?sid=511&flags=8224&sn=2", "90s90s Ibiza", "http://logo/a.png", "90s90s Radio")
        has(d, "<dc:title>90s90s Ibiza</dc:title>")
        has(d, "<res protocolInfo=\"x-sonosapi-stream:*:*:*\">x-sonosapi-stream:s123?sid=511&amp;flags=8224&amp;sn=2</res>")
        has(d, "<r:description>90s90s Radio</r:description>")
        // Sonos's ids, escaped once more inside resMD: the station behind 10092064, the service as 511 × 256 + 7.
        has(d, "id=&quot;10092064s123&quot; parentID=&quot;10092064s123&quot;")
        has(d, "SA_RINCON130823_")
        // A bare stream, or a station without its service: no favourite.
        assertNull(Favorites.station("x-rincon-mp3radio://stream.example/live", "Live", "", ""))
        assertNull(Favorites.station("x-sonosapi-stream:s123", "No service", "", ""))
    }

    @Test
    fun library() {
        val uri = "x-file-cifs://nas/Music/A+B/Song%20One.flac"
        val d = Favorites.library(uri, "Song One", "10.0.0.10:1400", "")
        // Every slash spelled out, a + kept as a +, a space decoded.
        has(d, "S:__UNENCODED_SLASH____UNENCODED_SLASH__nas__UNENCODED_SLASH__Music__UNENCODED_SLASH__A+B__UNENCODED_SLASH__Song One.flac")
        has(d, "RINCON_AssociatedZPUDN")
        has(d, "<res protocolInfo=\"x-file-cifs:*:audio/flac:*\">$uri</res>") // By its extension, without the speaker's.
        has(d, "http://10.0.0.10:1400/getaa?u=x-file-cifs%3A%2F%2Fnas%2FMusic%2FA%2BB%2FSong%2520One.flac&amp;v=45")
        has(d, "<r:description>From Music Library</r:description>")
        has(Favorites.library(uri, "Song One", "10.0.0.10:1400", "x-file-cifs:*:audio/x-flac:*"), "protocolInfo=\"x-file-cifs:*:audio/x-flac:*\"")
        assertNull(Favorites.library("http://10.0.0.5/a.flac", "Song", "10.0.0.10:1400", ""))
        assertNull(Favorites.library("x-file-cifs://nas/Music/Bad%zz.flac", "Song", "10.0.0.10:1400", "")) // A broken escape.
    }

    @Test
    fun server() {
        val meta = "<DIDL-Lite><item id=\"1\"><dc:title>Can You Dance</dc:title></item></DIDL-Lite>"
        val d = Favorites.server("http://10.0.0.5:9790/a.m4a", "Can You Dance", "Artist", "http://10.0.0.5:9790/c.jpg", "", meta)
        has(d, "<r:description>Artist</r:description>")
        has(d, "<res protocolInfo=\"http-get:*:audio/mp4:*\">")
        has(d, "<upnp:albumArtURI>http://10.0.0.5:9790/c.jpg</upnp:albumArtURI>")
        has(d, "<r:resMD>&lt;DIDL-Lite&gt;&lt;item id=&quot;1&quot;&gt;")
        // Without the metadata it plays with, or not a server's: no favourite.
        assertNull(Favorites.server("http://10.0.0.5:9790/a.m4a", "Can You Dance", "Artist", "", "", ""))
        assertNull(Favorites.server("x-sonos-spotify:abc", "Track", "Artist", "", "", meta))
    }
}

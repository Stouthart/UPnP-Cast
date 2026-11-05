package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** Now Playing's source, by the URI the speaker reports and, for the TV, the soundbar's model. */
class SourceTest {
    private val tv = "x-sonos-htastream:RINCON_74CA6010A47601400:spdif"

    @Test
    fun tvInput() {
        assertEquals(Source.HDMI, source(tv, "Beam Ultra"))
        assertEquals(Source.HDMI, source(tv, "Sonos Arc")) // As kept by an earlier build.
        assertEquals(Source.HDMI, source(tv, "Amp"))
        assertEquals(Source.SPDIF, source(tv, "Ray"))
        assertEquals(Source.SPDIF, source(tv, "Playbase"))
        assertEquals(Source.TV, source(tv, "")) // Model not read yet.
    }

    @Test
    fun services() {
        assertEquals(Source.SPOTIFY, source("x-sonos-vli:RINCON_74CA60B5620801400:2,spotify:40fb8e72b3117aa97d72f1792348e8f8"))
        assertEquals(Source.AIRPLAY, source("x-sonos-vli:RINCON_74CA60B5620801400:1,airplay:5c0d3f"))
        assertEquals(Source.OTHER, source("x-sonos-vli:RINCON_74CA60B5620801400:3,"))
        assertEquals(Source.SPOTIFY, source("x-sonos-spotify:spotify%3atrack%3a4uLU6hMCjMI75M1A2tKUQC?sid=12&flags=8224"))
        assertEquals(Source.RADIO, source("x-sonosapi-stream:s24939?sid=254&flags=8224&sn=0"))
        assertEquals(Source.RADIO, source("x-rincon-mp3radio://icecast.example/stream"))
        assertEquals(Source.OTHER, source("x-sonos-http:librarytrack%3ai.abc.mp4?sid=204"))
        // Sonos Radio: a track its station plays, and the station.
        assertEquals(Source.SONOS_RADIO, source("x-sonos-http:sonos%3a820cb4f7e009-DZR%3a28%3amiddle%3a154250.mp4?sid=303&flags=0&sn=1"))
        assertEquals(Source.SONOS_RADIO, source("x-sonosapi-radio:sonos%3a154250?sid=303&flags=0&sn=1"))
        assertEquals(Source.OTHER, source("x-sonos-http:track.mp4?sid=3030&flags=0"))
    }

    /** A stream's own title that's only its link's file name isn't shown; a station's name is, even one in the link. */
    @Test
    fun linkTitles() {
        val link = "x-rincon-mp3radio://https://regiocast.streamabc.net/regc-90s90strance-mp3-192-2383645?sABC=6nro95ps%230"
        assertEquals(true, linkTitle("regc-90s90strance-mp3-192-2383645?sABC=6nro95ps#0#4qp100", link))
        assertEquals(false, linkTitle("radio", link)) // Part of mp3radio, but not the file name.
        assertEquals(false, linkTitle("90s90s Trance", link))
        assertEquals(false, linkTitle("", "x-rincon-mp3radio://icecast.example/"))
    }

    /** A music service by its number in a link, and its name from the speakers' list, as a Beam Ultra lists them. */
    @Test
    fun serviceNumbersAndNames() {
        assertEquals("31", serviceId("x-sonos-http:track%3a6112208%3a5.mp3?sid=31&flags=8232&sn=6")) // Qobuz.
        assertEquals("9", serviceId("x-sonos-spotify:spotify:track:abc?sid=9&flags=0&sn=2"))
        assertEquals(null, serviceId("x-rincon-mp3radio://stream.example.com/live?sid=31")) // Not a service's link.
        assertEquals(null, serviceId("x-sonos-http:t.mp4?sid=31x"))
        val list = "<Services SchemaVersion=\"1\"><Service Id=\"31\" Name=\"Qobuz\" Version=\"1.1\" Uri=\"https://q\">" +
            "<Policy Auth=\"AppLink\"/></Service><Service Name=\"CBC Radio &amp; Music\" Id=\"256\"/></Services>"
        assertEquals(mapOf("31" to "Qobuz", "256" to "CBC Radio & Music"), serviceNames(list))
    }

    /** The speaker's own state in a stream's text, as after Play on 80s80s Dance: no song to show. */
    @Test
    fun speakerStates() {
        assertEquals(true, speakerState("ZPSTR_CONNECTING"))
        assertEquals(true, speakerState("ZPSTR_BUFFERING"))
        assertEquals(false, speakerState("Erasure - Who Needs Love Like That"))
        assertEquals(false, speakerState("80s80s Dance"))
    }

    @Test
    fun local() {
        assertEquals(Source.LIBRARY, source("x-file-cifs://192.168.8.2/podcasts/+Archive/Episode.mp3"))
        assertEquals(Source.LINE_IN, source("x-rincon-stream:RINCON_74CA60B5620801400"))
        assertEquals(Source.SERVER, source("http://192.168.8.2:9790/minimserver/*/Music/Album/01.flac"))
        assertEquals(Source.OTHER, source(""))
    }
}

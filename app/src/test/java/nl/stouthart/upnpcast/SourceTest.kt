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
    }

    @Test
    fun local() {
        assertEquals(Source.LIBRARY, source("x-file-cifs://192.168.8.2/podcasts/+Archive/Episode.mp3"))
        assertEquals(Source.LINE_IN, source("x-rincon-stream:RINCON_74CA60B5620801400"))
        assertEquals(Source.SERVER, source("http://192.168.8.2:9790/minimserver/*/Music/Album/01.flac"))
        assertEquals(Source.OTHER, source(""))
    }
}

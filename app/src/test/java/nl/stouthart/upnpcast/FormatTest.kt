package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** A track's format as the rows and Now Playing show it: one unit, spaces inside; its bit depth with a hyphen. */
class FormatTest {
    @Test
    fun format() {
        assertEquals("FLAC 44.1 kHz 16-bit", Res("", "http-get:*:audio/x-flac:*", 44100, 16, "").format())
        assertEquals("FLAC 96 kHz 24-bit", Res("", "http-get:*:audio/flac:*", 96000, 24, "").format())
        assertEquals("MP3 22.05 kHz", Res("", "http-get:*:audio/mpeg:*", 22050, 0, "").format()) // Exact, not 22.1.
        assertEquals("Lossless 44.1 kHz 16-bit", Res("", "", 44100, 16, "").format("Lossless")) // Spotify's stream.
        assertEquals("MP3", Res("", "x-file-cifs:*:audio/mpeg:*", 0, 0, "").format()) // The Music library's.
        assertEquals("DSF 2.8 MHz 1-bit", Res("", "http-get:*:audio/x-dsf:*", 2822400, 1, "").format())
        assertEquals("MP3", Res("", "x-rincon-mp3radio:*:*:*", 0, 0, "").format()) // A radio stream: by its scheme.
        assertEquals("", Res("", "x-sonosapi-hls:*:*:*", 0, 0, "").format()) // Not known: no * shown.
        assertEquals("", Res("", "sonos.com-spotify:*:audio/x-spotify:*", 0, 0, "").format()) // The service, no format.
    }

    /** Of the sizes a server offers a cover in, the largest: the thumbnail loses. */
    @Test
    fun coverSizes() {
        assertEquals(true, coverSize("JPEG_MED") > coverSize("JPEG_TN"))
        assertEquals(true, coverSize("JPEG_LRG") > coverSize("JPEG_MED"))
        assertEquals(true, coverSize("PNG_SM") > coverSize("PNG_TN"))
        assertEquals(0, coverSize(null)) // A cover without a label.
    }

    /** What Now Playing shows in green: a lossless codec, or a stream that says it's lossless. */
    @Test
    fun lossless() {
        assertEquals(true, Res("", "http-get:*:audio/x-flac:*", 44100, 16, "").lossless())
        assertEquals(true, Res("", "http-get:*:audio/wav:*", 48000, 24, "").lossless())
        assertEquals(false, Res("", "x-file-cifs:*:audio/mpeg:*", 0, 0, "").lossless())
        assertEquals(false, Res("", "http-get:*:audio/mp4:*", 44100, 16, "").lossless()) // ALAC or AAC: can't tell.
        assertEquals(true, Res("", "", 44100, 16, "").lossless("Lossless")) // Spotify's stream says so.
        assertEquals(false, Res("", "", 44100, 16, "").lossless("Lossy"))
    }
}

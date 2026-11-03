package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** A track's format as the rows and Now Playing show it: one unit, spaces inside; its bit depth with a hyphen. */
class FormatTest {
    @Test
    fun format() {
        assertEquals("FLAC 44.1 kHz 16-bit", Res("", "http-get:*:audio/x-flac:*", 44100, 16, "").format())
        assertEquals("FLAC 96 kHz 24-bit", Res("", "http-get:*:audio/flac:*", 96000, 24, "").format())
        assertEquals("Lossless 44.1 kHz 16-bit", Res("", "", 44100, 16, "").format("Lossless")) // Spotify's stream.
        assertEquals("MP3", Res("", "x-file-cifs:*:audio/mpeg:*", 0, 0, "").format()) // The Music library's.
        assertEquals("DSF 2.8 MHz 1-bit", Res("", "http-get:*:audio/x-dsf:*", 2822400, 1, "").format())
    }
}

package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The position's own clock: what shows between polls, and what an answer from the room changes. Times in ms. */
class ClockTest {
    private fun playing(at: Int = 10, length: Int = 300, now: Long = 0) = Clock().apply { answer("PLAYING", at, length, now) }

    @Test
    fun countsOnWhilePlaying() {
        val c = playing()
        assertEquals(10, c.shown(0))
        assertEquals(11, c.shown(500)) // Started in that second's middle.
        assertEquals(12, c.shown(1500))
        assertEquals(500, c.untilNext(0))
    }

    @Test
    fun standsWhilePaused() {
        val c = Clock().apply { answer("PAUSED_PLAYBACK", 10, 300, 0) }
        assertEquals(10, c.shown(0))
        assertEquals(10, c.shown(5000))
    }

    @Test
    fun noStepBack() {
        val c = playing()
        // At 12.9 s the speaker says 12: it rounds down, so the clock is right and stays.
        assertFalse(c.answer("PLAYING", 12, 300, 2400).moved)
        assertEquals(13, c.shown(2500))
    }

    @Test
    fun behindCatchesUpAheadFallsBack() {
        val behind = playing()
        assertTrue(behind.answer("PLAYING", 12, 300, 1400).moved) // 11.9 s against 12: catches up.
        assertEquals(12, behind.shown(1400))
        val ahead = playing()
        assertTrue(ahead.answer("PLAYING", 12, 300, 2900).moved) // 13.4 s against 12: back into that second.
        assertEquals(12, ahead.shown(2900))
        assertEquals(13, ahead.shown(2901))
    }

    @Test
    fun bufferingHolds() {
        val c = playing()
        assertTrue(c.answer("TRANSITIONING", 11, 300, 1000).moved)
        assertEquals(11, c.shown(1000))
        assertEquals(11, c.shown(9000))
        assertFalse(c.running)
    }

    @Test
    fun aStreamStartingClearsTheTrackAtOnce() {
        val c = playing()
        val change = c.answer("TRANSITIONING", 0, 0, 1000) // A radio station, buffering.
        assertTrue(change.length && change.moved)
        assertEquals(0, c.length)
        assertEquals(0, c.shown(1000))
    }

    @Test
    fun aSeekLands() {
        val c = playing()
        c.seek(100, 1000)
        assertEquals(100, c.shown(1000))
        assertFalse(c.answer("PLAYING", 11, 300, 1500).moved) // Still the old place, within 4 s: left out.
        assertEquals(100, c.shown(1500))
        c.answer("PLAYING", 100, 300, 2000) // Landed.
        assertTrue(c.answer("PLAYING", 50, 300, 2500).moved) // Settled: an answer counts again.
        assertEquals(50, c.shown(2500))
    }

    @Test
    fun anotherTrackWhileASeekLands() {
        val c = playing()
        c.seek(100, 1000)
        val change = c.answer("PLAYING", 0, 200, 1500)
        assertTrue(change.length && change.moved)
        assertEquals(200, c.length)
        assertEquals(0, c.shown(1500))
    }

    @Test
    fun previousAtOnce() {
        val c = playing(at = 60)
        c.restart(1000)
        assertEquals(0, c.shown(1000))
        assertFalse(c.answer("TRANSITIONING", 61, 300, 1200).moved) // The old place, while it lands.
        assertEquals(0, c.shown(1200))
        val stream = Clock().apply { answer("PLAYING", 30, 0, 0) }
        stream.restart(1000) // No length: Previous as the speaker has it.
        assertEquals(0, stream.length)
    }

    @Test
    fun skipStaysWithinTheTrack() {
        val c = playing()
        c.skip(-30, 0)
        assertEquals(0, c.shown(0))
        c.skip(1000, 0)
        assertEquals(300, c.shown(0))
        assertEquals(300, c.shown(5000)) // Never past the end.
    }

    @Test
    fun aStreamShowsNoTime() {
        val c = Clock().apply { answer("PLAYING", 754, 0, 0) }
        assertEquals(0, c.shown(10_000))
    }
}

package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room settings: whether a room has a Sub, by its player's channel map, and a level's value as the page shows it. */
class RoomTest {
    @Test
    fun sub() {
        // The Beam Ultra and its Sub Mini, as the topology had them.
        assertTrue(hasSub("RINCON_74CA6010A47601400:LF,RF;RINCON_804AF26991A601400:SW"))
        assertTrue(hasSub(";RINCON_A:LF,RF;RINCON_B:SW,SW")) // Two subs, and an empty map in front.
        assertTrue(hasSub("RINCON_A:LF,RF;RINCON_S:LR,RR;RINCON_B:SW;"))
        assertFalse(hasSub("RINCON_A:LF,RF;RINCON_S:LR,RR")) // Surrounds alone.
        assertFalse(hasSub(";")) // The Era 300: no map at all.
        assertFalse(hasSub("RINCON_SWISS:LF,RF")) // SW in an id, not a channel.
    }

    @Test
    fun levels() {
        assertEquals("-1", signed(-1))
        assertEquals("0", signed(0))
        assertEquals("+10", signed(10))
    }
}

package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rooms; Room settings: whether a room has a Sub, by its player's channel map, and a level's value as the page shows it. */
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

    /** The household from a player's ZoneGroupState: its groups and rooms, a bonded Sub on its room, sorted by name. */
    @Test
    fun topology() {
        val state = "<ZoneGroupState><ZoneGroups>" +
            "<ZoneGroup Coordinator=\"RINCON_B\" ID=\"RINCON_B:1\">" +
            "<ZoneGroupMember UUID=\"RINCON_B\" Location=\"http://192.168.8.10:1400/xml/device_description.xml\" ZoneName=\"Living Room\" " +
            "HTSatChanMapSet=\"RINCON_B:LF,RF;RINCON_S:SW\"/>" +
            "<ZoneGroupMember UUID=\"RINCON_S\" Location=\"http://192.168.8.11:1400/xml/device_description.xml\" ZoneName=\"Living Room\" Invisible=\"1\"/>" +
            "</ZoneGroup>" +
            "<ZoneGroup Coordinator=\"RINCON_A\" ID=\"RINCON_A:2\">" +
            "<ZoneGroupMember UUID=\"RINCON_A\" Location=\"http://192.168.8.217:1400/xml/device_description.xml\" ZoneName=\"Kitchen\"/>" +
            "</ZoneGroup>" +
            "</ZoneGroups></ZoneGroupState>"
        val t = Upnp.topology(state, ::isPrivate)
        assertEquals(listOf("Kitchen", "Living Room"), t.rooms.map { it.name })
        assertEquals(2, t.groups.size)
        assertTrue(t.rooms[1].sub)
        assertEquals("192.168.8.10:1400", t.group("RINCON_B")?.host)
        assertEquals("Kitchen", t.group(null)?.name) // None chosen: the first room's.
    }
}

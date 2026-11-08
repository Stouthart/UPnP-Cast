package nl.stouthart.upnpcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A queue read over several pages holds together only when every page comes from the same revision of it. */
class QueueTest {
    private fun entry(n: Int) = Entry("Q:0/${n + 1}", "Track ${n + 1}", false, "object.item.audioItem.musicTrack", "", "", null, -1, null)

    /** The page from [start] of a queue [total] long, at revision [update], 100 at most, as Sonos sends. */
    private fun page(start: Int, total: Int, update: Long?): Upnp.Page {
        val n = minOf(100, total - start)
        return Upnp.Page((start until start + n).map(::entry), n, total, update)
    }

    @Test
    fun oneRevision() {
        val q = Sonos.pages({ page(it, 250, 7) })
        assertEquals(250, q.size)
        assertEquals(listOf("Track 1", "Track 101", "Track 250"), listOf(q[0], q[100], q[249]).map { it.title })
    }

    @Test
    fun changedBetweenPages() {
        // A track removed after the first page: the second comes from the next revision, and would skip a track.
        assertThrows(QueueChanged::class.java) { Sonos.pages({ page(it, if (it == 0) 150 else 149, if (it == 0) 7 else 8) }) }
        // Also a speaker that stops sending one after the first page.
        assertThrows(QueueChanged::class.java) { Sonos.pages({ page(it, 250, if (it == 0) 7 else null) }) }
    }

    @Test
    fun onePageOrNoRevision() {
        assertEquals(80, Sonos.pages({ page(it, 80, 3) }).size)
        assertEquals(250, Sonos.pages({ page(it, 250, null) }).size) // A speaker that never sends one: nothing to compare.
    }

    @Test
    fun eachPageAsItComes() {
        val seen = ArrayList<Pair<Int, Int>>()
        Sonos.pages({ page(it, 250, 7) }, each = { entries, total -> seen += entries.size to total })
        assertEquals(listOf(100 to 250, 100 to 250, 50 to 250), seen)
    }

    @Test
    fun stopsWhenNoLongerWanted() {
        var asked = 0
        val q = Sonos.pages({ page(it, 500, 7) }, wanted = { asked++ < 2 })
        assertEquals(200, q.size)
    }
}

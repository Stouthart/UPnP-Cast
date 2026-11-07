package nl.stouthart.upnpcast

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

private const val TAG = "UPnPCast"

/** The room's queue: the playing track marked, a tap plays a track, and Clear queue empties it. */
class QueueActivity : Activity() {
    private class Holder(v: View) {
        val art: ImageView = v.findViewById(R.id.art)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    private lateinit var player: Player // Its room is this one's, following the speakers' grouping.
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private lateinit var clear: View
    private lateinit var art: Art
    private val work = Executors.newSingleThreadExecutor() // Loads, in the order asked.
    private val sonos = Executors.newSingleThreadExecutor() // Jump and Clear queue: never waiting behind a long load.
    private var items: List<Entry> = emptyList()
    private var current = -1 // The playing track's row, or -1 while the room plays something else.
    private var rows: Room? = null // The group whose queue the rows show, once loaded; null while none is.
    private var started = false // Between onStart and onStop: a jump or clear that ends later doesn't load again.
    @Volatile
    private var gen = 0 // Drops a load that a newer one, or onStop, has overtaken; and stops it at its next page.

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()

        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(R.layout.item, parent, false).apply {
                tag = Holder(this)
                findViewById<View>(R.id.chevron).visibility = View.GONE
                findViewById<View>(R.id.more).visibility = View.GONE // The browse rows' menu; nothing to offer here yet.
            }
            val h = v.tag as Holder
            val e = items[i]
            h.name.text = e.title
            // The playing track by its colour alone: blue, set well apart from the text in both modes.
            h.name.setTextColor(getColor(if (i == current) R.color.accent_text else R.color.text))
            // And for TalkBack, which doesn't see colour; cleared on a row reused for another track.
            v.stateDescription = if (i == current) getString(R.string.current_track) else null
            h.meta.text = e.meta
            h.meta.visibility = if (e.meta.isEmpty()) View.GONE else View.VISIBLE
            art.show(h.art, e.art, R.drawable.ic_music)
            return v
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        uprightOnPhone()
        val room = Player.room(intent) ?: return finish()
        setContentView(R.layout.activity_queue)
        list = findViewById(R.id.list)
        summary = findViewById(R.id.summary)
        clear = findViewById(R.id.clear)
        art = Art((48 * resources.displayMetrics.density).toInt()) { url ->
            for (c in 0 until list.childCount) {
                val h = list.getChildAt(c).tag as? Holder ?: continue
                if (h.art.tag == url) art[url]?.let { art.fill(h.art, it) }
            }
        }
        player = Player(this).apply {
            hosts += Player.hosts(intent)
            media = true
            onOpen = { finish() } // Now Playing opened this screen: back to it, rather than a second copy.
            onPosition = { p, queued -> mark(if (queued) p.index - 1 else -1) }
            onNetwork = { if (Lan.wifi != null) load() }
            onRegroup = { load() } // Another coordinator, another queue.
            this.room = room
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, n, _ -> jump(n) }
        clear.setOnClickListener {
            val shown = rows ?: return@setOnClickListener // The group whose queue the list shows.
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_queue)
                .setMessage(R.string.clear_message)
                // Only that group's: the speakers may have been regrouped meanwhile, and the list then reloads.
                .setPositiveButton(R.string.clear_all) { _, _ -> if (shown.same(player.room)) clear(shown) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        player.start()
        load()
    }

    override fun onStop() {
        started = false
        gen++
        player.stop()
        art.pause() // load() at the next start asks again.
        super.onStop()
    }

    // The volume buttons set the room's volume while this screen is in front.
    override fun dispatchKeyEvent(e: KeyEvent) = player.key(e) || super.dispatchKeyEvent(e)

    override fun onDestroy() {
        art.stop()
        player.destroy()
        work.shutdown()
        sonos.shutdown() // A command already sent still finishes: the user asked for it.
        super.onDestroy()
    }

    // The app's context: a message that lands after the screen closed doesn't keep the screen alive.

    private fun mark(i: Int) {
        if (i == current) return
        current = i
        adapter.notifyDataSetChanged()
    }

    private fun load() {
        val room = player.room ?: return
        val g = ++gen
        // Another group's queue: the old rows go at once, and Clear queue with them, so nothing acts on them; the same
        // group's stay while it's read again, without a flash of Loading.
        if (!room.same(rows)) {
            rows = null
            items = emptyList()
            current = -1
            clear.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
        if (items.isEmpty()) summary(getString(R.string.loading), R.color.text2)
        art.retry() // Covers that failed, as without WiFi, get another go when the rows show again.
        later(work) {
            val q = try {
                Sonos.queue(room, { player.cover(room, it) }) { g == gen }
            } catch (e: Exception) {
                Log.i(TAG, "Queue of ${room.name}: $e")
                null
            }
            q?.forEach { it.meta = meta(resources, it) }
            runOnUiThread {
                if (g != gen) return@runOnUiThread
                if (q == null) return@runOnUiThread summary(unreachable(room.name), R.color.warning)
                items = q
                rows = room
                summary(resources.getQuantityString(R.plurals.track_count, q.size, q.size), R.color.success)
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** The count line, in [color]: success with the queue in, warning when the room didn't answer, grey while loading. */
    private fun summary(text: String, color: Int) {
        summary.text = text
        summary.setTextColor(getColor(color))
    }

    private fun jump(n: Int) {
        val room = player.room ?: return
        val e = items[n]
        mark(n) // At once; the player's next answer confirms it.
        later(sonos) {
            try {
                // A jump goes to a position, and the queue may have changed elsewhere since it was read: first the track
                // there is checked to be this one; if it isn't, the list is read again instead (~15 ms).
                val now = Sonos.queueItem(room, n) { player.cover(room, it) }
                if (now == null || now.title != e.title || now.artist != e.artist || now.album != e.album) {
                    runOnUiThread { if (started) load() }
                    return@later
                }
                Sonos.jump(room, n + 1)
            } catch (x: Exception) {
                Log.i(TAG, "Jump to ${n + 1} on ${room.name}: $x")
                runOnUiThread { toast(getString(R.string.cant_play, e.title)) }
            }
            player.refresh()
        }
    }

    private fun clear(room: Room) {
        later(sonos) {
            try {
                Sonos.clear(room)
            } catch (x: Exception) {
                Log.i(TAG, "Clear on ${room.name}: $x")
                runOnUiThread { toast(getString(R.string.clear_error)) }
            }
            runOnUiThread { if (started) load() }
            player.refresh()
        }
    }
}

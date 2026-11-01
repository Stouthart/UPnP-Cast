package nl.stouthart.upnpcast

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

private const val TAG = "UPnPCast"

/** The room's queue: the playing track marked, a tap plays a track, and Clear queue empties it. */
class QueueActivity : Activity() {
    private class Holder(v: View) {
        val art: ImageView = v.findViewById(R.id.art)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    private lateinit var room: Room
    private lateinit var player: Player
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private lateinit var clear: View
    private lateinit var art: Art
    private val work = Executors.newSingleThreadExecutor() // Loads and commands, in the order asked.
    private var items: List<Entry> = emptyList()
    private var current = -1 // The playing track's row, or -1 while the room plays something else.
    private var gen = 0 // Drops a load that a newer one, or onStop, has overtaken.

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
            // Bars as well as the colour: in light mode the accent navy is close to the text's near-black.
            val now = i == current
            h.name.setTextColor(getColor(if (now) R.color.accent else R.color.text))
            h.name.setCompoundDrawablesRelativeWithIntrinsicBounds(if (now) R.drawable.ic_playing else 0, 0, 0, 0)
            h.meta.text = e.meta
            h.meta.visibility = if (e.meta.isEmpty()) View.GONE else View.VISIBLE
            art.show(h.art, e.art, R.drawable.ic_music)
            return v
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        room = Player.room(intent) ?: return finish()
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
            this.room = this@QueueActivity.room
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, n, _ -> jump(n) }
        clear.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_queue)
                .setMessage(R.string.clear_message)
                .setPositiveButton(R.string.clear_all) { _, _ -> clear() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        player.start()
        load()
    }

    override fun onStop() {
        gen++
        player.stop()
        super.onStop()
    }

    override fun onDestroy() {
        art.stop()
        player.destroy()
        work.shutdown()
        super.onDestroy()
    }

    // The app's context: a message that lands after the screen closed doesn't keep the screen alive.
    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    private fun mark(i: Int) {
        if (i == current) return
        current = i
        adapter.notifyDataSetChanged()
    }

    private fun load() {
        val g = ++gen
        if (items.isEmpty()) summary.setText(R.string.loading)
        art.retry() // Covers that failed, as without WiFi, get another go when the rows show again.
        later(work) {
            val q = try {
                Sonos.queue(room) { player.cover(room, it) }
            } catch (e: Exception) {
                Log.i(TAG, "Queue of ${room.name}: $e")
                null
            }
            q?.forEach { it.meta = meta(resources, it) }
            runOnUiThread {
                if (g != gen) return@runOnUiThread
                if (q == null) {
                    summary.text = unreachable(room.name)
                    return@runOnUiThread
                }
                items = q
                summary.text = resources.getQuantityString(R.plurals.track_count, q.size, q.size)
                clear.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun jump(n: Int) {
        val e = items[n]
        mark(n) // At once; the player's next answer confirms it.
        later(work) {
            try {
                Sonos.jump(room, n + 1)
            } catch (x: Exception) {
                Log.i(TAG, "Jump to ${n + 1} on ${room.name}: $x")
                runOnUiThread { toast(getString(R.string.cant_play, e.title)) }
            }
            player.refresh()
        }
    }

    private fun clear() {
        later(work) {
            try {
                Sonos.clear(room)
            } catch (x: Exception) {
                Log.i(TAG, "Clear on ${room.name}: $x")
                runOnUiThread { toast(getString(R.string.clear_error)) }
            }
            runOnUiThread { if (!isDestroyed) load() }
            player.refresh()
        }
    }
}

package nl.stouthart.upnpcast

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.Executors

private const val TAG = "UPnPCast"
private const val PAGE = 200 // The first page shows at once; the rest follow while the list is already usable.
private const val MAX_ITEMS = 50_000 // A server that never stops paging can't fill memory.
private const val MAX_QUEUE = 500 // Tracks per command: an album at once, and a long list in a window from the tap.
private const val KEY_TILES = "tiles" // The list or tiles, for every server and level.
private const val TILE_DP = 150 // The tile size aimed at, rounded to whole columns: 4 on the Astra, 3 on the S25.
private const val GAP_DP = 12

/** One server's tree, a level at a time; Back goes up a level, and leaves at the top. */
class BrowseActivity : Activity() {
    companion object {
        const val CONTROL = "control"
        const val HOST = "host"
        const val NAME = "name"
        const val ROOM_ID = "room_id"
        const val ROOM_NAME = "room_name"
        const val ROOM_HOST = "room_host"
    }

    private class Level(val id: String, val title: String) {
        val items = ArrayList<Entry>()
        var total = -1 // Unknown until the first page.
        var error: String? = null
        var resume = 0 // Where paging stopped at the error: a retry goes on from there.
        var alive = true // False once Back leaves it: its remaining pages are not fetched.
        var pos = 0 // The scroll position, restored on the way back up.
        var top = 0
    }

    private class Holder(v: View) {
        val art: ImageView = v.findViewById(R.id.art)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val chevron: View? = v.findViewById(R.id.chevron) // A list row's; tiles have none.
        val more: View = v.findViewById(R.id.more)
    }

    private lateinit var server: Server
    private var room: Room? = null // Where tracks play; null when no Sonos was found.
    private val sonos = Executors.newSingleThreadExecutor() // One command at a time: queue edits mustn't interleave.
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var list: GridView
    private lateinit var viewType: ImageView
    private lateinit var art: Art // List thumbnails.
    private var tileArt: Art? = null // Tile covers, decoded at the tile's size; made when tiles first show.
    private var tiles = false
    private var cell = 0 // A tile cover's side, in px.
    private val prefs by lazy { getSharedPreferences("view", MODE_PRIVATE) }
    private lateinit var player: Player
    private val pool = Executors.newCachedThreadPool()
    private val stack = ArrayList<Level>()
    private val level get() = stack.last()
    private val back = OnBackInvokedCallback { up() }
    private var backing = false // The callback is registered: there is a level to go up to.

    private val adapter = object : BaseAdapter() {
        override fun getCount() = if (stack.isEmpty()) 0 else level.items.size // setAdapter asks before the first level.
        override fun getItem(i: Int) = level.items[i]
        override fun getItemId(i: Int) = i.toLong()

        // Views of the other layout never come back here: switching sets the adapter again, which drops them.
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val v = convert ?: layoutInflater.inflate(if (tiles) R.layout.cell else R.layout.item, parent, false).apply {
                tag = Holder(this)
                if (tiles) findViewById<View>(R.id.art).layoutParams.height = cell
            }
            val h = v.tag as Holder
            val e = level.items[i]
            h.name.text = e.title
            h.meta.text = e.meta
            // A list row hides an empty line, else the title sits off-centre; a tile keeps it, so a row of tiles lines up.
            if (!tiles) h.meta.visibility = if (e.meta.isEmpty()) View.GONE else View.VISIBLE
            h.chevron?.visibility = if (e.container) View.VISIBLE else View.GONE
            // ⋯ only where the menu has tracks to queue: a track, or an album (a category like Artist holds none).
            val queueable = if (e.container) e.cls.startsWith("object.container.album") else e.playable
            h.more.visibility = if (queueable) View.VISIBLE else View.GONE
            h.more.setOnClickListener { menu(it, e) }
            (if (tiles) tileArt!! else art).show(h.art, e.art, if (e.container) R.drawable.ic_folder else R.drawable.ic_music)
            v.alpha = if (e.container || e.playable) 1f else 0.5f
            return v
        }
    }

    /** Puts a thumbnail that has just arrived in the rows showing it, when they belong to [a]'s layout. */
    private fun loaded(a: Art, url: String) {
        if ((a === tileArt) != tiles) return
        for (c in 0 until list.childCount) {
            val h = list.getChildAt(c).tag as? Holder ?: continue
            if (h.art.tag == url) a[url]?.let { a.fill(h.art, it) }
        }
    }

    /** The list, or tiles in as many columns as the list's width holds; also on a new width, as after rotating. */
    private fun layout(t: Boolean) {
        val d = resources.displayMetrics.density
        val pos = list.firstVisiblePosition
        if (t) {
            val gap = (GAP_DP * d).toInt()
            val w = if (list.width > 0) list.width else resources.displayMetrics.widthPixels - (32 * d).toInt()
            val cols = maxOf(2, Math.round(w / (TILE_DP * d)))
            val side = (w - (cols - 1) * gap) / cols - (20 * d).toInt() // Less the tile's own padding.
            if (tiles && cols == list.numColumns && side == cell) return // As after the first layout: nothing to redo.
            tiles = true
            list.numColumns = cols
            list.horizontalSpacing = gap
            list.verticalSpacing = gap
            cell = side
            // Covers decoded for smaller tiles would show soft once the tiles grow, as on turning a tablet.
            if (tileArt.let { it == null || cell > it.px * 5 / 4 }) {
                tileArt?.stop()
                tileArt = Art(cell) { url -> loaded(tileArt ?: return@Art, url) }
            }
        } else {
            tiles = false
            list.numColumns = 1
            list.verticalSpacing = (8 * d).toInt()
        }
        viewType.setImageResource(if (t) R.drawable.ic_list else R.drawable.ic_grid)
        list.adapter = adapter
        list.setSelection(pos)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val i = intent
        server = Server(
            "", i.getStringExtra(NAME) ?: "",
            i.getStringExtra(HOST) ?: return finish(),
            i.getStringExtra(CONTROL) ?: return finish(), null,
        )
        val id = i.getStringExtra(ROOM_ID)
        val host = i.getStringExtra(ROOM_HOST)
        if (id != null && host != null) room = Room(id, i.getStringExtra(ROOM_NAME) ?: "", host)
        setContentView(R.layout.activity_browse)
        title = findViewById(R.id.title)
        summary = findViewById(R.id.summary)
        list = findViewById(R.id.list)
        viewType = findViewById(R.id.view_type)
        art = Art((48 * resources.displayMetrics.density).toInt()) { url -> loaded(art, url) }
        layout(prefs.getBoolean(KEY_TILES, false))
        findViewById<View>(R.id.home).setOnClickListener { finish() } // The opening screen is right below.
        viewType.setOnClickListener {
            layout(!tiles)
            prefs.edit().putBoolean(KEY_TILES, tiles).apply()
        }
        list.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (tiles && r - l != or - ol) list.post { layout(true) } // Not inside the layout pass itself.
        }
        list.setOnItemClickListener { _, _, n, _ -> click(level.items[n]) }
        player = Player(this).apply {
            hosts += server.host
            room = this@BrowseActivity.room
            onNetwork = { retry() }
        }
        open(Level("0", server.name))
    }

    override fun onStart() {
        super.onStart()
        player.start()
        retry() // WiFi may have come back while the screen was away.
    }

    override fun onStop() {
        player.stop()
        super.onStop()
    }

    override fun onDestroy() {
        for (l in stack) l.alive = false
        art.stop()
        tileArt?.stop()
        player.destroy()
        pool.shutdownNow()
        sonos.shutdown() // A command already sent still finishes: the user asked for it.
        super.onDestroy()
    }

    // The app's context: a message that lands after the screen closed doesn't keep the screen alive.
    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    /** A container opens; a track plays with the rest of this level queued around it, as in an album. */
    private fun click(e: Entry) {
        if (e.container) return open(Level(e.id, e.title))
        val r = e.res ?: return
        if (!e.playable) return toast(getString(R.string.unsupported_rate, e.title, r.hz.toString()))
        val tracks = level.items.filter { !it.container && it.playable }
        val k = tracks.indexOf(e)
        if (tracks.size <= MAX_QUEUE) send(tracks, How.REPLACE, k, e.title)
        else send(tracks.subList(k, minOf(k + MAX_QUEUE, tracks.size)), How.REPLACE, 0, e.title)
    }

    /** The Sonos app's queue choices, for a track or for all of a container's tracks. */
    private fun menu(v: View, e: Entry) {
        if (!e.container && !e.playable) return click(e) // Says why it can't play.
        val ways = arrayOf(How.NOW, How.NEXT, How.END, How.REPLACE)
        val names = intArrayOf(R.string.play_now, R.string.play_next, R.string.add_end, R.string.replace_queue)
        PopupMenu(this, v).apply {
            for (n in ways.indices) menu.add(0, n, n, names[n])
            setOnMenuItemClickListener { m ->
                if (!e.container) send(listOf(e), ways[m.itemId], 0, e.title)
                else later(pool) {
                    val tracks = runCatching { Upnp.tracks(server, e.id, MAX_QUEUE) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread // Left while the album's tracks loaded.
                        if (tracks.isNullOrEmpty()) toast(getString(R.string.cant_play, e.title))
                        else send(tracks, ways[m.itemId], 0, e.title)
                    }
                }
                true
            }
            show()
        }
    }

    /** Sends one queue command to the room, off the main thread; only a failure shows. */
    private fun send(tracks: List<Entry>, how: How, start: Int, what: String) {
        val r = room ?: return toast(getString(R.string.no_rooms))
        later(sonos) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                Sonos.play(r, tracks, how, start)
                Log.i(TAG, "$how ${tracks.size} tracks on ${r.name} in ${SystemClock.elapsedRealtime() - t0} ms")
                player.refresh()
            } catch (x: HttpException) {
                Log.i(TAG, "$how on ${r.name}: $x")
                runOnUiThread { toast(getString(R.string.cant_play, what)) }
            } catch (x: Exception) {
                Log.i(TAG, "$how on ${r.name}: $x")
                runOnUiThread { toast(if (Lan.wifi == null) getString(R.string.no_wifi) else getString(R.string.cant_play_reach, what, r.name)) }
            }
        }
    }

    private fun open(l: Level) {
        if (stack.isNotEmpty()) {
            level.pos = list.firstVisiblePosition
            level.top = list.getChildAt(0)?.top ?: 0
        }
        stack += l
        show()
        list.setSelection(0)
        load(l)
    }

    private fun up() {
        stack.removeAt(stack.size - 1).alive = false
        show()
        list.setSelectionFromTop(level.pos, level.top)
    }

    private fun show() {
        title.text = level.title
        summary()
        adapter.notifyDataSetChanged()
        val want = stack.size > 1
        if (want == backing) return
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        backing = want
    }

    private fun summary() {
        val l = level
        summary.text = when {
            l.error != null -> l.error
            l.total < 0 -> getString(R.string.loading)
            l.items.isNotEmpty() && l.items.none { it.container } -> resources.getQuantityString(R.plurals.track_count, l.total, l.total)
            else -> resources.getQuantityString(R.plurals.item_count, l.total, l.total)
        }
    }

    /** With WiFi, once it came back or the screen shows again: the levels and covers that failed, once more. */
    private fun retry() {
        if (Lan.wifi == null) return
        if (art.retry() or (tileArt?.retry() == true)) adapter.notifyDataSetChanged()
        val shown = level.error != null
        for (l in stack) if (l.error != null) {
            l.error = null
            load(l, l.resume)
        }
        if (shown) summary() // Loading…, or the count so far when paging stopped halfway.
    }

    /** Fetches [l]'s pages in order from [from], each shown as it arrives, until all are in or Back has left the level. */
    private fun load(l: Level, from: Int = 0) = later(pool) {
        var start = from
        while (l.alive) {
            val page = try {
                Upnp.browse(server, l.id, start, PAGE)
            } catch (e: Exception) {
                Log.i(TAG, "Browse ${l.id} at $start: $e")
                val at = start
                runOnUiThread {
                    l.error = unreachable(server.name)
                    l.resume = at
                    if (l === level) summary()
                }
                return@later
            }
            for (e in page.entries) e.meta = meta(resources, e)
            runOnUiThread {
                if (!l.alive) return@runOnUiThread
                l.items += page.entries
                l.total = page.total
                if (l === level) {
                    adapter.notifyDataSetChanged()
                    summary()
                }
            }
            start += page.returned
            if (page.returned == 0 || start >= page.total || start >= MAX_ITEMS) return@later
        }
    }
}

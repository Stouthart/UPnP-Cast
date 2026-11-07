package nl.stouthart.upnpcast

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.text.style.ForegroundColorSpan
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.concurrent.Executors

private const val TAG = "UPnPCast"
private const val PAGE = 200 // The first page shows at once; the rest follow while the list is already usable.
private const val MAX_ITEMS = 50_000 // A server that never stops paging can't fill memory.
private const val MAX_PAGES = 1000 // Nor keep it going with a few entries a page.
private const val MAX_QUEUE = 500 // Tracks per command: an album at once, and a long list in a window from the tap.
private const val KEY_TILES = "tiles" // The list or tiles, for every server and level.
private const val TILE_DP = 150 // The tile size aimed at, rounded to whole columns: 4 on the Astra, 3 on the S25.
private const val GAP_DP = 12
private const val SCAN_POLL_MS = 1000L // How often a scan is checked; the speaker answers in ~10 ms.

/** One server's tree, a level at a time; Back goes up a level, and leaves at the top. */
class BrowseActivity : Activity() {
    companion object {
        const val CONTROL = "control"
        const val HOST = "host"
        const val NAME = "name"
        const val LIBRARY = "library"
        const val FAVORITES = "favorites"
        const val ROOM_ID = "room_id"
        const val ROOM_NAME = "room_name"
        const val ROOM_HOST = "room_host"
        const val ROOM_MEMBERS = "room_members"
        const val ROOM_OWN = "room_own"
        const val ROOM_PLAYERS = "room_players"
    }

    /** One level of the server's tree; [album]: an album's own, whose tracks show their numbers. */
    private class Level(val id: String, val title: String, val album: Boolean = false) {
        val items = ArrayList<Entry>()
        var discs = false // An album with tracks on more than one disc: their numbers name the disc too.
        var total = -1 // Unknown until the first page.
        var error: String? = null
        var resume = 0 // Where paging stopped, at an error or when the screen did: it goes on from there.
        var alive = true // False once Back leaves it: its remaining pages are not fetched.
        var paging = false // A load is out for it (main thread only).
        var done = false // All its pages are in.
        var pos = 0 // The scroll position, restored on the way back up.
        var top = 0
    }

    private val outset by lazy { (2 * resources.displayMetrics.density).toInt() }
    private val inset by lazy { (5 * resources.displayMetrics.density).toInt() }

    private class Holder(v: View) {
        val art: ImageView = v.findViewById(R.id.art)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val chevron: View? = v.findViewById(R.id.chevron) // A list row's; tiles have none.
        val more: View = v.findViewById(R.id.more)
    }

    private lateinit var server: Server
    private val sonos = Executors.newSingleThreadExecutor() // One command at a time: queue edits mustn't interleave.
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var list: GridView
    private lateinit var play: View
    private lateinit var scan: ImageView
    private var scanning = false // A scan of the Music library is being followed: main thread only.
    @Volatile
    private var started = false // The screen shows: a scan is followed only then, nothing runs behind it.
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
            // In an album, a track's number before its title, in grey, as a row and as a tile: the server's, from the
            // files' tags, so an album with tracks missing keeps the numbers it has; the disc too when it has several.
            h.name.text = if (level.album && !e.container && e.track > 0) {
                val n = if (level.discs && e.disc > 0) "${e.disc}-${e.track}. " else "${e.track}. "
                SpannableString(n + e.title).apply { setSpan(ForegroundColorSpan(getColor(R.color.text2)), 0, n.length, 0) }
            } else {
                e.title
            }
            // A row greyed out above 48 kHz has its format in the warning colour, at full strength: why it can't play.
            val high = !tiles && !e.container && (e.res?.hz ?: 0) > MAX_HZ
            // A tile is too narrow for a track's artist and format as well: its length alone; a row has the room. A
            // favourite shows its service.
            h.meta.text = when {
                tiles && !e.container && e.fav == null -> e.res?.time().orEmpty()
                high -> SpannableString(e.meta).apply {
                    val f = e.res!!.format()
                    val at = e.meta.lastIndexOf(f)
                    if (at >= 0) setSpan(ForegroundColorSpan(getColor(R.color.warning)), at, at + f.length, 0)
                }
                else -> e.meta
            }
            // A list row hides an empty line, else the title sits off-centre; a tile keeps it, so a row of tiles lines up.
            if (!tiles) h.meta.visibility = if (e.meta.isEmpty()) View.GONE else View.VISIBLE
            h.chevron?.visibility = if (e.container) View.VISIBLE else View.GONE
            // ⋯ only where the menu has tracks to queue: a track, or an album (a category like Artist holds none).
            val queueable = e.fav == null && if (e.container) e.cls.startsWith("object.container.album") else e.playable
            h.more.visibility = if (queueable) View.VISIBLE else View.GONE
            // A list row's last glyph ends where a folder's › does, and the Play circle: a track's dots stop 10dp
            // inside their 24dp, so 2dp further out, into the row's padding. An album's dots, before its ›, end where
            // the header's Home does, 5dp further in: two columns, Home over ⋯ and Grid/List over ›.
            if (!tiles) (h.more.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = if (e.container) inset else -outset
            h.more.setOnClickListener { menu(it, e) }
            (if (tiles) tileArt!! else art).show(h.art, e.art, if (e.container) R.drawable.ic_folder else R.drawable.ic_music)
            val dim = if (e.container || e.playable) 1f else 0.5f
            h.art.alpha = dim
            h.name.alpha = dim
            h.meta.alpha = if (high) 1f else dim
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
            val w = if (list.width > 0) list.width - list.paddingLeft - list.paddingRight
            else resources.displayMetrics.widthPixels - (32 * d).toInt()
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
        uprightOnPhone()
        val i = intent
        server = Server(
            "", i.getStringExtra(NAME) ?: "",
            i.getStringExtra(HOST) ?: return finish(),
            i.getStringExtra(CONTROL) ?: return finish(), null, i.getBooleanExtra(LIBRARY, false),
            i.getBooleanExtra(FAVORITES, false),
        )
        setContentView(R.layout.activity_browse)
        title = findViewById(R.id.title)
        summary = findViewById(R.id.summary)
        list = findViewById(R.id.list)
        play = findViewById(R.id.play)
        scan = findViewById(R.id.scan)
        viewType = findViewById(R.id.view_type)
        art = Art((48 * resources.displayMetrics.density).toInt()) { url -> loaded(art, url) }
        layout(prefs.getBoolean(KEY_TILES, false))
        play.setOnClickListener { tracks().takeIf { it.isNotEmpty() }?.let { send(it.take(MAX_QUEUE), How.REPLACE, 0, level.title) } }
        scan.setOnClickListener { scan() }
        findViewById<View>(R.id.home).setOnClickListener { finish() } // The opening screen is right below.
        viewType.setOnClickListener {
            layout(!tiles)
            prefs.edit().putBoolean(KEY_TILES, tiles).apply()
        }
        list.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (tiles && r - l != or - ol) list.post { layout(true) } // Not inside the layout pass itself.
        }
        // A new width, as when Play shows or hides, or after rotating: the title's size again.
        title.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) title.post { fit() } }
        list.setOnItemClickListener { _, _, n, _ -> click(level.items[n]) }
        player = Player(this).apply {
            hosts += server.host
            hosts += Player.hosts(i) // The main screen's servers too: the track playing may be another server's.
            room = Player.room(i) // Where tracks play, following the speakers' grouping; null when no Sonos was found.
            onNetwork = { retry() }
        }
        open(
            when {
                server.favorites -> Level("FV:2", server.name)
                server.library -> sections()
                else -> Level("0", server.name)
            },
        )
    }

    /**
     * The Music library's top level, as the Sonos app shows it: the player's own root names its parts Attributes
     * and Music Shares, and holds the queues, favorites and radio as well.
     */
    private fun sections() = Level("0", server.name).apply {
        val ids = arrayOf("A:ALBUMARTIST", "A:ALBUM", "A:COMPOSER", "A:GENRE", "A:TRACKS", "A:PLAYLISTS", "S:")
        val names = intArrayOf(
            R.string.artists, R.string.albums, R.string.composers, R.string.genres, R.string.songs,
            R.string.imported_playlists, R.string.folders,
        )
        for (n in ids.indices) items += Entry(ids[n], getString(names[n]), true, "object.container", "", "", null, -1, null)
        total = items.size
        done = true
    }

    override fun onStart() {
        super.onStart()
        started = true
        player.start()
        retry() // WiFi may have come back while the screen was away.
    }

    override fun onStop() {
        started = false // Paging stops after the page it's on; retry() at the next start goes on from there.
        player.stop()
        art.pause()
        tileArt?.pause()
        super.onStop()
    }

    // The volume buttons set the room's volume while this screen is in front.
    override fun dispatchKeyEvent(e: KeyEvent) = player.key(e) || super.dispatchKeyEvent(e)

    override fun onDestroy() {
        for (l in stack) l.alive = false
        art.stop()
        tileArt?.stop()
        player.destroy()
        pool.shutdownNow()
        sonos.shutdown() // A command already sent still finishes: the user asked for it.
        super.onDestroy()
    }

    /**
     * The Music library's Scan for new content, as in the Sonos app: one that already runs is followed, not restarted.
     * The speaker scans by itself; the screen follows it while it shows, and says when it's done.
     */
    private fun scan() {
        if (scanning) return
        scanning = true
        scan.isEnabled = false
        // Blue while it scans, as Shuffle while it's on; grey again once done, as Browse's other buttons.
        scan.imageTintList = ColorStateList.valueOf(getColor(R.color.accent_text))
        summary()
        later(pool) {
            val done = runCatching {
                if (!Sonos.scanning(server.control)) Sonos.scan(server.control)
                do Thread.sleep(SCAN_POLL_MS) while (started && Sonos.scanning(server.control))
                started // Else the screen left first: no word on it.
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                scanning = false
                scan.isEnabled = true
                scan.imageTintList = null
                summary()
                done.onSuccess { if (it) toast(getString(R.string.scan_complete)) }.onFailure {
                    Log.i(TAG, "Scan on ${server.host}: $it")
                    toast(if (Lan.wifi == null) getString(R.string.no_wifi) else getString(R.string.scan_error))
                }
            }
        }
    }

    /** The level's tracks that can play: what a tap queues around itself, and what Play plays from the top. */
    private fun tracks() = level.items.filter { !it.container && it.playable }

    /** A container opens; a track plays with the rest of this level queued around it, as in an album; a favourite plays. */
    private fun click(e: Entry) {
        e.fav?.let { return if (e.playable) favorite(e, it) else toast(getString(R.string.shortcut, e.title)) }
        if (e.container) return open(Level(e.id, e.title, e.cls.startsWith("object.container.album")))
        val r = e.res ?: return
        if (!e.playable) return toast(getString(R.string.unsupported_rate, e.title, khz(r.hz), khz(MAX_HZ)))
        val tracks = tracks()
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
                else {
                    // The group chosen for, while the album's tracks load: regrouped meanwhile, Replace queue would
                    // empty another group's queue, so then nothing goes.
                    val target = player.room
                    later(pool) {
                        val tracks = runCatching { Upnp.tracks(server, e.id, MAX_QUEUE) }.getOrNull()
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread // Left while the album's tracks loaded.
                            when {
                                tracks.isNullOrEmpty() -> toast(getString(R.string.cant_play, e.title))
                                !target.same(player.room) -> toast(getString(R.string.regrouped))
                                else -> send(tracks, ways[m.itemId], 0, e.title)
                            }
                        }
                    }
                }
                true
            }
            show()
        }
    }

    /** Plays favourite [f] in the room. */
    private fun favorite(e: Entry, f: Favorite) = command(e.title) { Sonos.favorite(it, f) }

    /** Sends one queue command to the room. */
    private fun send(tracks: List<Entry>, how: How, start: Int, what: String) = command(what) { r ->
        val t0 = SystemClock.elapsedRealtime()
        Sonos.play(r, tracks, how, start)
        Log.i(TAG, "$how ${tracks.size} tracks on ${r.name} in ${SystemClock.elapsedRealtime() - t0} ms")
    }

    /** Runs [work] for the room off the main thread, playing [what]; only a failure shows. */
    private fun command(what: String, work: (Room) -> Unit) {
        val r = player.room ?: return toast(getString(R.string.no_rooms))
        later(sonos) {
            try {
                work(r)
                player.refresh()
            } catch (x: HttpException) {
                Log.i(TAG, "$what on ${r.name}: $x")
                runOnUiThread { toast(getString(R.string.cant_play, what)) }
            } catch (x: Exception) {
                Log.i(TAG, "$what on ${r.name}: $x")
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
        if (!l.done) load(l) // The Music library's sections are already there.
    }

    private fun up() {
        stack.removeAt(stack.size - 1).alive = false
        show()
        list.setSelectionFromTop(level.pos, level.top)
    }

    private fun show() {
        title.text = level.title
        summary()
        fit()
        adapter.notifyDataSetChanged()
        val want = stack.size > 1
        if (want == backing) return
        if (want) onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, back)
        else onBackInvokedDispatcher.unregisterOnBackInvokedCallback(back)
        backing = want
    }

    /**
     * The title at the largest size, from the Title style's 20sp down to 16, at which it fits its two lines; a longer
     * one ends in "…" at 16. In code: autosize keeps a shrunk size when a wrap_content title gets a shorter text.
     */
    private fun fit() {
        val w = title.width - title.paddingLeft - title.paddingRight
        if (w <= 0) return // Not laid out yet; the layout listener comes back.
        val text = title.text
        val paint = TextPaint(title.paint)
        var sp = 20
        while (sp > 16) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp.toFloat(), resources.displayMetrics)
            val lines = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setBreakStrategy(title.breakStrategy) // Breaks lines as the title itself does.
                .setHyphenationFrequency(title.hyphenationFrequency)
                .build().lineCount
            if (lines <= 2) break
            sp--
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
    }

    /** The count line, and Play once the level shows a track that can play; both follow each page and error. */
    private fun summary() {
        val l = level
        val top = server.library && stack.size == 1
        // Not for favourites: each is its own thing to play, not a level's tracks.
        play.visibility = if (!server.favorites && l.items.any { !it.container && it.playable }) View.VISIBLE else View.GONE
        scan.visibility = if (top) View.VISIBLE else View.GONE
        // The count in green once it's known; the server not answering in the warning colour; grey while loading.
        val busy = (top && scanning) || l.total < 0
        summary.setTextColor(getColor(if (busy) R.color.text2 else if (l.error != null) R.color.warning else R.color.success))
        summary.text = when {
            top && scanning -> getString(R.string.scanning)
            l.error != null -> l.error
            l.total < 0 -> getString(R.string.loading)
            server.favorites -> resources.getQuantityString(R.plurals.favorite_count, l.total, l.total)
            l.items.isNotEmpty() && l.items.none { it.container } -> resources.getQuantityString(R.plurals.track_count, l.total, l.total)
            else -> resources.getQuantityString(R.plurals.item_count, l.total, l.total)
        }
    }

    /**
     * When WiFi came back or the screen shows again: the levels and covers that failed or stopped, once more, and the
     * levels not loaded yet.
     */
    private fun retry() {
        val wifi = Lan.wifi != null
        if (wifi && (art.retry() or (tileArt?.retry() == true))) adapter.notifyDataSetChanged()
        val shown = level.error != null
        // With WiFi, the levels that failed; always, those not loaded or whose paging stopped with the screen. Without
        // WiFi that load fails at once, and the summary says why.
        for (l in stack) if ((wifi && l.error != null) || (l.error == null && !l.done && !l.paging)) {
            l.error = null
            load(l, l.resume)
        }
        if (shown) summary() // Loading…, or the count so far when paging stopped halfway.
    }

    /**
     * Fetches [l]'s pages in order from [from], each shown as it arrives, until all are in, Back has left the level,
     * or the screen stopped: nothing fetches for a screen that isn't shown, and retry() at the next start goes on.
     */
    private fun load(l: Level, from: Int = 0) {
        if (!started) return // Before the first start, as from onCreate: onStart's retry() begins it.
        l.paging = true
        var count = l.items.size // The entries in, as appended: a server's own count may say otherwise.
        later(pool) {
            var start = from
            var pages = 0
            while (l.alive) {
                if (!started) {
                    val at = start
                    runOnUiThread { paused(l, at) }
                    return@later
                }
                val page = try {
                    Upnp.browse(server, l.id, start, PAGE)
                } catch (e: Exception) {
                    Log.i(TAG, "Browse ${l.id} at $start: $e")
                    val at = start
                    runOnUiThread {
                        l.error = unreachable(server.name)
                        l.resume = at
                        l.paging = false
                        if (l === level) summary()
                    }
                    return@later
                }
                val got = page.entries.take(MAX_ITEMS - count)
                count += got.size
                for (e in got) e.meta = meta(resources, e)
                runOnUiThread {
                    if (!l.alive) return@runOnUiThread
                    l.items += got
                    l.total = page.total
                    if (l.album) l.discs = l.items.filter { !it.container && it.disc > 0 }.mapTo(HashSet()) { it.disc }.size > 1
                    if (l === level) {
                        adapter.notifyDataSetChanged()
                        summary()
                    }
                }
                // Only forward and within bounds: a page of nothing or a negative count ends it, as do MAX_ITEMS
                // entries, counted as they came, or MAX_PAGES pages, so a server can't keep it going.
                val end = page.returned <= 0 || start + page.returned >= page.total ||
                    count >= MAX_ITEMS || ++pages >= MAX_PAGES
                start += maxOf(page.returned, 0)
                if (end) {
                    runOnUiThread {
                        l.done = true
                        l.paging = false
                    }
                    return@later
                }
            }
        }
    }

    /** Paging stopped with the screen at [at]; started again before this landed, it goes on at once. */
    private fun paused(l: Level, at: Int) {
        l.resume = at
        l.paging = false
        if (started && l.alive) load(l, at)
    }
}

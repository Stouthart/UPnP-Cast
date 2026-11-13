package nl.stouthart.upnpcast

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "UPnPCast"
private const val SEARCH_MS = 2000L // Devices answer within MX (1 s); 2 s, a usual timeout on a network. As the Mac app.
private const val KEY_SONOS = "sonos" // A player's description URL: any player knows every room.
private const val KEY_SERVERS = "servers" // The servers' description URLs.
private const val MAX_DEVICES = 32 // Per search: a device answering with endless paths can't start endless fetches.
private const val SETTLE_MS = 350L // Between topology reads after a grouping change, until the speakers report it.
private const val SETTLE_TRIES = 10 // Up to 3.5 s: a hand-over with Spotify playing takes 4 s, the call itself most of it. As the Mac app.

/** A device's icon, refused when its size is beyond any icon's: the PNG comes from the network. */
private fun icon(b: ByteArray): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, o)
    return if (o.outWidth !in 1..512 || o.outHeight !in 1..512) null else BitmapFactory.decodeByteArray(b, 0, b.size)
}

// The App settings card left open, while the app runs: closed at each start, as the settings are rarely changed. The
// same in the Mac app (AppModel.settingsOpen).
private var settingsOpen = false

/** The opening screen: the Sonos rooms and the media servers on this network, and the settings. */
class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("devices", MODE_PRIVATE) }
    private val pool = Executors.newCachedThreadPool()
    private lateinit var roomsSummary: TextView
    private lateinit var rooms: LinearLayout
    private lateinit var allow: View
    private lateinit var serversSummary: TextView
    private lateinit var servers: LinearLayout
    private lateinit var player: Player
    private var gen = 0 // Drops results from a search that a newer one, or onStop, has overtaken.
    private var found: List<Room> = emptyList() // The rows: every room.
    private var household: Upnp.Topology? = null
    private var checked: Set<String> = emptySet() // The rooms that play together, by id.
    private var room: Room? = null // Their group, as the player controls it: one room, or Kitchen + 1.
    private var library: View? = null // The Music library row, kept after the servers whatever order they answer in.
    private lateinit var favoritesCard: View
    private var favorites: Server? = null // Where the favourites came from: Browse opens them there.

    @SuppressLint("InlinedApi") // ACCESS_LOCAL_NETWORK is Android 17's; before, lanAllowed() is true and it's never used.
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        uprightOnPhone()
        setContentView(R.layout.activity_main)
        roomsSummary = findViewById(R.id.rooms_summary)
        rooms = findViewById(R.id.rooms)
        allow = findViewById(R.id.allow)
        serversSummary = findViewById(R.id.servers_summary)
        servers = findViewById(R.id.servers)
        favoritesCard = findViewById(R.id.favorites_card)
        findViewById<View>(R.id.favorites_header).setOnClickListener { favorites?.let(::browse) }
        // The rooms' own settings, in a sheet; shown once a search has found rooms.
        findViewById<View>(R.id.room_settings).setOnClickListener {
            if (found.isNotEmpty()) startActivity(RoomActivity.intent(this, found))
        }
        player = Player(this)
        player.onNetwork = { search() } // Another network may hold other rooms and servers; none, and the screen says so.
        // Grouped elsewhere, as in the Sonos app: the checks follow. A change made here shows once it settled.
        player.onRegroup = { if (!settling) place(it) }
        // A favourite saved or removed with the mini player's heart: the Favorites card follows at once.
        player.onFavorites = { all -> favorites?.let { showFavorites(it, all) } }
        findViewById<TextView>(R.id.copyright).text = getString(R.string.copyright, BuildConfig.VERSION_NAME)
        allow.setOnClickListener {
            // After a second "Don't allow" Android stops asking; then only the app's settings page can grant it.
            if (shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK)) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 0)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (!lanAllowed()) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 0)
        settings()
        fold()
    }

    /**
     * The App settings card opens and closes with a tap on its title row, the whole card while closed: closed at each
     * start of the app, then as it was left while it runs ([settingsOpen]).
     */
    private fun fold() {
        val header: View = findViewById(R.id.settings_header)
        val body: View = findViewById(R.id.settings_body)
        val chevron: View = findViewById(R.id.settings_chevron)
        fun open(o: Boolean) {
            body.shown = o
            header.stateDescription = getString(if (o) R.string.expanded else R.string.collapsed)
        }
        open(settingsOpen)
        chevron.rotation = if (body.shown) 180f else 0f
        val card = body.parent as ViewGroup
        var scroll: LayoutTransition.TransitionListener? = null // An open still to scroll into view.
        header.setOnClickListener {
            val o = !body.shown
            settingsOpen = o
            scroll?.let { card.layoutTransition.removeTransitionListener(it) }
            scroll = null
            open(o)
            chevron.animate().rotation(if (o) 180f else 0f).duration = 200
            if (o) scroll = into(card) { scroll === it }.also { card.layoutTransition.addTransitionListener(it) }
        }
    }

    /**
     * Scrolls all of [card] into view, with the margin below it, once it has grown: it's the last on the screen, so
     * it opens below the fold. Not before: while the card and the screen grow, animated, a scroll stops at their
     * height so far. In view is above the mini player, which sits below the scrolling part. [current]: the open is
     * still the latest.
     */
    private fun into(card: ViewGroup, current: (LayoutTransition.TransitionListener) -> Boolean) =
        object : LayoutTransition.TransitionListener {
            private var done = false

            override fun startTransition(t: LayoutTransition, c: ViewGroup?, v: View?, type: Int) = Unit

            override fun endTransition(t: LayoutTransition, c: ViewGroup?, v: View?, type: Int) {
                if (done || type != LayoutTransition.CHANGE_APPEARING || t.isChangingLayout) return
                done = true
                card.post { t.removeTransitionListener(this) } // Not while it calls its listeners.
                if (!current(this)) return
                val gap = (16 * resources.displayMetrics.density).toInt()
                card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height + gap), false)
            }
        }

    /**
     * The Volume button step (the next press uses it), Quick skip (Now Playing, the next time it opens), the Polling
     * interval (applies at once) and Appearance (Android recreates the screens in the new mode).
     */
    private fun settings() {
        val choices = arrayOf<TextView>(findViewById(R.id.appearance_system), findViewById(R.id.appearance_light), findViewById(R.id.appearance_dark))
        // When the mode stays the same, as System default while the system is in that mode, nothing recreates: the
        // choice is filled in place.
        segments(choices, Prefs.appearance(this)) { Prefs.appearance(this, it) }
        fit(choices)

        val step: SeekBar = findViewById(R.id.step)
        val stepValue: TextView = findViewById(R.id.step_value)
        step.progress = Prefs.step(this) - 1
        stepValue.text = getString(R.string.step, step.progress + 1)
        // Saved with every change the user makes, so a key or TalkBack counts as much as a drag.
        step.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                stepValue.text = getString(R.string.step, p + 1)
                if (user) Prefs.step(this@MainActivity, p + 1)
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit

            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })

        val skip: SeekBar = findViewById(R.id.skip)
        val skipValue: TextView = findViewById(R.id.skip_value)
        fun skipped(p: Int) {
            skipValue.text = if (p == 0) getString(R.string.off) else getString(R.string.skip_step, p * 5)
            skip.stateDescription = skipValue.text // TalkBack says "±15 s", not "25 percent".
        }
        skip.progress = Prefs.skip(this) / 5
        skipped(skip.progress)
        // Saved with every change, as the Volume button step.
        skip.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                skipped(p)
                if (user) Prefs.skip(this@MainActivity, p * 5)
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit

            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })

        val poll: SeekBar = findViewById(R.id.poll)
        val value: TextView = findViewById(R.id.poll_value)
        poll.progress = Prefs.poll(this) - 1
        value.text = getString(R.string.seconds, poll.progress + 1)
        // A drag applies on release, as it restarts the player; a key or TalkBack with each step.
        poll.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            private var dragging = false

            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                value.text = getString(R.string.seconds, p + 1)
                if (user && !dragging) interval(p + 1)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                interval(s.progress + 1)
            }
        })
    }

    /** A new Polling interval: the mini player here picks it up now; other screens when they open. */
    private fun interval(s: Int) {
        Prefs.poll(this, s)
        player.stop()
        player.start()
    }

    override fun onStart() {
        super.onStart()
        player.start()
        search()
    }

    override fun onStop() {
        gen++
        player.stop()
        super.onStop()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        player.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) = search()

    // The volume buttons set the room's volume while this screen is in front, once the mini player shows.
    override fun dispatchKeyEvent(e: KeyEvent) = player.key(e) || super.dispatchKeyEvent(e)

    /** Android 16 and older don't know the permission, and don't need it. */
    private fun lanAllowed() = Build.VERSION.SDK_INT < 37 || checkSelfPermission(ACCESS_LOCAL_NETWORK) == PERMISSION_GRANTED

    /** The remembered addresses first, for an instant list; SSDP alongside finds the rest and any that moved. */
    private fun search() {
        val g = ++gen
        rooms.removeAllViews()
        found = emptyList() // The rows and the rooms they show, in step; the household stays known until it's read again.
        rooms.shown = false
        servers.removeAllViews()
        servers.shown = false
        library = null
        allow.shown = !lanAllowed()
        if (allow.shown) {
            roomsLine(getString(R.string.lan_needed))
            serversLine("")
            favoritesCard.shown = false
            return
        }
        if (Lan.wifi == null) { // The player hides by itself without WiFi.
            roomsLine(getString(R.string.connect_wifi), R.color.warning)
            serversLine("")
            favoritesCard.shown = false
            return
        }
        roomsLine(getString(R.string.searching))
        serversLine(getString(R.string.searching))

        val t0 = SystemClock.elapsedRealtime()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val haveRooms = AtomicBoolean(false)
        val pending = AtomicInteger(1) // The SSDP search, plus every fetch in flight: the last one done says so.
        val done = Runnable { if (g == gen) searched(SystemClock.elapsedRealtime() - t0) }

        fun task(work: () -> Unit) {
            pending.incrementAndGet()
            later(pool) {
                try {
                    work()
                } finally {
                    if (pending.decrementAndGet() == 0) runOnUiThread(done)
                }
            }
        }

        fun sonos(location: String) {
            if (haveRooms.get() || seen.size >= MAX_DEVICES || !seen.add(location)) return
            task {
                val host = Upnp.hostOf(location) ?: return@task
                val t = try {
                    Upnp.topology(host)
                } catch (e: Exception) {
                    Log.i(TAG, "No rooms from $host: $e")
                    return@task
                }
                if (t.groups.isEmpty() || !haveRooms.compareAndSet(false, true)) return@task
                prefs.edit().putString(KEY_SONOS, location).apply()
                val ms = SystemClock.elapsedRealtime() - t0
                runOnUiThread { if (g == gen) showRooms(t, ms) }
                // The household's favourites and music library, the same from any player; the library is listed once
                // it has a share.
                val dev = try {
                    Upnp.server(location)
                } catch (e: Exception) {
                    Log.i(TAG, "No library at $location: $e")
                    null
                } ?: return@task
                // Alongside the library's shares; read again on every search, for one saved elsewhere meanwhile. The
                // panel stays as it was until then: no jump of the cards below it.
                val fav = Server(dev.udn, getString(R.string.favorites), dev.host, dev.control, null, favorites = true)
                Heart.server = fav // The heart's too, on every screen.
                task {
                    runCatching { Sonos.favorites(fav) }.onSuccess { // All of them, over every page, as the Mac app.
                        runOnUiThread { if (g == gen) showFavorites(fav, it) }
                    }
                }
                val lib = Server(dev.udn, getString(R.string.music_library), dev.host, dev.control, dev.icon, true)
                val shares = runCatching { Upnp.browse(lib, "S:", 0, 20).entries }.getOrNull()
                if (shares.isNullOrEmpty()) return@task
                val at = SystemClock.elapsedRealtime() - t0
                // Its own icon, not the player's 48 px picture: sharp at any size, and one request less.
                runOnUiThread { if (g == gen) addServer(lib, null, at, shares.joinToString(" · ") { it.title }, R.drawable.ic_library) }
            }
        }

        fun server(location: String) {
            if (seen.size >= MAX_DEVICES || !seen.add(location)) return
            task {
                val s = try {
                    Upnp.server(location)
                } catch (e: Exception) {
                    Log.i(TAG, "No server at $location: $e")
                    null
                }
                remember(location, s != null)
                if (s == null) return@task
                val ms = SystemClock.elapsedRealtime() - t0
                // The row at once, usable without its icon, which can take up to the request's 15 s: its tile shows
                // meanwhile, so the name doesn't move when the icon lands.
                runOnUiThread { if (g == gen) addServer(s, null, ms, tile = s.icon != null) }
                val url = s.icon ?: return@task
                val bmp = runCatching { icon(Http.get(url)) }.getOrNull()
                runOnUiThread { if (g == gen) serverIcon(s, bmp) }
            }
        }

        prefs.getString(KEY_SONOS, null)?.let(::sonos)
        prefs.getStringSet(KEY_SERVERS, null)?.forEach(::server)
        later(pool) {
            try {
                Ssdp.search(arrayOf(Ssdp.SONOS, Ssdp.SERVER), SEARCH_MS) { a ->
                    if (a.st == Ssdp.SONOS) sonos(a.location)
                    else if (!a.usn.startsWith("uuid:RINCON_")) server(a.location) // A player's library comes once, above.
                }
            } catch (e: Exception) {
                Log.i(TAG, "SSDP failed: $e")
            } finally {
                if (pending.decrementAndGet() == 0) runOnUiThread(done)
            }
        }
    }

    /** Keeps a server's address for the next start, or forgets one that no longer answers. */
    private fun remember(location: String, keep: Boolean) = synchronized(prefs) {
        val set = HashSet(prefs.getStringSet(KEY_SERVERS, null) ?: emptySet())
        if (if (keep) set.add(location) else set.remove(location)) prefs.edit().putStringSet(KEY_SERVERS, set).apply()
    }

    /** A room's or server's row: the device's own picture as [bmp], or one of the app's icons as [glyph]. */
    private fun row(parent: ViewGroup, name: String, meta: String, bmp: Bitmap? = null, glyph: Int = 0): View =
        layoutInflater.inflate(R.layout.row, parent, false).apply {
            findViewById<TextView>(R.id.name).text = name
            findViewById<TextView>(R.id.meta).text = meta
            if (bmp != null || glyph != 0) findViewById<ImageView>(R.id.icon).apply {
                if (bmp != null) setImageBitmap(bmp) else glyph(this, glyph)
                shown = true
            }
        }

    private fun showRooms(t: Upnp.Topology, ms: Long) {
        Log.i(TAG, "${t.rooms.size} rooms in ${t.groups.size} groups in $ms ms")
        place(t)
    }

    /** Every room: a tap on one chooses it for the player; a tap on its circle groups or ungroups it (see checks()). */
    private fun place(t: Upnp.Topology) {
        household = t
        found = t.rooms
        for (x in t.rooms) Player.players += x.own.substringBeforeLast(':')
        Player.rooms = t.rooms // For Room settings from the player's room name, on any screen.
        rooms.removeAllViews()
        // Each room by its main player's kind and model, known from the last start; a new room shows a speaker and its
        // address alone until read. The address is the room's own player's.
        for (x in t.rooms) rooms.addView(row(rooms, x.name, roomMeta(x, model(x)), glyph = productIcon(product(model(x)))).apply {
            setOnClickListener { choose(x) }
            findViewById<ImageView>(R.id.check).apply {
                contentDescription = getString(R.string.group_room, x.name)
                setOnClickListener { toggle(x) }
                // A check box to TalkBack: "Group Kitchen, checkbox, checked".
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(v: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(v, info)
                        info.className = CheckBox::class.java.name
                        info.isCheckable = true
                        val on = x.id in checked
                        // Android 16's three states; before it, the yes or no it replaced.
                        if (Build.VERSION.SDK_INT >= 36) {
                            info.checked = if (on) AccessibilityNodeInfo.CHECKED_STATE_TRUE else AccessibilityNodeInfo.CHECKED_STATE_FALSE
                        } else {
                            @Suppress("DEPRECATION")
                            info.isChecked = on
                        }
                    }
                }
            }
        })
        pick(t)
        rooms.shown = true
        roomsLine(resources.getQuantityString(R.plurals.room_count, t.rooms.size, t.rooms.size), R.color.success)
        findViewById<View>(R.id.room_settings).shown = true // Once there are rooms to set.
        models(t.rooms)
    }

    /**
     * The remembered room's group, as the speakers are grouped now, also by the Sonos app: its rooms checked, and the
     * player on it, one room or all of them as Kitchen + 1.
     */
    private fun pick(t: Upnp.Topology) {
        val group = t.group(Prefs.room(this))
        checked = t.rooms.filter { t.groupOf[it.id] == group?.id }.mapTo(HashSet()) { it.id }
        room = group
        if (!group.same(player.room)) player.room = group // It may have followed the speakers there already.
        checks()
    }

    /** A tap on [x]'s row: it for the player, with any rooms it plays with; the speakers stay grouped as they are. */
    private fun choose(x: Room) {
        val t = household ?: return
        if (settling || x.id == anchor()) return
        Prefs.room(this, x.id)
        pick(t)
    }

    private var settling = false // A grouping change is out; taps wait for it.

    /**
     * A tap on [x]'s circle: ✓ leaves the group the player controls, ○ joins it. A room that plays something of its
     * own, as the TV or Spotify, is asked about first, as joining stops that; a tap on the wrong circle shouldn't.
     */
    private fun toggle(x: Room) {
        if (x.id in checked) return regroup(x)
        if (settling) return
        settling = true // Also while its group is asked whether it plays.
        val g = gen
        later(pool) {
            val state = runCatching { Sonos.state(x) }.getOrNull() // Its group's, from its coordinator.
            runOnUiThread {
                settling = false
                if (g != gen || isDestroyed) return@runOnUiThread
                // Not known whether it plays: no join that might stop something unasked.
                if (state.isNullOrEmpty()) return@runOnUiThread toast(unreachable(x.name))
                val lead = room
                if (lead == null || (state != "PLAYING" && state != "TRANSITIONING")) return@runOnUiThread regroup(x)
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.group_title, x.name))
                    .setMessage(getString(R.string.group_message, x.name, lead.name))
                    // Only while it still means what it asked: the speakers may have been regrouped meanwhile.
                    .setPositiveButton(R.string.group) { _, _ -> if (g == gen && lead.same(room) && x.id !in checked) regroup(x) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * Groups the speakers to match a check or uncheck of [x]: a checked room joins the others and plays what they
     * play; an unchecked one leaves and goes quiet, and if it led them, it first hands the music to another, which
     * plays on without a gap. The last checked room stays checked.
     */
    private fun regroup(x: Room) {
        val t = household ?: return
        val lead = room ?: return
        val on = x.id in checked
        if (settling || (on && checked.size == 1)) return
        val next = if (on) checked - x.id else checked + x.id
        val leader = if (on && x.id == lead.id) t.rooms.first { it.id in next }.id else lead.id // Afterwards.
        val mine = anchor()
        settling = true
        checked = next
        // The player stays on the room it controls while that one stays in the group; else on the group's leader.
        Prefs.room(this, mine?.takeIf { it in next } ?: leader)
        checks() // At once; the speakers' own report confirms it below.
        val g = gen
        later(pool) {
            try {
                when {
                    !on -> Sonos.join(x, lead)
                    x.id == lead.id -> Sonos.handOver(lead.host, leader, t.rooms.first { it.id == leader }.own)
                    else -> Sonos.leave(x)
                }
            } catch (e: InterruptedException) {
                return@later // The screen closed: nothing to say, nor to wait for.
            } catch (e: Exception) {
                Log.i(TAG, "Grouping ${x.name}: $e")
                runOnUiThread { toast(unreachable(x.name)) }
            }
            settle(t, leader, next, g)
        }
    }

    /**
     * On the pool, after a grouping change: the speakers report it a moment later, so this reads until [leader]'s
     * group is [next], then shows what they say.
     */
    private fun settle(t: Upnp.Topology, leader: String, next: Set<String>, g: Int) {
        val at = t.rooms.first { it.id == leader }.own
        var now: Upnp.Topology? = null
        for (n in 1..SETTLE_TRIES) {
            try {
                Thread.sleep(SETTLE_MS)
            } catch (e: InterruptedException) {
                return // The screen closed meanwhile.
            }
            val u = runCatching { Upnp.topology(at) }.getOrNull() ?: continue
            now = u
            if (u.rooms.filter { u.groupOf[it.id] == u.groupOf[leader] }.mapTo(HashSet()) { it.id } == next) break
        }
        runOnUiThread {
            settling = false
            if (g != gen) return@runOnUiThread
            (now ?: household)?.let { place(it) }
        }
    }

    private fun model(r: Room) = Prefs.model(this, r.id)

    /**
     * Reads the model of each room's main player not known yet (~15 ms each), and redraws its icon. Known ones aren't
     * asked again: the id is the player's own, and a player doesn't change its model.
     */
    private fun models(r: List<Room>) {
        val g = gen
        if (r.all { model(it).isNotEmpty() }) return
        later(pool) {
            for ((i, x) in r.withIndex()) {
                if (model(x).isNotEmpty()) continue
                val m = runCatching { Upnp.model(x.own) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
                Prefs.model(this, x.id, m)
                runOnUiThread {
                    if (g != gen) return@runOnUiThread
                    val v = rooms.getChildAt(i) ?: return@runOnUiThread
                    v.findViewById<ImageView>(R.id.icon)?.let { glyph(it, productIcon(product(m))) }
                    v.findViewById<TextView>(R.id.meta)?.text = roomMeta(x, m)
                }
            }
        }
    }

    /**
     * Every room with its circle, as in the Sonos app's group sheet: ✓ in the group the player controls, in the text's
     * colour, ○ not, in grey; and the room the player controls with its name in blue, as Room settings marks the open
     * room, the only blue here: a blue ✓ read as that room too. TalkBack hears that room as "Selected". A single room has
     * nothing to group, so no circle.
     */
    private fun checks() {
        val mine = anchor()
        for (i in found.indices) {
            val x = found[i]
            val on = x.id in checked
            rooms.getChildAt(i)?.apply { // None while a search has cleared the rows.
                findViewById<TextView>(R.id.name).setTextColor(getColor(if (x.id == mine) R.color.accent_text else R.color.text))
                findViewById<ImageView>(R.id.check).apply {
                    shown = found.size > 1
                    setImageResource(if (on) R.drawable.ic_check else R.drawable.ic_circle)
                    imageTintList = ColorStateList.valueOf(getColor(if (on) R.color.text else R.color.text2))
                }
                isSelected = x.id == mine
            }
        }
    }

    /** The room the player controls: the one chosen last while it's in the group, else the group's first. */
    private fun anchor(): String? = Prefs.room(this)?.takeIf { it in checked } ?: found.firstOrNull { it.id in checked }?.id

    /** A server's row, or the Music library's, which says its shares in place of an address and stays last. */
    private fun addServer(s: Server, bmp: Bitmap?, ms: Long, meta: String = s.host, glyph: Int = 0, tile: Boolean = false) {
        Log.i(TAG, "${s.name} in $ms ms")
        player.hosts += s.host // Its covers may show in the player.
        if (!s.library) Player.servers[s.host] = s.name // Now Playing's source for its tracks.
        val v = row(servers, s.name, meta, bmp, glyph).apply {
            findViewById<View>(R.id.chevron).shown = true
            if (tile) findViewById<View>(R.id.icon).shown = true // Empty until serverIcon() fills it.
            tag = s
            setOnClickListener { browse(s) }
        }
        if (s.library) {
            library = v
            servers.addView(v)
        } else {
            servers.addView(v, servers.childCount - if (library == null) 0 else 1)
        }
        servers.shown = true
        val n = servers.childCount
        serversLine(resources.getQuantityString(R.plurals.server_count, n, n), R.color.success)
    }

    /** [s]'s own icon into its row's tile, once it's in; when it couldn't be read, the tile goes. */
    private fun serverIcon(s: Server, bmp: Bitmap?) {
        val v = (0 until servers.childCount).map(servers::getChildAt).firstOrNull { it.tag === s } ?: return
        v.findViewById<ImageView>(R.id.icon).apply { if (bmp != null) setImageBitmap(bmp) else shown = false }
    }

    /** Browse on [s], playing in the player's room. */
    private fun browse(s: Server) {
        val i = Intent(this, BrowseActivity::class.java)
            .putExtra(BrowseActivity.CONTROL, s.control)
            .putExtra(BrowseActivity.HOST, s.host)
            .putExtra(BrowseActivity.NAME, s.name)
            .putExtra(BrowseActivity.LIBRARY, s.library)
            .putExtra(BrowseActivity.FAVORITES, s.favorites)
        Player.hosts(i, player.hosts) // Every server's covers in its player: the track playing may be another's.
        room?.let {
            i.putExtra(BrowseActivity.ROOM_ID, it.id)
                .putExtra(BrowseActivity.ROOM_NAME, it.name)
                .putExtra(BrowseActivity.ROOM_HOST, it.host)
                .putExtra(BrowseActivity.ROOM_MEMBERS, it.members)
                .putExtra(BrowseActivity.ROOM_OWN, it.own)
                .putExtra(BrowseActivity.ROOM_PLAYERS, it.players.toTypedArray())
        }
        startActivity(i)
    }

    /** The Favorites card, opening the favourites of [s]: shown while one of them plays here. */
    private fun showFavorites(s: Server, all: List<Entry>) {
        favorites = s
        favoritesCard.shown = all.any { it.playable } // Shortcuts alone can't be played here.
    }

    /**
     * The Rooms card's line, in [color]: success with rooms found, warning when the app has nothing to control, as
     * without WiFi or a Sonos system; grey while it searches.
     */
    private fun roomsLine(text: String, color: Int = R.color.text2) {
        roomsSummary.text = text
        roomsSummary.setTextColor(getColor(color))
    }

    /** The servers card's line, in [color]: success with servers found; grey while it searches, or with none. */
    private fun serversLine(text: String, color: Int = R.color.text2) {
        serversSummary.text = text
        serversSummary.setTextColor(getColor(color))
    }

    /** Everything has answered or timed out. */
    private fun searched(ms: Long) {
        Log.i(TAG, "Search done in $ms ms")
        if (rooms.childCount == 0) {
            room = null // No Sonos here: nothing for the player to control, so it hides.
            player.room = null
            roomsLine(getString(R.string.no_rooms), R.color.warning)
            favoritesCard.shown = false // Nothing to play them on.
            findViewById<View>(R.id.room_settings).shown = false
        }
        if (servers.childCount == 0) serversLine(getString(R.string.no_servers))
    }
}

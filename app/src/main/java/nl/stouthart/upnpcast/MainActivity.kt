package nl.stouthart.upnpcast

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "UPnPCast"
private const val SEARCH_MS = 1500L // Devices answer within MX (1 s); the rest is slack for the network.
private const val KEY_SONOS = "sonos" // A player's description URL: any player knows every room.
private const val KEY_SERVERS = "servers" // The servers' description URLs.
private const val MAX_DEVICES = 32 // Per search: a device answering with endless paths can't start endless fetches.
private const val SETTLE_MS = 350L // Between topology reads after a grouping change, until the speakers report it.
private const val SETTLE_TRIES = 6

private var View.shown: Boolean
    get() = visibility == View.VISIBLE
    set(v) {
        visibility = if (v) View.VISIBLE else View.GONE
    }

/** A device's icon, refused when its size is beyond any icon's: the PNG comes from the network. */
private fun icon(b: ByteArray): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(b, 0, b.size, o)
    return if (o.outWidth !in 1..512 || o.outHeight !in 1..512) null else BitmapFactory.decodeByteArray(b, 0, b.size)
}

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
    private lateinit var copyright: TextView
    private var gen = 0 // Drops results from a search that a newer one, or onStop, has overtaken.
    private var found: List<Room> = emptyList() // The rows: every room.
    private var household: Upnp.Topology? = null
    private var checked: Set<String> = emptySet() // The rooms that play together, by id.
    private var room: Room? = null // Their group, as the player controls it: one room, or Kitchen + 1.
    private var library: View? = null // The Music library row, kept after the servers whatever order they answer in.

    @SuppressLint("InlinedApi") // ACCESS_LOCAL_NETWORK is Android 17's; before, lanAllowed() is true and it's never used.
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        roomsSummary = findViewById(R.id.rooms_summary)
        rooms = findViewById(R.id.rooms)
        allow = findViewById(R.id.allow)
        serversSummary = findViewById(R.id.servers_summary)
        servers = findViewById(R.id.servers)
        player = Player(this)
        player.onNetwork = { search() } // Another network may hold other rooms and servers; none, and the screen says so.
        player.onShown = { copyright.shown = !it } // The player takes the foot of the screen.
        // Grouped elsewhere, as in the Sonos app: the checks follow. A change made here shows once it settled.
        player.onRegroup = { if (!settling) place(it) }
        copyright = findViewById(R.id.copyright)
        copyright.text = getString(R.string.copyright, BuildConfig.VERSION_NAME)
        allow.setOnClickListener {
            // After a second "Don't allow" Android stops asking; then only the app's settings page can grant it.
            if (shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK)) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 0)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (!lanAllowed()) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 0)
        settings()
        fold()
    }

    /** The Settings card opens and closes with a tap on its title: closed at first, then as it was left. */
    private fun fold() {
        val header: View = findViewById(R.id.settings_header)
        val body: View = findViewById(R.id.settings_body)
        val chevron: View = findViewById(R.id.settings_chevron)
        fun open(o: Boolean) {
            body.shown = o
            header.stateDescription = getString(if (o) R.string.expanded else R.string.collapsed)
        }
        open(Prefs.open(this))
        chevron.rotation = if (body.shown) 180f else 0f
        val card = body.parent as ViewGroup
        var scroll: LayoutTransition.TransitionListener? = null // An open still to scroll into view.
        header.setOnClickListener {
            val o = !body.shown
            Prefs.open(this, o)
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

    /** A row of [choices], [chosen] filled; a tap on another calls [choose] with its index, then fills it. */
    private fun segments(choices: Array<TextView>, chosen: Int, choose: (Int) -> Unit) {
        fun mark(c: Int) {
            for ((i, v) in choices.withIndex()) {
                v.isSelected = i == c // TalkBack says "Selected" on the chosen one.
                v.setBackgroundResource(if (i == c) R.drawable.segment_on else R.drawable.segment)
                v.setTextColor(getColor(if (i == c) R.color.accent_text else R.color.text))
            }
        }
        for ((i, v) in choices.withIndex()) v.setOnClickListener {
            if (v.isSelected) return@setOnClickListener
            choose(i)
            mark(i)
        }
        mark(chosen)
    }

    /**
     * The Volume button step (the next press uses it), Group rooms (the next tap on a room), the Polling interval
     * (applies at once) and Appearance (Android recreates the screens in the new mode).
     */
    private fun settings() {
        segments(arrayOf(findViewById(R.id.group_off), findViewById(R.id.group_on)), if (Prefs.group(this)) 1 else 0) {
            Prefs.group(this, it == 1)
            if (it == 0) single()
        }
        val choices = arrayOf<TextView>(findViewById(R.id.appearance_system), findViewById(R.id.appearance_light), findViewById(R.id.appearance_dark))
        // When the mode stays the same, as System default while the system is in that mode, nothing recreates: the
        // choice is filled in place.
        segments(choices, Prefs.appearance(this)) { Prefs.appearance(this, it) }
        // Sonos's labels at one size: the largest, from 14 sp down to 11, at which the longest fits its third of the row.
        choices[0].addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
            if (r - l == or - ol) return@addOnLayoutChangeListener // Only on a new width, as at first and after rotating.
            val t = v as TextView
            val room = t.width - t.paddingLeft - t.paddingRight
            val paint = Paint(t.paint)
            var sp = 14f
            while (sp > 11f) {
                paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
                if (choices.all { paint.measureText(it.text.toString()) <= room }) break
                sp -= 0.5f
            }
            t.post { for (c in choices) c.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) } // Not inside the layout pass.
        }

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
            roomsSummary.setText(R.string.lan_needed)
            serversSummary.text = ""
            return
        }
        if (Lan.wifi == null) { // The player hides by itself without WiFi.
            roomsSummary.setText(R.string.connect_wifi)
            serversSummary.text = ""
            return
        }
        roomsSummary.setText(R.string.searching)
        serversSummary.setText(R.string.searching)

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
                // The household's music library, the same from any player: listed once it has a share.
                val lib = try {
                    Upnp.server(location)?.let { Server(it.udn, getString(R.string.music_library), it.host, it.control, it.icon, true) }
                } catch (e: Exception) {
                    Log.i(TAG, "No library at $location: $e")
                    null
                } ?: return@task
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
                val bmp = s.icon?.let { runCatching { icon(Http.get(it)) }.getOrNull() }
                runOnUiThread { if (g == gen) addServer(s, bmp, ms) }
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

    /** An app icon in a row's 40dp tile: 24dp, centred, as a line icon is drawn. */
    private fun glyph(v: ImageView, res: Int) {
        val pad = (8 * resources.displayMetrics.density).toInt()
        v.setPadding(pad, pad, pad, pad)
        v.setImageResource(res)
    }

    private fun productIcon(p: Product) = when (p) {
        Product.SOUNDBAR -> R.drawable.ic_soundbar
        Product.SUB -> R.drawable.ic_sub
        Product.PORTABLE -> R.drawable.ic_portable
        Product.AMP -> R.drawable.ic_amp
        Product.SPEAKER -> R.drawable.ic_speaker
    }

    private fun showRooms(t: Upnp.Topology, ms: Long) {
        Log.i(TAG, "${t.rooms.size} rooms in ${t.groups.size} groups in $ms ms")
        place(t)
    }

    /** Every room, the checked ones playing together; a tap groups them or only chooses, as Group rooms says. */
    private fun place(t: Upnp.Topology) {
        household = t
        found = t.rooms
        rooms.removeAllViews()
        // Each room by its main player's kind and model, known from the last start; a new room shows a speaker and its
        // address alone until read. The address is the room's own player's.
        for (x in t.rooms) rooms.addView(row(rooms, x.name, roomMeta(x, model(x)), glyph = productIcon(product(model(x)))).apply {
            setOnClickListener { if (Prefs.group(this@MainActivity)) toggle(x) else choose(x) }
        })
        pick(t)
        rooms.shown = true
        roomsSummary.text = resources.getQuantityString(R.plurals.room_count, t.rooms.size, t.rooms.size)
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

    /** With Group rooms off: [x] for the player, with any rooms it plays with; the speakers stay grouped as they are. */
    private fun choose(x: Room) {
        val t = household ?: return
        if (settling || x.id in checked) return
        Prefs.room(this, x.id)
        pick(t)
    }

    private var settling = false // A grouping change is out; taps wait for it.

    /**
     * With Group rooms on: checks or unchecks [x], as a check box. A room that plays something of its own, as the TV
     * or Spotify, is asked about first, as joining stops that; a tap on the wrong row shouldn't.
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
        settling = true
        checked = next
        checks() // At once; the speakers' own report confirms it below.
        Prefs.room(this, leader)
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
     * Group rooms turned off: the rooms playing with the leading one leave it and go quiet, and it plays on, the only
     * one checked. A room alone stays as it is.
     */
    private fun single() {
        val t = household ?: return
        val lead = room ?: return
        val others = t.rooms.filter { it.id in checked && it.id != lead.id }
        if (settling || others.isEmpty()) return // A change still out: single() follows once it settled.
        val next = setOf(lead.id)
        settling = true
        checked = next
        checks()
        Prefs.room(this, lead.id)
        val g = gen
        later(pool) {
            for (x in others) {
                try {
                    Sonos.leave(x)
                } catch (e: Exception) {
                    Log.i(TAG, "Grouping ${x.name}: $e")
                    runOnUiThread { toast(unreachable(x.name)) }
                }
            }
            settle(t, lead.id, next, g)
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
            // Turned off while a change that left rooms together was out. Once only: single()'s own change leaves one,
            // so a leave that failed isn't tried again and again.
            if (next.size > 1 && !Prefs.group(this)) single()
        }
    }

    // The app's context: a message that lands after the screen closed doesn't keep the screen alive.
    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()

    private fun model(r: Room) = Prefs.model(this, r.id)

    /** A room's second line, as the Sonos app's: its model, such as "Era 300", then its address. */
    private fun roomMeta(r: Room, model: String): String {
        val ip = r.own.substringBefore(':')
        // One kept by an earlier build is the model name whole, as "Sonos Era 300".
        return if (model.isEmpty()) ip else "${model.removePrefix("Sonos ")} · $ip"
    }

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

    // The checked rooms' marks; "Selected" for TalkBack, as a check box would say.
    private fun checks() {
        for (i in found.indices) {
            val on = found[i].id in checked
            rooms.getChildAt(i)?.apply { // None while a search has cleared the rows.
                findViewById<View>(R.id.check).shown = on
                isSelected = on
            }
        }
    }

    /** A server's row, or the Music library's, which says its shares in place of an address and stays last. */
    private fun addServer(s: Server, bmp: Bitmap?, ms: Long, meta: String = s.host, glyph: Int = 0) {
        Log.i(TAG, "${s.name} in $ms ms")
        player.hosts += s.host // Its covers may show in the player.
        if (!s.library) Player.servers[s.host] = s.name // Now Playing's source for its tracks.
        val v = row(servers, s.name, meta, bmp, glyph).apply {
            findViewById<View>(R.id.chevron).shown = true
            setOnClickListener {
                val i = Intent(this@MainActivity, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.CONTROL, s.control)
                    .putExtra(BrowseActivity.HOST, s.host)
                    .putExtra(BrowseActivity.NAME, s.name)
                    .putExtra(BrowseActivity.LIBRARY, s.library)
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
        }
        if (s.library) {
            library = v
            servers.addView(v)
        } else {
            servers.addView(v, servers.childCount - if (library == null) 0 else 1)
        }
        servers.shown = true
        val n = servers.childCount
        serversSummary.text = resources.getQuantityString(R.plurals.server_count, n, n)
    }

    /** Everything has answered or timed out. */
    private fun searched(ms: Long) {
        Log.i(TAG, "Search done in $ms ms")
        if (rooms.childCount == 0) {
            room = null // No Sonos here: nothing for the player to control, so it hides.
            player.room = null
            roomsSummary.setText(R.string.no_rooms)
        }
        if (servers.childCount == 0) serversSummary.setText(R.string.no_servers)
    }
}

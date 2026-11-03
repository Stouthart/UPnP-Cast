package nl.stouthart.upnpcast

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.annotation.SuppressLint
import android.app.Activity
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "UPnPCast"
private const val SEARCH_MS = 1500L // Devices answer within MX (1 s); the rest is slack for the network.
private const val KEY_SONOS = "sonos" // A player's description URL: any player knows every room.
private const val KEY_SERVERS = "servers" // The servers' description URLs.
private const val KEY_ROOM = "room" // The coordinator id of the room tracks play in.
private const val MAX_DEVICES = 32 // Per search: a device answering with endless paths can't start endless fetches.

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
    private var found: List<Room> = emptyList()
    private var room: Room? = null // The selected room: the remembered one if it's still there, else the first.
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
        header.setOnClickListener {
            val o = !body.shown
            Prefs.open(this, o)
            open(o)
            chevron.animate().rotation(if (o) 180f else 0f).duration = 200
            // Once laid out, scrolled into view: the card is the last on the screen, so it opens below the fold.
            if (o) body.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                    v.removeOnLayoutChangeListener(this)
                    v.post { v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false) }
                }
            })
        }
    }

    /**
     * Appearance (Android recreates the screens in the new mode), the Volume button step (the next press uses it) and
     * the Polling interval (applies at once).
     */
    private fun settings() {
        val choices = arrayOf<TextView>(findViewById(R.id.appearance_system), findViewById(R.id.appearance_light), findViewById(R.id.appearance_dark))
        fun mark() {
            val chosen = Prefs.appearance(this)
            for ((i, v) in choices.withIndex()) {
                v.isSelected = i == chosen // TalkBack says "Selected" on the chosen one.
                v.setBackgroundResource(if (i == chosen) R.drawable.segment_on else R.drawable.segment)
                v.setTextColor(getColor(if (i == chosen) R.color.on_accent else R.color.text))
            }
        }
        for ((i, v) in choices.withIndex()) v.setOnClickListener {
            if (i == Prefs.appearance(this)) return@setOnClickListener
            Prefs.appearance(this, i)
            mark() // When the mode stays the same, as System default while the system is in that mode, nothing recreates.
        }
        mark()
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
                val r = try {
                    Upnp.rooms(host)
                } catch (e: Exception) {
                    Log.i(TAG, "No rooms from $host: $e")
                    return@task
                }
                if (r.isEmpty() || !haveRooms.compareAndSet(false, true)) return@task
                prefs.edit().putString(KEY_SONOS, location).apply()
                val ms = SystemClock.elapsedRealtime() - t0
                runOnUiThread { if (g == gen) showRooms(r, ms) }
                // The household's music library, the same from any player: listed once it has a share.
                val lib = try {
                    Upnp.server(location)?.let { Server(it.udn, getString(R.string.music_library), it.host, it.control, it.icon, true) }
                } catch (e: Exception) {
                    Log.i(TAG, "No library at $location: $e")
                    null
                } ?: return@task
                val shares = runCatching { Upnp.browse(lib, "S:", 0, 20).entries }.getOrNull()
                if (shares.isNullOrEmpty()) return@task
                val bmp = lib.icon?.let { runCatching { icon(Http.get(it)) }.getOrNull() }
                val at = SystemClock.elapsedRealtime() - t0
                runOnUiThread { if (g == gen) addServer(lib, bmp, at, shares.joinToString(" · ") { it.title }) }
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

    private fun row(parent: ViewGroup, name: String, meta: String, bmp: Bitmap? = null): View =
        layoutInflater.inflate(R.layout.row, parent, false).apply {
            findViewById<TextView>(R.id.name).text = name
            findViewById<TextView>(R.id.meta).text = meta
            if (bmp != null) findViewById<ImageView>(R.id.icon).apply {
                setImageBitmap(bmp)
                shown = true
            }
        }

    private fun showRooms(r: List<Room>, ms: Long) {
        Log.i(TAG, "${r.size} rooms in $ms ms")
        found = r
        val id = prefs.getString(KEY_ROOM, null)
        room = r.firstOrNull { it.id == id } ?: r.firstOrNull()
        player.room = room
        rooms.removeAllViews()
        for (x in r) rooms.addView(row(rooms, x.name, x.host.substringBefore(':')).apply {
            setOnClickListener {
                room = x
                player.room = x
                prefs.edit().putString(KEY_ROOM, x.id).apply()
                checks()
            }
        })
        checks()
        rooms.shown = true
        roomsSummary.text = resources.getQuantityString(R.plurals.room_count, r.size, r.size)
    }

    private fun checks() {
        for (i in found.indices) rooms.getChildAt(i).findViewById<View>(R.id.check).shown = found[i] === room
    }

    /** A server's row, or the Music library's, which says its shares in place of an address and stays last. */
    private fun addServer(s: Server, bmp: Bitmap?, ms: Long, meta: String = s.host) {
        Log.i(TAG, "${s.name} in $ms ms")
        player.hosts += s.host // Its covers may show in the player.
        val v = row(servers, s.name, meta, bmp).apply {
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

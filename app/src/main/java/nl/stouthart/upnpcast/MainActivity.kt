package nl.stouthart.upnpcast

import android.Manifest.permission.ACCESS_LOCAL_NETWORK
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
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

    @SuppressLint("InlinedApi") // ACCESS_LOCAL_NETWORK is Android 17's; on 16, lanAllowed() is true and it's never used.
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
        copyright = findViewById(R.id.copyright)
        copyright.text = getString(R.string.copyright, BuildConfig.VERSION_NAME)
        allow.setOnClickListener {
            // After a second "Don't allow" Android stops asking; then only the app's settings page can grant it.
            if (shouldShowRequestPermissionRationale(ACCESS_LOCAL_NETWORK)) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 0)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (!lanAllowed()) requestPermissions(arrayOf(ACCESS_LOCAL_NETWORK), 0)
        settings()
    }

    /** Appearance (Android recreates the screens in the new mode) and the Polling interval (applies at once). */
    private fun settings() {
        val choices = arrayOf<TextView>(findViewById(R.id.appearance_system), findViewById(R.id.appearance_light), findViewById(R.id.appearance_dark))
        fun mark() {
            val chosen = Prefs.appearance(this)
            for ((i, v) in choices.withIndex()) {
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

        val poll: SeekBar = findViewById(R.id.poll)
        val value: TextView = findViewById(R.id.poll_value)
        poll.progress = Prefs.poll(this) - 1
        value.text = getString(R.string.seconds, poll.progress + 1)
        poll.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                value.text = getString(R.string.seconds, p + 1)
            }

            override fun onStartTrackingTouch(s: SeekBar) = Unit

            override fun onStopTrackingTouch(s: SeekBar) {
                Prefs.poll(this@MainActivity, s.progress + 1)
                player.stop() // The mini player here picks up the new interval now; other screens when they open.
                player.start()
            }
        })
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

    /** Android 16 doesn't know the permission, and doesn't need it. */
    private fun lanAllowed() = Build.VERSION.SDK_INT < 37 || checkSelfPermission(ACCESS_LOCAL_NETWORK) == PERMISSION_GRANTED

    /** The remembered addresses first, for an instant list; SSDP alongside finds the rest and any that moved. */
    private fun search() {
        val g = ++gen
        rooms.removeAllViews()
        rooms.shown = false
        servers.removeAllViews()
        servers.shown = false
        allow.shown = !lanAllowed()
        if (allow.shown) {
            roomsSummary.setText(R.string.lan_needed)
            serversSummary.text = ""
            return
        }
        if (Lan.wifi == null) {
            select(null)
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
                    else if (!a.usn.startsWith("uuid:RINCON_")) server(a.location) // Not the Sonos's own music library.
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
        select(r.firstOrNull { it.id == id } ?: r.firstOrNull())
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

    /** The room tracks play in, shown by the mini player; none hides it. */
    private fun select(r: Room?) {
        room = r
        player.room = r
        copyright.shown = r == null // The player takes the foot of the screen.
    }

    private fun checks() {
        for (i in found.indices) rooms.getChildAt(i).findViewById<View>(R.id.check).shown = found[i] === room
    }

    private fun addServer(s: Server, bmp: Bitmap?, ms: Long) {
        Log.i(TAG, "${s.name} in $ms ms")
        player.hosts += s.host // Its covers may show in the player.
        servers.addView(row(servers, s.name, s.host, bmp).apply {
            setOnClickListener {
                val i = Intent(this@MainActivity, BrowseActivity::class.java)
                    .putExtra(BrowseActivity.CONTROL, s.control)
                    .putExtra(BrowseActivity.HOST, s.host)
                    .putExtra(BrowseActivity.NAME, s.name)
                room?.let {
                    i.putExtra(BrowseActivity.ROOM_ID, it.id)
                        .putExtra(BrowseActivity.ROOM_NAME, it.name)
                        .putExtra(BrowseActivity.ROOM_HOST, it.host)
                }
                startActivity(i)
            }
        })
        servers.shown = true
        val n = servers.childCount
        serversSummary.text = resources.getQuantityString(R.plurals.server_count, n, n)
    }

    /** Everything has answered or timed out. */
    private fun searched(ms: Long) {
        Log.i(TAG, "Search done in $ms ms")
        if (rooms.childCount == 0) {
            select(null) // Not the last network's room, as after switching to one without Sonos.
            roomsSummary.setText(R.string.no_rooms)
        }
        if (servers.childCount == 0) serversSummary.setText(R.string.no_servers)
    }
}

package nl.stouthart.upnpcast

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val QUIET_MS = 1500L // After a change, polls leave that control alone until it has landed.
private const val HOSTS = "hosts"

/**
 * The room's track and controls: the mini player at the foot of a screen, or all of Now Playing when the layout has a
 * progress bar. While its screen is visible it asks the room every few seconds; commands share that one thread, so a poll
 * never overlaps a command.
 */
@SuppressLint("SetTextI18n") // The volume is a plain number, as the Sonos app shows it.
class Player(private val a: Activity) {
    companion object {
        /** Hands [r] and the cover [hosts] to the next screen's player. */
        fun put(i: Intent, r: Room, hosts: Set<String>): Intent = i.putExtra(BrowseActivity.ROOM_ID, r.id)
            .putExtra(BrowseActivity.ROOM_NAME, r.name).putExtra(BrowseActivity.ROOM_HOST, r.host)
            .putExtra(HOSTS, hosts.toTypedArray())

        fun room(i: Intent): Room? {
            val id = i.getStringExtra(BrowseActivity.ROOM_ID) ?: return null
            return Room(id, i.getStringExtra(BrowseActivity.ROOM_NAME) ?: "", i.getStringExtra(BrowseActivity.ROOM_HOST) ?: return null)
        }

        fun hosts(i: Intent): Array<String> = i.getStringArrayExtra(HOSTS) ?: emptyArray()
    }

    /** One poll's answer; volume -1 and the nulls mean "not asked this time". */
    private class Answer(
        val state: String, val pos: Position, val track: Track?, val volume: Int, val muted: Boolean?, val mode: Mode?, val queued: Boolean?,
    )

    /** Where covers may come from besides the speaker itself: the servers this app knows. */
    val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var room: Room? = null
        set(v) {
            field = v
            gen++
            view.visibility = if (v == null) View.GONE else View.VISIBLE
            refresh()
        }

    /** Each answer, on the main thread, with whether the room plays its queue (only known when [media] is set). */
    var onPosition: ((Position, Boolean) -> Unit)? = null

    /** Also asks whether the room plays its queue, with the other slow values: for the Queue screen. */
    @Volatile
    var media = false

    // Play state and position change all the time; volume, mute, play mode and the queue check only when someone
    // changes them. So those are asked every SLOW-th poll, and at once when [fresh]: after a change made here.
    private val slow = 3
    @Volatile
    private var fresh = true
    private var ticks = 0 // The poll thread's, like the two below.
    private var lastMeta: String? = null // TrackMetaData is parsed only when it differs from the last.
    private var lastTrack: Track? = null

    /** What a tap on the mini player does: Now Playing, unless a screen says otherwise. */
    var onOpen: () -> Unit = { open(NowPlayingActivity::class.java) }

    /** What else the screen does when WiFi comes, goes or changes, after the player has asked the room again. */
    var onNetwork: (() -> Unit)? = null

    private val lan: () -> Unit = {
        covers.retry()
        refresh()
        onNetwork?.invoke()
    }

    private val view: View = a.findViewById(R.id.player)
    private val art: ImageView = a.findViewById(R.id.player_art)
    private val title: TextView = a.findViewById(R.id.player_title)
    private val meta: TextView = a.findViewById(R.id.player_meta)
    private val toggle: ImageView = a.findViewById(R.id.player_toggle)
    private val volume: SeekBar = a.findViewById(R.id.player_volume)
    private val level: TextView = a.findViewById(R.id.player_level)
    private val mute: ImageView = a.findViewById(R.id.player_mute)

    // Now Playing only.
    private val format: TextView? = a.findViewById(R.id.player_format)
    private val progress: SeekBar? = a.findViewById(R.id.player_progress)
    private val elapsed: TextView? = a.findViewById(R.id.player_elapsed)
    private val remaining: TextView? = a.findViewById(R.id.player_remaining)
    private val shuffle: ImageView? = a.findViewById(R.id.player_shuffle)
    private val repeat: ImageView? = a.findViewById(R.id.player_repeat)
    private val full = progress != null

    private val covers: Art = Art(
        if (full) minOf(a.resources.displayMetrics.widthPixels, 1200) else (48 * a.resources.displayMetrics.density).toInt(),
    ) { url -> if (art.tag == url) covers[url]?.let { covers.fill(art, it) } }
    private var exec: ScheduledExecutorService? = null

    @Volatile
    private var gen = 0 // Drops a poll's answer once the room changed or the screen went away.

    // Main thread only, from here on.
    private var playing: Boolean? = null
    private var muted: Boolean? = null
    private var queued = false // The last answer to the queue check, kept between the polls that skip it.
    private var shown = "" // What the text and cover show now, so an unchanged second touches no view.
    private var mode: Mode? = null
    private var dragging = false
    private var seeking = false
    private var quiet = 0L
    private var wanted = -1 // The volume still to send; one SetVolume is in flight at most.
    private var sending = false

    init {
        toggle.setOnClickListener {
            show(playing != true) // At once; the poll that follows confirms it.
            command(Sonos::toggle)
        }
        mute.setOnClickListener {
            muted(muted != true)
            command(Sonos::toggleMute)
        }
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                level.text = v.toString()
                if (user) send(v)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                dragging = false
                send(s.progress)
            }
        })
        if (!full) view.setOnClickListener { onOpen() }
        a.findViewById<View?>(R.id.player_previous)?.setOnClickListener { command(Sonos::previous) }
        a.findViewById<View?>(R.id.player_next)?.setOnClickListener { command(Sonos::next) }
        a.findViewById<View?>(R.id.player_queue)?.setOnClickListener { open(QueueActivity::class.java) }
        shuffle?.setOnClickListener {
            command { r ->
                val m = Sonos.mode(r) // Read right before, as for Play/Pause.
                Sonos.mode(r, Mode(!m.shuffle, m.repeat))
            }
        }
        repeat?.setOnClickListener {
            command { r ->
                val m = Sonos.mode(r)
                Sonos.mode(r, Mode(m.shuffle, Repeat.entries[(m.repeat.ordinal + 1) % Repeat.entries.size]))
            }
        }
        progress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                if (user) times(v, s.max)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                seeking = false
                quiet = SystemClock.uptimeMillis() + QUIET_MS
                val to = s.progress
                command { Sonos.seek(it, to) }
            }
        })
    }

    /** Asks the room at the Polling interval from the main screen: 2 s by default, up to 10 s. Watches WiFi meanwhile. */
    fun start() {
        Lan.start(a, lan)
        val s = Prefs.poll(a).toLong()
        exec = Executors.newSingleThreadScheduledExecutor().apply { scheduleWithFixedDelay(::poll, 0, s, TimeUnit.SECONDS) }
        if (full) ui.postDelayed(tick, 1000)
    }

    fun stop() {
        Lan.stop(lan)
        gen++
        exec?.shutdownNow()
        exec = null
        ui.removeCallbacks(tick)
    }

    // Between polls, the progress counts on by itself while the room plays: smooth at a 10 s interval, and no network.
    private val ui = Handler(Looper.getMainLooper())
    private var base = 0 // The elapsed time of the last answer, its moment, and the track's length.
    private var at = 0L
    private var length = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!seeking && playing == true && length > 0) {
                val e = minOf(length, base + ((SystemClock.uptimeMillis() - at) / 1000).toInt())
                progress?.progress = e
                times(e, length)
            }
            ui.postDelayed(this, 1000)
        }
    }

    fun destroy() = covers.stop()

    /** Asks the room everything now, e.g. right after a play command, rather than at the next tick. */
    fun refresh() = later {
        fresh = true
        poll()
    }

    fun open(screen: Class<out Activity>) {
        val r = room ?: return
        a.startActivity(put(Intent(a, screen), r, hosts))
    }

    private fun later(work: () -> Unit) {
        runCatching { exec?.execute(work) } // Refused once stopped: nothing to show then.
    }

    /** Runs [work] on the room, then asks again so the screen follows; only a failure shows. */
    private fun command(work: (Room) -> Unit) {
        val r = room ?: return
        later {
            try {
                work(r)
            } catch (_: Exception) {
                // The app's context: a message that lands after the screen closed doesn't keep the screen alive.
                a.runOnUiThread { Toast.makeText(a.applicationContext, a.unreachable(r.name), Toast.LENGTH_LONG).show() }
            }
            fresh = true
            poll()
        }
    }

    private fun send(v: Int) {
        wanted = v
        quiet = SystemClock.uptimeMillis() + QUIET_MS
        if (sending) return
        val r = room ?: return
        sending = true
        wanted = -1
        later {
            runCatching { Sonos.volume(r, v) }
            fresh = true // The next poll reads the volume back, once the quiet time lets it show.
            a.runOnUiThread {
                sending = false
                if (wanted >= 0) send(wanted)
            }
        }
        if (exec == null) sending = false
    }

    private fun poll() {
        val r = room ?: return
        val g = gen
        val all = fresh || ticks % slow == 0
        fresh = false
        ticks++
        val answer = if (Lan.wifi == null) null else runCatching {
            val state = Sonos.state(r)
            val p = Sonos.position(r)
            if (p.meta != lastMeta) {
                lastTrack = Sonos.track(p.meta)
                lastMeta = p.meta
            }
            Answer(
                state, p, lastTrack,
                if (all) Sonos.volume(r) else -1, if (all) Sonos.muted(r) else null,
                if (all && full) Sonos.mode(r) else null, if (all && media) Sonos.queued(r) else null,
            )
        }.getOrNull()
        a.runOnUiThread { if (g == gen) show(r, answer) }
    }

    private fun show(play: Boolean) {
        if (play == playing) return
        playing = play
        toggle.setImageResource(if (play) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = a.getString(if (play) R.string.pause else R.string.play)
    }

    private fun muted(m: Boolean) {
        if (m == muted) return
        muted = m
        mute.setImageResource(if (m) R.drawable.ic_volume_off else R.drawable.ic_volume)
        mute.contentDescription = a.getString(if (m) R.string.unmute else R.string.mute)
    }

    private fun tint(v: ImageView, on: Boolean) {
        v.imageTintList = ColorStateList.valueOf(a.getColor(if (on) R.color.accent else R.color.text2))
    }

    private fun times(e: Int, d: Int) {
        elapsed?.text = if (d > 0) clock(e) else ""
        remaining?.text = if (d > 0) "-" + clock(d - e) else ""
    }

    private fun show(r: Room, answer: Answer?) {
        val t = answer?.track
        if (answer != null) {
            show(answer.state == "PLAYING" || answer.state == "TRANSITIONING")
            answer.muted?.let { muted(it) }
            answer.queued?.let { queued = it }
        }
        val name = if (answer == null) a.unreachable(r.name) else t?.title ?: a.getString(R.string.no_content)
        val detail = listOf(r.name, t?.artist ?: "", if (full) t?.album ?: "" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val cover = t?.art?.let { cover(r, it) }
        val now = "$name\n$detail\n$cover\n${t?.format}"
        if (now != shown) {
            shown = now
            title.text = name
            meta.text = SpannableString(detail).apply {
                if (r.name.isNotEmpty()) setSpan(ForegroundColorSpan(a.getColor(R.color.accent)), 0, r.name.length, 0)
            }
            format?.text = t?.format ?: ""
            covers.show(art, cover, R.drawable.ic_music)
        }
        if (answer == null) return
        val v = answer.volume
        val calm = SystemClock.uptimeMillis() > quiet
        if (v >= 0 && !dragging && calm && v != volume.progress) volume.progress = v
        if (level.text.isEmpty() && v >= 0) level.text = v.toString()

        progress?.let {
            val p = answer.pos
            base = p.elapsed
            at = SystemClock.uptimeMillis()
            length = p.duration
            if (!seeking && calm) {
                it.isEnabled = p.duration > 0
                it.max = maxOf(p.duration, 1)
                it.progress = p.elapsed
                times(p.elapsed, p.duration)
            }
        }
        val m = answer.mode
        if (m != null && (mode?.upnp != m.upnp)) {
            mode = m
            shuffle?.let { tint(it, m.shuffle) }
            repeat?.let {
                it.setImageResource(if (m.repeat == Repeat.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
                tint(it, m.repeat != Repeat.OFF)
            }
        }
        onPosition?.invoke(answer.pos, queued)
    }

    /** The cover's URL when it is on the speaker (often relative, as /getaa?…) or on a known server; else none. */
    fun cover(r: Room, ref: String): String? {
        if (ref.isEmpty()) return null
        val u = runCatching { URL(URL("http://${r.host}/"), ref) }.getOrNull() ?: return null
        val ok = u.protocol == "http" && (u.host == r.host.substringBefore(':') || u.host in hosts)
        return if (ok) u.toString() else null
    }
}

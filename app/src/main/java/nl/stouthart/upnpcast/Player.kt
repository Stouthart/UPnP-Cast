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
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val QUIET_MS = 1500L // After a change, polls leave that control alone until it has landed.
private const val SLACK_MS = 250L // A position reply's trip, on top of the speaker rounding down to whole seconds.
private const val SETTLE_MS = 4000L // How long a seek may take to land, while the speaker still reports 0 or the old place.
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

        // Once the room has had anything loaded (a track, playing or paused, or a source such as the TV), the mini
        // player shows on every screen and stays, for the rest of the session.
        @Volatile
        private var seen = false
    }

    /**
     * One poll's answer, [at] the uptime the position arrived; volume -1 and the nulls mean "not asked this time".
     * [tv]: the TV input's format, asked only on Now Playing while the TV plays.
     */
    private class Answer(
        val state: String, val pos: Position, val at: Long, val track: Track?, val volume: Int, val muted: Boolean?, val mode: Mode?,
        val queued: Boolean?, val tv: String?,
    )

    /** Where covers may come from besides the speaker itself: the servers this app knows. */
    val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Main thread only, like the views it resets.
    @Volatile
    var room: Room? = null
        set(v) {
            field = v
            gen++
            // Whatever the room before still had to send stays there, and its volume isn't this one's: the number
            // clears and the volume buttons wait until this room's own is read.
            wanted = -1
            sending = false
            quiet = 0L
            level.text = ""
            place()
            refresh()
        }

    /** Runs when the mini player shows or hides. */
    var onShown: ((Boolean) -> Unit)? = null

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
        if (full && Lan.wifi == null) a.finish() // Now Playing has nothing to show without WiFi.
        place()
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
    private var keyed = false // The last volume button press went to the room, so its release does too.

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
                if (!user) return
                times(v, s.max)
                if (!seeking) seek(v) // A key or TalkBack, not a drag: each of their steps is a seek of its own.
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                seeking = false
                seek(s.progress)
            }
        })
    }

    private fun seek(to: Int) {
        quiet = SystemClock.uptimeMillis() + QUIET_MS
        base = to * 1000L // The clock goes along at once; answers that don't fit it can't pull it back meanwhile.
        at = SystemClock.uptimeMillis()
        settle = at + SETTLE_MS
        retick()
        command { Sonos.seek(it, to) }
    }

    /** Asks the room at the Polling interval from the main screen: 2 s by default, up to 10 s. Watches WiFi meanwhile. */
    fun start() {
        Lan.start(a, lan)
        if (full && Lan.wifi == null) a.finish() // As when WiFi went while the app was away.
        place()
        if (covers.retry()) shown = "" // A cover dropped at stop() is asked for again by the first answer.
        live = true
        val s = Prefs.poll(a).toLong()
        exec = Executors.newSingleThreadScheduledExecutor().apply { scheduleWithFixedDelay(::poll, 0, s, TimeUnit.SECONDS) }
    }

    fun stop() {
        Lan.stop(lan)
        live = false
        gen++
        // shutdownNow drops a volume change still queued, so nothing is in flight any more as far as the slider goes.
        wanted = -1
        sending = false
        exec?.shutdownNow()
        exec = null
        ui.removeCallbacks(tick)
        covers.pause()
    }

    // Between start() and stop(): a command still running when the screen stopped doesn't poll, nor restart the clock.
    @Volatile
    private var live = false

    // Between polls the progress counts on by itself, on a clock of its own while the room plays: each second shows for a
    // second, at any interval and with no network. A poll corrects the clock only where it disagrees with the speaker.
    private val ui = Handler(Looper.getMainLooper())
    private var base = 0L // The position in ms at [at] (uptime), whether it counts on, and the track's length in s.
    private var at = 0L
    private var running = false
    private var length = 0
    private var settle = 0L // Until then (uptime), a seek is landing.
    private val tick = object : Runnable {
        override fun run() {
            val ms = if (running) base + (SystemClock.uptimeMillis() - at) else base
            if (!seeking) {
                val e = minOf(length.toLong(), ms / 1000).toInt()
                progress?.progress = e
                times(e, length)
            }
            if (running && length > 0) ui.postDelayed(this, 1000 - ms % 1000) // Right when the next second starts.
        }
    }

    /** The clock changed: shows it now, then at each second it reaches. */
    private fun retick() {
        ui.removeCallbacks(tick)
        tick.run()
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
            } catch (e: Exception) {
                // 701: the speaker answered that the source has no such action, as Pause on the TV input.
                val text = if (e is HttpException && e.upnp == 701) a.getString(R.string.not_supported) else a.unreachable(r.name)
                // The app's context: a message that lands after the screen closed doesn't keep the screen alive.
                a.runOnUiThread { Toast.makeText(a.applicationContext, text, Toast.LENGTH_LONG).show() }
            }
            fresh = true
            poll()
        }
    }

    /**
     * A volume button, pressed (also each repeat while held) or released while this screen is in front: the room's
     * volume by the Volume button step, sent as a drag of the slider is. False, so the phone's own volume takes the key,
     * while there's nothing to control: no room or WiFi, no volume read yet, or the mini player hidden.
     */
    fun key(e: KeyEvent): Boolean {
        val up = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        if (e.action != KeyEvent.ACTION_DOWN) return keyed
        keyed = room != null && Lan.wifi != null && level.text.isNotEmpty() && (full || view.visibility == View.VISIBLE)
        if (!keyed) return false
        val step = Prefs.step(a)
        val v = (volume.progress + if (up) step else -step).coerceIn(0, 100)
        if (v != volume.progress) {
            volume.progress = v // Not the user's: its listener shows the number, and the send is here.
            send(v)
        }
        return true
    }

    private fun send(v: Int) {
        wanted = v
        quiet = SystemClock.uptimeMillis() + QUIET_MS
        if (sending) return
        val r = room ?: return
        sending = true
        wanted = -1
        val g = gen
        later {
            runCatching { Sonos.volume(r, v) }
            fresh = true // The next poll reads the volume back, once the quiet time lets it show.
            a.runOnUiThread {
                if (g != gen) return@runOnUiThread // A room switch or stop() has reset all this meanwhile.
                sending = false
                if (wanted >= 0) send(wanted)
            }
        }
        if (exec == null) sending = false
    }

    private fun poll() {
        if (!live) return
        val r = room ?: return
        val g = gen
        val all = fresh || ticks % slow == 0
        fresh = false
        ticks++
        val answer = if (Lan.wifi == null) null else runCatching {
            val state = Sonos.state(r)
            val p = Sonos.position(r)
            val got = SystemClock.uptimeMillis() // Before the calls below, which would make the position look older.
            if (p.meta != lastMeta) {
                lastTrack = Sonos.track(p.meta)
                lastMeta = p.meta
            }
            Answer(
                state, p, got, lastTrack,
                if (all) Sonos.volume(r) else -1, if (all) Sonos.muted(r) else null,
                if (all && full) Sonos.mode(r) else null, if (all && media) Sonos.queued(r) else null,
                // Its own failure only leaves the line empty; the player still shows the rest.
                if (full && p.uri.startsWith("x-sonos-htastream:")) runCatching { Sonos.tvFormat(r) }.getOrNull() else null,
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

    /**
     * The mini player shows from the first time the room has anything loaded, and stays, whatever plays or not. It
     * hides only with nothing to control: without WiFi, or without a Sonos room. Now Playing closes instead.
     */
    private fun place() {
        if (full) return
        val on = seen && room != null && Lan.wifi != null
        val now = if (on) View.VISIBLE else View.GONE
        if (view.visibility == now) return
        view.visibility = now
        onShown?.invoke(on)
    }

    private fun show(r: Room, answer: Answer?) {
        val t = answer?.track
        val plays = answer != null && (answer.state == "PLAYING" || answer.state == "TRANSITIONING")
        if (answer != null && (plays || answer.pos.uri.isNotEmpty())) seen = true
        place()
        if (answer != null) {
            show(plays)
            answer.muted?.let { muted(it) }
            answer.queued?.let { queued = it }
        }
        val name = when {
            answer == null -> a.unreachable(r.name)
            t != null -> t.title
            // Sources without metadata, known by their URI: the TV input, and Spotify Connect while paused.
            answer.pos.uri.startsWith("x-sonos-htastream:") -> a.getString(R.string.tv)
            answer.pos.uri.startsWith("x-sonos-vli:") && ",spotify:" in answer.pos.uri -> a.getString(R.string.spotify)
            else -> a.getString(R.string.no_content)
        }
        val detail = listOf(r.name, t?.artist ?: "", if (full) t?.album ?: "" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val cover = t?.let { cover(r, it.art, it.uri) }
        val quality = t?.format ?: answer?.tv ?: "" // The TV has no track, but its input format.
        val now = "$name\n$detail\n$cover\n$quality"
        if (now != shown) {
            shown = now
            title.text = name
            meta.text = SpannableString(detail).apply {
                if (r.name.isNotEmpty()) setSpan(ForegroundColorSpan(a.getColor(R.color.accent)), 0, r.name.length, 0)
            }
            format?.text = quality
            covers.show(art, cover, R.drawable.ic_music)
        }
        if (answer == null) return
        val v = answer.volume
        val calm = SystemClock.uptimeMillis() > quiet
        if (v >= 0 && !dragging && calm && v != volume.progress) volume.progress = v
        if (level.text.isEmpty() && v >= 0) level.text = v.toString()

        progress?.let {
            val p = answer.pos
            if (seeking) return@let
            val reported = p.elapsed * 1000L
            val ms = if (running) base + (answer.at - at) else base // The clock, when the position arrived.
            // The speaker rounds down: anywhere in the reported second, plus the reply's trip, the clock is right.
            val top = reported + 1000 + SLACK_MS
            val near = p.duration == length && ms >= reported - 1500 && ms <= top + 1500
            if (!near && answer.at < settle) return@let // A seek landing: 0, the old place, or no length for a moment.
            if (near) settle = 0
            if (answer.state == "TRANSITIONING") { // Buffering: the clock waits where it is, whatever the speaker says.
                if (running) {
                    base = ms
                    at = answer.at
                    running = false
                    retick()
                }
                return@let
            }
            it.isEnabled = p.duration > 0
            it.max = maxOf(p.duration, 1)
            val play = answer.state == "PLAYING"
            val to = when {
                play != running || !near -> reported + 500 // Another track, a seek, play or pause: that second's middle.
                ms < reported -> reported // Behind: just catches up.
                ms > top -> reported + 999 // Ahead: just falls back into that second.
                else -> return@let
            }
            base = to
            at = answer.at
            running = play
            length = p.duration
            retick()
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

    /**
     * The cover's URL when it is on the speaker (often relative, as /getaa?…) or on a known server. One on the internet,
     * as Spotify's, comes from the speaker too: it fetches the cover of the track's [uri] itself, so the phone never
     * leaves the local network. Else none.
     */
    fun cover(r: Room, ref: String, uri: String = ""): String? {
        if (ref.isEmpty()) return null
        val u = runCatching { URL(URL("http://${r.host}/"), ref) }.getOrNull() ?: return null
        if (u.protocol == "http" && (u.host == r.host.substringBefore(':') || u.host in hosts)) return u.toString()
        return if (uri.isEmpty()) null else "http://${r.host}/getaa?s=1&u=${URLEncoder.encode(uri, Charsets.UTF_8)}"
    }
}

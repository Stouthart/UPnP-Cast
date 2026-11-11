package nl.stouthart.upnpcast

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val QUIET_MS = 1500L // After a change, polls leave that control alone until it has landed.
private const val SEED_MS = 15_000L // How old what another screen showed may be for the next to start from it.
private const val HOSTS = "hosts"
private const val META_AGAIN_MS = 60_000L // After a failed read of a station or of the speakers' music services.
private const val GLIDE_DP_S = 30f // The mini player's title slides at the speed of Android's own scrolling text.
private const val GLIDE_WAIT_MS = 1200L // Before it slides, as Android's does; and a pause at its end, then back.
// Now Playing's controls stand at most this far apart, by what they draw: on a wide screen, as a tablet's, the row stays
// together in the middle rather than spreading to the edges. A phone's gaps are smaller (26dp with five on the S25).
// The same in the Mac app. The progress bar and the volume keep the full width.
private const val MAX_GAP_DP = 32f
// From this screen width the mini player has Now Playing's heart too, left of Play/Pause: a tablet's either way round,
// not a phone's, nor a narrow window in split screen. The same in the Mac app, by its window's width.
private const val WIDE_DP = 600

/**
 * The room's track and controls: the mini player at the foot of a screen, or all of Now Playing when the layout has a
 * progress bar. While its screen is visible it asks the room every few seconds; commands share that one thread, so a poll
 * never overlaps a command. The volume has a thread of its own: a speaker of a group slow to answer doesn't hold up
 * Play/Pause or the polls.
 */
@SuppressLint("SetTextI18n") // The volume is a plain number, as the Sonos app shows it.
class Player(private val a: Activity) {
    companion object {
        /** Hands [r] and the cover [hosts] to the next screen's player. */
        fun put(i: Intent, r: Room, hosts: Set<String>): Intent = i.putExtra(BrowseActivity.ROOM_ID, r.id)
            .putExtra(BrowseActivity.ROOM_NAME, r.name).putExtra(BrowseActivity.ROOM_HOST, r.host)
            .putExtra(BrowseActivity.ROOM_MEMBERS, r.members).putExtra(BrowseActivity.ROOM_OWN, r.own)
            .putExtra(BrowseActivity.ROOM_PLAYERS, r.players.toTypedArray()).putExtra(HOSTS, hosts.toTypedArray())

        fun room(i: Intent): Room? {
            val id = i.getStringExtra(BrowseActivity.ROOM_ID) ?: return null
            val host = i.getStringExtra(BrowseActivity.ROOM_HOST) ?: return null
            val own = i.getStringExtra(BrowseActivity.ROOM_OWN) ?: host
            return Room(
                id, i.getStringExtra(BrowseActivity.ROOM_NAME) ?: "", host, i.getIntExtra(BrowseActivity.ROOM_MEMBERS, 0), own,
                i.getStringArrayExtra(BrowseActivity.ROOM_PLAYERS)?.toList() ?: listOf(own),
            )
        }

        fun hosts(i: Intent): Array<String> = i.getStringArrayExtra(HOSTS) ?: emptyArray()

        /** Hands the cover [hosts] alone to the next screen's player, as the main screen's to Browse. */
        fun hosts(i: Intent, hosts: Set<String>): Intent = i.putExtra(HOSTS, hosts.toTypedArray())

        // The speakers' list of music services, read at most once a run unless it failed: kept in Prefs, and read again
        // only for a service it hadn't, as one added since.
        private val listed = AtomicBoolean()

        // After a failure, the uptime from which the list is asked for again: not at every poll, as each try may take
        // the whole timeout, and the poll shares its thread with Play/Pause and the other controls.
        @Volatile
        private var listAgain = 0L

        /** The UPnP servers' names by address, as the main screen found them: Now Playing's source for their tracks. */
        val servers: MutableMap<String, String> = ConcurrentHashMap()

        /** The Sonos players' addresses, as the household lists them: a favourite's cover may be on any of them. */
        val players: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** The household's rooms, as the main screen found them or a regrouping brought them: Room settings opens on them. */
        @Volatile
        var rooms: List<Room> = emptyList()

        /**
         * The household's favourites, at the player the main screen found them at, where its Favorites card and Browse
         * read them: the heart reads and saves there too, on every screen and whichever room plays, as in the Mac app.
         * Each player keeps its own copy, which takes a while to follow a change made at another: read at the room
         * that plays, the heart showed the copy before a change made in another room, and a tap did nothing or the
         * opposite. Until the main screen has found them, no heart, as in the Mac app.
         */
        @Volatile
        var favorites: Server? = null

        // What a player showed last, main thread only: the next screen's player on the same room starts from it, so
        // its cover, title and volume are there at once rather than after its first questions to the room.
        private var last: Last? = null
    }

    /** [answer] for [room]; [slow]: the uptime its volume, mute and play mode were read, maybe by an earlier poll. */
    private class Last(val room: Room, val answer: Answer, val slow: Long)

    /**
     * One poll's answer, [at] the uptime the position arrived; volume -1 and the nulls mean "not asked this time".
     * [tv]: the TV input's format, asked only on Now Playing while the TV plays. [household]: read again when the
     * group reported itself changed. [station]: a radio stream's, read once per stream.
     */
    private class Answer(
        val state: String, val pos: Position, val at: Long, val track: Track?, val volume: Int, val muted: Boolean?, val mode: Mode?,
        val queued: Boolean?, val tv: String?, val household: Upnp.Topology?, val group: String? = null,
        val station: Station? = null,
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
            skips = 0
            quiet = 0L
            playSent.out = 0
            muteSent.out = 0
            level.text = ""
            heard = false
            grouping = null
            place()
            v?.let { seed(it) }
            refresh()
        }

    /** Runs after the player followed the speakers to a new group, with the household as it is now. */
    var onRegroup: ((Upnp.Topology) -> Unit)? = null

    /**
     * Runs after the mini player's heart saved or removed a favourite, with the favourites as they are now: the screen
     * under it shows them at once, as the opening screen's Favorites card and Browse's Favorites. Now Playing's need
     * none: those screens read them again as they show again.
     */
    var onFavorites: ((List<Entry>) -> Unit)? = null

    /**
     * Each answer, on the main thread, with whether the room plays its queue (only known when [media] is set), and the
     * uptime it was read.
     */
    var onPosition: ((Position, Boolean, Long) -> Unit)? = null

    /** Also asks whether the room plays its queue, with the other slow values: for the Queue screen. */
    @Volatile
    var media = false

    /**
     * Whether the screen shows the mini player. The Queue doesn't, as in the Mac app: its ‹ goes back to Now Playing,
     * which has the controls. It keeps the player all the same, for the playing track's mark, a regrouping and the
     * volume buttons.
     */
    var bar = true
        set(value) {
            field = value
            place()
            if (value && live) retick() // Shown again: the clock shows and ticks on from where it is.
        }

    // Play state and position change all the time; volume, mute, play mode and the queue check only when someone
    // changes them. So those are asked every SLOW-th poll, and at once when [fresh]: after a change made here.
    private val slow = 3
    @Volatile
    private var fresh = true
    private var ticks = 0 // The poll thread's, like the two below.
    private var lastMeta: String? = null // TrackMetaData is parsed only when it differs from the last.
    private var lastTrack: Track? = null
    private var stationFor = "" // The stream lastStation was last asked for: asked again for another one.
    private var stationAgain = 0L // After a failure, when the same stream's station is asked again.
    private var lastStation: Station? = null
    @Volatile
    private var grouping: String? = null // The group as the coordinator last reported it; null: not asked yet.

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
    private val back: View? = a.findViewById(R.id.player_back)
    private val forward: View? = a.findViewById(R.id.player_forward)
    private val heart: ImageView? = a.findViewById(R.id.player_heart)
    private val roomName: TextView? = a.findViewById(R.id.player_room) // Now Playing's, in a row of its own.
    private val full = progress != null

    // Whether the heart shows: always on Now Playing; on the mini player while it shows, from WIDE_DP of screen width.
    // Without it, the favourites aren't read for it either.
    private val hearts get() = full || bar && a.resources.configuration.screenWidthDp >= WIDE_DP
    // The mini player's progress, a ring in its Play/Pause circle; Now Playing has its bar.
    private val ring: Ring? = if (full) null else {
        Ring(a.resources.displayMetrics.density, a.getColor(R.color.ring)).also { toggle.foreground = it }
    }

    // The heart: what plays now as a favourite (see Favorites), by its key, its DIDL to save it, and the favourite's id
    // while it is one: null when it isn't, "" while a save is on its way. Main thread only.
    private var heartKey = ""
    private var heartItem: String? = null
    private var heartId: String? = null
    private var saveableFor: List<Any?> = emptyList() // What saveable() last built its answer from, and that answer.
    private var saveableItem: Pair<String, String>? = null

    // Now Playing's cover by the screen's short side, which covers its widest in either orientation; the mini player
    // makes that size too, from the same download, so Now Playing has it the moment it opens.
    private val covers: Art = a.resources.displayMetrics.let { m ->
        val big = minOf(m.widthPixels, m.heightPixels, 1200)
        if (full) Art(big) { url -> loaded(url) } else Art((48 * m.density).toInt(), big) { url -> loaded(url) }
    }

    private fun loaded(url: String) {
        if (art.tag == url) covers[url]?.let { covers.fill(art, it) }
    }
    private var exec: ScheduledExecutorService? = null
    private var volumes: ExecutorService? = null // SetVolume, one at a time, beside exec.

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
    private var landed = 0L // The uptime the last SetVolume came back.
    private var sending = false
    private var skips = 0 // Quick skip seconds tapped while one is out: they go as one once it's back.
    private var skipping = false
    private var keyed = false // The last volume button press went to the room, so its release does too.
    // Changes sent here that show at once, Play/Pause and mute: an answer read before one landed would flip it back for
    // a moment, as a poll already out when it was pressed. As in the Mac app.
    private class Pending {
        var out = 0
        var landed = 0L // The uptime the last one came back.

        fun calm(at: Long) = out == 0 && at > landed
    }

    private val playSent = Pending()
    private val muteSent = Pending()

    // This room's volume was read since the room or the screen came: what another screen showed may be old, and the
    // volume buttons set an absolute level from it.
    private var heard = false

    init {
        toggle.setOnClickListener {
            if (room == null) return@setOnClickListener
            show(playing != true) // At once; the answers read after it landed confirm it.
            command(playSent, Sonos::toggle)
        }
        mute.setOnClickListener {
            if (room == null) return@setOnClickListener
            muted(muted != true) // The same.
            command(muteSent, Sonos::toggleMute)
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
        if (!full) {
            view.setOnClickListener { onOpen() }
            // The room's name, at the start of the line under the title, opens Room settings for it, as a link; anywhere
            // else on the player opens Now Playing, as before. For TalkBack, an action of the player's.
            roomLink()
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (rooms.isNotEmpty()) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_room_settings, a.getString(R.string.room_settings)))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action != R.id.action_room_settings) return super.performAccessibilityAction(host, action, args)
                    roomSettings()
                    return true
                }
            }
        } else {
            // Now Playing's room, in the row at its foot: the same.
            roomName?.setOnClickListener { roomSettings() }
        }
        a.findViewById<View?>(R.id.player_previous)?.setOnClickListener { previous() }
        a.findViewById<View?>(R.id.player_next)?.setOnClickListener { command(Sonos::next) }
        a.findViewById<View?>(R.id.player_queue)?.setOnClickListener { open(QueueActivity::class.java) }
        heart?.setOnClickListener { toggleHeart() }
        // The mini player's heart follows the width across WIDE_DP, as a window in split screen is resized: gone at once
        // below it, and above it the room is asked at once, so it shows without waiting for the next poll.
        if (!full) {
            var wide = hearts
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (hearts == wide) return@addOnLayoutChangeListener
                wide = hearts
                if (wide) refresh() else view.post { heart(room ?: return@post, null) } // Not inside the layout pass.
            }
        }
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
        // Now Playing's controls spread evenly, by what each draws; again whenever the row's width changes.
        if (full) {
            val bar = toggle.parent.parent as LinearLayout
            bar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
                if (r - l != or - ol) bar.post { spread(bar) } // Not inside the layout pass.
            }
        }
        // Quick skip, by the step chosen in Settings: without one, the row has its five.
        val s = Prefs.skip(a)
        if (s > 0 && back != null && forward != null) {
            row(s)
            back.setOnClickListener { skip(-s) }
            forward.setOnClickListener { skip(s) }
        }
    }

    /**
     * The quick skip buttons beside Play, the step in their arrows; spread() makes room for them, as for the five.
     */
    private fun row(s: Int) {
        back?.visibility = View.VISIBLE
        forward?.visibility = View.VISIBLE
        back?.contentDescription = a.resources.getQuantityString(R.plurals.skip_back, s, s)
        forward?.contentDescription = a.resources.getQuantityString(R.plurals.skip_forward, s, s)
        a.findViewById<TextView>(R.id.player_back_step).text = s.toString()
        a.findViewById<TextView>(R.id.player_forward_step).text = s.toString()
    }

    // What each draws, in dp, left to right: the icon's size times its path's width in its 24-unit grid (Shuffle 16,
    // the arrows 16, Repeat 18), and the circles: Previous and Next's 52, Play's 72.
    private val drawn = floatArrayOf(28 * 16 / 24f, 52f, 32 * 16 / 24f, 72f, 32 * 16 / 24f, 52f, 28 * 18 / 24f)

    /**
     * Each button shown as wide as what it draws plus one gap, half a gap at the ends, so the room between them is
     * the same whether five show or seven, and the circles sit midway; its height keeps the icon's size.
     */
    private fun spread(bar: LinearLayout) {
        val d = a.resources.displayMetrics.density
        var sum = 0f
        var n = 0
        for (i in 0 until bar.childCount) if (bar.getChildAt(i).visibility == View.VISIBLE) {
            sum += drawn[i]
            n++
        }
        val gap = ((bar.width / d - sum) / n).coerceIn(0f, MAX_GAP_DP) // The row is centred: what's left goes to its sides.
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                width = ((drawn[i] + gap) * d).toInt()
                weight = 0f
            }
            if (v is ImageView) v.setPadding(0, v.paddingTop, 0, v.paddingBottom)
        }
    }

    /**
     * Previous: the track's start, or the track before, which starts there too. The clock goes to 0:00 at once, as for a
     * seek: the answer right after it may still say the old place, or that the speaker buffers, which holds the clock,
     * and the next comes a whole Polling interval later. Not for what has no length: it gets Previous as it is.
     */
    private fun previous() {
        if (clock.length > 0) {
            clock.restart(SystemClock.uptimeMillis())
            retick()
        }
        command(Sonos::previous)
    }

    private fun seek(to: Int) {
        quiet = SystemClock.uptimeMillis() + QUIET_MS
        clock.seek(to, SystemClock.uptimeMillis())
        retick()
        command { Sonos.seek(it, to) }
    }

    /** [d] seconds on or back: the clock goes along at once, within the track, and the speaker from where it is. */
    private fun skip(d: Int) {
        if (progress?.isEnabled != true) return
        val now = SystemClock.uptimeMillis()
        clock.skip(d, now)
        quiet = now + QUIET_MS
        retick()
        skips += d
        if (!skipping) skipNow()
    }

    // One relative Seek out at a time: taps meanwhile add up and go as one once it's back.
    private fun skipNow() {
        val d = skips
        if (room == null || d == 0) return
        skips = 0
        skipping = true
        command { r ->
            try {
                Sonos.skip(r, d)
            } finally {
                ui.post {
                    skipping = false
                    skipNow()
                }
            }
        }
    }

    /** Quick skip only where the bar seeks: a track with a length, not the TV, a radio stream or paused Spotify. */
    private fun skippable(on: Boolean) {
        for (v in arrayOf(back, forward)) {
            v ?: continue
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.5f
        }
    }

    /** Asks the room at the Polling interval from the main screen: 2 s by default, up to 10 s. Watches WiFi meanwhile. */
    fun start() {
        heartKey = "" // The favourites are read again: one may have been saved or removed meanwhile, also elsewhere.
        Lan.start(a, lan)
        if (full && Lan.wifi == null) a.finish() // As when WiFi went while the app was away.
        place()
        if (covers.retry()) shown = "" // A cover dropped at stop() is asked for again by the first answer.
        fresh = true // Everything at once: while away, the volume or the grouping may have changed elsewhere.
        glide() // The screen shows again: the title slides once more.
        live = true
        val s = Prefs.poll(a).toLong()
        exec = Executors.newSingleThreadScheduledExecutor().apply { scheduleWithFixedDelay(::poll, 0, s, TimeUnit.SECONDS) }
        volumes = Executors.newSingleThreadExecutor()
        heard = false // While away, the volume may have changed elsewhere: the buttons wait until it's read again.
        // stop() halted the clock's ticks, and an answer that agrees with the clock doesn't restart them: back from
        // another app within the same track, the progress would stand still. Where the clock is now, ticking on.
        retick()
    }

    fun stop() {
        sliding?.cancel()
        Lan.stop(lan)
        live = false
        gen++
        // shutdownNow drops a volume change or quick skip still queued, so nothing is in flight any more.
        wanted = -1
        sending = false
        skips = 0
        skipping = false
        playSent.out = 0
        muteSent.out = 0
        exec?.shutdownNow()
        exec = null
        volumes?.shutdownNow()
        volumes = null
        ui.removeCallbacks(tick)
        covers.pause()
    }

    // Between start() and stop(): a command still running when the screen stopped doesn't poll, nor restart the clock.
    @Volatile
    private var live = false

    // The position's own clock (see Clock), shown at each second it reaches. Main thread only.
    private val ui = Handler(Looper.getMainLooper())
    private val clock = Clock()
    private val tick = object : Runnable {
        override fun run() {
            // Nothing to show on a screen without the mini player, as the Queue: its polls and volume buttons go on.
            if (!full && !bar) return
            val now = SystemClock.uptimeMillis()
            if (!seeking) {
                val e = clock.shown(now)
                progress?.progress = e
                times(e, clock.length)
                ring?.set(e, clock.length)
            }
            if (clock.running && clock.length > 0) ui.postDelayed(this, clock.untilNext(now)) // Right when the next second starts.
        }
    }

    /** The clock changed: shows it now, then at each second it reaches. */
    private fun retick() {
        ui.removeCallbacks(tick)
        tick.run()
    }

    fun destroy() = covers.stop()

    private var sliding: Animator? = null // The mini player's title on its way, main thread only.

    /**
     * The mini player's title, when it's too long for its row: after a moment it slides to its end, waits, and slides
     * back as fast, its edges fading meanwhile; then it ends in "…" again. Once per title, and again as the screen shows. Not
     * with Android's animations off, as by Remove animations, nor for right-to-left text.
     */
    private fun glide() {
        sliding?.cancel()
        if (full || !ValueAnimator.areAnimatorsEnabled()) return
        title.post {
            // Once laid out; not for a screen that stopped meanwhile, and only one at a time: a title set twice in a
            // row, as the seeded one and then the room's, posts two.
            if (!live) return@post
            sliding?.cancel()
            val room = title.width - title.totalPaddingLeft - title.totalPaddingRight
            val dx = (title.paint.measureText(title.text, 0, title.text.length) - room).toInt()
            if (room <= 0 || dx <= 0 || title.layout?.getParagraphDirection(0) != Layout.DIR_LEFT_TO_RIGHT) return@post
            val ms = (dx * 1000 / (GLIDE_DP_S * a.resources.displayMetrics.density)).toLong()
            val there = ValueAnimator.ofInt(0, dx).setDuration(ms).apply { startDelay = GLIDE_WAIT_MS }
            val back = ValueAnimator.ofInt(dx, 0).setDuration(ms).apply { startDelay = GLIDE_WAIT_MS } // Back as fast as there.
            for (v in listOf(there, back)) v.addUpdateListener {
                // Its first frame: the whole title on one line, clipped at the edges, not "…". In code: Android turns
                // a layout's scrollHorizontally off again for a view with maxLines.
                if (title.ellipsize != null) {
                    title.ellipsize = null
                    title.setHorizontallyScrolling(true)
                    title.isHorizontalFadingEdgeEnabled = true
                }
                title.scrollTo(it.animatedValue as Int, 0)
            }
            sliding = AnimatorSet().apply {
                interpolator = LinearInterpolator()
                playSequentially(there, back)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(x: Animator) { // Also after a cancel.
                        title.scrollTo(0, 0)
                        title.isHorizontalFadingEdgeEnabled = false
                        title.setHorizontallyScrolling(false)
                        title.ellipsize = TextUtils.TruncateAt.END
                    }
                })
                start()
            }
        }
    }

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

    /** [work] as command() runs it, followed by [p]: the answers read before it's back don't show (see Pending). */
    private fun command(p: Pending, work: (Room) -> Unit) {
        p.out++
        val g = gen
        command { r ->
            try {
                work(r)
            } finally {
                val t = SystemClock.uptimeMillis() // Here, before the poll that follows it on this thread.
                a.runOnUiThread {
                    if (g != gen) return@runOnUiThread // A room switch or stop() has reset it meanwhile.
                    p.out--
                    p.landed = t
                }
            }
        }
    }

    /** Runs [work] on the room, then asks again so the screen follows; only a failure shows. */
    private fun command(work: (Room) -> Unit) {
        val r = room ?: return
        later {
            try {
                work(r)
            } catch (e: Exception) {
                // The speaker answered, but refused, as Pause on the TV input (701): not a lost connection. That message
                // is only for a speaker that didn't answer at all.
                val text = if (e is HttpException && e.upnp > 0) a.getString(R.string.not_supported) else a.unreachable(r.name)
                a.runOnUiThread { a.toast(text) }
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
        keyed = room != null && Lan.wifi != null && heard && (full || view.visibility == View.VISIBLE || !bar)
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
        val sent = runCatching {
            volumes?.execute {
                runCatching { Sonos.volume(r, v) }
                fresh = true // The next poll reads the volume back, once the quiet time lets it show.
                a.runOnUiThread {
                    if (g != gen) return@runOnUiThread // A room switch or stop() has reset all this meanwhile.
                    landed = SystemClock.uptimeMillis()
                    sending = false
                    if (wanted >= 0) send(wanted)
                }
            }
        }.isSuccess
        if (volumes == null || !sent) sending = false // Stopped: nothing went out.
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
            // A radio stream's station, once per stream link (~10 ms): for Sonos Radio once per track, as each has its
            // own. After a failure it waits a minute, as the music services below; meanwhile no station shows, not the
            // one before.
            if (source(p.uri).let { it != Source.RADIO && it != Source.SONOS_RADIO }) {
                stationFor = ""
                lastStation = null
            } else if (p.uri != stationFor || lastStation == null && SystemClock.elapsedRealtime() >= stationAgain) {
                lastStation = null
                stationFor = p.uri
                runCatching { Sonos.station(r) }.onSuccess {
                    lastStation = it
                }.onFailure {
                    stationAgain = SystemClock.elapsedRealtime() + META_AGAIN_MS
                }
            }
            // A music service's name for Now Playing, as Qobuz: from the speakers' own list, read for one not kept yet.
            if (source(p.uri) == Source.OTHER) serviceId(p.uri)?.let { id ->
                if (Prefs.service(a, id) == null && SystemClock.elapsedRealtime() >= listAgain && listed.compareAndSet(false, true)) {
                    runCatching { Sonos.services(r) }.onSuccess { Prefs.services(a, it) }.onFailure {
                        listAgain = SystemClock.elapsedRealtime() + META_AGAIN_MS
                        listed.set(false)
                    }
                }
            }
            // With the slow values, whether the speakers were grouped anew, also by the Sonos app or Spotify; only then
            // the household itself (~4 KB), once per change. Neither failing keeps the rest from showing.
            val now = if (all) runCatching { Sonos.group(r) }.getOrNull() else null
            val household = if (now == null || now == grouping) null else runCatching { Upnp.topology(r.host) }.getOrNull()
            Answer(
                state, p, got, lastTrack,
                if (all) Sonos.volume(r) else -1, if (all) Sonos.muted(r) else null,
                // The play mode for Now Playing, the queue check for the Queue: neither failing keeps the rest from
                // showing, as the TV or line-in may not answer the mode. As the Mac app.
                if (all && full) runCatching { Sonos.mode(r) }.getOrNull() else null,
                if (all && media) runCatching { Sonos.queued(r) }.getOrNull() else null,
                // Its own failure only leaves the line empty; the player still shows the rest.
                if (full && p.uri.startsWith("x-sonos-htastream:")) runCatching { Sonos.tvFormat(r) }.getOrNull() else null,
                household, now, lastStation,
            )
        }.getOrNull()
        a.runOnUiThread { if (g == gen) show(r, answer) }
    }

    /**
     * What plays now as a favourite to save, by its key: a service's station, a Music library track, a server's track.
     * Null for anything else, as Spotify Connect, the TV, line-in, AirPlay, Sonos Radio or a station's bare stream.
     * Built again only when what it's built from changes: the track and station are parsed once per change already,
     * so at a poll that changes nothing this is a comparison.
     */
    private fun saveable(r: Room, answer: Answer, kind: Source, t: Track?, st: Station?): Pair<String, String>? {
        val p = answer.pos
        val service = if (kind == Source.RADIO) st?.uri?.let(::serviceId)?.let { Prefs.service(a, it) }.orEmpty() else ""
        val from = listOf(r.host, kind, p.uri, t, st, service)
        if (from == saveableFor) return saveableItem
        val didl = when (kind) {
            Source.RADIO -> st?.let { Favorites.station(it.uri, it.name, it.art, service) }
            Source.LIBRARY -> t?.let { Favorites.library(p.uri, it.title, r.host, it.protocol) }
            Source.SERVER -> t?.let { Favorites.server(p.uri, it.title, it.artist, it.art, it.protocol, p.meta.orEmpty()) }
            else -> null
        }
        saveableFor = from
        saveableItem = didl?.let { Favorites.key(if (kind == Source.RADIO) st?.uri ?: p.uri else p.uri) to it }
        return saveableItem
    }

    /**
     * The heart for [item], what plays now: hidden for what can't be a favourite; else, for something new, hidden until
     * the favourites are read, so it never shows the wrong state, then outlined or filled.
     */
    private fun heart(r: Room, item: Pair<String, String>?) {
        val h = heart ?: return
        if (item == null) {
            heartKey = ""
            h.shown = false
            return
        }
        heartItem = item.second
        if (item.first == heartKey) return
        val key = item.first
        heartKey = key
        h.shown = false
        later {
            val s = favorites ?: return@later a.runOnUiThread { if (key == heartKey) heartKey = "" } // Asked again later.
            val f = runCatching { Sonos.favorites(s) }.getOrNull()
            a.runOnUiThread {
                if (key != heartKey) return@runOnUiThread
                // Unknown: no heart, rather than a wrong one, and the next poll asks again.
                if (f == null) return@runOnUiThread run { heartKey = "" }
                heartId = match(f, key)
                drawHeart()
                h.shown = true
            }
        }
    }

    /** Saves what plays as a favourite, or removes it: the heart turns at once; the favourites read after confirm it. */
    private fun toggleHeart() {
        val key = heartKey.ifEmpty { return }
        val didl = heartItem ?: return
        val s = favorites ?: return
        val id = heartId
        if (id == "") return // A save on its way: the heart follows once it's in.
        heartId = if (id == null) "" else null
        drawHeart()
        command {
            try {
                if (id == null) Sonos.addFavorite(s.control, didl) else Sonos.removeFavorite(s.control, id)
            } catch (_: Exception) {
                a.runOnUiThread {
                    a.toast(a.getString(if (id == null) R.string.favorite_error else R.string.unfavorite_error))
                    if (key == heartKey) { // As it was, unless the read below says otherwise.
                        heartId = id
                        drawHeart()
                    }
                }
            } finally {
                val f = runCatching { Sonos.favorites(s) }.getOrNull()
                a.runOnUiThread {
                    f?.let { onFavorites?.invoke(it) } // Also when what plays changed meanwhile: the save stands.
                    if (key != heartKey) return@runOnUiThread
                    if (f != null) {
                        heartId = match(f, key)
                        drawHeart()
                    } else { // Unconfirmed, maybe saved after all: no heart until the next poll has read them again.
                        heartKey = ""
                        heart?.shown = false
                    }
                }
            }
        }
    }

    private fun match(favorites: List<Entry>, key: String) =
        favorites.firstOrNull { it.fav?.uri?.isNotEmpty() == true && Favorites.key(it.fav.uri) == key }?.id

    /** Filled in green while what plays is a favourite; grey and outlined while it isn't. */
    private fun drawHeart() {
        val on = heartId != null
        heart?.setImageResource(if (on) R.drawable.ic_heart_on else R.drawable.ic_heart)
        heart?.contentDescription = a.getString(if (on) R.string.favorite_remove else R.string.favorite_add)
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
        v.imageTintList = ColorStateList.valueOf(a.getColor(if (on) R.color.accent_text else R.color.text2))
    }

    private fun times(e: Int, d: Int) {
        elapsed?.text = if (d > 0) clock(e) else ""
        remaining?.text = if (d > 0) "-" + clock(d - e) else ""
    }

    /**
     * The mini player shows whenever there's a room to control, whatever plays or not, No content too: it doesn't come
     * and go with what played before, as in the Mac app. It hides only with nothing to control: without WiFi, or
     * without a Sonos room; and on a screen without it ([bar]). Now Playing closes instead.
     */
    private fun place() {
        if (full) return
        val on = bar && room != null && Lan.wifi != null
        val now = if (on) View.VISIBLE else View.GONE
        if (view.visibility == now) return
        view.visibility = now
    }

    /** Room settings, opened on the room the player follows: the one chosen on the main screen, in its group. */
    private fun roomSettings() {
        val all = rooms
        if (all.isNotEmpty()) a.startActivity(RoomActivity.intent(a, all))
    }

    /**
     * The room's name as a link in the mini player's line. Not a click of the line: the rest of it opens Now Playing, so
     * TalkBack has Room settings as the player's own action instead (see init).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun roomLink() = meta.setOnTouchListener { _, e -> roomTouch(e) }

    private var onRoom = false // A touch began on the room's name in the mini player's line (main thread only).

    /**
     * A touch on the mini player's line: one that begins on the room's name, at its start, is the name's, and opens Room
     * settings when it ends there; any other goes to the player, which opens Now Playing.
     */
    private fun roomTouch(e: MotionEvent): Boolean {
        val n = room?.name?.length ?: 0
        val l = meta.layout
        val on = n > 0 && rooms.isNotEmpty() && l != null && meta.text.length >= n &&
            e.x <= meta.totalPaddingLeft + l.getPrimaryHorizontal(n) - meta.scrollX
        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> on.also { onRoom = it }
            MotionEvent.ACTION_UP -> onRoom.also {
                if (onRoom && on) roomSettings()
                onRoom = false
            }
            MotionEvent.ACTION_CANCEL -> onRoom.also { onRoom = false }
            else -> onRoom
        }
    }

    /**
     * The speakers were grouped anew: the player moves to the group of the room chosen on the main screen, as that
     * screen does, when it isn't on it already. True when it moved.
     */
    private fun follow(t: Upnp.Topology): Boolean {
        rooms = t.rooms // Room settings shows the household as it is now.
        val g = t.group(Prefs.room(a)) ?: return false
        if (g.same(room)) return false
        room = g // Asks the new group at once.
        onRegroup?.invoke(t)
        return true
    }

    /**
     * Shows what a player showed for [r] last, if it did lately: the track, its place, which counts on from when it
     * was read, and the volume and mute when those are recent too.
     */
    private fun seed(r: Room) {
        val l = last?.takeIf { it.room.same(r) } ?: return
        val now = SystemClock.uptimeMillis()
        if (now - l.answer.at > SEED_MS) return
        val a = l.answer
        show(
            r, if (now - l.slow <= SEED_MS) a else Answer(a.state, a.pos, a.at, a.track, -1, null, null, null, a.tv, null, station = a.station),
            seeded = true,
        )
    }

    /** Keeps [a] for the next screen's player, with the volume, mute and play mode of the last answer that had them. */
    private fun remember(r: Room, a: Answer) {
        val p = last?.takeIf { a.volume < 0 && it.room.same(r) }
        val s = p?.answer
        val kept = Answer(
            a.state, a.pos, a.at, a.track, s?.volume ?: a.volume, s?.muted ?: a.muted, s?.mode ?: a.mode,
            s?.queued ?: a.queued, a.tv, null, // Never the household: a seed doesn't regroup.
            station = a.station,
        )
        last = Last(r, kept, p?.slow ?: a.at)
    }

    /** [seeded]: what another screen showed, not this room's answer yet. */
    private fun show(r: Room, answer: Answer?, seeded: Boolean = false) {
        // The new grouping counts as seen only once its answer is here, whole: a poll that failed after reading it
        // leaves it to the next. Before follow(), whose room change resets it.
        val h = answer?.household
        if (h != null) {
            grouping = answer.group
            if (follow(h)) return // The answer was the group before.
        }
        if (answer != null) remember(r, answer)
        val t = answer?.track
        val plays = answer != null && (answer.state == "PLAYING" || answer.state == "TRANSITIONING")
        place()
        if (answer != null) {
            // Not from a reading taken before a Play/Pause or mute sent here landed: it would flip back for a moment.
            if (playSent.calm(answer.at)) show(plays)
            if (muteSent.calm(answer.at)) answer.muted?.let { muted(it) }
            answer.queued?.let { queued = it }
        }
        val st = answer?.station
        val kind = if (answer != null) source(answer.pos.uri, Prefs.model(a, r.id)) else Source.OTHER
        heart(r, answer?.takeIf { hearts }?.let { saveable(r, it, kind, t, st) })
        val name = when {
            answer == null -> a.unreachable(r.name)
            t != null -> t.title
            // Sources without metadata, known by their URI: the TV input, and Spotify Connect while paused; a radio
            // station by its name.
            answer.pos.uri.startsWith("x-sonos-htastream:") -> a.getString(R.string.tv)
            answer.pos.uri.startsWith("x-sonos-vli:") && ",spotify:" in answer.pos.uri -> a.getString(R.string.spotify)
            st != null && st.name.isNotEmpty() -> st.name
            else -> a.getString(R.string.no_content)
        }
        // A radio stream: its station where the artist goes, as the Sonos app has it, and its logo as the cover. Not
        // twice: between songs a station may send its own name as the song.
        val artist = t?.artist?.ifEmpty { st?.name?.takeIf { it != t.title } }.orEmpty()
        // The room leads the mini player's line; Now Playing has it in its own row, under the controls.
        val detail = listOf(if (full) "" else r.name, artist, if (full) t?.album ?: "" else "").filter { it.isNotEmpty() }.joinToString(" · ")
        val cover = when {
            // Sonos Radio: the speaker's copy of a track's cover can be another station's logo; its station's is right.
            kind == Source.SONOS_RADIO -> st?.let { cover(r, it.art, it.uri) }
            t != null && t.art.isNotEmpty() -> cover(r, t.art, t.uri)
            st != null -> cover(r, st.art, st.uri)
            else -> null
        }
        // Now Playing's line: where it comes from, unless the title says so already, then its format. The TV has no
        // track, but its input format.
        val src = if (full && answer != null) sourceName(kind, answer.pos.uri).takeIf { it != name } ?: "" else ""
        val quality = listOf(src, t?.format ?: answer?.tv ?: "").filter { it.isNotEmpty() }.joinToString(" · ")
        val now = "${r.name}\n$name\n$detail\n$cover\n$quality"
        if (now != shown) {
            shown = now
            if (title.text.toString() != name) {
                title.text = name
                glide() // Only a new title slides again.
            }
            title.setTextColor(a.getColor(if (answer == null) R.color.warning else R.color.text)) // No WiFi, or no room.
            meta.text = SpannableString(detail).apply {
                if (!full && r.name.isNotEmpty()) setSpan(ForegroundColorSpan(a.getColor(R.color.accent_text)), 0, r.name.length, 0)
            }
            roomName?.text = r.name
            // A lossless track's format in green: it reaches the speaker as it was recorded.
            format?.text = SpannableString(quality).apply {
                val f = t?.format.orEmpty()
                if (t?.lossless == true && f.isNotEmpty() && quality.endsWith(f)) {
                    setSpan(ForegroundColorSpan(a.getColor(R.color.success)), quality.length - f.length, quality.length, 0)
                }
            }
            covers.show(art, cover, R.drawable.ic_music)
        }
        if (answer == null) return
        val v = answer.volume
        // Not while a change is out, nor from a reading taken before the last one landed: a slow speaker would have
        // the slider jump back, and the next volume button press go from there.
        val calm = !sending && SystemClock.uptimeMillis() > quiet && answer.at > landed
        if (v >= 0 && !dragging && calm && v != volume.progress) volume.progress = v
        if (level.text.isEmpty() && v >= 0) level.text = v.toString()
        if (v >= 0 && !seeded) heard = true // Read from the room itself: the volume buttons may go from it.

        // The clock, for Now Playing's bar and times and the mini player's ring (see Clock). Not while the bar is dragged.
        if (!seeking) {
            val p = answer.pos
            val c = clock.answer(answer.state, p.elapsed, p.duration, answer.at)
            if (c.length) {
                progress?.isEnabled = p.duration > 0
                progress?.max = maxOf(p.duration, 1)
                skippable(p.duration > 0)
            }
            if (c.moved) retick()
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
        onPosition?.invoke(answer.pos, queued, answer.at)
    }

    /**
     * [uri]'s source in the Sonos app's words, a music service as the speakers name it, or "" for one without a name, as
     * a server unknown.
     */
    private fun sourceName(kind: Source, uri: String) = when (kind) {
        Source.HDMI -> a.getString(R.string.hdmi)
        Source.SPDIF -> a.getString(R.string.spdif)
        Source.SPOTIFY -> a.getString(R.string.spotify)
        Source.AIRPLAY -> a.getString(R.string.airplay)
        Source.LIBRARY -> a.getString(R.string.music_library)
        Source.LINE_IN -> a.getString(R.string.line_in)
        Source.RADIO -> a.getString(R.string.radio)
        Source.SONOS_RADIO -> a.getString(R.string.sonos_radio)
        Source.SERVER -> runCatching { URL(uri).host }.getOrNull()?.let { servers[it] } ?: ""
        Source.OTHER -> serviceId(uri)?.let { Prefs.service(a, it) } ?: ""
        Source.TV -> "" // The title says TV already.
    }

    /**
     * The cover's URL when it is on the speaker (often relative, as /getaa?…) or on a known server. One on the internet,
     * as Spotify's, comes from the speaker too: it fetches the cover of the track's [uri] itself, so the phone never
     * leaves the local network. Else none.
     */
    fun cover(r: Room, ref: String, uri: String = ""): String? {
        if (ref.isEmpty()) return null
        val u = runCatching { URL(URL("http://${r.host}/"), ref) }.getOrNull() ?: return null
        // A server's own: one this screen was told of, or any the main screen found since, as the track playing may be
        // another server's than the one browsed.
        if (u.protocol == "http" && (u.host == r.host.substringBefore(':') || u.host in hosts || u.host in servers)) {
            return u.toString()
        }
        return if (uri.isEmpty()) null else Sonos.copy(r.host, uri)
    }
}

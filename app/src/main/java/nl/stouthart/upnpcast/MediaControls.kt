package nl.stouthart.upnpcast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val QUIET_MS = 10 * 60_000L // Paused, stopped or out of reach this long: the media player goes.
// Silent: Android shows a media notification as the media player alone, in Quick Settings and on the lock screen,
// with no status bar icon, whatever its importance. "playing" was the first channel's name; it goes.
private const val CHANNEL = "now_playing"
private const val NOTE = 1
private const val STOP = "nl.stouthart.upnpcast.STOP" // The media player swiped away.

/**
 * The system's media player for the room the player controls, as the Sonos app has it: in Quick Settings and on the
 * lock screen, with the cover, Play/Pause, Previous, Next and the progress; and the phone's volume buttons set the room's
 * volume from anywhere, also with the screen off. From when the room plays, also in the background, until it has been
 * paused, stopped or out of reach for 10 minutes, or the media player is swiped away. As the Mac app's menu bar Now
 * Playing (MediaKeys.swift): one for the app, however many screens; Play and Pause by the room's own state. A screen's
 * player feeds it what it reads ([show]), so the room isn't asked twice; with no screen shown, the service asks it at the
 * Polling interval. Main thread only, but for [now], which the service's poll reads.
 */
object MediaControls {
    /** What the room plays, as a screen showed it last: [at] the uptime of the reading, [volume] -1 while unknown. */
    data class Now(
        val room: Room, val title: String, val by: String, val album: String, val cover: String?, val playing: Boolean,
        val elapsed: Int, val duration: Int, val at: Long, val volume: Int, val hosts: Set<String>,
    )

    @Volatile // Set on the main thread; the service's poll reads it on its own.
    var now: Now? = null
        internal set

    internal var service: MediaControlsService? = null

    // The screens' players asking the room now, each with its way to ask again: then the service doesn't ask.
    private val watchers = LinkedHashSet<() -> Unit>()

    @Volatile
    internal var watched = false
        private set

    fun watch(again: () -> Unit) {
        watchers += again
        watched = true
    }

    fun unwatch(again: () -> Unit) {
        watchers -= again
        watched = watchers.isNotEmpty()
    }

    /** A command went out from the media player: the screen showing asks the room again at once. */
    internal fun again() = watchers.lastOrNull()?.invoke()

    /** What a screen read; the media player starts once the room plays, from the screen, as Android allows it. */
    fun show(c: Context, n: Now) {
        now = n
        val s = service
        if (s != null) s.show()
        else if (n.playing) runCatching { c.startForegroundService(Intent(c, MediaControlsService::class.java)) }
    }

    /** A screen's cover came in, or the service's own: shown if it's the one. */
    fun covered(url: String) {
        if (now?.cover == url) service?.covered(url)
    }
}

class MediaControlsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var session: MediaSession
    private lateinit var art: Art // Covers while no screen shows, at Now Playing's size, so a screen finds them too.
    private var side = 0
    private var exec: ScheduledExecutorService? = null // The polls and commands, one at a time, as a screen's player's.
    private var volumes: ExecutorService? = null // SetVolume, beside them.
    private val volumeSender = VolumeSender().apply { send = ::sendVolume }
    private var provider: VolumeProvider? = null
    private var volumeRoom: Room? = null // The room the provider's volume is: another room's starts afresh.
    private var quiet = 0L // Since then (elapsed realtime) the room hasn't played; 0 while it plays.
    private var meta = "" // What the media player shows, to set it again only when it changes.
    private var shownPlaying: Boolean? = null
    private var shownAt = 0L // The position shown, in ms, as of shownTime (elapsed realtime).
    private var shownTime = 0L
    private var shownDuration = -1 // Seeking only where there's a length: a stream has none.
    private val lan = { later { if (!MediaControls.watched) poll() } }

    // While no screen shows, the service's own reading, on exec's thread: the track parsed once per change, a radio
    // stream's station once per link, the volume every third poll, as a screen's player does.
    private var lastMeta: String? = null
    private var lastTrack: Track? = null
    private var station: Station? = null
    private var stationFor = ""
    private var stationAgain = 0L // After a failed read, a minute until the next, as a screen's player waits.
    private var reads: ExecutorService? = null // A poll's questions side by side, as a screen's player asks them.
    private var ticks = 0

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        CoverDisk.init(this)
        side = Art.side(this)
        art = Art(side) { MediaControls.covered(it) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("playing")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.playing_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        session = MediaSession(this, getString(R.string.app_name)).apply {
            setCallback(callback, main)
            setSessionActivity(open())
            isActive = true
        }
        MediaControls.service = this
        startForeground(NOTE, note(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        Lan.start(this, lan)
        volumes = Executors.newSingleThreadExecutor()
        reads = Executors.newCachedThreadPool()
        exec = Executors.newSingleThreadScheduledExecutor().apply {
            scheduleWithFixedDelay(::tick, 0, Prefs.poll(this@MediaControlsService).toLong(), TimeUnit.SECONDS)
        }
        show()
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        MediaControls.service = null
        exec?.shutdownNow()
        volumes?.shutdownNow()
        reads?.shutdownNow()
        main.removeCallbacksAndMessages(null)
        Lan.stop(lan)
        art.stop()
        session.release()
        super.onDestroy()
    }

    /** What [MediaControls.now] holds, onto the media player: its track and cover when they change, its progress, its volume. */
    // NotificationPermission: a media session's notification, which Android 13 and later show without that permission.
    @SuppressLint("NotificationPermission")
    fun show() {
        val n = MediaControls.now ?: return
        val t = SystemClock.elapsedRealtime()
        if (n.playing) quiet = 0 else if (quiet == 0L) quiet = t
        val cover = n.cover?.let { Art.cached(it, side) }
        // A screen's player fetches it, in the size this takes too (covered); with none shown, or one fetched before
        // this service started, the service.
        if (n.cover != null && cover == null && (!MediaControls.watched || meta.isEmpty())) art.want(n.cover)
        val m = "${n.title}\n${n.by}\n${n.album}\n${n.duration}\n${n.cover}\n${cover != null}"
        if (m != meta || n.playing != shownPlaying) {
            if (m != meta) {
                meta = m
                session.setMetadata(
                    MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, n.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, n.by).putString(MediaMetadata.METADATA_KEY_ALBUM, n.album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, n.duration * 1000L)
                        .apply { cover?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }.build(),
                )
            }
            getSystemService(NotificationManager::class.java).notify(NOTE, note(cover))
        }
        // The progress, as of the reading: the system moves it on by itself while it plays. Set again only when the
        // state changes, or the room is somewhere else than it would be by now, as after a seek.
        val asOf = t - (SystemClock.uptimeMillis() - n.at)
        val at = n.elapsed * 1000L
        val expected = shownAt + if (shownPlaying == true) asOf - shownTime else 0
        if (n.playing != shownPlaying || n.duration != shownDuration || abs(expected - at) > 2000) {
            shownPlaying = n.playing
            shownDuration = n.duration
            shownAt = at
            shownTime = asOf
            val seek = if (n.duration > 0) PlaybackState.ACTION_SEEK_TO else 0
            session.setPlaybackState(
                PlaybackState.Builder().setState(
                    if (n.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, at, if (n.playing) 1f else 0f, asOf,
                ).setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or seek,
                ).build(),
            )
        }
        // Another room: what the one before still had to send stays there, as a screen's player does it, and the
        // volume buttons wait until this one's is read.
        if (!n.room.same(volumeRoom)) {
            volumeRoom = n.room
            volumeSender.forget()
            provider = null
        }
        // The volume buttons on the room once its volume is known; not from a reading taken before a change landed.
        if (n.volume >= 0) {
            val p = provider
            if (p == null) session.setPlaybackToRemote(volume(n.volume).also { provider = it })
            else if (volumeSender.calm(n.at)) p.currentVolume = n.volume
        }
    }

    /** [url] came in at a screen's size: at this size too, or else fetched at it, from disk if kept there. */
    fun covered(url: String) {
        if (Art.cached(url, side) == null) art.want(url) else show()
    }

    /** The room's volume for the phone's volume buttons: by the Volume button step, as in the app, and absolute. */
    private fun volume(v: Int) = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, v) {
        override fun onSetVolumeTo(volume: Int) = want(volume)

        override fun onAdjustVolume(direction: Int) {
            if (direction != 0) want(currentVolume + direction * Prefs.step(this@MediaControlsService))
        }

        private fun want(to: Int) {
            if (provider !== this) return // Another room's by now.
            val v = to.coerceIn(0, 100)
            if (v == currentVolume) return
            currentVolume = v
            volumeSender.want(v)
        }
    }

    /** VolumeSender's way out: SetVolume [v] on its own thread, [landed] once it's back. */
    private fun sendVolume(v: Int, landed: () -> Unit): Boolean {
        val r = volumeRoom ?: return false
        val p = provider ?: return false
        val pool = volumes ?: return false
        return runCatching {
            pool.execute {
                runCatching { Sonos.volume(r, v) }
                main.post { if (provider === p) landed() } // Not once another room's volume has taken over.
            }
        }.isSuccess
    }

    private val callback = object : MediaSession.Callback() {
        // By the room's own state, as the Mac app's Play and Pause: not the opposite of what was asked.
        override fun onPlay() = play(true)

        override fun onPause() = play(false)

        override fun onSkipToNext() = command(Sonos::next)

        override fun onSkipToPrevious() = command(Sonos::previous)

        override fun onSeekTo(ms: Long) = command { Sonos.seek(it, (ms / 1000).toInt()) }
    }

    /** Play or pause; the media player shows it at once, the answers after it landed confirm it. */
    private fun play(on: Boolean) {
        MediaControls.now?.let {
            MediaControls.now = it.copy(playing = on)
            show()
        }
        command { Sonos.toggle(it, on) }
    }

    /** Runs [work] on the room, then reads it again: the service itself, or the screen showing. */
    private fun command(work: (Room) -> Unit) {
        val r = MediaControls.now?.room ?: return
        later {
            runCatching { work(r) }
            if (MediaControls.watched) main.post { MediaControls.again() } else poll()
        }
    }

    private fun later(work: () -> Unit) {
        runCatching { exec?.execute(work) } // Refused once stopped.
    }

    /** At the Polling interval: the room asked while no screen does; the media player gone once quiet long enough. */
    private fun tick() {
        if (!MediaControls.watched) poll()
        main.post { if (quiet > 0 && SystemClock.elapsedRealtime() - quiet > QUIET_MS) stopSelf() }
    }

    /** The room's state, position, track and volume, named as a screen names them (Player.name). On exec's thread. */
    private fun poll() {
        val n = MediaControls.now ?: return
        val r = n.room
        val pool = reads ?: return
        val read = if (Lan.wifi == null) null else runCatching {
            // The play state, the position and every third time the volume side by side, as a screen's player asks
            // them: each reply's wait is its own, not added to the others'.
            val asked = pool.submit<String> { Sonos.state(r) }
            val vol = if (ticks++ % 3 == 0) pool.submit<Int> { runCatching { Sonos.volume(r) }.getOrDefault(-1) } else null
            val p = Sonos.position(r)
            val at = SystemClock.uptimeMillis() // Before the waits below, which would make the position look older.
            val state = asked.get()
            if (p.meta != lastMeta) {
                lastTrack = Sonos.track(p.meta)
                lastMeta = p.meta
            }
            val kind = source(p.uri, Prefs.model(this, r.id))
            if (kind != Source.RADIO && kind != Source.SONOS_RADIO) {
                stationFor = ""
                station = null
            } else if (p.uri != stationFor || station == null && SystemClock.elapsedRealtime() >= stationAgain) {
                stationFor = p.uri
                station = runCatching { Sonos.station(r) }.getOrNull()
                if (station == null) stationAgain = SystemClock.elapsedRealtime() + META_AGAIN_MS
            }
            val v = vol?.get() ?: -1
            val named = Player.name(this, r, p.uri, kind, lastTrack, station, n.hosts)
            n.copy(
                title = named.title, by = named.by, album = lastTrack?.album.orEmpty(), cover = named.cover,
                playing = state == "PLAYING" || state == "TRANSITIONING", elapsed = p.elapsed, duration = p.duration, at = at,
                volume = if (v >= 0) v else n.volume,
            )
        }.getOrNull()
        main.post {
            // A screen took over meanwhile, or another room: theirs counts.
            if (MediaControls.watched || MediaControls.now?.room?.id != r.id) return@post
            MediaControls.now = read ?: n.copy(playing = false) // Out of reach: as paused, until it answers again.
            show()
        }
    }

    /** The notification the media player comes from: what plays, and back to the app with a tap. */
    private fun note(cover: Bitmap?): Notification {
        val n = MediaControls.now
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n?.title ?: getString(R.string.app_name))
            .setContentText(n?.by)
            .setLargeIcon(cover)
            .setContentIntent(open())
            .setDeleteIntent(PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(n?.playing == true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
            .build()
    }

    /** The app as it was left, as from the launcher: no second opening screen on top. */
    private fun open() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )
}

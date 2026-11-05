package nl.stouthart.upnpcast

import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.START_TAG
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.abs

/** Where tracks go in the room's queue, in the Sonos app's terms. */
enum class How { NOW, NEXT, END, REPLACE }

enum class Repeat { OFF, ALL, ONE }

/** What the room is playing, from its TrackMetaData; [art] may be relative to the speaker. */
/** The playing track; [uri] is its own, which the speaker can find a streaming service's cover for. */
class Track(
    val title: String, val artist: String, val album: String, val art: String, val format: String, val uri: String,
    val lossless: Boolean, // See Res.lossless: Now Playing shows the format in green.
)

/**
 * [title] is no more than its stream link's file name, as regc-90s90strance-mp3-192-…?sABC=… for
 * …/regc-90s90strance-mp3-192-2383645?sABC=…: not a name to show.
 */
fun linkTitle(title: String, uri: String): Boolean {
    val file = uri.substringBefore('?').substringAfterLast('/')
    return file.isNotEmpty() && title.substringBefore('?') == file
}

/**
 * A stream's text that's the speaker's own state, not a song: ZPSTR_CONNECTING, then ZPSTR_BUFFERING, for a few
 * seconds after Play. The Sonos app shows neither.
 */
fun speakerState(text: String) = text.startsWith("ZPSTR_")

/** A radio station as GetMediaInfo has it: its name, its logo, and the URI the speaker keeps that logo for. */
class Station(val name: String, val art: String, val uri: String)

/**
 * The room's track as raw TrackMetaData (parse it with [Sonos.track] only when it changed), its queue position,
 * and the times in seconds (0 when the source has none, as a stream).
 */
/**
 * Where the room is; [uri] is the track's own, empty while nothing is loaded. It names the sources without metadata:
 * x-sonos-htastream:… for the TV input, and x-sonos-vli:<player>:2,spotify:<session> for Spotify Connect while paused.
 */
class Position(val meta: String?, val index: Int, val elapsed: Int, val duration: Int, val uri: String)

/** Sonos's play mode, split into its two switches. */
class Mode(val shuffle: Boolean, val repeat: Repeat) {
    val upnp
        get() = when (repeat) {
            Repeat.OFF -> if (shuffle) "SHUFFLE_NOREPEAT" else "NORMAL"
            Repeat.ALL -> if (shuffle) "SHUFFLE" else "REPEAT_ALL"
            Repeat.ONE -> if (shuffle) "SHUFFLE_REPEAT_ONE" else "REPEAT_ONE"
        }

    companion object {
        fun of(m: String) = Mode(
            m.startsWith("SHUFFLE"),
            when (m) {
                "REPEAT_ALL", "SHUFFLE" -> Repeat.ALL
                "REPEAT_ONE", "SHUFFLE_REPEAT_ONE" -> Repeat.ONE
                else -> Repeat.OFF
            },
        )
    }
}

/** "H:MM:SS" in seconds; 0 for anything else, as NOT_IMPLEMENTED. */
fun seconds(t: String?): Int {
    val p = t?.substringBefore('.')?.split(':') ?: return 0
    if (p.size != 3) return 0
    return (p[0].toIntOrNull() ?: return 0) * 3600 + (p[1].toIntOrNull() ?: return 0) * 60 + (p[2].toIntOrNull() ?: return 0)
}

/** "1:46" or "1:02:03". */
fun clock(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

private const val TAG = "UPnPCast"

/** "H:MM:SS" for the speaker: in ASCII digits whatever the phone's language, as Sonos reads no other. */
fun hms(s: Int) = "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)

/** A relative Seek's target: [s] seconds on, or back when negative, as +0:00:15 or -0:00:15. */
fun delta(s: Int) = (if (s < 0) "-" else "+") + hms(if (s < 0) -s else s)

/** The room's queue and transport, always on its coordinator; the speaker fetches each track from the server itself. */
object Sonos {
    private const val BATCH = 16 // The most AddMultipleURIsToQueue takes per call.
    private const val RESUME_MS = 300L // After a hand-over, between tries to put the music back where it was.
    private const val RESUME_TRIES = 10
    private const val HAND_OVER_MS = 10_000 // Sonos answers a hand-over once it's done: 4 s with Spotify playing.
    private const val END_S = 2 // A hand-over's place is put back at most this short of the track's end.
    private const val MEMBERS_AT_ONCE = 4 // A group's volume goes to this many of its players at a time.
    private const val QUEUE_PAGE = 100 // Sonos caps the items per Browse.

    private fun avt(r: Room, action: String, args: String = ""): ByteArray = avt(r.host, action, args)

    private fun avt(host: String, action: String, args: String = "", wait: Int = Http.TIMEOUT): ByteArray = Http.soap(
        "http://$host/MediaRenderer/AVTransport/Control", "AVTransport", action, "<InstanceID>0</InstanceID>$args", wait,
    )

    /**
     * [x] joins the group [lead] leads, and plays what it plays, in sync, at its volume: a group's is absolute, so
     * the new room takes it first, before it sounds, rather than joining at its own. Not at all when that volume
     * can't be read: it might join far louder.
     */
    fun join(x: Room, lead: Room) {
        val v = volume(lead)
        if (v !in 0..100) throw IOException("No volume from ${lead.name}")
        rc(x, "SetVolume", "<DesiredVolume>$v</DesiredVolume>")
        avt(x.own, "SetAVTransportURI", "<CurrentURI>x-rincon:${Upnp.esc(lead.id)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
    }

    /** [x] leaves its group and goes quiet; the others play on. */
    fun leave(x: Room) {
        avt(x.own, "BecomeCoordinatorOfStandaloneGroup")
    }

    /**
     * The group's leader at [host] hands the music to the player [to] (its id), whose own player is at [toHost], and
     * leaves. Sonos starts the track over on the new leader, so it's put back where it was: the same queue track,
     * as far in as it had got by then. Music without a position, as the TV or a stream, just carries on.
     */
    fun handOver(host: String, to: String, toHost: String) {
        val was = runCatching { position(host) }.getOrNull()
        // Paused, the place stays where it was; a seek leaves play or pause as it is.
        val playing = runCatching { Upnp.arg(avt(host, "GetTransportInfo"), "CurrentTransportState") }.getOrNull() == "PLAYING"
        val t0 = SystemClock.elapsedRealtime()
        avt(
            host, "DelegateGroupCoordinationTo",
            "<NewCoordinator>${Upnp.esc(to)}</NewCoordinator><RejoinGroup>0</RejoinGroup><ClearSource>0</ClearSource>",
            HAND_OVER_MS,
        )
        if (was == null || was.duration <= 0 || was.index <= 0) return
        var s = was.elapsed
        for (n in 1..RESUME_TRIES) { // The new leader takes a moment to have the queue; a seek before that fails.
            Thread.sleep(RESUME_MS)
            val gone = if (playing) ((SystemClock.elapsedRealtime() - t0) / 1000).toInt() else 0
            s = minOf(was.elapsed + gone, was.duration - END_S).coerceAtLeast(0) // Never past the track's end.
            if (runCatching { resumeAt(toHost, was.index, s) }.getOrDefault(false)) return
        }
        Log.i(TAG, "Hand-over to $toHost: not back at track ${was.index}, ${hms(s)}")
    }

    /**
     * Puts the player at [host] at queue track [index], [s] seconds in, unless it's there already, as when the
     * hand-over kept its place; true once it reports being there, on that track.
     */
    private fun resumeAt(host: String, index: Int, s: Int): Boolean {
        val p = position(host)
        if (p.index == index && abs(p.elapsed - s) <= 3) return true
        if (p.index != index) avt(host, "Seek", "<Unit>TRACK_NR</Unit><Target>$index</Target>")
        avt(host, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(s)}</Target>")
        val now = position(host)
        return now.index == index && abs(now.elapsed - s) <= 3
    }

    // The room's own volume and mute: on its own player, which for a room in a group isn't the coordinator.
    private fun rc(r: Room, action: String, args: String): ByteArray = Http.soap(
        "http://${r.own}/MediaRenderer/RenderingControl/Control", "RenderingControl", action,
        "<InstanceID>0</InstanceID><Channel>Master</Channel>$args",
    )

    // A room's own settings, on its own player ("ip:port"): tone and EQ in RenderingControl, the light and the buttons in
    // DeviceProperties.
    private fun own(host: String, service: String, action: String, args: String = ""): ByteArray = Http.soap(
        "http://$host/${if (service == "DeviceProperties") "" else "MediaRenderer/"}$service/Control", service, action,
        "<InstanceID>0</InstanceID>$args",
    )

    /**
     * A room setting by [key] from its own player [host]: Bass, Treble, Loudness, Trueplay (1 on, plus 2 once Trueplay
     * has tuned the room: both from one reply), Speech (0 off, else its level 1..4), LED and Touch (1 on), else an EQ
     * type, as NightMode.
     * Null when the player hasn't got it: it answers with a UPnP error, as 402 for Night Sound on an Era 300.
     */
    fun setting(host: String, key: String): Int? {
        fun rc(action: String, args: String, name: String) = Upnp.arg(own(host, "RenderingControl", action, args), name)
        fun eq(type: String) = rc("GetEQ", "<EQType>$type</EQType>", "CurrentValue")
        fun dp(action: String, name: String) = Upnp.arg(own(host, "DeviceProperties", action), name)
        return try {
            when (key) {
                "Bass", "Treble" -> rc("Get$key", "", "Current$key")
                "Loudness" -> rc("GetLoudness", "<Channel>Master</Channel>", "CurrentLoudness")
                "Trueplay" -> {
                    val x = own(host, "RenderingControl", "GetRoomCalibrationStatus")
                    val (on, tuned) = Upnp.args(x, "RoomCalibrationEnabled", "RoomCalibrationAvailable")
                    on?.toIntOrNull()?.let { (it + if (tuned == "1") 2 else 0).toString() }
                }
                "Speech" -> if (eq("SpeechEnhanceEnabled") == "1") eq("DialogLevel") else "0"
                "LED" -> if (dp("GetLEDState", "CurrentLEDState") == "On") "1" else "0"
                "Touch" -> if (dp("GetButtonLockState", "CurrentButtonLockState") == "Off") "1" else "0" // Not locked.
                else -> eq(key)
            }?.toIntOrNull()
        } catch (e: HttpException) {
            if (e.upnp > 0) null else throw e
        }
    }

    /** Sets [key] (see the read) to [v] on the room's own player [host]. */
    fun setting(host: String, key: String, v: Int) {
        fun rc(action: String, args: String) = own(host, "RenderingControl", action, args)
        fun eq(type: String, x: Int) = rc("SetEQ", "<EQType>$type</EQType><DesiredValue>$x</DesiredValue>")
        fun dp(action: String, name: String, x: String) = own(host, "DeviceProperties", action, "<Desired$name>$x</Desired$name>")
        when (key) {
            "Bass", "Treble" -> rc("Set$key", "<Desired$key>$v</Desired$key>")
            "Loudness" -> rc("SetLoudness", "<Channel>Master</Channel><DesiredLoudness>$v</DesiredLoudness>")
            "Trueplay" -> rc("SetRoomCalibrationStatus", "<RoomCalibrationEnabled>$v</RoomCalibrationEnabled>")
            // Its switch, then the level once that took; the speaker takes level 0 and ignores it, so 0 is the switch.
            "Speech" -> {
                eq("SpeechEnhanceEnabled", if (v > 0) 1 else 0)
                if (v > 0) eq("DialogLevel", v)
            }
            "LED" -> dp("SetLEDState", "LEDState", if (v == 1) "On" else "Off")
            "Touch" -> dp("SetButtonLockState", "ButtonLockState", if (v == 1) "Off" else "On")
            else -> eq(key, v)
        }
    }

    private fun queueUri(r: Room) = "x-rincon-queue:${r.id}#0"

    /** The player at [host]'s own copy of the cover for [uri], fetched by the player: the phone stays on the LAN. */
    fun copy(host: String, uri: String) = "http://$host/getaa?s=1&u=${URLEncoder.encode(uri, Charsets.UTF_8)}"

    /** Whether the room plays its queue, rather than e.g. Spotify or the TV. */
    fun queued(r: Room) = Upnp.arg(avt(r, "GetMediaInfo"), "CurrentURI") == queueUri(r)

    /** The household's music services by number, as [r]'s player lists them, as 31 Qobuz: ~48 KB, ~75 ms. */
    fun services(r: Room): Map<String, String> = serviceNames(
        Upnp.arg(Http.soap("http://${r.host}/MusicServices/Control", "MusicServices", "ListAvailableServices"),
            "AvailableServiceDescriptorList").orEmpty(),
    )

    /** The radio station [r] plays: a stream's own track info may have neither its name nor its logo. */
    fun station(r: Room): Station {
        val (uri, meta) = Upnp.args(avt(r, "GetMediaInfo"), "CurrentURI", "CurrentURIMetaData")
        val t = track(meta)
        return Station(t?.title.orEmpty(), t?.art.orEmpty(), uri.orEmpty())
    }

    /**
     * The group of [r]'s coordinator as it reports it, in ~600 bytes: its id and players change with every regrouping,
     * made here or elsewhere.
     */
    fun group(r: Room): String {
        val (id, players) = Upnp.args(
            Http.soap("http://${r.host}/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupAttributes"),
            "CurrentZoneGroupID", "CurrentZonePlayerUUIDsInGroup",
        )
        return "$id $players"
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED or TRANSITIONING. */
    fun state(r: Room) = Upnp.arg(avt(r, "GetTransportInfo"), "CurrentTransportState") ?: ""

    /** Pauses what plays, else plays; the state is read right before, never trusted from an earlier poll. */
    fun toggle(r: Room) {
        val s = state(r)
        if (s == "PLAYING" || s == "TRANSITIONING") avt(r, "Pause") else avt(r, "Play", "<Speed>1</Speed>")
    }

    fun next(r: Room) {
        avt(r, "Next")
    }

    fun previous(r: Room) {
        avt(r, "Previous")
    }

    fun seek(r: Room, s: Int) {
        avt(r, "Seek", "<Unit>REL_TIME</Unit><Target>${hms(s)}</Target>")
    }

    /** [s] seconds on, or back, from where the track is: the speaker stops at 0:00, and past the end goes to the next. */
    fun skip(r: Room, s: Int) {
        avt(r, "Seek", "<Unit>TIME_DELTA</Unit><Target>${delta(s)}</Target>")
    }

    // A group's volume and mute, on its coordinator: what the Sonos app's slider sets for a group.
    private fun grc(r: Room, action: String, args: String = ""): ByteArray = Http.soap(
        "http://${r.host}/MediaRenderer/GroupRenderingControl/Control", "GroupRenderingControl", action,
        "<InstanceID>0</InstanceID>$args",
    )

    fun volume(r: Room) = Upnp.arg(
        if (r.members > 0) grc(r, "GetGroupVolume") else rc(r, "GetVolume", ""), "CurrentVolume",
    )?.toIntOrNull() ?: -1

    /**
     * Sets the room's volume. A group's is absolute, unlike the Sonos app's: every speaker gets [v] itself, rather than
     * all moving by the same share. One that doesn't answer doesn't keep the others from theirs.
     */
    fun volume(r: Room, v: Int) {
        val d = "<DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>"
        if (r.members == 0) {
            rc(r, "SetVolume", d)
            return
        }
        // Side by side, so one slow to answer costs its own wait only, not the others' as well.
        val pool = Executors.newFixedThreadPool(minOf(r.players.size, MEMBERS_AT_ONCE))
        var failed: Exception? = null
        try {
            val sets = r.players.map { h ->
                pool.submit {
                    Http.soap(
                        "http://$h/MediaRenderer/RenderingControl/Control", "RenderingControl", "SetVolume",
                        "<InstanceID>0</InstanceID><Channel>Master</Channel>$d",
                    )
                }
            }
            for (f in sets) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    failed = e.cause as? Exception ?: e
                }
            }
        } finally {
            pool.shutdownNow() // Done, or the player stopped waiting: writes not yet begun don't start.
        }
        failed?.let { throw it }
    }

    fun muted(r: Room) =
        Upnp.arg(if (r.members > 0) grc(r, "GetGroupMute") else rc(r, "GetMute", ""), "CurrentMute") == "1"

    /** Like [toggle], from the state read right before. */
    fun toggleMute(r: Room) {
        val d = "<DesiredMute>${if (muted(r)) 0 else 1}</DesiredMute>"
        if (r.members > 0) grc(r, "SetGroupMute", d) else rc(r, "SetMute", d)
    }

    fun mode(r: Room) = Mode.of(Upnp.arg(avt(r, "GetTransportSettings"), "PlayMode") ?: "")

    fun mode(r: Room, m: Mode) {
        avt(r, "SetPlayMode", "<NewPlayMode>${m.upnp}</NewPlayMode>")
    }

    // The TV input's formats, by the HTAudioIn code in the player's zone info.
    private val TV = mapOf(
        0 to "No input connected", 2 to "Stereo", 7 to "Dolby Digital 2.0", 18 to "Dolby Digital 5.1", 21 to "No input",
        22 to "No audio", 59 to "Dolby Atmos (DD+)", 61 to "Dolby Atmos (TrueHD)", 63 to "Dolby Atmos (MAT)",
        33554434 to "PCM 2.0", 33554454 to "PCM 2.0 no audio", 33554488 to "Dolby Digital 2.0",
        33554490 to "Dolby Digital Plus 2.0", 33554492 to "Dolby TrueHD 2.0", 33554494 to "Dolby Multichannel PCM 2.0",
        84934658 to "Multichannel PCM 5.1", 84934713 to "Dolby Digital 5.1", 84934714 to "Dolby Digital Plus 5.1",
        84934716 to "Dolby TrueHD 5.1", 84934718 to "Dolby Multichannel PCM 5.1", 84934721 to "DTS 5.1",
        118489090 to "Multichannel PCM 7.1", 118489146 to "Dolby Digital Plus 7.1", 118489148 to "Dolby TrueHD 7.1",
    )

    /** The format the TV sends, such as Dolby Atmos (MAT); a code not in the list shows as a number, nonsense as nothing. */
    fun tvFormat(r: Room): String {
        val code = Upnp.arg(Http.soap("http://${r.host}/DeviceProperties/Control", "DeviceProperties", "GetZoneInfo"), "HTAudioIn")
            ?.trim() ?: return ""
        if (code.isEmpty() || code.length > 10 || !code.all { it in '0'..'9' }) return ""
        return code.toIntOrNull()?.let { TV[it] } ?: "Format $code"
    }

    fun position(r: Room) = position(r.host)

    private fun position(host: String): Position {
        val (index, meta, duration, elapsed, uri) =
            Upnp.args(avt(host, "GetPositionInfo"), "Track", "TrackMetaData", "TrackDuration", "RelTime", "TrackURI")
        return Position(meta, index?.toIntOrNull() ?: 0, seconds(elapsed), seconds(duration), uri ?: "")
    }

    fun track(didl: String?): Track? {
        if (didl.isNullOrBlank() || didl == "NOT_IMPLEMENTED") return null
        val p = Xml.newPullParser().apply { setInput(StringReader(noDtd(didl))) }
        var title = ""
        var artist = ""
        var album = ""
        var art = ""
        var stream = ""
        var info = ""
        var uri = ""
        var hz = 0
        var bits = 0
        var quality = ""
        while (p.next() != END_DOCUMENT) {
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "title" -> title = p.nextText()
                "creator" -> artist = p.nextText()
                "album" -> album = p.nextText()
                "albumArtURI" -> art = p.nextText().trim()
                // A radio station's "Artist - Title"; the speaker's state while it connects counts as none, so the
                // station's name shows, as while paused.
                "streamContent" -> stream = p.nextText().takeUnless(::speakerState).orEmpty()
                "res" -> {
                    info = p.getAttributeValue(null, "protocolInfo") ?: ""
                    hz = p.getAttributeValue(null, "sampleFrequency")?.toIntOrNull() ?: hz
                    bits = p.getAttributeValue(null, "bitsPerSample")?.toIntOrNull() ?: bits
                    uri = p.nextText().trim()
                }
                // Sonos's own, as for Spotify: "bd:16,sr:44100,c:0,l:1,d:0"; l is lossless. Files have none.
                "streamInfo" -> for (kv in p.nextText().split(',')) {
                    val v = kv.substringAfter(':').toIntOrNull() ?: continue
                    if (kv.startsWith("sr:") && hz == 0) hz = v
                    if (kv.startsWith("bd:") && bits == 0) bits = v
                    if (kv.startsWith("l:")) quality = if (v == 1) "Lossless" else "Lossy"
                }
            }
        }
        val res = Res("", info, hz, bits, "")
        val format = res.format(quality)
        val lossless = res.lossless(quality)
        // A stream's own title is often its station's, else its link's file name: that one not, the station's name
        // from GetMediaInfo goes there instead. Also while paused, when the station sends no song: then no track,
        // and the station's name is the title.
        val own = if (linkTitle(title, uri)) "" else title
        if (stream.isNotEmpty()) return Track(stream, own, album, art, format, uri, lossless)
        return if (own.isEmpty()) null else Track(own, artist, album, art, format, uri, lossless)
    }

    /**
     * Puts [tracks] in [r]'s queue as [how] says; for [How.NOW] and [How.REPLACE] it then plays, from the track at
     * index [start] of [tracks].
     */
    fun play(r: Room, tracks: List<Entry>, how: How, start: Int = 0) {
        val queued = queued(r)
        val first = when (how) {
            How.REPLACE -> {
                avt(r, "RemoveAllTracksFromQueue")
                add(r, tracks, 0, false)
            }
            // After the current track while the queue plays; otherwise there is no current track to follow.
            How.NOW, How.NEXT -> add(r, tracks, if (queued) position(r).index + 1 else 0, queued) // Index only: no parse.
            How.END -> add(r, tracks, 0, false)
        }
        if (how == How.NOW || how == How.REPLACE) start(r, first + start, queued)
    }

    /**
     * Plays favourite [f] on [r]: a playlist or album replaces the queue and plays from its first track; anything
     * else, as a station, plays at once.
     */
    fun favorite(r: Room, f: Favorite) {
        val uri = Upnp.esc(f.uri)
        val md = Upnp.esc(f.meta)
        if (listOf("x-rincon-cpcontainer:", "x-rincon-playlist:", "file:").none { f.uri.startsWith(it) }) {
            avt(r, "SetAVTransportURI", "<CurrentURI>$uri</CurrentURI><CurrentURIMetaData>$md</CurrentURIMetaData>")
            avt(r, "Play", "<Speed>1</Speed>")
            return
        }
        val queued = queued(r)
        avt(r, "RemoveAllTracksFromQueue")
        avt(
            r, "AddURIToQueue",
            "<EnqueuedURI>$uri</EnqueuedURI><EnqueuedURIMetaData>$md</EnqueuedURIMetaData>" +
                "<DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued><EnqueueAsNext>0</EnqueueAsNext>",
        )
        start(r, 1, queued)
    }

    /** Plays queue position [n] (1-based), switching the room to its queue first when it plays something else. */
    fun jump(r: Room, n: Int) = start(r, n, queued(r))

    private fun start(r: Room, n: Int, queued: Boolean) {
        if (!queued) avt(r, "SetAVTransportURI", "<CurrentURI>${queueUri(r)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        avt(r, "Seek", "<Unit>TRACK_NR</Unit><Target>$n</Target>")
        avt(r, "Play", "<Speed>1</Speed>")
    }

    fun clear(r: Room) {
        avt(r, "RemoveAllTracksFromQueue")
    }

    /** Starts the music library's Scan for new content on the player at [control]; it scans by itself. */
    fun scan(control: String) {
        // Empty: the library keeps its own album artist setting.
        Http.soap(control, "ContentDirectory", "RefreshShareIndex", "<AlbumArtistDisplayOption></AlbumArtistDisplayOption>")
    }

    /** Whether the music library is being scanned, from here or elsewhere. */
    fun scanning(control: String) = Upnp.arg(Http.soap(control, "ContentDirectory", "GetShareIndexInProgress"), "IsIndexing") == "1"

    /** The room's queue over all its pages; [url] vets each cover and track URL, as for a server. */
    fun queue(r: Room, url: (String) -> String?, wanted: () -> Boolean = { true }): List<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        var pages = 0
        // Up to 10,000 tracks in at most 200 pages, and only forward: a page of nothing, or a negative count, ends it.
        // [wanted] is asked before each page: a load the screen has left, or overtaken, stops there.
        while (out.size < 10_000 && pages++ < 200 && wanted()) {
            val p = Upnp.browse(contentDirectory(r), url, "Q:0", start, QUEUE_PAGE)
            out += p.entries
            if (p.returned <= 0) break
            start += p.returned
            if (start >= p.total) break
        }
        return out
    }

    /** The queue's track at index [n] (0-based) as it is now: a tap on the Queue screen checks it still is the one. */
    fun queueItem(r: Room, n: Int, url: (String) -> String?) =
        Upnp.browse(contentDirectory(r), url, "Q:0", n, 1).entries.firstOrNull()

    private fun contentDirectory(r: Room) = "http://${r.host}/MediaServer/ContentDirectory/Control"

    /** Adds [tracks] at queue position [at] (0 = the end); returns the position of the first one. */
    private fun add(r: Room, tracks: List<Entry>, at: Int, next: Boolean): Int {
        var first = -1
        var pos = at
        for (batch in tracks.chunked(BATCH)) {
            // The lists are space-separated; MinimServer escapes spaces in its URLs, but another server might not.
            val uris = batch.joinToString(" ") { it.res!!.url.replace(" ", "%20") }
            val meta = batch.joinToString(" ") { didl(it) }
            val reply = avt(
                r, "AddMultipleURIsToQueue",
                "<UpdateID>0</UpdateID><NumberOfURIs>${batch.size}</NumberOfURIs><EnqueuedURIs>${Upnp.esc(uris)}" +
                    "</EnqueuedURIs><EnqueuedURIsMetaData>${Upnp.esc(meta)}</EnqueuedURIsMetaData><ContainerURI>" +
                    "</ContainerURI><ContainerMetaData></ContainerMetaData><DesiredFirstTrackNumberEnqueued>$pos" +
                    "</DesiredFirstTrackNumberEnqueued><EnqueueAsNext>${if (next) 1 else 0}</EnqueueAsNext>",
            )
            val n = Upnp.arg(reply, "FirstTrackNumberEnqueued")?.toIntOrNull() ?: throw IOException("No queue position")
            if (first < 0) first = n
            if (pos > 0) pos = n + batch.size
        }
        return first
    }

    /** The track's metadata: Sonos shows it, and hands rate and bit depth back in TrackMetaData for Now Playing. */
    private fun didl(e: Entry): String {
        val r = e.res!!
        val art = e.art?.let { "<upnp:albumArtURI>${Upnp.esc(it)}</upnp:albumArtURI>" } ?: ""
        val attrs = (if (r.duration.isEmpty()) "" else " duration=\"${Upnp.esc(r.duration)}\"") +
            (if (r.hz > 0) " sampleFrequency=\"${r.hz}\"" else "") + (if (r.bits > 0) " bitsPerSample=\"${r.bits}\"" else "")
        return "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/" +
            "upnp/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><item id=\"${Upnp.esc(e.id)}\" parentID=\"-1\" " +
            "restricted=\"1\"><dc:title>${Upnp.esc(e.title)}</dc:title><dc:creator>${Upnp.esc(e.artist)}</dc:creator>" +
            "<upnp:album>${Upnp.esc(e.album)}</upnp:album>$art<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<res protocolInfo=\"${Upnp.esc(r.protocolInfo)}\"$attrs>${Upnp.esc(r.url)}</res></item></DIDL-Lite>"
    }
}

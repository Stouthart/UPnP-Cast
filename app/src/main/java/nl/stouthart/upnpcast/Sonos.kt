package nl.stouthart.upnpcast

import android.util.Xml
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.START_TAG
import java.io.IOException
import java.io.StringReader
import java.util.Locale

/** Where tracks go in the room's queue, in the Sonos app's terms. */
enum class How { NOW, NEXT, END, REPLACE }

enum class Repeat { OFF, ALL, ONE }

/** What the room is playing, from its TrackMetaData; [art] may be relative to the speaker. */
/** The playing track; [uri] is its own, which the speaker can find a streaming service's cover for. */
class Track(val title: String, val artist: String, val album: String, val art: String, val format: String, val uri: String)

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

/** "H:MM:SS" for the speaker: in ASCII digits whatever the phone's language, as Sonos reads no other. */
fun hms(s: Int) = "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)

/** The room's queue and transport, always on its coordinator; the speaker fetches each track from the server itself. */
object Sonos {
    private const val BATCH = 16 // The most AddMultipleURIsToQueue takes per call.
    private const val QUEUE_PAGE = 100 // Sonos caps the items per Browse.

    private fun avt(r: Room, action: String, args: String = ""): ByteArray =
        Http.soap("http://${r.host}/MediaRenderer/AVTransport/Control", "AVTransport", action, "<InstanceID>0</InstanceID>$args")

    private fun rc(r: Room, action: String, args: String): ByteArray = Http.soap(
        "http://${r.host}/MediaRenderer/RenderingControl/Control", "RenderingControl", action,
        "<InstanceID>0</InstanceID><Channel>Master</Channel>$args",
    )

    private fun queueUri(r: Room) = "x-rincon-queue:${r.id}#0"

    /** Whether the room plays its queue, rather than e.g. Spotify or the TV. */
    fun queued(r: Room) = Upnp.arg(avt(r, "GetMediaInfo"), "CurrentURI") == queueUri(r)

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

    fun volume(r: Room) = Upnp.arg(rc(r, "GetVolume", ""), "CurrentVolume")?.toIntOrNull() ?: -1

    fun volume(r: Room, v: Int) {
        rc(r, "SetVolume", "<DesiredVolume>${v.coerceIn(0, 100)}</DesiredVolume>")
    }

    fun muted(r: Room) = Upnp.arg(rc(r, "GetMute", ""), "CurrentMute") == "1"

    /** Like [toggle], from the state read right before. */
    fun toggleMute(r: Room) {
        rc(r, "SetMute", "<DesiredMute>${if (muted(r)) 0 else 1}</DesiredMute>")
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

    fun position(r: Room): Position {
        val (index, meta, duration, elapsed, uri) =
            Upnp.args(avt(r, "GetPositionInfo"), "Track", "TrackMetaData", "TrackDuration", "RelTime", "TrackURI")
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
                "streamContent" -> stream = p.nextText() // A radio station's "Artist - Title".
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
        val format = Res("", info, hz, bits, "").format(quality)
        if (stream.isNotEmpty()) return Track(stream, title, album, art, format, uri)
        return if (title.isEmpty()) null else Track(title, artist, album, art, format, uri)
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
    fun queue(r: Room, url: (String) -> String?): List<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        var pages = 0
        // Up to 10,000 tracks in at most 200 pages, and only forward: a page of nothing, or a negative count, ends it.
        while (out.size < 10_000 && pages++ < 200) {
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

package nl.stouthart.upnpcast

import android.content.res.Resources
import android.util.Xml
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import java.io.StringReader
import java.net.URL
import java.util.Locale

/**
 * A cover's size class by its DLNA profile (dlna:profileID), as a server labels each size it offers: JPEG_TN, the
 * thumbnail, up to JPEG_LRG; PNG's the same. Of an item's covers the largest is kept, the first of equals; 0 without a
 * label, so a server that sends one cover, or several unlabelled, gives the first as before.
 */
fun coverSize(profile: String?) = when (profile?.substringAfterLast('_')) {
    "TN" -> 1
    "SM" -> 2
    "MED" -> 3
    "LRG" -> 4
    else -> 0
}

/** Sonos plays nothing above 48 kHz over UPnP. */
const val MAX_HZ = 48000

/** A sample rate as every screen shows it: "44.1 kHz", "96 kHz", "22.05 kHz", exact and without trailing zeros. */
fun khz(hz: Int) = if (hz % 1000 == 0) "${hz / 1000} kHz" else "${hz / 1000.0} kHz"

private val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "LPCM", "AIFF")

/** A track's audio as the server offers it; 0 where the server doesn't say. */
class Res(val url: String, val protocolInfo: String, val hz: Int, val bits: Int, val duration: String) {
    private fun codec(): String {
        val p = protocolInfo.split(':')
        val mime = p.getOrNull(2)?.substringAfter('/')?.removePrefix("x-")?.lowercase() ?: ""
        return when (mime) {
            // Spotify played from the Sonos app, x-sonos-spotify:…: audio/x-spotify names the service, not a format.
            "spotify" -> ""
            "mpeg", "mp3" -> "MP3"
            "mp4", "m4a" -> "M4A"
            "wav", "wave" -> "WAV"
            "l16", "l24" -> "LPCM"
            // A radio stream's says only *, as x-rincon-mp3radio:*:*:*: its scheme tells, or nothing does.
            "*" -> when {
                "mp3" in p[0] -> "MP3"
                "aac" in p[0] -> "AAC"
                else -> ""
            }
            else -> mime.uppercase()
        }
    }

    /**
     * The music reaches the speaker as it was recorded: a lossless codec, or a stream that says so ([quality]). An M4A
     * may hold ALAC or AAC, so it doesn't count.
     */
    fun lossless(quality: String = "") = if (quality.isNotEmpty()) quality == "Lossless" else codec() in LOSSLESS

    /** "FLAC 44.1 kHz 16-bit", as far as the server tells; a stream's [quality] ("Lossless") follows the codec. */
    fun format(quality: String = ""): String {
        val codec = codec()
        val rate = when {
            hz >= 1_000_000 -> "%.1f MHz".format(Locale.ROOT, hz / 1e6) // DSD.
            hz > 0 -> khz(hz)
            else -> ""
        }
        return listOf(codec, quality, rate, if (bits > 0) "$bits-bit" else "").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** "5:40" or "1:02:03" from UPnP's "H:MM:SS.fff"; nothing without a length. */
    fun time() = seconds(duration).let { if (it > 0) clock(it) else "" }
}

/**
 * A Sonos favourite, as the speakers keep it for the household: what plays it, [uri] and its description [meta]. A
 * shortcut, to a page in a music service, has no [uri]: it can't be played over UPnP.
 */
class Favorite(val uri: String, val meta: String)

/** A container or track from a Browse; [res] is the first resource Sonos can play, else the first. [fav]: a favourite. */
class Entry(
    val id: String,
    val title: String,
    val container: Boolean,
    val cls: String,
    val artist: String,
    val album: String,
    val art: String?,
    val count: Int, // A container's children, or -1.
    val res: Res?,
    val fav: Favorite? = null,
    val track: Int = 0, // Its number on its album, from the file's tags; 0 when the server sends none.
    val disc: Int = 0, // Its disc's, for an album of several; 0 when none.
) {
    val playable get() = if (fav != null) fav.uri.isNotEmpty() else res != null && res.url.isNotEmpty() && res.hz <= MAX_HZ
    var meta = "" // The row's detail line, made with the page, off the main thread.
}

/** A row's detail line: the artist, or a container's size, or for a track also its format and length. */
fun meta(r: Resources, e: Entry): String {
    if (e.container) return e.artist.ifEmpty {
        if (e.count < 0) ""
        else r.getQuantityString(if (e.cls.startsWith("object.container.album")) R.plurals.track_count else R.plurals.item_count, e.count, e.count)
    }
    val s = e.res ?: return e.artist
    return listOf(e.artist, s.format(), s.time()).filter { it.isNotEmpty() }.joinToString(" · ")
}

object Didl {
    /** A URL check that keeps only http URLs on [host]: a server's own files and covers. */
    fun on(host: String): (String) -> String? = { local(host, it) }

    /**
     * The Sonos music library's check: its tracks are SMB links (x-file-cifs), which only the speaker opens; its
     * covers are relative links on the player at [control], kept only there.
     */
    fun library(control: String): (String) -> String? {
        val base = URL(control)
        return { u -> if (u.startsWith("x-file-cifs://")) u else player(base, u) }
    }

    /** [u], resolved against the player at [base], when it's an http link on that player. */
    private fun player(base: URL, u: String) =
        runCatching { URL(base, u) }.getOrNull()?.takeIf { it.protocol == "http" && it.host == base.host }?.toString()

    /**
     * The entries of a DIDL-Lite document. [url] vets every cover and track URL; a refused track URL keeps the
     * track's format and length but makes it unplayable.
     */
    fun parse(xml: String, url: (String) -> String?): List<Entry> {
        if (xml.isBlank()) return emptyList()
        val p = Xml.newPullParser().apply { setInput(StringReader(noDtd(xml))) }
        val out = ArrayList<Entry>()
        var id = ""
        var container = false
        var count = -1
        var title = ""
        var cls = ""
        var artist = ""
        var creator = ""
        var album = ""
        var art: String? = null
        var size = -1 // The size class of the cover kept so far.
        var track = 0
        var disc = 0
        var res: Res? = null
        var first: Res? = null
        while (p.next() != END_DOCUMENT) {
            val name = p.name
            if (p.eventType == END_TAG && (name == "item" || name == "container")) {
                out += Entry(
                    id, title, container, cls, artist.ifEmpty { creator }, album, art, count, res ?: first, track = track,
                    disc = disc,
                )
                continue
            }
            if (p.eventType != START_TAG) continue
            when (name) {
                "item", "container" -> {
                    id = p.getAttributeValue(null, "id") ?: ""
                    container = name == "container"
                    count = p.getAttributeValue(null, "childCount")?.toIntOrNull() ?: -1
                    title = ""
                    cls = ""
                    artist = ""
                    creator = ""
                    album = ""
                    art = null
                    size = -1
                    track = 0
                    disc = 0
                    res = null
                    first = null
                }
                // Local names: Android's parser resolves namespaces, so dc:title arrives as "title", whatever the prefix.
                "title" -> title = p.nextText()
                "class" -> cls = p.nextText()
                // Album artist and composer come as extra artists with a role; the plain one is the performer.
                "artist" -> if (artist.isEmpty() && p.getAttributeValue(null, "role") == null) artist = p.nextText()
                "creator" -> creator = p.nextText()
                "album" -> album = p.nextText()
                "originalTrackNumber" -> track = p.nextText().trim().toIntOrNull()?.takeIf { it in 1..999 } ?: 0
                "originalDiscNumber" -> disc = p.nextText().trim().toIntOrNull()?.takeIf { it in 1..99 } ?: 0
                "albumArtURI" -> {
                    val k = coverSize(p.getAttributeValue(null, "profileID"))
                    val u = url(p.nextText().trim())
                    if (u != null && k > size) {
                        art = u
                        size = k
                    }
                }
                "res" -> if (res == null) {
                    val hz = p.getAttributeValue(null, "sampleFrequency")?.toIntOrNull() ?: 0
                    val bits = p.getAttributeValue(null, "bitsPerSample")?.toIntOrNull() ?: 0
                    val info = p.getAttributeValue(null, "protocolInfo") ?: ""
                    val duration = p.getAttributeValue(null, "duration") ?: ""
                    val r = Res(url(p.nextText().trim()) ?: "", info, hz, bits, duration)
                    if (first == null) first = r
                    if (r.url.isNotEmpty() && hz <= MAX_HZ) res = r // One whose link was refused leaves room for the next.
                }
            }
        }
        return out
    }

    /**
     * The Sonos favourites (FV:2) from the player at [control]: each by its title and its service ("90s90s Radio") as
     * the artist. The cover is the favourite's, on the player or on a server this app knows (a server track's); else the
     * player's copy for the favourite's link.
     */
    fun favorites(xml: String, control: String): List<Entry> {
        if (xml.isBlank()) return emptyList()
        val base = URL(control)
        val p = Xml.newPullParser().apply { setInput(StringReader(noDtd(xml))) }
        val out = ArrayList<Entry>()
        var id = ""
        var title = ""
        var type = ""
        var service = ""
        var uri = ""
        var meta = ""
        var art: String? = null
        while (p.next() != END_DOCUMENT) {
            val name = p.name
            if (p.eventType == END_TAG && name == "item") {
                val u = if (type == "shortcut") "" else uri
                val cover = art ?: if (u.isEmpty()) null else Sonos.copy(base.authority, u)
                out += Entry(id, title, false, "object.item.sonos-favorite", service, "", cover, -1, null, Favorite(u, meta))
                continue
            }
            if (p.eventType != START_TAG) continue
            when (name) {
                "item" -> {
                    id = p.getAttributeValue(null, "id") ?: ""
                    title = ""
                    type = ""
                    service = ""
                    uri = ""
                    meta = ""
                    art = null
                }
                "title" -> title = p.nextText()
                "type" -> type = p.nextText()
                "description" -> service = p.nextText()
                "res" -> uri = p.nextText().trim()
                "resMD" -> meta = p.nextText() // DIDL-Lite of its own, as text: the speaker takes it back as it is.
                "albumArtURI" -> if (art == null) art = cover(base, p.nextText().trim()) // A cover, never a track's link.
            }
        }
        return out
    }

    /** [u] on the player at [base], or an http link on a server the main screen found. */
    private fun cover(base: URL, u: String) = player(base, u)
        ?: runCatching { URL(u) }.getOrNull()?.takeIf { it.protocol == "http" && it.host in Player.servers }?.toString()

    private fun local(host: String, url: String): String? = if (url.isEmpty()) null
    else runCatching { URL(url) }.getOrNull()?.takeIf { it.protocol == "http" && it.host == host }?.toString()
}

package nl.stouthart.upnpcast

import android.content.res.Resources
import android.util.Xml
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import java.io.StringReader
import java.net.URL
import java.util.Locale

/** Sonos plays nothing above 48 kHz over UPnP. */
const val MAX_HZ = 48000

private val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "LPCM", "AIFF")

/** A track's audio as the server offers it; 0 where the server doesn't say. */
class Res(val url: String, val protocolInfo: String, val hz: Int, val bits: Int, val duration: String) {
    private fun codec(): String {
        val mime = protocolInfo.split(':').getOrNull(2)?.substringAfter('/')?.removePrefix("x-")?.lowercase() ?: ""
        return when (mime) {
            "mpeg", "mp3" -> "MP3"
            "mp4", "m4a" -> "M4A"
            "wav", "wave" -> "WAV"
            "l16", "l24" -> "LPCM"
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
            hz % 1000 == 0 && hz > 0 -> "${hz / 1000} kHz"
            hz > 0 -> "%.1f kHz".format(Locale.ROOT, hz / 1000.0)
            else -> ""
        }
        return listOf(codec, quality, rate, if (bits > 0) "$bits-bit" else "").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** "5:40" or "1:02:03" from UPnP's "H:MM:SS.fff". */
    fun time(): String {
        val p = duration.substringBefore('.').split(':').map { it.toIntOrNull() ?: return "" }
        if (p.size != 3) return ""
        return if (p[0] > 0) "%d:%02d:%02d".format(p[0], p[1], p[2]) else "%d:%02d".format(p[1], p[2])
    }
}

/** A container or track from a Browse; [res] is the first resource Sonos can play, else the first. */
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
) {
    val playable get() = res != null && res.url.isNotEmpty() && res.hz <= MAX_HZ
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
        return { u ->
            if (u.startsWith("x-file-cifs://")) u
            else runCatching { URL(base, u) }.getOrNull()?.takeIf { it.protocol == "http" && it.host == base.host }?.toString()
        }
    }

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
        var res: Res? = null
        var first: Res? = null
        while (p.next() != END_DOCUMENT) {
            val name = p.name
            if (p.eventType == END_TAG && (name == "item" || name == "container")) {
                out += Entry(id, title, container, cls, artist.ifEmpty { creator }, album, art, count, res ?: first)
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
                "albumArtURI" -> if (art == null) art = url(p.nextText().trim())
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

    private fun local(host: String, url: String): String? = if (url.isEmpty()) null
    else runCatching { URL(url) }.getOrNull()?.takeIf { it.protocol == "http" && it.host == host }?.toString()
}

package nl.stouthart.upnpcast

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import java.io.IOException
import java.io.StringReader
import java.net.URL

/** A Sonos room or group: every command goes to its coordinator, at [host] ("ip:port"). */
class Room(val id: String, val name: String, val host: String)

/** A media server: its name, the address it lives at, its ContentDirectory control URL and its icon. */
class Server(val udn: String, val name: String, val host: String, val control: String, val icon: String?)

private fun XmlPullParser.attr(name: String) = getAttributeValue(null, name) ?: ""

private val DOCTYPE = "<!DOCTYPE".toByteArray()
private val ENTITY = "<!ENTITY".toByteArray()

private fun at(b: ByteArray, i: Int, w: ByteArray) = i + w.size <= b.size && w.indices.all { b[i + it] == w[it] }

/**
 * Refuses XML with a DTD before it reaches the parser: it comes from the network, and entities defined there could
 * expand until memory runs out. No UPnP device sends one.
 */
fun noDtd(b: ByteArray): ByteArray {
    for (i in 0 until b.size - 1) {
        if (b[i] == '<'.code.toByte() && b[i + 1] == '!'.code.toByte() && (at(b, i, DOCTYPE) || at(b, i, ENTITY))) {
            throw IOException("XML with a DTD")
        }
    }
    return b
}

/** The same for XML that arrived escaped inside a reply, as a track list or TrackMetaData. */
fun noDtd(s: String): String {
    if (s.contains("<!DOCTYPE") || s.contains("<!ENTITY")) throw IOException("XML with a DTD")
    return s
}

private fun parser(b: ByteArray): XmlPullParser = Xml.newPullParser().apply { setInput(noDtd(b).inputStream(), null) }

object Upnp {
    /** "ip:port" of an http URL, or null. */
    fun hostOf(url: String): String? = runCatching { URL(url) }.getOrNull()
        ?.takeIf { it.protocol == "http" }?.let { "${it.host}:${if (it.port < 0) 80 else it.port}" }

    /** The text of the first [name] element in a SOAP reply: an output argument, unescaped once. */
    fun arg(reply: ByteArray, name: String): String? {
        val p = parser(reply)
        while (p.next() != END_DOCUMENT) if (p.eventType == START_TAG && p.name == name) return p.nextText()
        return null
    }

    /** Several output arguments in one pass, in the order of [names]. */
    fun args(reply: ByteArray, vararg names: String): Array<String?> {
        val out = arrayOfNulls<String>(names.size)
        val p = parser(reply)
        while (p.next() != END_DOCUMENT) {
            if (p.eventType != START_TAG) continue
            val i = names.indexOf(p.name)
            if (i >= 0) out[i] = p.nextText()
        }
        return out
    }

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** One page of a container's children; [total] tells whether more pages follow. */
    class Page(val entries: List<Entry>, val returned: Int, val total: Int)

    // Only what the rows and playback use: MinimServer honours the filter, which keeps genre and date off the wire.
    private const val FILTER = "dc:title,dc:creator,upnp:artist,upnp:album,upnp:albumArtURI,upnp:class," +
        "res,res@protocolInfo,res@sampleFrequency,res@bitsPerSample,res@duration,@childCount"

    fun browse(s: Server, id: String, start: Int, count: Int) = browse(s.control, Didl.on(s.host), id, start, count)

    /** A page of any ContentDirectory: a server's, or a Sonos queue's ("Q:0"); [url] vets the URLs in it. */
    fun browse(control: String, url: (String) -> String?, id: String, start: Int, count: Int): Page {
        val reply = Http.soap(
            control, "ContentDirectory", "Browse",
            "<ObjectID>${esc(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>$FILTER</Filter>" +
                "<StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>",
        )
        val (result, returned, total) = args(reply, "Result", "NumberReturned", "TotalMatches")
        return Page(Didl.parse(result ?: "", url), returned?.toIntOrNull() ?: 0, total?.toIntOrNull() ?: 0)
    }

    /** A container's playable tracks over all its pages, up to [max]: what a menu on an album queues. */
    fun tracks(s: Server, id: String, max: Int): List<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        while (out.size < max) {
            val p = browse(s, id, start, 200)
            p.entries.filterTo(out) { !it.container && it.playable }
            start += p.returned
            if (p.returned == 0 || start >= p.total) break
        }
        return if (out.size > max) out.subList(0, max) else out
    }

    /** Every visible room in the household, from any one player at [host]. */
    fun rooms(host: String): List<Room> {
        val reply = Http.soap("http://$host/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState")
        val p = Xml.newPullParser().apply { setInput(StringReader(noDtd(arg(reply, "ZoneGroupState") ?: return emptyList()))) }
        val rooms = ArrayList<Room>()
        var coordinator = ""
        var name = ""
        var at = ""
        var others = 0
        while (p.next() != END_DOCUMENT) {
            if (p.eventType == END_TAG && p.name == "ZoneGroup") {
                if (at.isNotEmpty()) rooms += Room(coordinator, if (others == 0) name else "$name + $others", at)
                continue
            }
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "ZoneGroup" -> {
                    coordinator = p.attr("Coordinator")
                    name = ""
                    at = ""
                    others = 0
                }
                // A bonded Sub or surround is an Invisible member, or a Satellite element inside one.
                "ZoneGroupMember" -> if (p.attr("Invisible") != "1") {
                    if (p.attr("UUID") != coordinator) others++
                    else hostOf(p.attr("Location"))?.let {
                        at = it
                        name = p.attr("ZoneName")
                    }
                }
            }
        }
        return rooms.sortedBy { it.name }
    }

    /** The media server described at [location], or null when it has no ContentDirectory. */
    fun server(location: String): Server? {
        val base = URL(location)
        val p = parser(Http.get(location))
        var udn = ""
        var name = ""
        var service = ""
        var control = ""
        var mime = ""
        var width = 0
        var url = ""
        var icon = ""
        var best = 0
        while (p.next() != END_DOCUMENT) {
            if (p.eventType == END_TAG && p.name == "icon") {
                // The largest PNG up to 120 px: a 40 dp row icon at xxhdpi.
                if (mime == "image/png" && width in best + 1..120) {
                    best = width
                    icon = url
                }
                mime = ""
                width = 0
                url = ""
                continue
            }
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "UDN" -> if (udn.isEmpty()) udn = p.nextText().trim()
                "friendlyName" -> if (name.isEmpty()) name = p.nextText().trim()
                "mimetype" -> mime = p.nextText().trim()
                "width" -> width = p.nextText().trim().toIntOrNull() ?: 0
                "url" -> url = p.nextText().trim()
                "serviceType" -> service = p.nextText().trim()
                "controlURL" -> if (control.isEmpty() && service.contains(":ContentDirectory:")) control = p.nextText().trim()
            }
        }
        val c = local(base, control) ?: return null
        return Server(udn, name.ifEmpty { base.host }, base.host, c, if (icon.isEmpty()) null else local(base, icon))
    }

    /** Resolves [ref] against [base], refusing anything that would leave the device's own host. */
    private fun local(base: URL, ref: String): String? = if (ref.isEmpty()) null
    else runCatching { URL(base, ref) }.getOrNull()?.takeIf { it.protocol == "http" && it.host == base.host }?.toString()
}

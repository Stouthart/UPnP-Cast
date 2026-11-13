package nl.stouthart.upnpcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.END_TAG
import org.xmlpull.v1.XmlPullParser.START_TAG
import java.io.IOException
import java.io.StringReader
import java.net.URL

/**
 * A Sonos room or group: playback and the queue go to its coordinator, at [host] ("ip:port"). [members]: the other
 * visible players grouped with it; a group's mute is the group's. [own]: the room's own player, where it joins or
 * leaves a group, and whose volume a room alone has. [players]: a group's players, coordinator included, which its
 * volume sets one by one. [sub]: the room has a bonded Sub, whose settings its own player keeps.
 */
class Room(
    val id: String, val name: String, val host: String, val members: Int = 0, val own: String = host,
    val players: List<String> = listOf(own), val sub: Boolean = false,
)

/**
 * Whether a player's channel map, as "RINCON_A:LF,RF;RINCON_B:SW", has a Sub: a player on the SW channel. A soundbar's
 * comes as HTSatChanMapSet, a speaker's as ChannelMapSet. A player answers the Sub's settings without one, too.
 */
fun hasSub(map: String) = map.split(';').any { "SW" in it.substringAfter(':', "").split(',') }

/** The same group as [o]: the same coordinator and players; two nulls are the same, nothing to control. */
fun Room?.same(o: Room?) = if (this == null) o == null else o != null && id == o.id && members == o.members && players == o.players

/**
 * A media server: its name, the address it lives at, its ContentDirectory control URL and its icon. [library]: the
 * Sonos household's own music library, served by a player, whose tracks are SMB links.
 */
class Server(
    val udn: String, val name: String, val host: String, val control: String, val icon: String?,
    val library: Boolean = false,
    val favorites: Boolean = false, // The household's Sonos favourites (FV:2), as the same player serves them.
)

private fun XmlPullParser.attr(name: String) = getAttributeValue(null, name) ?: ""

private val DOCTYPE = "<!DOCTYPE".toByteArray()
private val ENTITY = "<!ENTITY".toByteArray()

private fun at(b: ByteArray, i: Int, w: ByteArray) = i + w.size <= b.size && w.indices.all { b[i + it] == w[it] }

/**
 * Refuses XML with a DTD before it reaches the parser: it comes from the network, and entities defined there could
 * expand until memory runs out. No UPnP device sends one. Also XML with a NUL byte: not UTF-8, as UPnP's must be, and
 * in UTF-16 or UTF-32, which always has one, a DTD wouldn't show to this check.
 */
fun noDtd(b: ByteArray): ByteArray {
    for (i in b.indices) {
        if (b[i] == 0.toByte()) throw IOException("XML not in UTF-8")
        if (b[i] == '<'.code.toByte() && i + 1 < b.size && b[i + 1] == '!'.code.toByte() && (at(b, i, DOCTYPE) || at(b, i, ENTITY))) {
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

// The shared factory's, namespace-aware as Android's own: dc:title reads as "title", whatever the prefix.
private fun parser(b: ByteArray): XmlPullParser = Http.parsers.newPullParser().apply { setInput(noDtd(b).inputStream(), null) }

/**
 * An IP address written out: IPv4, or IPv6 in brackets as a URL has it, hex digits and colons with a zone after %, as
 * fe80::1%wlan0. Resolving one never asks DNS.
 */
fun literal(host: String): Boolean {
    if (host.length > 2 && host[0] == '[' && host[host.length - 1] == ']') {
        val (address, zone) = host.substring(1, host.length - 1).split('%', limit = 2).let { it[0] to it.getOrNull(1) }
        return address.contains(':') && address.all { it == ':' || it == '.' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
            (zone == null || zone.isNotEmpty() && zone.all { it.isLetterOrDigit() })
    }
    val p = host.split('.')
    return p.size == 4 && p.all { it.length in 1..3 && it.all { c -> c in '0'..'9' } && it.toInt() <= 255 }
}

/** An address in the private ranges a home network uses: where the speakers and servers live. */
fun isPrivate(ip: String): Boolean {
    val p = ip.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
}

/** [ip] lies within [net]/[prefix]; both 4 bytes or both 16. */
fun inSubnet(ip: ByteArray, net: ByteArray, prefix: Int): Boolean {
    if (ip.size != net.size || prefix < 0 || prefix > ip.size * 8) return false
    for (i in 0 until prefix) {
        val m = 0x80 ushr (i % 8)
        if ((ip[i / 8].toInt() and m) != (net[i / 8].toInt() and m)) return false
    }
    return true
}

/** The kind of Sonos product a room's main player is, for its icon. */
enum class Product { SOUNDBAR, SUB, PORTABLE, AMP, SPEAKER }

/** The family in a model name, such as "Sonos Beam Ultra" or "Sonos Era 300"; any other is a speaker. */
fun product(model: String): Product {
    val m = model.lowercase()
    return when {
        listOf("beam", "arc", "ray", "playbar", "playbase").any { it in m } -> Product.SOUNDBAR
        "sub" in m -> Product.SUB
        "move" in m || "roam" in m -> Product.PORTABLE
        "amp" in m || "port" in m || "connect" in m -> Product.AMP
        else -> Product.SPEAKER
    }
}

private val SID = Regex("[?&]sid=(\\d{1,5})(&|$)") // A music service's number, in a link's query.
private val SERVICE = Regex("<Service\\b([^>]*)>")
private val ATTR = Regex("(\\w+)=\"([^\"]*)\"")

/** The number of the music service [uri] comes from, as 31 for Qobuz in x-sonos-http:…?sid=31; null for none. */
fun serviceId(uri: String): String? = if (uri.startsWith("x-sonos")) SID.find(uri)?.groupValues?.get(1) else null

/**
 * The music services by number, from a speaker's descriptor list (ListAvailableServices): <Service Id="31"
 * Name="Qobuz" …>, its attributes in any order.
 */
fun serviceNames(list: String): Map<String, String> {
    val out = HashMap<String, String>()
    for (m in SERVICE.findAll(list)) {
        val a = ATTR.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        val id = a["Id"]?.takeIf { it.all(Char::isDigit) } ?: continue
        out[id] = Upnp.unesc(a["Name"] ?: continue)
    }
    return out
}

private const val SONOS_RADIO = "303"

/** Where a room's music comes from, as the Sonos app names it; [SERVER]: a UPnP server's link. */
enum class Source { HDMI, SPDIF, TV, SPOTIFY, AIRPLAY, LIBRARY, LINE_IN, SONOS_RADIO, RADIO, SERVER, OTHER }

/**
 * The source of what plays, by its URI, as the speaker reports it. The TV's input by the soundbar's [model]: the speaker
 * names neither, and Beam, Arc and Amp connect by HDMI, Ray, Playbar and Playbase optically.
 */
fun source(uri: String, model: String = ""): Source {
    val m = model.lowercase()
    return when {
        uri.startsWith("x-sonos-htastream:") -> when {
            listOf("beam", "arc", "amp").any { it in m } -> Source.HDMI
            listOf("ray", "playbar", "playbase").any { it in m } -> Source.SPDIF
            else -> Source.TV
        }
        // A service streaming to the speaker itself, named after the comma: x-sonos-vli:<player>:2,spotify:<session>.
        uri.startsWith("x-sonos-vli:") -> when (uri.substringAfter(',', "").substringBefore(':')) {
            "spotify" -> Source.SPOTIFY
            "airplay" -> Source.AIRPLAY
            else -> Source.OTHER
        }
        uri.startsWith("x-sonos-spotify:") -> Source.SPOTIFY
        // Sonos's own radio, service 303: its stations, and the tracks they play, as x-sonos-http:…?sid=303.
        serviceId(uri) == SONOS_RADIO -> Source.SONOS_RADIO
        uri.startsWith("x-file-cifs:") -> Source.LIBRARY
        uri.startsWith("x-rincon-stream:") -> Source.LINE_IN
        listOf("x-rincon-mp3radio:", "x-sonosapi-stream:", "x-sonosapi-radio:", "x-sonosapi-hls:", "aac:").any { uri.startsWith(it) } ->
            Source.RADIO
        uri.startsWith("http://") -> Source.SERVER
        else -> Source.OTHER
    }
}

private const val SONOS_PORT = 1400
private const val MAX_PAGES = 50 // Per track list for a menu: up to 10,000 entries looked through for 500 tracks.

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

    /** The five entities an attribute may carry, as esc() writes them, back to text. */
    fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /**
     * One page of a container's children; [total] tells whether more pages follow. [update] is the container's revision,
     * as a Sonos queue counts its changes: a delete sent with it is refused once the queue changed since.
     */
    class Page(val entries: List<Entry>, val returned: Int, val total: Int, val update: Long? = null)

    // Only what the rows and playback use: MinimServer honours the filter, which keeps genre and date off the wire.
    private const val FILTER = "dc:title,dc:creator,upnp:artist,upnp:album,upnp:class," +
        "upnp:albumArtURI,upnp:albumArtURI@dlna:profileID,upnp:originalTrackNumber,upnp:originalDiscNumber," +
        "res,res@protocolInfo,res@sampleFrequency,res@bitsPerSample,res@duration,@childCount"

    fun browse(s: Server, id: String, start: Int, count: Int) =
        if (s.favorites) page(s.control, id, "*", start, count) { Didl.favorites(it, s.control) } // With what plays them.
        else browse(s.control, if (s.library) Didl.library(s.control) else Didl.on(s.host), id, start, count)

    /** A page of any ContentDirectory: a server's, or a Sonos queue's ("Q:0"); [url] vets the URLs in it. */
    fun browse(control: String, url: (String) -> String?, id: String, start: Int, count: Int): Page =
        page(control, id, FILTER, start, count) { Didl.parse(it, url) }

    private fun page(control: String, id: String, filter: String, start: Int, count: Int, parse: (String) -> List<Entry>): Page {
        val (result, returned, total, update) = Http.soapArgs(
            control, "ContentDirectory", "Browse",
            "<ObjectID>${esc(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>$filter</Filter>" +
                "<StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>",
            "Result", "NumberReturned", "TotalMatches", "UpdateID",
        )
        return Page(parse(result ?: ""), returned?.toIntOrNull() ?: 0, total?.toIntOrNull() ?: 0, update?.toLongOrNull())
    }

    /** A container's playable tracks over all its pages, up to [max]: what a menu on an album queues. */
    fun tracks(s: Server, id: String, max: Int, wanted: () -> Boolean = { true }): List<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        var pages = 0
        // Bounded by pages too, and only forward: a server can't keep it going with pages of nothing playable, or
        // send it back with a negative count.
        while (out.size < max && pages++ < MAX_PAGES && wanted()) { // Only while it's still wanted, as Sonos.pages.
            val p = browse(s, id, start, 200)
            p.entries.filterTo(out) { !it.container && it.playable }
            if (p.returned <= 0) break
            start += p.returned
            if (start >= p.total) break
        }
        return if (out.size > max) out.subList(0, max) else out
    }

    /**
     * The model of the player at [host] ("ip:port") as the Sonos app names it, such as "Era 300": its description's
     * displayName, else its modelName without "Sonos "; "" without either. Only the player's own, not its parts'.
     */
    fun model(host: String): String {
        val p = parser(Http.get("http://$host/xml/device_description.xml"))
        var model = ""
        while (p.next() != END_DOCUMENT) {
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "displayName" -> return p.nextText().trim()
                "modelName" -> if (model.isEmpty()) model = p.nextText().trim()
                "deviceList" -> break // Its media server and renderer follow, with no name of their own.
            }
        }
        return model.removePrefix("Sonos ")
    }

    /** "ip:port" names a Sonos player on the phone's WiFi. */
    private fun player(host: String, local: (String) -> Boolean): Boolean {
        val ip = host.substringBeforeLast(':')
        return host.substringAfterLast(':') == SONOS_PORT.toString() && literal(ip) && local(ip)
    }

    /**
     * The household: its [groups] (a room on its own is a group of one), which the player controls; every room in
     * [rooms], the rows to check; and the group each room is in ([groupOf]), by coordinator id.
     */
    class Topology(val groups: List<Room>, val rooms: List<Room>, val groupOf: Map<String, String>) {
        /** The group the room [id] is in, or the first room's once it's gone: the one the player controls. */
        fun group(id: String?): Room? {
            val lead = groupOf[id] ?: groupOf[rooms.firstOrNull()?.id]
            return groups.firstOrNull { it.id == lead }
        }
    }

    private class Member(val id: String, val name: String, val host: String, val sub: Boolean)

    /** The household, from any one player at [host]. */
    fun topology(host: String): Topology {
        val reply = Http.soap("http://$host/ZoneGroupTopology/Control", "ZoneGroupTopology", "GetZoneGroupState")
        return topology(state = arg(reply, "ZoneGroupState") ?: "")
    }

    /** The household from a ZoneGroupState document. Only players at Sonos's port on an address [local] takes count. */
    fun topology(state: String, local: (String) -> Boolean = Lan::local): Topology {
        if (state.isEmpty()) return Topology(emptyList(), emptyList(), emptyMap())
        val p = Http.parsers.newPullParser().apply { setInput(StringReader(noDtd(state))) }
        val groups = ArrayList<Room>()
        val rooms = ArrayList<Room>()
        val groupOf = HashMap<String, String>()
        val members = ArrayList<Member>()
        var coordinator = ""
        var name = ""
        var at = ""
        while (p.next() != END_DOCUMENT) {
            if (p.eventType == END_TAG && p.name == "ZoneGroup") {
                if (at.isNotEmpty()) {
                    val others = members.size - 1
                    groups += Room(coordinator, if (others == 0) name else "$name + $others", at, others, at, members.map { it.host })
                    for (m in members) {
                        rooms += Room(m.id, m.name, at, 0, m.host, sub = m.sub)
                        groupOf[m.id] = coordinator
                    }
                }
                continue
            }
            if (p.eventType != START_TAG) continue
            when (p.name) {
                "ZoneGroup" -> {
                    coordinator = p.attr("Coordinator")
                    name = ""
                    at = ""
                    members.clear()
                }
                // A bonded Sub or surround is an Invisible member, or a Satellite element inside one. Only a player on
                // this WiFi, at Sonos's port: the topology is the one list the SSDP check doesn't cover, and the
                // players in it are asked every few seconds.
                "ZoneGroupMember" -> if (p.attr("Invisible") != "1") {
                    val h = hostOf(p.attr("Location"))?.takeIf { player(it, local) } ?: continue
                    val m = Member(p.attr("UUID"), p.attr("ZoneName"), h, hasSub(p.attr("HTSatChanMapSet") + ";" + p.attr("ChannelMapSet")))
                    members += m
                    if (m.id == coordinator) {
                        at = h
                        name = m.name
                    }
                }
            }
        }
        return Topology(groups.sortedBy { it.name }, rooms.sortedBy { it.name }, groupOf)
    }

    /**
     * The media server described at [location], or null when it has no ContentDirectory. Only on the phone's WiFi, as a
     * player: a remembered address, or one a device answered with, is never taken further than that.
     */
    fun server(location: String): Server? {
        val base = URL(location)
        if (!literal(base.host) || !Lan.local(base.host)) return null
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

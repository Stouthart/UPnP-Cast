package nl.stouthart.upnpcast

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What plays now as a Sonos favourite: the DIDL-Lite the speaker takes for one (CreateObject in FV:2). A service's station
 * and a Music library track carry Sonos's own ids, as the Sonos app saves them: without those, the Sonos app hides the
 * favourite. A server's track has no such id, so the Sonos app hides it either way; it shows, and plays, in this app.
 */
object Favorites {
    private const val NS = "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
        "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
    private const val STATION = "x-sonosapi-stream:"
    private val SID = Regex("[?&]sid=(\\d+)")

    /** What a favourite plays, to match what plays now: a station by itself, as its link's flags change; else the link. */
    fun key(uri: String) = if (uri.startsWith(STATION)) uri.substringBefore('?') else uri

    /** A service's station, x-sonosapi-stream:<station>?sid=<service>…, by its name, logo and [service]; else null. */
    fun station(uri: String, title: String, art: String, service: String): String? {
        if (!uri.startsWith(STATION)) return null
        val station = uri.removePrefix(STATION).substringBefore('?').ifEmpty { return null }
        val sid = SID.find(uri)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        // As Sonos names a station and its service: the station behind 10092064, the service as its number × 256 + 7.
        val id = esc("10092064$station")
        val md = "<DIDL-Lite $NS><item id=\"$id\" parentID=\"$id\" restricted=\"true\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
            "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">SA_RINCON${sid * 256 + 7}_</desc></item></DIDL-Lite>"
        return item(title, "x-sonosapi-stream:*:*:*", uri, art, service, md)
    }

    /**
     * A Music library file, x-file-cifs://<host>/<share>/…, its cover the speaker's at [player] (host:port), as the Sonos
     * app saves one; [protocol] as the speaker reported it, else by the file's extension. Else null.
     */
    fun library(uri: String, title: String, player: String, protocol: String): String? {
        if (!uri.startsWith("x-file-cifs://")) return null
        // Its path as written, every slash spelled out: a + stays a +. A broken escape, as the speaker could send, is none.
        val path = runCatching { URLDecoder.decode(uri.removePrefix("x-file-cifs:").replace("+", "%2B"), Charsets.UTF_8) }
            .getOrNull() ?: return null
        val md = "<DIDL-Lite $NS><item id=\"${esc("S:" + path.replace("/", "__UNENCODED_SLASH__"))}\" restricted=\"true\" " +
            "parentID=\"\"><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
            "<desc id=\"cdudn\" namespace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">RINCON_AssociatedZPUDN</desc></item></DIDL-Lite>"
        val art = "http://$player/getaa?u=${URLEncoder.encode(uri, Charsets.UTF_8)}&v=45"
        return item(title, protocol.ifEmpty { "x-file-cifs:*:${mime(uri)}:*" }, uri, art, "From Music Library", md)
    }

    /** A server's track (http), with the metadata it plays with, its artist as the favourite's line. */
    fun server(uri: String, title: String, artist: String, art: String, protocol: String, meta: String): String? {
        if (!uri.startsWith("http://") || meta.isEmpty()) return null
        return item(title, protocol.ifEmpty { "http-get:*:${mime(uri)}:*" }, uri, art, artist, meta)
    }

    private fun item(title: String, protocol: String, uri: String, art: String, line: String, md: String) =
        "<DIDL-Lite $NS><item id=\"\" parentID=\"FV:2\" restricted=\"false\"><dc:title>${esc(title)}</dc:title>" +
            "<upnp:class>object.itemobject.item.sonos-favorite</upnp:class><r:ordinal>-1</r:ordinal>" +
            "<res protocolInfo=\"${esc(protocol)}\">${esc(uri)}</res>" +
            (if (art.isNotEmpty()) "<upnp:albumArtURI>${esc(art)}</upnp:albumArtURI>" else "") +
            "<r:type>instantPlay</r:type>" + (if (line.isNotEmpty()) "<r:description>${esc(line)}</r:description>" else "") +
            "<r:resMD>${esc(md)}</r:resMD></item></DIDL-Lite>"

    private fun mime(uri: String) = when (uri.substringBefore('?').substringAfterLast('.').lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        else -> "audio/mpeg"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

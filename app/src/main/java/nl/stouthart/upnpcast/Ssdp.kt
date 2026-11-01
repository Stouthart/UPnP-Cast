package nl.stouthart.upnpcast

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** SSDP search: one M-SEARCH per target; answers are unicast, so no multicast lock is needed. */
object Ssdp {
    const val SONOS = "urn:schemas-upnp-org:device:ZonePlayer:1"
    const val SERVER = "urn:schemas-upnp-org:device:MediaServer:1"
    private val group = InetAddress.getByAddress(byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte()))

    /** An answer: what it is ([st]), the device's description URL, and its USN (`uuid:…::<st>`). */
    class Answer(val st: String, val location: String, val usn: String)

    /** Calls [found] for each answer until [ms] have passed; devices wait up to MX (1 s) before answering. */
    fun search(targets: Array<String>, ms: Long, found: (Answer) -> Unit) {
        DatagramSocket().use { s ->
            for (t in targets) {
                val m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $t\r\n\r\n"
                    .toByteArray()
                s.send(DatagramPacket(m, m.size, group, 1900))
            }
            val end = SystemClock.elapsedRealtime() + ms
            val buf = ByteArray(2048)
            val p = DatagramPacket(buf, buf.size)
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return
                s.soTimeout = left.toInt()
                p.length = buf.size
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    return
                }
                var st = ""
                var location = ""
                var usn = ""
                for (l in String(buf, 0, p.length, Charsets.ISO_8859_1).split("\r\n")) {
                    val c = l.indexOf(':')
                    if (c < 0) continue
                    when (l.substring(0, c).trim().uppercase()) {
                        "ST" -> st = l.substring(c + 1).trim()
                        "LOCATION" -> location = l.substring(c + 1).trim()
                        "USN" -> usn = l.substring(c + 1).trim()
                    }
                }
                // A device names its own address; any other would aim our requests at a third party.
                val u = runCatching { URL(location) }.getOrNull()
                if (st in targets && u?.protocol == "http" && u.host == p.address.hostAddress) found(Answer(st, location, usn))
            }
        }
    }
}

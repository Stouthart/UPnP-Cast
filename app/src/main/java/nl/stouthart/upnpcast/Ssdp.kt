package nl.stouthart.upnpcast

import android.os.SystemClock
import java.io.IOException
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
        val net = Lan.wifi ?: throw IOException("No WiFi")
        DatagramSocket().use { s ->
            runCatching { net.bindSocket(s) } // As in Http: on the WiFi, wherever the process binding points by now.
            for (t in targets) {
                val m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $t\r\n\r\n"
                    .toByteArray()
                s.send(DatagramPacket(m, m.size, group, 1900))
            }
            val end = SystemClock.elapsedRealtime() + ms
            val buf = ByteArray(4096) // Sonos and MinimServer answer in a few hundred bytes; a wordy server has room.
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
                parse(String(buf, 0, p.length, Charsets.ISO_8859_1), p.address.hostAddress ?: "", targets)?.let(found)
            }
        }
    }

    /**
     * The answer in [text], sent from [address], when it's for one of [targets] and names its own address as its
     * location: any other would aim our requests at a third party. Else null.
     */
    internal fun parse(text: String, address: String, targets: Array<String>): Answer? {
        var st = ""
        var location = ""
        var usn = ""
        for (l in text.split("\r\n")) {
            val c = l.indexOf(':')
            if (c < 0) continue
            when (l.substring(0, c).trim().uppercase()) {
                "ST" -> st = l.substring(c + 1).trim()
                "LOCATION" -> location = l.substring(c + 1).trim()
                "USN" -> usn = l.substring(c + 1).trim()
            }
        }
        val u = runCatching { URL(location) }.getOrNull()
        return if (st in targets && u?.protocol == "http" && u.host == address) Answer(st, location, usn) else null
    }
}

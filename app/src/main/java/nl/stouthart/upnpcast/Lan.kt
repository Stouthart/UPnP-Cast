package nl.stouthart.upnpcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

/**
 * The phone's WiFi (or Ethernet, as on a docked tablet), where the speakers and servers are: watched while a screen
 * shows. The app's sockets are bound to it (the process, and each socket as it opens), so they stay on the LAN while Android
 * sends the internet over mobile data, as when the home internet is down; without it, requests fail at once rather
 * than trying mobile data for 3 s.
 */
object Lan {
    /** The network in use, or null while there's none; set on the main thread, read on any. */
    @Volatile
    var wifi: Network? = null
        private set

    // Its addresses with their prefixes, for [local]; empty until known.
    @Volatile
    private var subnets: List<LinkAddress> = emptyList()

    /** [ip], a literal, is on the phone's WiFi: in its subnet, or while that isn't known, a private one ([isPrivate]). */
    fun local(ip: String): Boolean {
        val nets = subnets
        if (nets.isEmpty()) return isPrivate(ip)
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        return nets.any { inSubnet(a.address, it.address.address, it.prefixLength) }
    }

    // Main thread only, from here on.
    private val watchers = ArrayList<() -> Unit>() // One per visible screen.
    private val nets = LinkedHashSet<Network>() // Every such network that's up: two, briefly, when the phone switches.
    private var cm: ConnectivityManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            nets += n
            if (wifi == null) use(n, true)
        }

        override fun onLost(n: Network) {
            nets -= n
            if (n == wifi) use(nets.firstOrNull(), true)
        }

        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            if (n == wifi) subnets = lp.linkAddresses
        }
    }

    /** Adds a screen's [watcher], which runs when the network comes, goes or changes; the first one starts watching. */
    fun start(c: Context, watcher: () -> Unit) {
        watchers += watcher
        if (cm != null) return
        val m = c.applicationContext.getSystemService(ConnectivityManager::class.java)
        cm = m
        // Known at once, so the screen opens on the right state; the callback then brings any other match, as WiFi
        // without internet while the phone uses mobile data. Nothing is told: each screen reads [wifi] as it starts.
        val n = m.activeNetwork
        val caps = n?.let { m.getNetworkCapabilities(it) }
        use(if (caps != null && request.canBeSatisfiedBy(caps)) n else null, false)
        m.registerNetworkCallback(request, callback, main)
    }

    /** Removes [watcher]; the last one stops watching. [wifi] and the binding stay, for requests still in flight. */
    fun stop(watcher: () -> Unit) {
        watchers -= watcher
        if (watchers.isNotEmpty()) return
        cm?.unregisterNetworkCallback(callback)
        cm = null
        nets.clear()
    }

    private fun use(n: Network?, tell: Boolean) {
        if (n == wifi) return
        subnets = n?.let { cm?.getLinkProperties(it)?.linkAddresses }.orEmpty()
        wifi = n
        cm?.bindProcessToNetwork(n) // Refused under a VPN that allows no bypass: requests then go its way, as before.
        if (tell) for (w in watchers.toList()) w()
    }
}

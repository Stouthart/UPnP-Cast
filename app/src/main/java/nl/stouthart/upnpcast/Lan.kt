package nl.stouthart.upnpcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/** Why [name] didn't answer: the phone has no WiFi, or else the device itself. */
fun Context.unreachable(name: String): String =
    if (Lan.wifi == null) getString(R.string.no_wifi) else getString(R.string.cant_reach, name)

/**
 * The phone's WiFi (or Ethernet, as on a docked tablet), where the speakers and servers are: watched while a screen
 * shows, as LDAC Lock watches Bluetooth. The app's sockets are bound to it, so they stay on the LAN while Android
 * sends the internet over mobile data, as when the home internet is down; without it, requests fail at once rather
 * than trying mobile data for 3 s.
 */
object Lan {
    /** The network in use, or null while there's none; set on the main thread, read on any. */
    @Volatile
    var wifi: Network? = null
        private set

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
        wifi = n
        cm?.bindProcessToNetwork(n) // Refused under a VPN that allows no bypass: requests then go its way, as before.
        if (tell) for (w in watchers.toList()) w()
    }
}

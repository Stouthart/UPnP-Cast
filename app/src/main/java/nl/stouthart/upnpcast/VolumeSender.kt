package nl.stouthart.upnpcast

import android.os.SystemClock

private const val QUIET_MS = 1500L // After a change, readings leave the volume alone until it has landed.

/**
 * The volume the slider or the volume buttons set: one SetVolume out at a time, the latest wanted going next once it's
 * back. A reading of the room's volume shows only when [calm]: not while a change is out, nor for QUIET_MS after the
 * last, nor from before the last one landed, as a slow speaker would have the slider jump back. [send] puts a volume on
 * its way and calls its argument once it's back; false when it couldn't. Main thread only. The same in the Mac app.
 */
class VolumeSender {
    var send: (Int, () -> Unit) -> Boolean = { _, _ -> false }

    private var wanted = -1 // The volume still to send.
    private var sending = false
    private var landed = 0L // The uptime the last one came back.
    private var quiet = 0L // Until then, after a change, readings are left alone.

    fun want(v: Int) {
        wanted = v
        quiet = SystemClock.uptimeMillis() + QUIET_MS
        next()
    }

    /** Whether a reading taken at [at] (uptime) may show. */
    fun calm(at: Long) = !sending && SystemClock.uptimeMillis() > quiet && at > landed

    /** Another room, or stopped: what this one still had to send stays there, and its own goes at once. */
    fun forget() {
        wanted = -1
        sending = false
        quiet = 0L
    }

    private fun next() {
        if (sending || wanted < 0) return
        val v = wanted
        wanted = -1
        sending = true
        if (!send(v) { landed = SystemClock.uptimeMillis(); sending = false; next() }) sending = false
    }
}

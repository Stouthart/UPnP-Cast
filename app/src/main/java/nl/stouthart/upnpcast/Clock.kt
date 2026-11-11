package nl.stouthart.upnpcast

private const val SLACK_MS = 250L // A position reply's trip, on top of the speaker rounding down to whole seconds.
private const val SETTLE_MS = 4000L // How long a seek may take to land, while the speaker still reports 0 or the old place.

/**
 * The position as Now Playing and the mini player show it: a clock of its own, which counts on between polls while the
 * room plays, so each second shows for a second at any Polling interval and with no network. An answer from the room
 * corrects it only where it disagrees. Times are in ms on one steady clock (uptime), given in, so a test can drive it.
 * The same in the Mac app.
 */
class Clock {
    /** What an answer changed: the track's [length] (the bar's range and Quick skip), and where the clock is ([moved]). */
    class Change(val length: Boolean, val moved: Boolean)

    private var base = 0L // ms into the track at [at], counting on from there while [running].
    private var at = 0L
    var running = false
        private set
    var length = 0 // The track's length in s; 0 for what has none, as a stream.
        private set
    private var settle = 0L // Until then, a seek is landing.

    /** ms into the track at [now]. */
    fun ms(now: Long) = if (running) base + (now - at) else base

    /** Seconds into the track at [now], within its length: what shows. 0 for a stream. */
    fun shown(now: Long) = minOf(length.toLong(), ms(now) / 1000).toInt()

    /** ms from [now] to the next whole second, when the shown time changes. */
    fun untilNext(now: Long) = 1000 - ms(now) % 1000

    /** A seek to [s] seconds: the clock goes along at once; answers that don't fit it can't pull it back meanwhile. */
    fun seek(s: Int, now: Long) {
        base = s.coerceIn(0, maxOf(length, 0)) * 1000L
        at = now
        settle = now + SETTLE_MS
    }

    /** [d] seconds on or back, within the track, from where the clock is, as Quick skip. */
    fun skip(d: Int, now: Long) {
        base = (ms(now) + d * 1000L).coerceIn(0, length * 1000L)
        at = now
        settle = now + SETTLE_MS
    }

    /**
     * Previous: the track's start, or the track before, which starts there too; at once, as for a seek. Nothing for what
     * has no length: it gets Previous as it is.
     */
    fun restart(now: Long) {
        if (length > 0) seek(0, now)
    }

    /**
     * The room's answer: [state] and [elapsed] of [duration] seconds, the position read at [now]. The speaker rounds down,
     * so anywhere in the reported second, plus the reply's trip, the clock is right and stays; behind, it catches up;
     * ahead, it falls back into that second. Another track, a seek, play or pause: that second's middle. Buffering holds
     * it where it is, unless it's another track or a stream, which take their own length at once, waiting at their own
     * place. While a seek lands, answers from before it are left out until one agrees, or one is another track.
     */
    fun answer(state: String, elapsed: Int, duration: Int, now: Long): Change {
        val reported = elapsed * 1000L
        val ms = ms(now)
        val top = reported + 1000 + SLACK_MS
        val near = duration == length && ms >= reported - 1500 && ms <= top + 1500
        val other = duration > 0 && duration != length
        if (!near && !other && now < settle) return Change(length = false, moved = false)
        if (near) settle = 0
        if (state == "TRANSITIONING") {
            if (duration != length) {
                set(reported, now, false, duration)
                return Change(length = true, moved = true)
            }
            if (!running) return Change(length = false, moved = false)
            set(ms, now, false, length)
            return Change(length = false, moved = true)
        }
        val play = state == "PLAYING"
        val to = when {
            play != running || !near -> reported + 500
            ms < reported -> reported
            ms > top -> reported + 999
            else -> return Change(length = true, moved = false)
        }
        set(to, now, play, duration)
        return Change(length = true, moved = true)
    }

    private fun set(ms: Long, now: Long, play: Boolean, seconds: Int) {
        base = ms
        at = now
        running = play
        length = seconds
    }
}

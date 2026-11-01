package nl.stouthart.upnpcast

import android.app.UiModeManager
import android.content.Context

/** The two settings on the main screen. */
object Prefs {
    private const val FILE = "settings"
    private const val APPEARANCE = "appearance" // 0 System default, 1 Light mode, 2 Dark mode.
    private const val POLL = "poll" // Seconds between the player's questions to the room.

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // 2 s by default: about half the CPU of 1 s, and Now Playing's progress still moves every second on its own.
    fun poll(c: Context) = prefs(c).getInt(POLL, 2).coerceIn(1, 10)

    fun poll(c: Context, s: Int) = prefs(c).edit().putInt(POLL, s.coerceIn(1, 10)).apply()

    fun appearance(c: Context) = prefs(c).getInt(APPEARANCE, 0)

    /**
     * Android keeps the app's own night mode across restarts and applies it to every screen, recreating them; the
     * preference only remembers which choice to mark.
     */
    fun appearance(c: Context, a: Int) {
        prefs(c).edit().putInt(APPEARANCE, a).apply()
        c.getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (a) {
                1 -> UiModeManager.MODE_NIGHT_NO
                2 -> UiModeManager.MODE_NIGHT_YES
                else -> UiModeManager.MODE_NIGHT_AUTO // Follows the system.
            },
        )
    }
}

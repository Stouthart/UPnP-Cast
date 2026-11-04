package nl.stouthart.upnpcast

import android.app.UiModeManager
import android.content.Context

/** The settings on the main screen. */
object Prefs {
    private const val FILE = "settings"
    private const val APPEARANCE = "appearance" // 0 System default, 1 Light mode, 2 Dark mode.
    private const val STEP = "step" // Volume per press of a volume button.
    private const val POLL = "poll" // Seconds between the player's questions to the room.
    private const val OPEN = "open" // The App settings card was left open.
    private const val GROUP = "group" // A tap on a room groups the speakers.
    private const val SKIP = "skip" // Seconds per tap of a quick skip button, in steps of 5; 0: no such buttons.

    private const val DEVICES = "devices" // The main screen's file, beside the speakers' and servers' addresses.
    private const val ROOM = "room" // A room among those that play together: theirs is the group the player controls.
    private const val MODEL = "model:" // + a player's id: its model, kept, as a player doesn't change it.

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The room chosen on the main screen: every screen's player follows its group. */
    fun room(c: Context): String? = c.getSharedPreferences(DEVICES, Context.MODE_PRIVATE).getString(ROOM, null)

    fun room(c: Context, id: String) = c.getSharedPreferences(DEVICES, Context.MODE_PRIVATE).edit().putString(ROOM, id).apply()

    /** A player's model, by its id, as the main screen read it: for its room's icon and line, and the TV's input. */
    fun model(c: Context, id: String) = c.getSharedPreferences(DEVICES, Context.MODE_PRIVATE).getString(MODEL + id, null) ?: ""

    fun model(c: Context, id: String, m: String) =
        c.getSharedPreferences(DEVICES, Context.MODE_PRIVATE).edit().putString(MODEL + id, m).apply()

    // 2 s by default: about half the CPU of 1 s, and Now Playing's progress still moves every second on its own.
    fun poll(c: Context) = prefs(c).getInt(POLL, 2).coerceIn(1, 10)

    fun poll(c: Context, s: Int) = prefs(c).edit().putInt(POLL, s.coerceIn(1, 10)).apply()

    // 2 by default: 20 to 40 in ten presses, and quick to hold.
    fun step(c: Context) = prefs(c).getInt(STEP, 2).coerceIn(1, 10)

    fun step(c: Context, s: Int) = prefs(c).edit().putInt(STEP, s.coerceIn(1, 10)).apply()

    // Closed at first: the settings are rarely changed.
    fun open(c: Context) = prefs(c).getBoolean(OPEN, false)

    fun open(c: Context, o: Boolean) = prefs(c).edit().putBoolean(OPEN, o).apply()

    // Off at first: grouping changes what plays in another room, for whoever is listening there.
    fun group(c: Context) = prefs(c).getBoolean(GROUP, false)

    fun group(c: Context, g: Boolean) = prefs(c).edit().putBoolean(GROUP, g).apply()

    // Off at first: Now Playing keeps its five controls until a step is chosen.
    fun skip(c: Context) = prefs(c).getInt(SKIP, 0).coerceIn(0, 60) / 5 * 5

    fun skip(c: Context, s: Int) = prefs(c).edit().putInt(SKIP, s.coerceIn(0, 60)).apply()

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

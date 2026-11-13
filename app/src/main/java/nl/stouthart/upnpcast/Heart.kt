package nl.stouthart.upnpcast

import android.os.Handler
import android.os.Looper

/**
 * The heart: whether what plays is a Sonos favourite, one state for every screen's player, so a screen shown again
 * never has an older heart than the one before. Read and saved at the household's favourites player ([server]),
 * whichever room plays: each player keeps its own copy of the favourites, which follows a change made at another only
 * a while later, and a heart read at the room that plays did nothing or the opposite after a room switch. Main thread
 * only; the reads and saves run where the caller's [work] puts them. The same in the Mac app (Heart.swift).
 */
object Heart {
    /** The household's favourites, at the player the main screen found them at, as its Favorites card; none yet, no heart. */
    @Volatile
    var server: Server? = null

    var shown = false // Once the favourites are read for what plays: never a wrong state.
        private set
    val on get() = id != null // Filled: what plays is a favourite, or a save of it is on its way.

    private var key = "" // What plays now as a favourite, by its key (see Favorites).
    private var item: String? = null // Its DIDL, to save it.
    private var id: String? = null // The favourite's id while it is one: null when it isn't, "" while a save is on its way.
    private val main = Handler(Looper.getMainLooper())
    private val views = LinkedHashSet<() -> Unit>() // The players showing it, each redrawing its own.

    /** [draw] redraws a player's heart at each change, from now on until [unwatch]. */
    fun watch(draw: () -> Unit) {
        views += draw
        draw()
    }

    fun unwatch(draw: () -> Unit) {
        views -= draw
    }

    /** Read again at the next poll: one may have been saved or removed meanwhile, also elsewhere; or [favorite] alone. */
    fun forget(favorite: String? = null) {
        if (favorite == null || favorite == key) key = ""
    }

    /**
     * The heart for [now], what plays: hidden for what can't be a favourite; for something new, hidden until the
     * favourites are read, so it never shows the wrong state, then outlined or filled.
     */
    fun follow(now: Pair<String, String>?, work: (() -> Unit) -> Unit) {
        if (now == null) {
            key = ""
            return show(false)
        }
        item = now.second
        if (now.first == key) return
        val k = now.first
        key = k
        show(false)
        val s = server ?: return run { key = "" } // None to read yet: asked again once they're there.
        work {
            val f = runCatching { Sonos.favorites(s) }.getOrNull()
            main.post {
                if (k != key) return@post
                if (f == null) return@post run { key = "" } // Unknown: no heart, rather than a wrong one; the next poll asks.
                id = match(f, k)
                show(true)
            }
        }
    }

    /**
     * Saves what plays as a favourite, or removes it: the heart turns at once; the favourites read after confirm it.
     * [failed] with whether it was a save; [read] with the favourites as they are after it, or null when unread.
     */
    fun toggle(work: (() -> Unit) -> Unit, failed: (Boolean) -> Unit, read: (List<Entry>?) -> Unit) {
        val k = key.ifEmpty { return }
        val didl = item ?: return
        val s = server ?: return
        val was = id
        if (was == "") return // A save on its way: the heart follows once it's in.
        id = if (was == null) "" else null
        changed()
        work {
            try {
                if (was == null) Sonos.addFavorite(s.control, didl) else Sonos.removeFavorite(s.control, was)
            } catch (_: Exception) {
                main.post {
                    failed(was == null)
                    if (k == key) { // As it was, unless the read below says otherwise.
                        id = was
                        changed()
                    }
                }
            }
            val f = runCatching { Sonos.favorites(s) }.getOrNull()
            main.post {
                read(f) // The Favorites card and list, also when what plays changed meanwhile: the save stands.
                if (k != key) return@post
                if (f != null) {
                    id = match(f, k)
                    changed()
                } else { // Unconfirmed, maybe saved after all: no heart until the next poll has read them again.
                    key = ""
                    show(false)
                }
            }
        }
    }

    private fun show(on: Boolean) {
        shown = on
        changed()
    }

    private fun changed() = views.toList().forEach { it() }

    private fun match(favorites: List<Entry>, key: String) =
        favorites.firstOrNull { it.fav?.uri?.isNotEmpty() == true && Favorites.key(it.fav.uri) == key }?.id
}

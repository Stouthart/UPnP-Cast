package nl.stouthart.upnpcast

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView

/**
 * The cover's side: [want] within [min]..[max], where a small window (split screen, a short one) can make [max] the
 * smaller of the two; then it's [max].
 */
fun coverSide(want: Int, min: Int, max: Int) = want.coerceIn(minOf(min, max), max)

/** The room's track at full size, with progress, transport, play mode, volume and the way to its queue. */
class NowPlayingActivity : Activity() {
    private lateinit var player: Player

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val room = Player.room(intent) ?: return finish()
        setContentView(R.layout.activity_now_playing)
        sheet(findViewById(R.id.scroll), findViewById(R.id.close))
        size()
        fit()
        player = Player(this).apply {
            hosts += Player.hosts(intent)
            this.room = room
        }
    }

    // Rotating keeps the screen (see the manifest), so only the cover needs a new size.
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        size()
    }

    private var largest = 0 // The cover's side when there is room: see size().

    /**
     * A square cover, as large as the screen leaves it: at most the width less the margins, and on every layout
     * exactly the height the controls don't take, which keep to the bottom. A taller screen, as the Astra's, gets a
     * larger cover; a title on two lines, the bars, split screen or landscape take it back, down to 160dp.
     */
    private fun size() {
        val m = resources.displayMetrics
        largest = m.widthPixels - (40 * m.density).toInt() // The layout's 20dp sides.
        cover(largest)
    }

    private fun fit() {
        val scroll: ScrollView = findViewById(R.id.scroll)
        val art: View = findViewById(R.id.player_art)
        val spare: View = findViewById(R.id.spacer)
        val player: View = findViewById(R.id.player)
        var asked = 0 // The side last set: never asked twice, so a frame can't wait for good.
        // Before each frame, so the cover is never drawn at a size it then leaves: a new side skips this frame for the
        // layout it needs. The content's own height is the column's less the spacer's; the screen's is the ScrollView's.
        player.viewTreeObserver.addOnPreDrawListener {
            val over = player.height - spare.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom)
            val side = coverSide(art.height - over, (160 * resources.displayMetrics.density).toInt(), largest)
            if (scroll.height == 0 || side == art.height || side == asked) return@addOnPreDrawListener true
            asked = side
            cover(side)
            false
        }
    }

    private fun cover(side: Int) {
        findViewById<View>(R.id.player_art).apply {
            layoutParams = layoutParams.apply {
                width = side
                height = side
            }
        }
    }

    override fun onStart() {
        super.onStart()
        player.start()
    }

    override fun onStop() {
        player.stop()
        super.onStop()
    }

    // The volume buttons set the room's volume while this screen is in front.
    override fun dispatchKeyEvent(e: KeyEvent) = player.key(e) || super.dispatchKeyEvent(e)

    override fun onDestroy() {
        player.destroy()
        super.onDestroy()
    }
}

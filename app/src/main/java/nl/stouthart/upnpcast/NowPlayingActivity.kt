package nl.stouthart.upnpcast

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import kotlin.math.abs

private const val SWIPE_DP = 1000 // dp/s downwards that close the sheet: a flick, not a slow scroll.

/**
 * The cover's side: [want] within [min]..[max], where a small window (split screen, a short one) can make [max] the
 * smaller of the two; then it's [max].
 */
fun coverSide(want: Int, min: Int, max: Int) = want.coerceIn(minOf(min, max), max)

/** The room's track at full size, with progress, transport, play mode, volume and the way to its queue. */
class NowPlayingActivity : Activity() {
    private lateinit var player: Player

    @SuppressLint("ClickableViewAccessibility") // The swipe only adds to ⌄, which is the accessible way to close.
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val room = Player.room(intent) ?: return finish()
        // A sheet: up from the bottom when it opens; down again on ⌄, a swipe down or Back.
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.sheet_up, R.anim.hold)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.sheet_down)
        setContentView(R.layout.activity_now_playing)
        size()
        fit()
        findViewById<View>(R.id.close).setOnClickListener { finish() }
        val scroll: ScrollView = findViewById(R.id.scroll)
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                // Only at the top: further down, a swipe down scrolls back up first.
                if (scroll.scrollY > 0 || vy < SWIPE_DP * resources.displayMetrics.density || vy < 2 * abs(vx)) return false
                finish()
                return true
            }
        })
        scroll.setOnTouchListener { _, e ->
            swipe.onTouchEvent(e)
            false // The ScrollView still scrolls as ever.
        }
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
     * A square cover: the width less the margins, but no more than 45% of the height. On a phone the rest can still
     * be taller than the screen, once the bars take their share or a title runs to two lines, so on every layout the
     * cover gives up exactly the overflow, and takes it back when there is room again.
     */
    private fun size() {
        val m = resources.displayMetrics
        largest = minOf(m.widthPixels - (48 * m.density).toInt(), (m.heightPixels * 0.45).toInt())
        cover(largest)
    }

    private fun fit() {
        val scroll: ScrollView = findViewById(R.id.scroll)
        val art: View = findViewById(R.id.player_art)
        val spare: View = findViewById(R.id.spacer)
        findViewById<View>(R.id.player).addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            // The content's own height is the column's less the spacer's share; the screen's is the ScrollView's.
            val over = v.height - spare.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom)
            val side = coverSide(art.height - over, (160 * resources.displayMetrics.density).toInt(), largest)
            if (side != art.height) v.post { cover(side) } // Not inside this layout pass.
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

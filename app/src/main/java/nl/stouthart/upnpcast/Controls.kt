package nl.stouthart.upnpcast

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Activity.OVERRIDE_TRANSITION_CLOSE
import android.app.Activity.OVERRIDE_TRANSITION_OPEN
import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

private const val SWIPE_DP = 1000 // dp/s downwards that close a sheet: a flick, not a slow scroll.

/** The screens' controls and rows, shared by the main screen, Now Playing and Room settings. */
var View.shown: Boolean
    get() = visibility == View.VISIBLE
    set(v) {
        visibility = if (v) View.VISIBLE else View.GONE
    }

/** A row of [choices], [chosen] filled; a tap on another calls [choose] with its index, then fills it. */
fun Context.segments(choices: Array<TextView>, chosen: Int, choose: (Int) -> Unit) {
    fun mark(c: Int) {
        for ((i, v) in choices.withIndex()) {
            v.isSelected = i == c // TalkBack says "Selected" on the chosen one.
            v.setBackgroundResource(if (i == c) R.drawable.segment_on else R.drawable.segment)
            v.setTextColor(getColor(if (i == c) R.color.accent_text else R.color.text))
        }
    }
    for ((i, v) in choices.withIndex()) v.setOnClickListener {
        if (v.isSelected) return@setOnClickListener
        choose(i)
        mark(i)
    }
    mark(chosen)
}

/** Sonos's labels at one size: the largest, from 14 sp down to 11, at which the longest fits its share of the row. */
fun fit(choices: Array<TextView>) {
    choices[0].addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ ->
        if (r - l == or - ol) return@addOnLayoutChangeListener // Only on a new width, as at first and after rotating.
        val t = v as TextView
        val room = t.width - t.paddingLeft - t.paddingRight
        val paint = Paint(t.paint)
        var sp = 14f
        while (sp > 11f) {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, t.resources.displayMetrics)
            if (choices.all { paint.measureText(it.text.toString()) <= room }) break
            sp -= 0.5f
        }
        t.post { for (c in choices) c.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) } // Not inside the layout pass.
    }
}

/** An app icon in a row's 40dp tile: 24dp, centred, as a line icon is drawn. */
fun glyph(v: ImageView, res: Int) {
    val pad = (8 * v.resources.displayMetrics.density).toInt()
    v.setPadding(pad, pad, pad, pad)
    v.setImageResource(res)
}

fun productIcon(p: Product) = when (p) {
    Product.SOUNDBAR -> R.drawable.ic_soundbar
    Product.SUB -> R.drawable.ic_sub
    Product.PORTABLE -> R.drawable.ic_portable
    Product.AMP -> R.drawable.ic_amp
    Product.SPEAKER -> R.drawable.ic_speaker
}

/** A room's second line, as the Sonos app's: its model, such as "Era 300", then its address. */
fun roomMeta(r: Room, model: String): String {
    val ip = r.own.substringBefore(':')
    // One kept by an earlier build is the model name whole, as "Sonos Era 300".
    return if (model.isEmpty()) ip else "${model.removePrefix("Sonos ")} · $ip"
}

/**
 * A sheet: up from the bottom when it opens; down again on ⌄ ([close]), a flick down at the top of [scroll], or Back.
 * Called in onCreate.
 */
@SuppressLint("ClickableViewAccessibility") // The swipe only adds to ⌄, which is the accessible way to close.
fun Activity.sheet(scroll: ScrollView, close: View) {
    overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.sheet_up, R.anim.hold)
    overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.hold, R.anim.sheet_down)
    close.setOnClickListener { finish() }
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
}

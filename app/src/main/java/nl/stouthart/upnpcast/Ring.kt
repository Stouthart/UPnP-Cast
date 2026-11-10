package nl.stouthart.upnpcast

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The track's progress in the mini player's Play/Pause circle: a ring on its edge, from the top clockwise as far as
 * the track has played; nothing for the rest. None for what has no length, as a radio stream or the TV. The same
 * in the Mac app: 2.5dp wide, its outer edge the circle's own; grey on the light card, white on the dark one.
 */
class Ring(density: Float, color: Int) : Drawable() {
    private val width = 2.5f * density
    private val inset = width / 2 // To the middle of the stroke: its outer edge on the circle's.
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }
    private val oval = RectF()
    private var part = -1f // The share played, 0 to 1; below 0, no ring.

    /** [elapsed] of [length] seconds; a length of 0 hides the ring. */
    fun set(elapsed: Int, length: Int) {
        val p = if (length > 0) elapsed.coerceIn(0, length) / length.toFloat() else -1f
        if (p == part) return
        part = p
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (part < 0) return
        oval.set(bounds)
        oval.inset(inset, inset)
        canvas.drawArc(oval, -90f, 360f * part, false, played) // From 12 o'clock, clockwise.
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

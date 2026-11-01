package nl.stouthart.upnpcast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Cover thumbnails: fetched and scaled down off the main thread, each URL once per size, and kept in memory.
 * [loaded] runs on the main thread when a thumbnail arrives, so the rows showing that URL can pick it up.
 */
class Art(val px: Int, private val loaded: (String) -> Unit) {
    companion object {
        // One cache for every screen, an eighth of the heap in all: screens on the back stack stay alive, and a cover
        // already shown at a size is there at once on the next screen that shows it at that size.
        private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    private val loading = HashSet<String>() // Main thread only, like failed.
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(
        3, 3, 10, TimeUnit.SECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(r: Runnable) = offerFirst(r) // The rows on screen now go before those scrolled past.
        },
    ).apply { allowCoreThreadTimeOut(true) } // Idle loaders leave after 10 s instead of living as long as the screen.

    private fun key(url: String) = "$px $url"

    operator fun get(url: String): Bitmap? = cache.get(key(url))

    /** Shows a cover in [v], filling it. */
    fun fill(v: ImageView, b: Bitmap) {
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
    }

    /** Shows [url]'s thumbnail in [v], or the [fallback] icon, centred at its own size, until it arrives or for good. */
    fun show(v: ImageView, url: String?, fallback: Int) {
        v.tag = url
        val b = url?.let { get(it) }
        if (b != null) return fill(v, b)
        v.scaleType = ImageView.ScaleType.CENTER
        v.setImageResource(fallback)
        if (url == null || url in failed || !loading.add(url)) return
        later(pool) {
            val t = runCatching { thumb(Http.get(url)) }.getOrNull()
            main.post {
                loading.remove(url)
                if (t == null) failed += url
                else {
                    cache.put(key(url), t)
                    loaded(url)
                }
            }
        }
    }

    /** Forgets the covers that failed, so the next show() fetches them again, as after WiFi came back; says if any had. */
    fun retry(): Boolean {
        if (failed.isEmpty()) return false
        failed.clear()
        return true
    }

    fun stop() {
        pool.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    /** Decoded at the smallest power-of-two step that still covers [px], then scaled to it. */
    private fun thumb(b: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val w = o.outWidth
        val h = o.outHeight
        if (w !in 1..8192 || h !in 1..8192) return null // It comes from the network: no decompression bombs.
        var s = 1
        while (minOf(w, h) / (s * 2) >= px) s *= 2
        val d = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val m = minOf(d.width, d.height)
        if (m <= px) return d
        return Bitmap.createScaledBitmap(d, d.width * px / m, d.height * px / m, true).also { if (it !== d) d.recycle() }
    }
}

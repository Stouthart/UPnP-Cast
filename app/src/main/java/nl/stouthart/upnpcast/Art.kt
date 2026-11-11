package nl.stouthart.upnpcast

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Cover thumbnails: fetched and scaled down off the main thread, each URL once per size, and kept in memory.
 * [loaded] runs on the main thread when a thumbnail arrives, so the rows showing that URL can pick it up. [also]: a
 * second size made from the same download, for another screen to find at once, as Now Playing's from the mini player.
 */
class Art(val px: Int, private val also: Int = 0, private val loaded: (String) -> Unit) {
    companion object {
        // One cache for every screen, an eighth of the heap in all: screens on the back stack stay alive, and a cover
        // already shown at a size is there at once on the next screen that shows it at that size.
        private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }

    private val loading = HashSet<String>() // Main thread only, like failed.
    private val queued = ConcurrentHashMap.newKeySet<String>() // Fetches not begun: pause() drops these alone.
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(
        3, 3, 10, TimeUnit.SECONDS,
        object : LinkedBlockingDeque<Runnable>() {
            override fun offer(r: Runnable) = offerFirst(r) // The rows on screen now go before those scrolled past.
        },
    ).apply { allowCoreThreadTimeOut(true) } // Idle loaders leave after 10 s instead of living as long as the screen.

    private fun key(url: String, size: Int = px) = "$size $url"

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
        queued += url
        later(pool) {
            queued -= url
            val bytes = runCatching { Http.get(url) }.getOrNull()
            val t = bytes?.let { runCatching { thumb(it, px) }.getOrNull() }
            val big = if (t == null || also <= 0 || cache.get(key(url, also)) != null) null
            else runCatching { thumb(bytes, also) }.getOrNull()
            main.post {
                loading.remove(url)
                if (t == null) failed += url
                else {
                    cache.put(key(url), t)
                    big?.let { cache.put(key(url, also), it) }
                    loaded(url)
                }
            }
        }
    }

    /**
     * Forgets the covers that failed or were dropped by [pause], so the next show() fetches them again, as after WiFi
     * came back or the screen showing again; says if there were any.
     */
    fun retry(): Boolean {
        if (failed.isEmpty() && !paused) return false
        failed.clear()
        paused = false
        return true
    }

    /**
     * The screen stopped: the loads not yet begun are dropped, nothing fetches for a screen that isn't shown. Those still
     * running land in the cache as they finish, and aren't fetched a second time meanwhile.
     */
    fun pause() {
        pool.queue.clear()
        loading.removeAll(queued)
        queued.clear()
        paused = true
    }

    private var paused = false // Main thread only.

    fun stop() {
        pool.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    /**
     * Decoded at the smallest power-of-two step that still covers [px], then its centre square scaled to it: every
     * cover is a square that shows the centre, so a wide one keeps no more than a square one.
     */
    private fun thumb(b: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        val w = o.outWidth
        val h = o.outHeight
        if (w !in 1..8192 || h !in 1..8192) return null // It comes from the network: no decompression bombs.
        // Nor more pixels than a 2:1 cover at full quality: 8192 × 2399 at Now Playing's size was 75 MiB decoded.
        val most = 8L * px * px
        var s = 1
        while (minOf(w, h) / (s * 2) >= px || w.toLong() / s * (h / s) > most) s *= 2
        val d = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val m = minOf(d.width, d.height)
        if (d.width == d.height && m <= px) return d
        val k = minOf(px, m).toFloat() / m
        val x = Matrix().apply { setScale(k, k) }
        return Bitmap.createBitmap(d, (d.width - m) / 2, (d.height - m) / 2, m, m, x, true).also { if (it !== d) d.recycle() }
    }
}

package nl.stouthart.upnpcast

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

private const val MOST = 64L shl 20 // Bytes on disk at most; then trimmed back to KEEP, the least used first.
private const val KEEP = 48L shl 20
private const val TRIM_EVERY = 50 // Writes between two looks at the total.

/**
 * Covers kept on disk between sessions, at the size they show, as JPEG: a large library's covers come at once the next
 * time, with no request, as the Music library's, which the speaker otherwise reads out of its files again each session.
 * By size and link, as in memory (Art); the least recently used go first once past MOST. Any thread. The same in the
 * Mac app (CoverDisk.swift).
 */
object CoverDisk {
    @Volatile
    private var dir: File? = null
    private val writes = AtomicInteger()

    /** In the app's cache, which Android may also empty when space runs short. */
    fun init(c: Context) {
        if (dir == null) dir = File(c.cacheDir, "covers").apply { mkdirs() }
    }

    private fun file(key: String) = dir?.let { File(it, hex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))) }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** The cover kept for [key], marked as just used; null when there is none. */
    fun get(key: String): ByteArray? {
        val f = file(key)?.takeIf { it.isFile } ?: return null
        return runCatching { f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    /** Keeps [b] for [key]; written whole or not at all. */
    fun put(key: String, b: Bitmap) {
        val f = file(key) ?: return
        val part = runCatching { File.createTempFile(f.name, ".part", f.parentFile) }.getOrNull() ?: return // Its own: two writes of one key don't mix.
        runCatching {
            part.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (!part.renameTo(f)) part.delete()
        }.onFailure { part.delete() }
        if (writes.incrementAndGet() % TRIM_EVERY == 0) trim()
    }

    private fun trim() {
        val files = dir?.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MOST) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= KEEP) break
            total -= f.length()
            f.delete()
        }
    }
}

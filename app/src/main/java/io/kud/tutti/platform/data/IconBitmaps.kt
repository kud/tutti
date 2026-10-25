package io.kud.tutti.platform.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Decoded TV app icons, kept in memory so a row, a sheet reopened or a notification update
 * never decodes the same file twice. An entry is keyed by the file's path, size and modified
 * time, so an icon refetched into the same path is decoded afresh, never served stale.
 *
 * With a [targetPx], the file is subsampled by a power of two while both sides stay at least
 * [targetPx]: the picture is never drawn larger than it was decoded, it only skips pixels the
 * screen could not show. Without one (the media notification, whose art the system may show
 * large) it is decoded whole, as before. Call off the main thread.
 */
object IconBitmaps {
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(
        minOf(MAX_CACHE_BYTES.toLong(), Runtime.getRuntime().maxMemory() / 16).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun load(path: String, targetPx: Int? = null): Bitmap? {
        val file = File(path)
        val modified = file.lastModified()
        if (modified == 0L) return null
        val key = cacheKey(path, file.length(), modified, targetPx)
        cache.get(key)?.let { return it }
        val sample = if (targetPx == null) 1 else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    /** The largest power of two that keeps both sides at least [targetPx]; 1 when already small. */
    internal fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetPx && height / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    internal fun cacheKey(path: String, length: Long, modified: Long, targetPx: Int?): String =
        "$path|$length|$modified|${targetPx ?: "full"}"
}

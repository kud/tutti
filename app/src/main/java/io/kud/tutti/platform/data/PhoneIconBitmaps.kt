package io.kud.tutti.platform.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import androidx.core.graphics.createBitmap
import io.kud.tutti.protocol.apps.PhoneIcons

/**
 * The icon of the matching app installed on this phone ([PhoneIcons.match]), rasterised once at
 * the tile's size and kept in memory, like [IconBitmaps]. Only the candidate packages are asked
 * about; the installed list is never read, and nothing leaves the phone. Call off the main thread.
 *
 * An adaptive icon is drawn full bleed: background then foreground, cropped to the 72/108 safe
 * zone so it fills the tile and the tile's own corner owns the edge (Iris, 2026-10-25). A missing
 * background leaves the bitmap transparent, so the foreground sits on the tile, never on a hole.
 * A legacy icon is drawn whole into the square, like the TV's art.
 */
object PhoneIconBitmaps {
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

    /** A miss is remembered this long, so a row of letter tiles doesn't ask on every recompose. */
    private const val MISS_TTL_MS = 60_000L

    private val cache = object : LruCache<String, Bitmap>(
        minOf(MAX_CACHE_BYTES.toLong(), Runtime.getRuntime().maxMemory() / 32).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val misses = HashMap<String, Long>()

    fun load(context: Context, tvPackage: String, targetPx: Int): Bitmap? {
        if (targetPx <= 0) return null
        val now = SystemClock.elapsedRealtime()
        synchronized(misses) { misses[tvPackage]?.let { if (now - it < MISS_TTL_MS) return null } }
        val pm = context.packageManager
        var updated = 0L
        val pkg = PhoneIcons.match(tvPackage) { candidate ->
            runCatching { pm.getPackageInfo(candidate, 0) }.getOrNull()?.also { updated = it.lastUpdateTime } != null
        }
        if (pkg == null) {
            synchronized(misses) { misses[tvPackage] = now }
            return null
        }
        val key = "$pkg|$updated|$targetPx"
        cache.get(key)?.let { return it }
        val drawable = try {
            pm.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val bitmap = rasterise(drawable, targetPx)
        cache.put(key, bitmap)
        return bitmap
    }

    fun has(context: Context, tvPackage: String): Boolean {
        val pm = context.packageManager
        return PhoneIcons.match(tvPackage) { runCatching { pm.getPackageInfo(it, 0) }.isSuccess } != null
    }

    private fun rasterise(drawable: Drawable, size: Int): Bitmap {
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            val inset = safeZoneInset(size)
            for (layer in listOfNotNull(drawable.background, drawable.foreground)) {
                layer.setBounds(-inset, -inset, size + inset, size + inset)
                layer.draw(canvas)
            }
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        return bitmap
    }

    /** How far each layer reaches past the tile so its 72-of-108 middle fills it: a quarter. */
    internal fun safeZoneInset(size: Int): Int = (size * (108 - 72) / 2 + 36) / 72
}

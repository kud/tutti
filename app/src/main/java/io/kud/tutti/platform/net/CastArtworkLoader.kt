package io.kud.tutti.platform.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.kud.tutti.protocol.cast.CastArtwork
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the artwork a Cast status names (`CastArtwork`): HTTPS to an allowed host only, the
 * system trust store with normal verification, no redirects (a hop could leave the allowlist), no
 * cookies, no auth header, no HTTP cache, at most [CastArtwork.MAX_BYTES]. Decoded bitmaps live in
 * memory only, keyed by URL, and go with the process; nothing reaches the disk. Nothing here logs:
 * the URL names what is playing, and a failure just means the card keeps the app's icon.
 */
internal object CastArtworkLoader {
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val bitmaps = LruCache<String, Bitmap>(6)

    /** URLs that already failed this process: not asked again until it restarts. */
    private val failed = LruCache<String, Boolean>(32)

    fun cached(url: String): Bitmap? = bitmaps.get(url)

    /** The artwork scaled down to about [widthPx] wide, or null for anything not allowed or not an image. */
    suspend fun load(url: String, widthPx: Int): Bitmap? {
        if (!CastArtwork.isAllowed(url) || failed.get(url) != null) return null
        bitmaps.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { runCatching { fetch(url, widthPx) }.getOrNull() }
        if (bitmap == null) failed.put(url, true) else bitmaps.put(url, bitmap)
        return bitmap
    }

    private fun fetch(url: String, widthPx: Int): Bitmap? {
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        val bytes = http.newCall(request).execute().use { response ->
            val body = response.body
            if (response.code != 200 || body.contentType()?.type != "image") return null
            if (body.contentLength() > CastArtwork.MAX_BYTES) return null
            val source = body.source()
            source.request(CastArtwork.MAX_BYTES + 1L)
            if (source.buffer.size > CastArtwork.MAX_BYTES) return null
            source.buffer.readByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_SIDE || bounds.outHeight > MAX_SIDE) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx.coerceAtLeast(1)) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A thumbnail is at most a few hundred pixels a side; past this it is not one. */
    private const val MAX_SIDE = 4096
}

package io.kud.tutti.quick.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import io.kud.tutti.platform.data.IconBitmaps
import io.kud.tutti.platform.data.PhoneIconBitmaps
import io.kud.tutti.ui.common.brandFace
import java.io.File

/**
 * The session's artwork for a card: the content's own picture when Cast named one and it has loaded
 * ([MediaCard.artworkUrl], fetched by the caller through `CastArtworkLoader`), else [MediaArt]'s
 * ground and glow, the glow in the app's colour from what tutti already holds: the brand's fill,
 * else the mean of the same app's icon on this phone or the TV's cached icon, else tutti's amber.
 * No icon is drawn into it (a lock screen magnifies the art too far for one to stay sharp); the
 * icon is the session's small icon. Nothing is fetched here. One picture is kept
 * and redrawn only when the picture, the app or its icon changes ([MediaArt.key],
 * [MediaArt.contentKey]), never on a volume tick. Call off the main thread.
 */
internal class MediaArtwork(private val context: Context) {
    private var drawnKey: String? = null
    private var drawn: Bitmap? = null

    // collectLatest can start the next card while a cancelled draw still runs: one draw at a time.
    @Synchronized
    fun artFor(card: MediaCard, content: Bitmap? = null): Bitmap {
        val side = MediaArt.sidePx(context.resources.displayMetrics.density)
        val url = card.artworkUrl
        if (content != null && url != null) {
            val key = MediaArt.contentKey(side, url)
            drawn?.takeIf { drawnKey == key }?.let { return it }
            runCatching { contentArt(side, content) }.getOrNull()?.let { art ->
                drawn = art
                drawnKey = key
                return art
            }
        }
        val modified = card.appIconPath?.let { File(it).lastModified() } ?: 0L
        val key = MediaArt.key(side, card.appPackage, card.appIconPath, modified)
        drawn?.takeIf { drawnKey == key }?.let { return it }
        val art = runCatching { draw(side, card) }.getOrElse { draw(side, null) }
        drawn = art
        drawnKey = key
        return art
    }

    private fun draw(side: Int, card: MediaCard?): Bitmap {
        val pkg = card?.appPackage
        val brand = brandFace(pkg)
        val fill = brand?.fill?.let { MediaArt.accentFor(it, null) }
        val accent = fill ?: run {
            val px = MediaArt.ACCENT_SAMPLE_PX
            val phone = pkg?.let { runCatching { PhoneIconBitmaps.load(context, it, px) }.getOrNull() }
            val tv = if (phone == null) card?.appIconPath?.let { runCatching { IconBitmaps.load(it, px) }.getOrNull() } else null
            val icon = phone ?: tv
            when {
                icon != null -> MediaArt.accentOf(pixelsOf(IconBitmaps.backdrop(icon)))
                brand != null -> brand.glyph
                else -> null
            }
        }
        val palette = MediaArt.palette(accent)

        val art = createBitmap(side, side)
        val canvas = Canvas(art)
        canvas.drawColor(palette.ground)
        val c = side / 2f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            val centre = (palette.glow and 0x00FFFFFF) or ((palette.glowAlpha * 255).toInt() shl 24)
            val half = (palette.glow and 0x00FFFFFF) or ((palette.glowAlpha * 0.35f * 255).toInt() shl 24)
            shader = RadialGradient(
                c, c, side * MediaArt.GLOW_RADIUS,
                intArrayOf(centre, half, palette.glow and 0x00FFFFFF),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), glow)
        return art
    }

    /**
     * What is playing, as the session's artwork: its middle 16:9 band ([MediaArt.contentCrop]) at
     * most [side] wide, handed as it is, since the player crops wide and draws its own scrim for the
     * title, like any media app's.
     */
    private fun contentArt(side: Int, content: Bitmap): Bitmap {
        val (left, top, width, height) = MediaArt.contentCrop(content.width, content.height)
        val scale = minOf(1f, side.toFloat() / width)
        val matrix = Matrix().apply { setScale(scale, scale) }
        return Bitmap.createBitmap(content, left, top, width, height, matrix, true)
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
}

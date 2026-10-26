package io.kud.tutti.quick.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import io.kud.tutti.R
import io.kud.tutti.platform.data.IconBitmaps
import io.kud.tutti.platform.data.PhoneIconBitmaps
import io.kud.tutti.ui.common.brandFace
import java.io.File

/**
 * Draws [MediaArt]'s artwork for a card, on device, from what tutti already holds: the same app's
 * icon on this phone, else the TV's cached icon, else the brand's logo, else tutti's own mark.
 * Nothing is fetched. One picture is kept and redrawn only when the app or its icon changes
 * ([MediaArt.key]), never on a volume tick. Call off the main thread.
 */
internal class MediaArtwork(private val context: Context) {
    private var drawnKey: String? = null
    private var drawn: Bitmap? = null

    // collectLatest can start the next card while a cancelled draw still runs: one draw at a time.
    @Synchronized
    fun artFor(card: MediaCard): Bitmap {
        val side = MediaArt.sidePx(context.resources.displayMetrics.density)
        val modified = card.appIconPath?.let { File(it).lastModified() } ?: 0L
        val key = MediaArt.key(side, card.appPackage, card.appIconPath, modified)
        drawn?.takeIf { drawnKey == key }?.let { return it }
        val art = runCatching { draw(side, card) }.getOrElse { draw(side, null) }
        drawn = art
        drawnKey = key
        return art
    }

    private fun draw(side: Int, card: MediaCard?): Bitmap {
        val tilePx = (side * MediaArt.SQUARE_TILE).toInt()
        val pkg = card?.appPackage
        val phone = pkg?.let { runCatching { PhoneIconBitmaps.load(context, it, tilePx) }.getOrNull() }
        val tv = if (phone == null) card?.appIconPath?.let { runCatching { IconBitmaps.load(it, tilePx) }.getOrNull() } else null
        val icon = phone ?: tv
        val brand = if (icon == null) brandFace(pkg) else null

        val accent = when {
            icon != null -> MediaArt.accentFor(brandFace(pkg)?.fill, MediaArt.accentOf(pixelsOf(IconBitmaps.backdrop(icon))))
            brand != null -> MediaArt.accentFor(brand.fill, null) ?: brand.glyph
            else -> null
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

        when {
            icon != null -> drawTile(canvas, side, icon)
            brand != null -> {
                val t = MediaArt.tile(side, 1, 1)
                val rect = centred(side, t.width, t.height)
                canvas.drawRoundRect(rect, t.corner, t.corner, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand.fill })
                ContextCompat.getDrawable(context, brand.drawable)?.mutate()?.let { logo ->
                    brand.glyph?.let { logo.setTint(it) }
                    val share = if (brand.crop) 1f else minOf(brand.scale, 1f)
                    canvas.withClip(rect) { drawFitted(this, logo, rect, share) }
                }
            }
            else -> ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)?.let { mark ->
                val box = (side * MediaArt.MARK_BOX).toInt()
                val left = (side - box) / 2
                mark.setBounds(left, left, left + box, left + box)
                mark.draw(canvas)
            }
        }
        return art
    }

    /** The icon cropped into its rounded tile, filtered so it stays smooth at its new size. */
    private fun drawTile(canvas: Canvas, side: Int, icon: Bitmap) {
        val t = MediaArt.tile(side, icon.width, icon.height)
        val rect = centred(side, t.width, t.height)
        val scale = maxOf(rect.width() / icon.width, rect.height() / icon.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(rect.centerX() - icon.width * scale / 2f, rect.centerY() - icon.height * scale / 2f)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        canvas.drawRoundRect(rect, t.corner, t.corner, paint)
    }

    /** A logo at [share] of the rect's longer side, keeping its own shape, centred. */
    private fun drawFitted(canvas: Canvas, logo: Drawable, rect: RectF, share: Float) {
        val iw = logo.intrinsicWidth.takeIf { it > 0 } ?: 1
        val ih = logo.intrinsicHeight.takeIf { it > 0 } ?: 1
        val fit = minOf(rect.width() / iw, rect.height() / ih) * share
        val w = iw * fit
        val h = ih * fit
        logo.setBounds(
            (rect.centerX() - w / 2).toInt(),
            (rect.centerY() - h / 2).toInt(),
            (rect.centerX() + w / 2).toInt(),
            (rect.centerY() + h / 2).toInt(),
        )
        logo.draw(canvas)
    }

    private fun centred(side: Int, width: Int, height: Int): RectF {
        val left = (side - width) / 2f
        val top = (side - height) / 2f
        return RectF(left, top, left + width, top + height)
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
}

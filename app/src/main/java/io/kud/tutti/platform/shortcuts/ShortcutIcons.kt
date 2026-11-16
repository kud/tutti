package io.kud.tutti.platform.shortcuts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import io.kud.tutti.R
import io.kud.tutti.platform.data.IconBitmaps
import io.kud.tutti.platform.data.PhoneIconBitmaps
import io.kud.tutti.ui.common.brandFace
import io.kud.tutti.ui.common.glyphRes
import io.kud.tutti.ui.common.systemSymbol

/**
 * The launcher shortcuts' icons, every one a full adaptive bitmap (108 units, the launcher's mask
 * shows the middle 72), so the launcher has a ground to draw and nothing of its own to fill.
 *
 * Until 2026-11-13 the shortcuts handed the launcher `ic_scene` and `ic_keyboard` as plain
 * resources: 24 dp vectors filled `#FFFFFFFF` for the app's own tinting, with no ground. A launcher
 * puts a legacy shortcut icon on its own white plate (OnePlus, Pixel), so Erwann's menu showed four
 * white squares: white on white. Never hand the launcher a bare glyph again.
 *
 * Apps wear the same face the dock and the Apps sheet give them ([io.kud.tutti.ui.common.AppIcon]),
 * best first: the app's icon on this phone, the TV's own art, the brand's logo on its colour, a
 * system app's symbol, its first letter. Scenes and Keyboard wear their glyph in tutti's ivory on
 * its ink, the launcher icon's own pair, legible on a light or a dark launcher alike.
 */
internal object ShortcutIcons {
    /** 108 dp at 4x: sharp on any launcher density without being heavy to hand over. */
    private const val SIZE = 432

    /** The middle 72 of 108 units the mask keeps: where a picture or a logo goes. */
    private val SAFE = Rect(SIZE / 6, SIZE / 6, SIZE * 5 / 6, SIZE * 5 / 6)

    /** A glyph's share of the safe zone: the launcher icon's own proportion. */
    private const val GLYPH_SHARE = 0.5f

    private const val INK = 0xFF1C1B22.toInt()
    private const val IVORY = 0xFFF2EDE4.toInt()

    @DrawableRes
    fun sceneGlyph(name: String?): Int = name?.let(::glyphRes) ?: R.drawable.ic_scene

    /** A glyph (a scene's, the keyboard's) in ivory on ink. */
    fun glyph(context: Context, @DrawableRes glyph: Int): IconCompat = adaptive { canvas ->
        canvas.drawColor(INK)
        drawTinted(context, canvas, glyph, IVORY, GLYPH_SHARE)
    }

    /** An app's face; see the class comment for the order. [tvIconPath] is the TV's art on disk, if fetched. */
    fun app(context: Context, packageName: String, label: String, tvIconPath: String?): IconCompat = adaptive { canvas ->
        val safe = SAFE.width()
        val phone = runCatching { PhoneIconBitmaps.load(context, packageName, safe) }.getOrNull()
        if (phone != null) return@adaptive drawRaster(canvas, phone)
        val tv = tvIconPath?.let { path -> runCatching { IconBitmaps.load(path, safe) }.getOrNull() }
        val brand = brandFace(packageName, label)
        when {
            tv != null && (tv.width == tv.height || brand == null) -> drawRaster(canvas, tv)
            brand != null -> {
                canvas.drawColor(brand.fill)
                if (brand.crop) {
                    ContextCompat.getDrawable(context, brand.drawable)?.mutate()?.let { it.bounds = SAFE; it.draw(canvas) }
                } else {
                    drawTinted(context, canvas, brand.drawable, brand.glyph, brand.scale)
                }
            }
            else -> {
                canvas.drawColor(INK)
                val symbol = systemSymbol(packageName)
                if (symbol != null) drawTinted(context, canvas, symbol, IVORY, GLYPH_SHARE) else drawLetter(canvas, label)
            }
        }
    }

    private inline fun adaptive(draw: (Canvas) -> Unit): IconCompat {
        val bitmap = createBitmap(SIZE, SIZE)
        draw(Canvas(bitmap))
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }

    /**
     * A picture of the visible square (the phone's icon is already its 72-unit middle; the TV's art
     * is drawn whole): fitted into the safe zone, on its own corner colour so the bleed the mask may
     * show matches it, or on ink when that corner is see-through.
     */
    private fun drawRaster(canvas: Canvas, image: Bitmap) {
        val corner = image.getPixel(0, 0)
        canvas.drawColor(if (Color.alpha(corner) < OPAQUE) INK else corner)
        val scale = minOf(SAFE.width().toFloat() / image.width, SAFE.height().toFloat() / image.height)
        val w = image.width * scale
        val h = image.height * scale
        val left = SAFE.exactCenterX() - w / 2
        val top = SAFE.exactCenterY() - h / 2
        canvas.drawBitmap(image, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** A drawable centred in the safe zone at [share] of it along its longer side, tinted when [tint] is set. */
    private fun drawTinted(context: Context, canvas: Canvas, @DrawableRes res: Int, tint: Int?, share: Float) {
        val drawable = ContextCompat.getDrawable(context, res)?.mutate() ?: return
        if (tint != null) drawable.setTint(tint)
        val box = SAFE.width() * share.coerceIn(0f, 1f)
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
        val scale = box / maxOf(iw, ih)
        val w = (iw * scale).toInt()
        val h = (ih * scale).toInt()
        val cx = SAFE.centerX()
        val cy = SAFE.centerY()
        drawable.setBounds(cx - w / 2, cy - h / 2, cx - w / 2 + w, cy - h / 2 + h)
        drawable.draw(canvas)
    }

    private fun drawLetter(canvas: Canvas, label: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IVORY
            textAlign = Paint.Align.CENTER
            textSize = SAFE.width() * GLYPH_SHARE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val letter = label.trim().take(1).uppercase().ifEmpty { "·" }
        val baseline = SAFE.exactCenterY() - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(letter, SAFE.exactCenterX(), baseline, paint)
    }

    private const val OPAQUE = 250
}

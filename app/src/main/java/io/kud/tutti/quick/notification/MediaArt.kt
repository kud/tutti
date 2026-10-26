package io.kud.tutti.quick.notification

import io.kud.tutti.ui.common.BrandColours
import kotlin.math.roundToInt

/**
 * The media session's artwork, as plain rules (Iris, 2026-10-26). Android draws
 * METADATA_KEY_ART large: as the expanded player's background and, on some lock screens, as the
 * whole wallpaper. A 192 px icon stretched to the screen read as a blurred, pixelated mark, so the
 * art is its own picture: tutti's warm near-black ground, one soft glow in the app's colour, and
 * the app's icon crisp at a modest size in the middle. No text: the lock clock and the player's
 * title sit over it. Decoration only; nothing is told by colour.
 *
 * Square, because the player crops it wide and the lock screen crops it tall; everything that
 * matters sits in the middle, where both crops keep it.
 */
object MediaArt {
    /** The launcher icon's ground (`ic_launcher_background`), the app's dark-first surface. */
    const val GROUND: Int = 0xFF1C1B22.toInt()

    /** The mark's amber pill, the glow when nothing more specific is known. */
    const val TUTTI_ACCENT: Int = 0xFFE3A35B.toInt()

    /**
     * The framework scales session bitmaps down to `config_mediaMetadataBitmapMaxSize` (320 dp),
     * so drawing larger only costs memory and a binder copy; 1024 caps it on the densest screens.
     */
    const val MAX_PX = 1024
    const val MIN_PX = 512
    private const val FRAMEWORK_DP = 320

    /** White over the brightest point of the art must still read at this (the lock clock, the title). */
    const val TEXT_CONTRAST = 7.0

    /** The glow never goes past this, however dark the accent: a hint of colour, not a wash. */
    const val MAX_GLOW_ALPHA = 0.5f

    /** The glow's reach, as a share of the side, measured from the centre. */
    const val GLOW_RADIUS = 0.62f

    /** An app's square icon, as a share of the side (about a third of a phone's width when a lock screen crops it full screen). */
    const val SQUARE_TILE = 0.16f

    /** A banner's width, as a share of the side; its height follows, at most [SQUARE_TILE]. */
    const val BANNER_TILE = 0.28f

    /** The tile's corner, as a share of its shorter side: the launcher's own rounding. */
    const val TILE_CORNER = 0.22f

    /** tutti's own mark: the adaptive foreground drawn into a box this share of the side. */
    const val MARK_BOX = 0.4f

    fun sidePx(density: Float): Int = (FRAMEWORK_DP * density).roundToInt().coerceIn(MIN_PX, MAX_PX)

    /** The ground, the glow's colour, and how strong the glow is at its centre. */
    data class Palette(val ground: Int, val glow: Int, val glowAlpha: Float)

    /**
     * The palette for an app whose colour is [accent] (null: tutti's own). The glow is as strong as
     * it can be, up to [MAX_GLOW_ALPHA], while white text over its brightest point keeps
     * [TEXT_CONTRAST]: a light accent (white, yellow) gets a faint glow, a deep one a fuller glow.
     */
    fun palette(accent: Int?): Palette {
        val glow = opaque(accent ?: TUTTI_ACCENT)
        var alpha = MAX_GLOW_ALPHA
        while (alpha > 0f && BrandColours.contrast(BrandColours.WHITE, blend(GROUND, glow, alpha)) < TEXT_CONTRAST) {
            alpha -= 0.01f
        }
        return Palette(GROUND, glow, alpha.coerceAtLeast(0f))
    }

    /** The mean of the opaque pixels of an icon (an 8 x 8 copy is plenty); null when none are. */
    fun accentOf(pixels: IntArray): Int? {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (p in pixels) {
            if ((p ushr 24) < 0x80) continue
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        if (n == 0) return null
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /**
     * The colour the glow takes: the brand's own fill when it is a colour (a white ground says
     * nothing about the app), else the icon's mean, else null for tutti's amber.
     */
    fun accentFor(brandFill: Int?, iconMean: Int?): Int? =
        brandFill?.takeIf { opaque(it) != BrandColours.WHITE } ?: iconMean

    /** The icon's tile, in pixels, centred: a square icon as a square, a banner at its own shape. */
    data class Tile(val width: Int, val height: Int, val corner: Float)

    fun tile(side: Int, iconWidth: Int, iconHeight: Int): Tile {
        val square = (side * SQUARE_TILE).roundToInt()
        if (iconWidth <= 0 || iconHeight <= 0 || BrandColours.isSquareish(iconWidth, iconHeight)) {
            return Tile(square, square, square * TILE_CORNER)
        }
        var w = side * BANNER_TILE
        var h = w * iconHeight / iconWidth
        if (h > square) {
            w *= square / h
            h = square.toFloat()
        }
        val tw = w.roundToInt()
        val th = h.roundToInt()
        return Tile(tw, th, minOf(tw, th) * TILE_CORNER)
    }

    /** Which picture the art was drawn from: the art is redrawn only when this changes, never per tick. */
    fun key(side: Int, packageName: String?, iconPath: String?, iconModified: Long): String =
        "$side|${packageName.orEmpty()}|${iconPath.orEmpty()}|$iconModified"

    internal fun opaque(argb: Int): Int = argb or (0xFF shl 24)

    /** [over] laid on [base] at [alpha], per channel, opaque. */
    internal fun blend(base: Int, over: Int, alpha: Float): Int {
        fun ch(shift: Int): Int {
            val a = (base shr shift) and 0xFF
            val b = (over shr shift) and 0xFF
            return (a + (b - a) * alpha).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

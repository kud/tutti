package io.kud.tutti.quick.notification

import io.kud.tutti.ui.common.BrandColours
import kotlin.math.roundToInt

/**
 * The media session's artwork, as plain rules (2026-10-26, icon dropped 2026-11-06). Android draws
 * METADATA_KEY_ART large: as the expanded player's background and, on some lock screens, as the
 * whole wallpaper. The framework keeps session bitmaps at 320 dp, so a lock screen that crops the
 * square to fill the screen (OxygenOS) scales it about 2.8 times: any mark drawn in it comes out
 * soft. The art is therefore only a ground and a light: tutti's warm near-black, one soft glow in
 * the app's colour, which stays smooth at any scale. The app's icon is the session's small icon
 * (METADATA_KEY_DISPLAY_ICON), drawn by the system at its own size. No text: the lock clock and
 * the player's title sit over it. Decoration only; nothing is told by colour.
 *
 * Square, because the player crops it wide and the lock screen crops it tall.
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

    /** The icon is read only for its colour: a small decode is plenty. */
    const val ACCENT_SAMPLE_PX = 64

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

    /** Which icon the glow's colour came from: the art is redrawn only when this changes, never per tick. */
    fun key(side: Int, packageName: String?, iconPath: String?, iconModified: Long): String =
        "$side|${packageName.orEmpty()}|${iconPath.orEmpty()}|$iconModified"

    /** The content artwork's key: a new picture only when the URL or the size changes. */
    fun contentKey(side: Int, url: String): String = "content|$side|$url"

    /**
     * The part of a [width] x [height] picture the session gets: the middle 16:9 band, as the
     * remote's tile crops it (YouTube's `hqdefault` is 4:3 with black bars, which this drops), or
     * the whole picture when it is already that wide or wider. Left, top, width, height.
     */
    fun contentCrop(width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0) return intArrayOf(0, 0, width.coerceAtLeast(0), height.coerceAtLeast(0))
        val band = (width * 9f / 16f).roundToInt().coerceIn(1, height)
        return intArrayOf(0, (height - band) / 2, width, band)
    }

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

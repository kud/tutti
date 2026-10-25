package io.kud.tutti.ui.common

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure colour rules behind the coloured app faces (Iris, 2026-10-25), kept free of Android so
 * they are tested on the JVM. Colours are packed ARGB ints.
 */
object BrandColours {
    const val WHITE: Int = 0xFFFFFFFF.toInt()
    const val NEAR_BLACK: Int = 0xFF1C1B1F.toInt()

    /** Below this, a glyph on its brand fill doesn't read: the tile falls through to the letter. */
    const val MIN_CONTRAST = 3.0

    /** How far apart (per channel, 0..255) a banner's corners may be and still share one flat fill. */
    const val CORNER_TOLERANCE = 12

    /** WCAG relative luminance of an sRGB colour. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * The glyph colour for a mark drawn on its brand fill: white or near-black, whichever reads
     * better; null when neither reaches [MIN_CONTRAST], so the caller shows the letter instead.
     */
    fun glyphOn(fill: Int): Int? {
        val white = contrast(fill, WHITE)
        val dark = contrast(fill, NEAR_BLACK)
        val best = if (white >= dark) WHITE else NEAR_BLACK
        return if (max(white, dark) >= MIN_CONTRAST) best else null
    }

    /** A TV icon close enough to square is cropped edge to edge; anything wider is a banner. */
    fun isSquareish(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val ratio = width.toDouble() / height
        return ratio in 0.8..1.25
    }

    /**
     * The flat colour a banner can be letterboxed with: its corners' shared colour, or null when
     * they disagree (a gradient or photo behind the wordmark would show a seam). Iris, 2026-10-25.
     */
    fun bannerFill(corners: IntArray): Int? {
        if (corners.isEmpty()) return null
        val first = corners[0]
        for (c in corners) {
            for (shift in intArrayOf(24, 16, 8, 0)) {
                if (abs(((c shr shift) and 0xFF) - ((first shr shift) and 0xFF)) > CORNER_TOLERANCE) return null
            }
        }
        // A transparent corner can't fill anything: it would show the tile, which is the inset look.
        if (((first ushr 24) and 0xFF) < 0xF0) return null
        return first
    }
}

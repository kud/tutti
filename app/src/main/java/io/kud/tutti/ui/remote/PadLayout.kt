package io.kud.tutti.ui.remote

import androidx.compose.ui.geometry.Rect
import io.kud.tutti.platform.data.Hand
import io.kud.tutti.platform.data.PadSize
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where the pad and the now-showing card stand in the room the remote leaves them (Erwann,
 * 2026-10-24: the thumb reaches about half the screen, so the pad sits low). The pad and the
 * volume rail share one height and stand on the floor, [PAD_BELOW] above the round-key row.
 * Whatever is left goes above the pad and becomes the card's room ([cardRoom]), never below it.
 * The pad never grows taller than [padRatio] × its width, so a tall screen doesn't stretch it
 * upward; on a short screen it takes what there is, less [PAD_MIN_ABOVE] and [PAD_BELOW]. The
 * mode switch lives on the pad itself, in its top corner facing the rail ([inPadSwitchCorner]), so
 * it takes no room here (Erwann, 2026-10-24).
 *
 * The card fills its room by steps ([cardFor]): full, from [CARD_FULL] up to [CARD_MAX] tall when
 * there is room for it and [CARD_MARGIN] either side; one [CARD_LINE] line below that; hidden
 * when even the line won't fit.
 *
 * All values are dp. [available] is the height between the scene pills (or the header) and the
 * round-key row; [padWidth] is the pad's own width, the rail and its gap already taken off.
 */
internal data class PadPlacement(
    /** The room above the pad, between the pills and the pad's top: the card centres in it. */
    val cardRoom: Float,
    val card: CardFit,
    val height: Float,
    val below: Float,
)

/** How the now-showing card fits its room: its size and height in dp (0 when hidden). */
internal data class CardFit(val size: CardSize, val height: Float)

internal enum class CardSize { FULL, LINE, HIDDEN }

internal fun placePad(available: Float, padWidth: Float, size: PadSize): PadPlacement {
    val room = max(0f, available - PAD_MIN_ABOVE - PAD_BELOW)
    val height = min(room, padWidth * padRatio(size))
    val below = min(PAD_BELOW, max(0f, available - height))
    val cardRoom = max(0f, available - height - below)
    return PadPlacement(cardRoom = cardRoom, card = cardFor(cardRoom), height = height, below = below)
}

/** Full when [room] holds [CARD_FULL] and its margins, growing to [CARD_MAX]; else a line; else nothing. */
internal fun cardFor(room: Float): CardFit {
    val inner = room - 2 * CARD_MARGIN
    return when {
        inner >= CARD_FULL -> CardFit(CardSize.FULL, min(inner, CARD_MAX))
        inner >= CARD_LINE -> CardFit(CardSize.LINE, CARD_LINE)
        else -> CardFit(CardSize.HIDDEN, 0f)
    }
}

/** The tallest a pad may stand, as a multiple of its width: Compact is shorter, Large taller. */
internal fun padRatio(size: PadSize): Float = when (size) {
    PadSize.COMPACT -> 0.85f
    PadSize.NORMAL -> 1f
    PadSize.LARGE -> 1.2f
}

/** Between the pad's foot and the round-key row: enough that a thumb heading for a key lands on one or the other. */
internal const val PAD_BELOW = 24f

/** The least room kept between the pills (or the header) and the pad's top. */
internal const val PAD_MIN_ABOVE = 6f

/** The card at full size, its ceiling when the room allows (Erwann's 412x915 band), one line, and the least margin either side. */
internal const val CARD_FULL = 104f
internal const val CARD_MAX = 140f
internal const val CARD_LINE = 56f
internal const val CARD_MARGIN = 12f

/**
 * Which hand holds the phone decides the pad's side (Erwann, 2026-10-24: right by default). The
 * pad goes under the thumb's own side, the rail on the other, and the mode switch sits in the
 * pad's top corner facing the rail, away from the screen's edge: top-left for the right hand,
 * top-right for the left.
 */
internal val Hand.padOnRight: Boolean get() = this == Hand.RIGHT

/** The switch sits in the pad's top-left corner when the pad stands on the right. */
internal val Hand.switchOnLeft: Boolean get() = padOnRight

/**
 * The mode switch's hit area: a [PAD_SWITCH_ZONE] square in the pad's top corner facing the rail,
 * top-right unless [onLeft], on the surface itself (Erwann, 2026-10-24). A corner of 0 never hits.
 * [x], [y], [width] and [height] share one unit (px at run time), as does [corner], the square's
 * side in that unit.
 */
internal fun inPadSwitchCorner(x: Float, y: Float, width: Float, corner: Float, onLeft: Boolean): Boolean {
    if (corner <= 0f || y < 0f || y > corner) return false
    return if (onLeft) x >= 0f && x <= corner else x >= width - corner && x <= width
}

/**
 * Half the D-pad face's side in a pad of [width] x [height] whose top [corner] square (top-left
 * when [onLeft], else top-right) holds the mode switch: half the shorter side, shrunk until the
 * disc's reach (drawn edge plus a small margin) stays out of that square. On a tall pad nothing
 * shrinks; on a square one the disc gives up a few percent, centred as before, so the switch's
 * glyph never sits on the ring. The disc is centred, so either corner gives the same radius. The
 * disc is the face's drawing only: the surface it sits on is the pad's whole box in both modes,
 * and the D-pad answers on all of it bar the corner ([dpadZone]).
 */
internal fun dpadFaceRadius(width: Float, height: Float, corner: Float, onLeft: Boolean): Float {
    val half = min(width, height) / 2f
    val cx = width / 2f
    val cy = height / 2f
    // The square's nearest point to the pad's centre.
    val nx = if (onLeft) cx.coerceIn(0f, corner) else cx.coerceIn(width - corner, width)
    val ny = cy.coerceIn(0f, corner)
    val clear = hypot(cx - nx, cy - ny) / DPAD_REACH
    return max(0f, min(half, clear))
}

/**
 * The switch's corner, in dp: the 24 dp glyph sits 16 dp in from the top and side edges (Erwann's
 * reference build), with a 48 dp hit area round it; the 4 dp sliver to the edges goes with it.
 */
internal const val PAD_SWITCH_ZONE = 52f

/**
 * Where the pad keeps Android's back gesture off, in the pad's own coordinates: a [strip]-wide
 * band along the side that faces the screen's edge (right when [edgeOnRight]), [tall] high at
 * most and centred on the pad, so a swipe that starts on the pad's outer edge steps instead of
 * going back. Android honours at most 200 dp per edge; [tall] stays under that, and the rail on the
 * other edge excludes nothing.
 */
internal fun padEdgeExclusion(width: Float, height: Float, edgeOnRight: Boolean, strip: Float, tall: Float): Rect {
    val w = min(strip, width)
    val h = min(tall, height)
    val top = (height - h) / 2f
    val left = if (edgeOnRight) width - w else 0f
    return Rect(left, top, left + w, top + h)
}

/** The back-gesture band: wide enough to cover the system's edge zone past the 16 dp margin, and under the 200 dp budget. */
internal const val PAD_EXCLUSION_STRIP = 32f
internal const val PAD_EXCLUSION_TALL = 180f

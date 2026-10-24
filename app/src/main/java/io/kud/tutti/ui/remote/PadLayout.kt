package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.PadSize
import kotlin.math.max
import kotlin.math.min

/**
 * Where the pad, its mode tab and the now-showing card stand in the room the remote leaves them
 * (Erwann, 2026-10-24: the thumb reaches about half the screen, so the pad sits low). The pad and
 * the volume rail share one height and stand on the floor, [PAD_BELOW] above the round-key row.
 * The mode switch is the pad's corner tab: [SWITCH_GROUP] above the pad's top, moving with it
 * (Iris's option A, chosen by Erwann). Whatever is left goes above the tab and becomes the card's
 * room ([cardRoom]), never below the pad. The pad never grows taller than [padRatio] × its width,
 * so a tall screen doesn't stretch it upward; on a short screen it takes what there is, less
 * [PAD_MIN_ABOVE], the tab and [PAD_BELOW], so nothing overlaps.
 *
 * The card fills its room by steps ([cardFor]): full, from [CARD_FULL] up to [CARD_MAX] tall when
 * there is room for it and [CARD_MARGIN] either side; one [CARD_LINE] line below that; hidden
 * when even the line won't fit. The tab never hides.
 *
 * All values are dp. [available] is the height between the scene pills (or the header) and the
 * round-key row; [padWidth] is the pad's own width, the rail and its gap already taken off.
 */
internal data class PadPlacement(
    /** The room above the tab, between the pills and the tab's target: the card centres in it. */
    val cardRoom: Float,
    val card: CardFit,
    val height: Float,
    val below: Float,
) {
    /** The tab's target plus its clearance, between [cardRoom] and the pad. */
    val tab: Float get() = SWITCH_GROUP
}

/** How the now-showing card fits its room: its size and height in dp (0 when hidden). */
internal data class CardFit(val size: CardSize, val height: Float)

internal enum class CardSize { FULL, LINE, HIDDEN }

internal fun placePad(available: Float, padWidth: Float, size: PadSize): PadPlacement {
    val room = max(0f, available - PAD_MIN_ABOVE - SWITCH_GROUP - PAD_BELOW)
    val height = min(room, padWidth * padRatio(size))
    val below = min(PAD_BELOW, max(0f, available - height))
    val cardRoom = max(0f, available - height - below - SWITCH_GROUP)
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

/** The least room kept between the pills (or the header) and the tab's target. */
internal const val PAD_MIN_ABOVE = 6f

/** The tab's 48 dp target and the 6 dp kept clear of the pad: the track draws 12 dp above the pad's top. */
internal const val SWITCH_TARGET = 48f
internal const val SWITCH_CLEAR = 6f
internal const val SWITCH_GROUP = SWITCH_TARGET + SWITCH_CLEAR

/** The card at full size, its ceiling when the room allows (Erwann's 412x915 band), one line, and the least margin either side. */
internal const val CARD_FULL = 104f
internal const val CARD_MAX = 140f
internal const val CARD_LINE = 56f
internal const val CARD_MARGIN = 12f

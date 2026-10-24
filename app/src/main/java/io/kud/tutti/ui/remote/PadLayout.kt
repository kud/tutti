package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.PadSize
import kotlin.math.max
import kotlin.math.min

/**
 * Where the pad stands in the room the remote leaves it (Erwann, 2026-10-24: the thumb reaches
 * about half the screen, so the pad sits low). The pad and the volume rail share one height and
 * stand on the floor, [PAD_BELOW] above the round-key row; whatever is left goes above them,
 * between the mode switch and the pad, never below. The pad never grows taller than
 * [padRatio] × its width, so a tall screen doesn't stretch it upward. On a short screen it takes
 * what there is, less [PAD_MIN_ABOVE] and [PAD_BELOW], so nothing overlaps.
 *
 * All values are dp. [available] is the height between the mode switch's target and the
 * round-key row; [padWidth] is the pad's own width, the rail and its gap already taken off.
 */
internal data class PadPlacement(val above: Float, val height: Float, val below: Float)

internal fun placePad(available: Float, padWidth: Float, size: PadSize): PadPlacement {
    val room = max(0f, available - PAD_MIN_ABOVE - PAD_BELOW)
    val height = min(room, padWidth * padRatio(size))
    val below = min(PAD_BELOW, max(0f, available - height))
    return PadPlacement(above = max(0f, available - height - below), height = height, below = below)
}

/** The tallest a pad may stand, as a multiple of its width: Compact is shorter, Large taller. */
internal fun padRatio(size: PadSize): Float = when (size) {
    PadSize.COMPACT -> 0.85f
    PadSize.NORMAL -> 1f
    PadSize.LARGE -> 1.2f
}

/** Between the pad's foot and the round-key row: enough that a thumb heading for a key lands on one or the other. */
internal const val PAD_BELOW = 24f

/** The least room kept between the mode switch and the pad's top (Iris, 2026-10-24). */
internal const val PAD_MIN_ABOVE = 16f

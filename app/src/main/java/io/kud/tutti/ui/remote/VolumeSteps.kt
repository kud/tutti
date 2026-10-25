package io.kud.tutti.ui.remote

import kotlin.math.roundToInt

/**
 * The volume step rules the rail and the pad's two-finger drag share, free of Android so they are
 * unit-tested. A step strictly inside the range ticks; landing on 0 or the ceiling is a wall,
 * which the controller already bumps (`TuttiEvent.VolumeLimit`), so the two never play together.
 */
internal object VolumeSteps {
    /**
     * The level a drag reaches: [start] plus [travelled] (in rail heights, up is positive) times
     * [stepsPerRail], clamped to 0..[ceiling].
     */
    fun level(start: Int, travelled: Float, stepsPerRail: Float, ceiling: Int): Int =
        (start + travelled * stepsPerRail).roundToInt().coerceIn(0, ceiling.coerceAtLeast(0))

    /** [delta] steps from [current], clamped like the controller clamps it. */
    fun step(current: Int, delta: Int, ceiling: Int): Int = (current + delta).coerceIn(0, ceiling.coerceAtLeast(0))

    /** A step inside the range ticks; the walls are the controller's bump. */
    fun ticks(level: Int, ceiling: Int): Boolean = level in 1 until ceiling

    /** At 0 or the ceiling: the rail stretches against it. */
    fun atWall(level: Int, ceiling: Int): Boolean = level <= 0 || level >= ceiling

    /**
     * Whether a move from [from] to [to] arrives on or passes over one of the [landmarks] (a saved
     * level, the high-volume guard's line, tonight's cap). Leaving a landmark is not crossing it,
     * so a level resting on one does not buzz again on the next step away. A fast drag that skips
     * levels still finds the landmark it jumped over.
     */
    fun crosses(from: Int, to: Int, landmarks: Collection<Int>): Boolean = when {
        to > from -> landmarks.any { it in (from + 1)..to }
        to < from -> landmarks.any { it in to until from }
        else -> false
    }

    /**
     * What one move feels like (Iris, 2026-10-25): the wall at 0 or [wall] is the controller's
     * bump, a landmark crossed inside the range is a detent, anything else the ordinary tick. An
     * unknown [from] is a plain tick: there is nothing to have crossed yet.
     */
    fun feel(from: Int?, to: Int, wall: Int, landmarks: Collection<Int>): StepFeel = when {
        !ticks(to, wall) -> StepFeel.WALL
        from != null && crosses(from, to, landmarks) -> StepFeel.DETENT
        else -> StepFeel.TICK
    }
}

/** How a volume move is felt: see [VolumeSteps.feel]. */
internal enum class StepFeel { TICK, DETENT, WALL }

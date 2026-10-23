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
}

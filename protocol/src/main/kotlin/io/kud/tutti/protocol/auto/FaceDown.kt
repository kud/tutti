package io.kud.tutti.protocol.auto

/**
 * "Phone face down on the table": read from gravity along the screen's axis (z, m/s², positive
 * when the screen faces up). Two thresholds and a dwell, so a wobble never toggles:
 * - face down once z has stayed at or under [DOWN_Z] (the screen within about 30 degrees of the
 *   table) for [DWELL_MS];
 * - face up again once z has stayed at or over [UP_Z] (tilted past upright towards the user, or
 *   lying screen up) for [DWELL_MS].
 * Between the two lines nothing changes, and a sample that crosses back resets the dwell. Samples
 * come every 200 ms or so; a gap longer than [GAP_MS] (sensors paused while the app was away)
 * restarts the dwell rather than counting the gap as time held.
 */
object FaceDown {
    const val DOWN_Z = -8.5f
    const val UP_Z = -2.0f
    const val DWELL_MS = 1_000L
    const val GAP_MS = 2_000L

    enum class Pose { UNKNOWN, UP, DOWN }

    data class State(val pose: Pose = Pose.UNKNOWN, val candidate: Pose? = null, val since: Long = 0, val last: Long = 0)

    enum class Change { WENT_DOWN, CAME_UP }

    data class Step(val state: State, val change: Change?)

    fun sample(state: State, z: Float, at: Long): Step {
        val seen = when {
            z <= DOWN_Z -> Pose.DOWN
            z >= UP_Z -> Pose.UP
            else -> null
        }
        val gap = state.last != 0L && at - state.last > GAP_MS
        // Between the lines, or the pose already held: no candidate.
        if (seen == null || seen == state.pose) return Step(state.copy(candidate = null, last = at), null)
        val since = if (state.candidate == seen && !gap) state.since else at
        if (at - since < DWELL_MS) return Step(State(state.pose, seen, since, at), null)
        val change = when {
            seen == Pose.DOWN -> Change.WENT_DOWN
            // Up from unknown is where every session starts: not a pick-up.
            state.pose == Pose.DOWN -> Change.CAME_UP
            else -> null
        }
        return Step(State(seen, null, 0, at), change)
    }
}

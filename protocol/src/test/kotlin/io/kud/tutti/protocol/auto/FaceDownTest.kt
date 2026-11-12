package io.kud.tutti.protocol.auto

import io.kud.tutti.protocol.auto.FaceDown.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaceDownTest {
    /** Feeds [z] every 200 ms from [from] for [ms], returning the changes seen and the state. */
    private fun feed(state: FaceDown.State, z: Float, from: Long, ms: Long): Pair<FaceDown.State, List<Change>> {
        var s = state
        val changes = mutableListOf<Change>()
        var t = from
        while (t <= from + ms) {
            val step = FaceDown.sample(s, z, t)
            s = step.state
            step.change?.let(changes::add)
            t += 200
        }
        return s to changes
    }

    @Test
    fun `laid face down for a second, then picked up`() {
        val (up, start) = feed(FaceDown.State(), 9.8f, 1_000, 1_200)
        assertEquals(emptyList(), start)
        val (down, wentDown) = feed(up, -9.8f, 2_400, 1_200)
        assertEquals(listOf(Change.WENT_DOWN), wentDown)
        val (_, cameUp) = feed(down, 6f, 3_800, 1_200)
        assertEquals(listOf(Change.CAME_UP), cameUp)
    }

    @Test
    fun `a wobble under the dwell never toggles`() {
        var s = feed(FaceDown.State(), -9.8f, 0, 1_200).first
        var t = 1_400L
        repeat(20) {
            val a = FaceDown.sample(s, 3f, t)
            assertNull(a.change)
            val b = FaceDown.sample(a.state, -9.6f, t + 200)
            assertNull(b.change)
            s = b.state
            t += 400
        }
        assertEquals(FaceDown.Pose.DOWN, s.pose)
    }

    @Test
    fun `tilted between the lines changes nothing`() {
        val down = feed(FaceDown.State(), -9.8f, 0, 1_200).first
        val (s, changes) = feed(down, -5f, 1_400, 5_000)
        assertEquals(emptyList(), changes)
        assertEquals(FaceDown.Pose.DOWN, s.pose)
    }

    @Test
    fun `a gap in the samples restarts the dwell`() {
        val up = feed(FaceDown.State(), 9.8f, 0, 1_200).first
        val first = FaceDown.sample(up, -9.8f, 2_000)
        // Three seconds without a sample (the sensor stopped while the app was away): no time held.
        val after = FaceDown.sample(first.state, -9.8f, 5_000)
        assertNull(after.change)
        assertEquals(Change.WENT_DOWN, FaceDown.sample(after.state, -9.8f, 6_000).change)
    }
}

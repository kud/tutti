package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeatTest {
    @Test
    fun `a press shorter than the delay repeats nothing`() {
        assertEquals(emptyList<Long>(), KeyRepeat.schedule(KeyRepeat.DELAY_MS - 1))
    }

    @Test
    fun `the first repeat lands at the delay, then one every interval`() {
        assertEquals(listOf(400L, 490L, 580L, 670L), KeyRepeat.schedule(700))
    }

    @Test
    fun `the cadence holds steady until the acceleration starts`() {
        val gaps = KeyRepeat.schedule(KeyRepeat.ACCELERATE_FROM_MS).zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it == KeyRepeat.INTERVAL_MS })
    }

    @Test
    fun `it eases faster and never passes the cap`() {
        val gaps = KeyRepeat.schedule(6_000).zipWithNext { a, b -> b - a }
        assertTrue(gaps.zipWithNext().all { (a, b) -> b <= a })
        assertTrue(gaps.all { it >= KeyRepeat.FASTEST_MS })
        assertEquals(KeyRepeat.FASTEST_MS, gaps.last())
        assertEquals(KeyRepeat.FASTEST_MS, KeyRepeat.interval(KeyRepeat.ACCELERATE_FROM_MS + KeyRepeat.RAMP_MS))
    }

    @Test
    fun `a two-second hold sends about a slider's worth of steps`() {
        // 400 ms wait, then ~11 steps by 1.5 s, a few faster ones after: right, right, right.
        val count = KeyRepeat.schedule(2_000).size
        assertTrue("got $count", count in 15..20)
    }
}

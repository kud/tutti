package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.atv.DirectionHold
import io.kud.tutti.protocol.atv.DirectionPress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectionDriveTest {
    private class Sent {
        var taps = 0
        var holds = 0
        var renews = 0
        var releases = 0
        var repeats = 0
    }

    /** A finger on an arrow for [heldMs]; [canHold] is the route's answer; [lifted] false is a cancel. */
    private fun TestScope.press(heldMs: Long, canHold: Boolean = true, lifted: Boolean = true): Sent {
        val sent = Sent()
        val press = DirectionPress(currentTime)
        val driver = launch {
            DirectionDrive.run(
                press = press,
                clock = { currentTime },
                hold = {
                    sent.holds++
                    canHold
                },
                renew = { sent.renews++ },
                repeat = { sent.repeats++ },
            )
        }
        advanceTimeBy(heldMs)
        runCurrent()
        driver.cancel()
        DirectionDrive.end(press, lifted, tap = { sent.taps++ }, release = { sent.releases++ })
        // Nothing is sent after the release, however long the clock runs on.
        val after = listOf(sent.taps, sent.holds, sent.releases, sent.repeats)
        advanceTimeBy(30_000)
        assertEquals(after, listOf(sent.taps, sent.holds, sent.releases, sent.repeats))
        return sent
    }

    @Test
    fun `a quick press is one tap`() = runTest {
        val sent = press(heldMs = 150)
        assertEquals(listOf(1, 0, 0, 0), listOf(sent.taps, sent.holds, sent.releases, sent.repeats))
    }

    @Test
    fun `a long scrub on the TV is one hold and one release, no repeats from the phone`() = runTest {
        val sent = press(heldMs = 30_000)
        assertEquals(listOf(0, 1, 1, 0), listOf(sent.taps, sent.holds, sent.releases, sent.repeats))
        // Renewed all the way, so the lease never lifts the key under a finger that stays.
        val expected = ((30_000 - DirectionHold.HOLD_AFTER_MS) / DirectionHold.RENEW_EVERY_MS).toInt()
        assertTrue("renews ${sent.renews}", sent.renews in expected - 1..expected + 1)
        assertTrue(DirectionHold.RENEW_EVERY_MS < DirectionHold.LEASE_MS)
    }

    @Test
    fun `a hold cancelled mid-scrub still comes up`() = runTest {
        val sent = press(heldMs = 5_000, lifted = false)
        assertEquals(listOf(0, 1, 1), listOf(sent.taps, sent.holds, sent.releases))
    }

    @Test
    fun `a route that can't hold repeats plain keys on the old schedule and lifts nothing`() = runTest {
        val sent = press(heldMs = 2_000, canHold = false)
        assertEquals(0, sent.releases)
        assertEquals(0, sent.taps)
        assertEquals(KeyRepeat.schedule(2_000).size, sent.repeats)
    }
}

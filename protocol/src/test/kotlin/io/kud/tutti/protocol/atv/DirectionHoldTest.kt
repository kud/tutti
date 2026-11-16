package io.kud.tutti.protocol.atv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionHoldTest {
    /** Drives one press on a fake clock: ticks every [DirectionHold.RENEW_EVERY_MS], lifts at [liftAt]. */
    private fun hold(liftAt: Long, cancelled: Boolean = false, afterLift: Long = 5_000): List<Pair<Long, DirectionEffect>> {
        val press = DirectionPress(startedAt = 0)
        val sent = mutableListOf<Pair<Long, DirectionEffect>>()
        var now = DirectionHold.HOLD_AFTER_MS
        while (now < liftAt) {
            press.tick(now).takeIf { it != DirectionEffect.NONE }?.let { sent += now to it }
            now += DirectionHold.RENEW_EVERY_MS
        }
        (if (cancelled) press.cancel() else press.release()).takeIf { it != DirectionEffect.NONE }?.let { sent += liftAt to it }
        // A late tick or a second release (a cancel racing the lift) must send nothing.
        while (now < liftAt + afterLift) {
            assertEquals(DirectionEffect.NONE, press.tick(now))
            now += DirectionHold.RENEW_EVERY_MS
        }
        assertEquals(DirectionEffect.NONE, press.release())
        assertEquals(DirectionEffect.NONE, press.cancel())
        return sent
    }

    private fun List<Pair<Long, DirectionEffect>>.toTv() = map { it.second }.filter { it != DirectionEffect.RENEW }

    @Test
    fun `a quick press is one tap on release`() {
        assertEquals(listOf(DirectionEffect.TAP), hold(liftAt = 150).toTv())
    }

    @Test
    fun `a cancelled quick press sends nothing`() {
        assertEquals(emptyList<DirectionEffect>(), hold(liftAt = 150, cancelled = true).toTv())
    }

    @Test
    fun `a thirty-second scrub is one key down and one key up, as a physical remote sends`() {
        val sent = hold(liftAt = 30_000)
        assertEquals(listOf(DirectionEffect.HOLD, DirectionEffect.RELEASE), sent.toTv())
        assertEquals(DirectionHold.HOLD_AFTER_MS, sent.first { it.second == DirectionEffect.HOLD }.first)
        assertEquals(30_000L, sent.last().first)
    }

    @Test
    fun `a cancelled hold still lifts the key`() {
        assertEquals(listOf(DirectionEffect.HOLD, DirectionEffect.RELEASE), hold(liftAt = 3_000, cancelled = true).toTv())
    }

    @Test
    fun `a route that can't hold falls back to the caller's repeats and lifts nothing`() {
        val press = DirectionPress(0)
        assertEquals(DirectionEffect.HOLD, press.tick(DirectionHold.HOLD_AFTER_MS))
        press.holdRefused()
        assertEquals(DirectionEffect.NONE, press.tick(2_000))
        assertEquals(DirectionEffect.NONE, press.release())
    }

    @Test
    fun `a renewed lease never cuts a long hold, an abandoned one runs out`() {
        val lease = HoldLease(DirectionHold.LEASE_MS, now = 0)
        var now = 0L
        while (now < 60_000) {
            assertTrue("cut at $now", lease.remaining(now) > 0)
            lease.renew(now)
            now += DirectionHold.RENEW_EVERY_MS
        }
        // The finger's word stops (a release lost on the phone): up within the lease.
        assertTrue(lease.remaining(now) > 0)
        assertEquals(0L, lease.remaining(now + DirectionHold.LEASE_MS))
    }

    @Test
    fun `an arrow comes up the moment the finger does, OK keeps its long-press minimum`() {
        assertEquals(0L, KeyHold.releaseDelay(startedAt = 400, now = 450, minHoldMs = 0))
        assertEquals(650L, KeyHold.releaseDelay(startedAt = 400, now = 450))
    }

    /**
     * The stuck case, on a fake clock: the old pad launched a SHORT key every 50 ms once the hold
     * had eased, each on its own coroutine, while a TV busy scrubbing reads one every 120 ms. The
     * unbounded pile drains long after the finger lifts; the backlog drops instead.
     */
    @Test
    fun `a flood of repeats against a slow TV keeps arriving after release unless dropped`() {
        fun lateKeys(backlog: KeyBacklog?): Int {
            val writeMs = 120L
            val liftAt = 10_000L
            val queue = ArrayDeque<Long>()
            var busyUntil = 0L
            var late = 0
            var now = 0L
            while (now < liftAt + 60_000) {
                if (now < liftAt && now % 50L == 0L) {
                    if (backlog == null || backlog.tryAcquire()) queue.addLast(now)
                }
                if (now >= busyUntil && queue.isNotEmpty()) {
                    queue.removeFirst()
                    busyUntil = now + writeMs
                    if (now > liftAt) late++
                    backlog?.done()
                }
                now += 10
            }
            return late
        }
        val unbounded = lateKeys(null)
        assertTrue("old path: $unbounded keys after release", unbounded > 50)
        assertTrue("bounded: at most what was in flight", lateKeys(KeyBacklog()) <= KeyBacklog.MAX_IN_FLIGHT)
    }

    @Test
    fun `the backlog holds at most two and frees as keys go`() {
        val backlog = KeyBacklog()
        assertTrue(backlog.tryAcquire())
        assertTrue(backlog.tryAcquire())
        assertEquals(false, backlog.tryAcquire())
        backlog.done()
        assertTrue(backlog.tryAcquire())
    }
}

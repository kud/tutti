package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.atv.V2Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JogSeekTest {
    private val ten = SeekStep(seconds = 10)

    /** Drains every key due by [until], waking when [JogSeek.wakeAt] says, as the sheet does. */
    private fun JogSeek.drain(from: Long, until: Long): List<Pair<Long, Int>> {
        val out = mutableListOf<Pair<Long, Int>>()
        var now = from
        while (now <= until) {
            next(now)?.let { out += now to it }
            now = wakeAt(now)?.takeIf { it > now } ?: (now + 1)
        }
        return out
    }

    @Test
    fun `the wheel never sends a speed key`() {
        for (pkg in listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "x.y", null)) {
            val step = SeekSteps.forPackage(pkg)
            for (key in listOf(step.key(1), step.key(-1))) {
                assertFalse(key == V2Key.MEDIA_FAST_FORWARD || key == V2Key.MEDIA_REWIND, "$pkg")
                // Media3 turns the skip keys into next / previous item.
                assertFalse(key == V2Key.MEDIA_SKIP_FORWARD || key == V2Key.MEDIA_SKIP_BACKWARD, "$pkg")
            }
            assertEquals(V2Key.DPAD_RIGHT, step.key(1))
            assertEquals(V2Key.DPAD_LEFT, step.key(-1))
        }
    }

    @Test
    fun `known apps read seconds, others count jumps, and none claims a documented step`() {
        assertEquals(10, SeekSteps.forPackage("com.google.android.youtube.tv").seconds)
        assertEquals(10, SeekSteps.forPackage("com.netflix.ninja").seconds)
        assertNull(SeekSteps.forPackage("org.example.player").seconds)
        assertNull(SeekSteps.forPackage(null).seconds)
        assertTrue(listOf("com.google.android.youtube.tv", "com.netflix.ninja", null).none { SeekSteps.forPackage(it).documented })
    }

    @Test
    fun `each notch is one key, and the readout adds them up`() {
        val seek = JogSeek(ten)
        repeat(3) { assertTrue(seek.turn(1, 0)) }
        val keys = seek.drain(0, 1_000)
        assertEquals(List(3) { V2Key.DPAD_RIGHT }, keys.map { it.second })
        assertEquals(JogReadout(jumps = 3, seconds = 30, approximate = true, revealed = false), seek.readout(1_000))
    }

    @Test
    fun `keys leave no closer than the gap, however fast the spin`() {
        val seek = JogSeek(ten, gapMs = 150)
        repeat(8) { seek.turn(-1, 0) }
        val times = seek.drain(0, 5_000).map { it.first }
        assertEquals(8, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 150 }, "$times")
    }

    @Test
    fun `a flick past the queue's limit is dropped and not counted`() {
        val seek = JogSeek(ten, maxAhead = 4)
        val kept = (1..10).count { seek.turn(1, 0) }
        assertEquals(4, kept)
        assertEquals(4, seek.readout(0)?.jumps)
        assertEquals(4, seek.drain(0, 3_000).size)
    }

    @Test
    fun `turning back takes queued jumps off first`() {
        val seek = JogSeek(ten)
        repeat(4) { seek.turn(1, 0) }
        assertEquals(V2Key.DPAD_RIGHT, seek.next(0))
        repeat(2) { seek.turn(-1, 10) }
        assertEquals(2, seek.readout(10)?.jumps)
        val rest = seek.drain(10, 2_000).map { it.second }
        assertEquals(listOf(V2Key.DPAD_RIGHT), rest)
        assertEquals(20, seek.readout(200)?.seconds)
    }

    @Test
    fun `the readout fades after the last notch and a new turn starts from zero`() {
        val seek = JogSeek(ten)
        seek.turn(-1, 0)
        seek.drain(0, 100)
        assertEquals(-10, seek.readout(100)?.seconds)
        assertEquals(JogSeek.READOUT_MS, seek.wakeAt(100))
        assertNull(seek.readout(JogSeek.READOUT_MS))
        assertNull(seek.wakeAt(JogSeek.READOUT_MS))
        seek.turn(1, 5_000)
        assertEquals(1, seek.readout(5_000)?.jumps)
    }

    @Test
    fun `an unknown step counts jumps with no seconds`() {
        val seek = JogSeek(SeekSteps.DEFAULT)
        seek.turn(1, 0)
        seek.turn(1, 0)
        assertEquals(JogReadout(2, null, approximate = true, revealed = false), seek.readout(0))
    }

    @Test
    fun `a first press that only reveals is sent but not counted`() {
        val seek = JogSeek(SeekStep(seconds = 10, revealFirst = true))
        seek.turn(1, 0)
        assertEquals(JogReadout(0, 0, approximate = true, revealed = true), seek.readout(0))
        seek.turn(1, 10)
        assertEquals(listOf(V2Key.DPAD_RIGHT, V2Key.DPAD_RIGHT), seek.drain(10, 1_000).map { it.second })
        assertEquals(1, seek.readout(1_000)?.jumps)
        // Still within the controls' life: the next turn's first notch counts.
        seek.turn(1, 3_000)
        assertEquals(JogReadout(1, 10, approximate = true, revealed = false), seek.readout(3_000))
        seek.drain(3_000, 3_500)
        // Long after the last key the controls have hidden again.
        seek.turn(-1, 3_400 + JogSeek.CONTROLS_HIDE_MS)
        assertEquals(true, seek.readout(3_400 + JogSeek.CONTROLS_HIDE_MS)?.revealed)
    }
}

package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.protocol.atv.V2Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadGesturesTest {
    @Test
    fun `a slow drag never flings`() {
        assertTrue(PadFling.schedule(400f, 44f).isEmpty())
        assertTrue(PadFling.schedule(PadFling.MIN_VELOCITY_DP, 44f).isEmpty())
    }

    @Test
    fun `just past the threshold adds a step or two, not a jump`() {
        assertTrue(PadFling.schedule(PadFling.MIN_VELOCITY_DP + 300f, 44f).size <= 2)
    }

    @Test
    fun `a fast fling carries more steps and slows down`() {
        val plan = PadFling.schedule(4_000f, 44f)
        assertTrue(plan.size > 10)
        assertTrue(plan.size <= PadFling.MAX_STEPS)
        val gaps = plan.zipWithNext { a, b -> b - a }
        assertTrue("gaps never shrink: $gaps", gaps.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(gaps.all { it >= PadFling.MIN_GAP_MS })
        assertTrue(gaps.last() > gaps.first())
    }

    @Test
    fun `a faster release never carries fewer steps`() {
        val counts = (1_000..8_000 step 250).map { PadFling.schedule(it.toFloat(), 44f).size }
        assertEquals(counts.sorted(), counts)
    }

    @Test
    fun `direction follows the dominant axis`() {
        assertEquals(V2Key.DPAD_RIGHT, PadFling.direction(2_000f, -300f))
        assertEquals(V2Key.DPAD_UP, PadFling.direction(100f, -2_000f))
        assertEquals(V2Key.DPAD_LEFT, PadFling.direction(-2_000f, 0f))
        assertEquals(V2Key.DPAD_DOWN, PadFling.direction(0f, 900f))
    }

    @Test
    fun `two fingers inside the slop are still a tap`() {
        val track = TwoFingerTrack(slop = 8f, volumeStep = 14f, skipStep = 72f)
        assertTrue(track.move(3f, 4f).isEmpty())
        assertEquals(false, track.moved)
    }

    @Test
    fun `vertical is volume, up louder, as before`() {
        val track = TwoFingerTrack(8f, 14f, 72f)
        val steps = (1..10).flatMap { track.move(0.5f, -3f) }
        assertEquals(TwoFingerAxis.VOLUME, track.axis)
        assertEquals(listOf(TwoFingerStep(TwoFingerAxis.VOLUME, 1), TwoFingerStep(TwoFingerAxis.VOLUME, 1)), steps)
    }

    @Test
    fun `horizontal is skip, and a diagonal wobble stays on its axis`() {
        val track = TwoFingerTrack(8f, 14f, 72f)
        val steps = (1..30).flatMap { track.move(-6f, if (it % 2 == 0) 4f else -4f) }
        assertEquals(TwoFingerAxis.SKIP, track.axis)
        assertTrue(steps.isNotEmpty())
        assertTrue(steps.all { it == TwoFingerStep(TwoFingerAxis.SKIP, -1) })
        assertEquals(180 / 72, steps.size)
    }

    @Test
    fun `skip keys and their fallback`() {
        assertEquals(272, skipKey(1, fallback = false))
        assertEquals(273, skipKey(-1, fallback = false))
        assertEquals(V2Key.MEDIA_FAST_FORWARD, skipKey(1, fallback = true))
        assertEquals(V2Key.MEDIA_REWIND, skipKey(-1, fallback = true))
    }

    @Test
    fun `back and home have defaults, play does not`() {
        assertEquals(RowDefault.PREVIOUS_APP, rowDefault(SecondaryButton.BACK))
        assertEquals(RowDefault.RECENT_APPS, rowDefault(SecondaryButton.HOME))
        assertNull(rowDefault(SecondaryButton.PLAY))
    }

    @Test
    fun `paste needs a clipboard, then a field`() {
        assertEquals(Paste.Empty, paste(null, "abc"))
        assertEquals(Paste.Empty, paste("   ", null))
        assertEquals(Paste.NoField, paste("hello", null))
        assertEquals(Paste.Send("hello"), paste("hello", ""))
        assertEquals(Paste.Send("search hello"), paste("hello", "search"))
        assertEquals(Paste.Send("search hello"), paste("hello", "search "))
    }
}

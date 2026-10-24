package io.kud.tutti.ui.intro

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchIntroTest {
    @Test
    fun `the spring starts at 0, overshoots a hair, and ends exactly at 1`() {
        val spring = SpringEasing(310f)
        assertEquals(0f, spring.transform(0f), 1e-6f)
        assertEquals(1f, spring.transform(1f), 0f)
        val peak = (0..100).maxOf { spring.transform(it / 100f) }
        assertTrue("snappy overshoots a little: $peak", peak > 1f && peak < 1.05f)
        assertEquals(1f, spring.transform(0.99f), 0.01f)
    }

    @Test
    fun `the cold intro stays inside its 800 ms and the warm one inside 300`() {
        val cold = OkBecomesPad(warm = false)
        val warm = OkBecomesPad(warm = true)
        assertEquals(800f, cold.durationMs, 0f)
        assertEquals(300f, warm.durationMs, 0f)
        assertEquals(1f, cold.travel.at(470f), 0f)
        assertEquals(1f, cold.ring.at(470f), 0f)
        assertEquals(1f, cold.reveal.at(800f), 0f)
        assertEquals(1f, warm.travel.at(220f), 0f)
        assertEquals(1f, warm.reveal.at(300f), 0f)
        assertEquals(470f, cold.landingMs!!, 0f)
        assertEquals(null, warm.landingMs)
        // Everything that moves has settled by the end.
        for (choreography in listOf(cold, warm)) {
            val end = choreography.durationMs
            assertEquals(0f, choreography.bloom.at(end), 0f)
            assertEquals(0f, choreography.ringFade.at(end), 0f)
            choreography.chevronScale.forEach { assertEquals(1f, it.at(end), 0f) }
            choreography.chevronAlpha.forEach { assertEquals(1f, it.at(end), 0f) }
        }
    }

    @Test
    fun `the chevrons pop clockwise 35 ms apart from the landing, and settle by 655`() {
        val cold = OkBecomesPad(warm = false)
        assertEquals(listOf(470f, 505f, 540f, 575f), cold.chevronStart)
        cold.chevronScale.forEach { scale ->
            assertEquals(1f, scale.at(655f), 0f)
            val peak = (470..655).maxOf { scale.at(it.toFloat()) }
            assertEquals(1.06f, peak, 0.005f)
        }
        assertEquals(0.4f, cold.chevronScale[3].at(575f), 0f)
        assertEquals(0f, cold.chevronAlpha[3].at(575f), 0f)
        // Warm: together, no stagger.
        assertEquals(1, OkBecomesPad(warm = true).chevronStart.distinct().size)
    }

    @Test
    fun `the bloom peaks just after the landing, at half on a warm start`() {
        val cold = OkBecomesPad(warm = false)
        val warm = OkBecomesPad(warm = true)
        assertEquals(0f, cold.bloom.at(470f), 0f)
        assertEquals(1f, cold.bloom.at(520f), 0f)
        assertEquals(0f, cold.bloom.at(740f), 0f)
        assertEquals(0.5f, (0..300).maxOf { warm.bloom.at(it.toFloat()) }, 0f)
        // Warm: no glint, no echo.
        assertEquals(null, warm.glint)
        assertEquals(null, warm.okGlint)
        assertEquals(null, warm.echo)
    }

    @Test
    fun `the shine is a soft sheen, no core, a low peak, clear at both ends`() {
        for (peak in listOf(MARK_SHEEN_PEAK, OK_SHEEN_PEAK, HALO_PEAK, BLOOM_PEAK)) {
            assertTrue("a low peak: $peak", peak <= 0.3f)
        }
        val stops = sheenStops(Color(0xFF3366CC), MARK_SHEEN_PEAK)
        assertEquals(0f, stops.first().second.alpha, 0f)
        assertEquals(0f, stops.last().second.alpha, 0f)
        assertEquals(MARK_SHEEN_PEAK, stops.maxOf { it.second.alpha }, 0.01f)
        // Only the colour it was given, never a white core.
        assertTrue(stops.all { it.second.copy(alpha = 1f) == Color(0xFF3366CC) })
    }

    @Test
    fun `reduced motion is a 200 ms fade that rests on nothing`() {
        assertSame(FadeIntro, choreographyFor(IntroKind.COLD, reduced = true, firstLaunch = true, pad = PadShown.DPAD))
        assertSame(FadeIntro, choreographyFor(IntroKind.WARM, reduced = true, firstLaunch = false, pad = PadShown.DPAD))
        assertEquals(200f, FadeIntro.durationMs, 0f)
        assertEquals(0f, FadeIntro.alpha.at(200f), 0f)
    }

    @Test
    fun `the first cold launch hands over to the welcome screen, never a warm one`() {
        assertSame(HandOffToWelcome, choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = true, pad = PadShown.NONE))
        assertTrue(choreographyFor(IntroKind.WARM, reduced = false, firstLaunch = true, pad = PadShown.DPAD) is OkBecomesPad)
    }

    @Test
    fun `each pad mode gets its own landing, and no pad gets the fade`() {
        assertSame(FadeIntro, choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = false, pad = PadShown.NONE))
        assertTrue(choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = false, pad = PadShown.DPAD) is OkBecomesPad)
        assertTrue(choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = false, pad = PadShown.TOUCHPAD) is OkBecomesTouchpad)
        assertTrue(choreographyFor(IntroKind.WARM, reduced = false, firstLaunch = false, pad = PadShown.TOUCHPAD) is OkBecomesTouchpad)
        // Reduced motion and the first launch win over the touchpad too.
        assertSame(FadeIntro, choreographyFor(IntroKind.COLD, reduced = true, firstLaunch = false, pad = PadShown.TOUCHPAD))
        assertSame(HandOffToWelcome, choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = true, pad = PadShown.TOUCHPAD))
    }

    @Test
    fun `the touchpad landing keeps the D-pad's budget and beats`() {
        for (warm in listOf(false, true)) {
            val pad = OkBecomesPad(warm)
            val touchpad = OkBecomesTouchpad(warm)
            assertEquals(pad.durationMs, touchpad.durationMs, 0f)
            assertEquals(pad.landingMs, touchpad.landingMs)
            for (ms in 0..pad.durationMs.toInt() step 10) {
                val at = ms.toFloat()
                assertEquals(pad.travel.at(at), touchpad.travel.at(at), 0f)
                assertEquals(pad.ring.at(at), touchpad.ring.at(at), 0f)
                assertEquals(pad.bloom.at(at), touchpad.bloom.at(at), 0f)
                assertEquals(pad.reveal.at(at), touchpad.reveal.at(at), 0f)
            }
        }
        val cold = OkBecomesTouchpad(warm = false)
        val warm = OkBecomesTouchpad(warm = true)
        assertEquals(800f, cold.durationMs, 0f)
        assertEquals(300f, warm.durationMs, 0f)
        assertEquals(470f, cold.landingMs!!, 0f)
        assertEquals(null, warm.landingMs)
        // The ring has become the pad by the landing; the OK sinks into the surface after it.
        assertEquals(1f, cold.ring.at(470f), 0f)
        assertEquals(1f, cold.okFade.at(470f), 0f)
        assertEquals(0f, cold.okFade.at(620f), 0f)
        assertEquals(1f, warm.okFade.at(220f), 0f)
        for (choreography in listOf(cold, warm)) {
            val end = choreography.durationMs
            assertEquals(0f, choreography.bloom.at(end), 0f)
            assertEquals(0f, choreography.ringFade.at(end), 0f)
            assertEquals(0f, choreography.okFade.at(end), 0f)
            assertEquals(1f, choreography.reveal.at(end), 0f)
        }
    }

    @Test
    fun `the opening starts on the system splash's lockup, word and all, and the halo breathes in by 300 ms`() {
        val opening = SplashOpening()
        val frame = OpeningFrame()
        // Any splash, warm ones included, already shows the word: the frame starts with it.
        assertEquals(1f, frame.word, 0f)
        assertEquals(0f, frame.halo, 0f)
        opening.pose(0f, frame)
        assertEquals(1f, frame.word, 0f)
        assertEquals(0f, frame.halo, 0f)
        opening.pose(SplashOpening.ARRIVAL_MS, frame)
        assertEquals(1f, frame.word, 0f)
        assertEquals(1f, frame.halo, 0f)
        assertEquals(300f, SplashOpening.ARRIVAL_MS, 0f)
        // The halo only changes opacity: there is nothing else to tone down under reduced motion.
        var last = 0f
        for (ms in 0..300 step 10) opening.halo.at(ms.toFloat()).also { assertTrue(it >= last); last = it }
    }

    @Test
    fun `the hold makes the lockup read for 1200 ms from the launch, overlapping the system splash`() {
        // A quick first frame: the rest of the 1.2 s, capped at 1 s.
        assertEquals(1000f, SplashOpening.lengthFor(0f), 0f)
        assertEquals(1000f, SplashOpening.lengthFor(150f), 0f)
        assertEquals(900f, SplashOpening.lengthFor(300f), 0f)
        assertEquals(700f, SplashOpening.lengthFor(500f), 0f)
        // A slow one (or a process started earlier by the tile or the widget) still gets a real hold.
        assertEquals(600f, SplashOpening.lengthFor(600f), 0f)
        assertEquals(600f, SplashOpening.lengthFor(5_000f), 0f)
        for (shown in 0..3000 step 50) {
            val length = SplashOpening.lengthFor(shown.toFloat())
            // Always longer than the halo's arrival, so the composition is whole and then held.
            assertTrue(length - SplashOpening.ARRIVAL_MS >= 300f)
            // Never a long wait: the whole cold start stays near 1.2 s plus the 800 ms landing.
            assertTrue(shown + length <= maxOf(SplashOpening.LOCKUP_MS, shown + SplashOpening.MIN_MS))
        }
    }

    @Test
    fun `only a cold start holds the composition`() {
        assertTrue(SplashOpening.runsFor(IntroKind.COLD))
        assertTrue(!SplashOpening.runsFor(IntroKind.WARM))
        assertTrue(!SplashOpening.runsFor(IntroKind.NONE))
    }

    @Test
    fun `the wordmark and halo leave as the choreography starts, before the body steps back`() {
        for (choreography in listOf(OkBecomesPad(warm = false), OkBecomesTouchpad(warm = false), HandOffToWelcome)) {
            val exit = choreography.openingExit
            assertEquals(1f, exit.at(0f), 0f)
            assertEquals(0f, exit.at(160f), 0f)
        }
        val cold = OkBecomesPad(warm = false)
        // Gone by the time the body starts to step back and long before the backdrop opens.
        assertEquals(1f, cold.body.at(160f), 0f)
        assertEquals(0f, cold.reveal.at(160f), 0f)
        // The cold budget stays 800 ms: the opening overlaps the wait, it does not stretch the landing.
        assertEquals(800f, cold.durationMs, 0f)
        // Reduced motion: they fade with the mark, on the same track.
        assertSame(FadeIntro.alpha, FadeIntro.openingExit)
        assertEquals(0f, FadeIntro.openingExit.at(200f), 0f)
    }

    @Test
    fun `the opening halo is low, falls away smoothly and ends clear`() {
        val stops = OPENING_HALO_STOPS
        assertEquals(0f, stops.first().first, 0f)
        assertEquals(1f, stops.last().first, 0f)
        assertEquals(0f, stops.last().second, 0f)
        assertTrue("a low peak", stops.maxOf { it.second } <= HALO_PEAK)
        stops.zipWithNext().forEach { (a, b) ->
            assertTrue("in order", b.first > a.first)
            assertTrue("falls away", b.second < a.second)
        }
    }

    @Test
    fun `the first activity of a process is cold, later ones warm, restored ones none`() {
        // The only test touching the process flag: the JVM runs this class once.
        assertEquals(IntroKind.COLD, LaunchIntro.kindFor(restored = false))
        assertEquals(IntroKind.WARM, LaunchIntro.kindFor(restored = false))
        assertEquals(IntroKind.NONE, LaunchIntro.kindFor(restored = true))
    }

    @Test
    fun `an icon not laid out yet falls back to the splash centre, then the screen's`() {
        val screen = Offset(540f, 1200f)
        assertEquals(Offset(500f, 1000f), splashCentre(Rect(356f, 856f, 644f, 1144f), Rect(0f, 0f, 1080f, 2400f), screen))
        assertEquals(Offset(540f, 1200f), splashCentre(Rect(0f, 0f, 0f, 0f), Rect(0f, 0f, 1080f, 2400f), screen))
        assertEquals(Offset(540f, 1100f), splashCentre(null, Rect(0f, 0f, 1080f, 2200f), screen))
        assertEquals(screen, splashCentre(null, null, screen))
    }
}

package io.kud.tutti.ui.intro

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
        assertSame(FadeIntro, choreographyFor(IntroKind.COLD, reduced = true, firstLaunch = true, padShown = true))
        assertSame(FadeIntro, choreographyFor(IntroKind.WARM, reduced = true, firstLaunch = false, padShown = true))
        assertEquals(200f, FadeIntro.durationMs, 0f)
        assertEquals(0f, FadeIntro.alpha.at(200f), 0f)
    }

    @Test
    fun `the first cold launch hands over to the welcome screen, never a warm one`() {
        assertSame(HandOffToWelcome, choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = true, padShown = false))
        assertTrue(choreographyFor(IntroKind.WARM, reduced = false, firstLaunch = true, padShown = true) is OkBecomesPad)
    }

    @Test
    fun `without a D-pad on screen the intro fades`() {
        assertSame(FadeIntro, choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = false, padShown = false))
        assertTrue(choreographyFor(IntroKind.COLD, reduced = false, firstLaunch = false, padShown = true) is OkBecomesPad)
    }

    @Test
    fun `the first activity of a process is cold, later ones warm, restored ones none`() {
        // The only test touching the process flag: the JVM runs this class once.
        assertEquals(IntroKind.COLD, LaunchIntro.kindFor(restored = false))
        assertEquals(IntroKind.WARM, LaunchIntro.kindFor(restored = false))
        assertEquals(IntroKind.NONE, LaunchIntro.kindFor(restored = true))
    }
}

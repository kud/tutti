package io.kud.tutti.ui.intro

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
    fun `the cold intro stays inside its 650 ms and the warm one inside 250`() {
        val cold = OkBecomesPad(warm = false)
        val warm = OkBecomesPad(warm = true)
        assertEquals(650f, cold.durationMs, 0f)
        assertEquals(250f, warm.durationMs, 0f)
        assertEquals(1f, cold.travel.at(460f), 0f)
        assertEquals(1f, cold.reveal.at(650f), 0f)
        assertEquals(1f, warm.reveal.at(250f), 0f)
        assertEquals(460f, cold.landingMs!!, 0f)
        assertEquals(null, warm.landingMs)
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

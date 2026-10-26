package io.kud.tutti.quick.notification

import io.kud.tutti.ui.common.BrandColours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtTest {
    private val accents = listOf(
        null, 0xFFFFFFFF, 0xFFFFC107, 0xFFE50914, 0xFF1ED760, 0xFF0064FF, 0xFF000000, 0xFF9146FF, 0xFFEBAF00,
    ).map { it?.toInt() }

    @Test fun `white text keeps its contrast over the brightest point of every glow`() {
        for (accent in accents) {
            val p = MediaArt.palette(accent)
            val brightest = MediaArt.blend(p.ground, p.glow, p.glowAlpha)
            assertTrue("$accent", BrandColours.contrast(BrandColours.WHITE, brightest) >= MediaArt.TEXT_CONTRAST)
            assertTrue(p.glowAlpha in 0f..MediaArt.MAX_GLOW_ALPHA)
        }
    }

    @Test fun `a deep accent glows fuller than a light one`() {
        val deep = MediaArt.palette(0xFF0064FF.toInt()).glowAlpha
        val light = MediaArt.palette(0xFFFFFFFF.toInt()).glowAlpha
        assertTrue(deep > light)
        assertTrue(light > 0f)
    }

    @Test fun `no accent is tutti's amber on tutti's ground`() {
        val p = MediaArt.palette(null)
        assertEquals(MediaArt.TUTTI_ACCENT, p.glow)
        assertEquals(MediaArt.GROUND, p.ground)
    }

    @Test fun `the art is drawn at the size the framework keeps, within bounds`() {
        assertEquals(840, MediaArt.sidePx(2.625f))
        assertEquals(MediaArt.MAX_PX, MediaArt.sidePx(3.5f))
        assertEquals(MediaArt.MIN_PX, MediaArt.sidePx(1f))
    }

    @Test fun `the icon mean skips transparent pixels`() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        assertEquals(0xFF7F007F.toInt(), MediaArt.accentOf(intArrayOf(red, blue, 0x00FFFFFF, 0x10FFFFFF)))
        assertNull(MediaArt.accentOf(intArrayOf(0, 0x00FF0000)))
        assertNull(MediaArt.accentOf(IntArray(0)))
    }

    @Test fun `a white brand ground gives way to the icon's own colour`() {
        val mean = 0xFF123456.toInt()
        assertEquals(mean, MediaArt.accentFor(BrandColours.WHITE, mean))
        assertEquals(0xFFE50914.toInt(), MediaArt.accentFor(0xFFE50914.toInt(), mean))
        assertNull(MediaArt.accentFor(null, null))
    }

    @Test fun `a square icon is a square tile, a banner keeps its shape within it`() {
        val square = MediaArt.tile(1000, 512, 512)
        assertEquals(160, square.width)
        assertEquals(160, square.height)
        val banner = MediaArt.tile(1000, 320, 180)
        assertEquals(280, banner.width)
        assertEquals(158, banner.height)
        // A tall-ish banner is capped at the square's height.
        val wide = MediaArt.tile(1000, 400, 300)
        assertEquals(160, wide.height)
        assertTrue(wide.width <= 280)
        // Nothing decoded: the square.
        assertEquals(160, MediaArt.tile(1000, 0, 0).width)
    }

    @Test fun `the art is redrawn only when the app or its icon changes`() {
        val a = MediaArt.key(840, "com.netflix.ninja", "/icons/a.png", 1L)
        assertEquals(a, MediaArt.key(840, "com.netflix.ninja", "/icons/a.png", 1L))
        assertNotEquals(a, MediaArt.key(840, "com.netflix.ninja", "/icons/a.png", 2L))
        assertNotEquals(a, MediaArt.key(840, "com.google.android.youtube.tv", "/icons/b.png", 1L))
        assertNotEquals(a, MediaArt.key(1024, "com.netflix.ninja", "/icons/a.png", 1L))
    }
}

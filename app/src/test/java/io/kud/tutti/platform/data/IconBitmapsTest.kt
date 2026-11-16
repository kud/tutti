package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IconBitmapsTest {
    @Test
    fun `small or exact icons are decoded whole`() {
        assertEquals(1, IconBitmaps.sampleSize(84, 84, 84))
        assertEquals(1, IconBitmaps.sampleSize(100, 100, 84))
        assertEquals(1, IconBitmaps.sampleSize(40, 40, 84))
        assertEquals(1, IconBitmaps.sampleSize(512, 512, 0))
    }

    @Test
    fun `large icons are subsampled but never below the drawn size`() {
        assertEquals(4, IconBitmaps.sampleSize(512, 512, 112))
        assertEquals(2, IconBitmaps.sampleSize(512, 512, 200))
        for (target in 1..600) {
            for (side in listOf(48, 96, 256, 320, 512, 1024)) {
                val sample = IconBitmaps.sampleSize(side, side, target)
                assert(side / sample >= minOf(side, target)) { "side $side target $target sample $sample" }
                assert(side / (sample * 2) < target || sample == 1 && side / 2 < target) { "not the largest: $side $target $sample" }
            }
        }
    }

    @Test
    fun `the narrow side decides for wide banners`() {
        assertEquals(2, IconBitmaps.sampleSize(1280, 240, 112))
    }

    @Test
    fun `a refetched file or another size is a different entry`() {
        val base = IconBitmaps.cacheKey("/icons/a.png", 100, 1, 84)
        assertNotEquals(base, IconBitmaps.cacheKey("/icons/a.png", 101, 1, 84))
        assertNotEquals(base, IconBitmaps.cacheKey("/icons/a.png", 100, 2, 84))
        assertNotEquals(base, IconBitmaps.cacheKey("/icons/a.png", 100, 1, 112))
        assertNotEquals(base, IconBitmaps.cacheKey("/icons/a.png", 100, 1, null))
    }
}

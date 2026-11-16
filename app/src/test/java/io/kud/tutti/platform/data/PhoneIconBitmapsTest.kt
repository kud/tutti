package io.kud.tutti.platform.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneIconBitmapsTest {
    @Test
    fun `adaptive layers reach a quarter past the tile so the safe zone fills it`() {
        assertEquals(0, PhoneIconBitmaps.safeZoneInset(1))
        assertEquals(21, PhoneIconBitmaps.safeZoneInset(84))
        assertEquals(66, PhoneIconBitmaps.safeZoneInset(264))
        for (size in 8..600) {
            val inset = PhoneIconBitmaps.safeZoneInset(size)
            val full = size + 2 * inset
            // The tile shows 72/108 of the layer, to within a pixel: never a tighter crop.
            assertTrue("size $size", kotlin.math.abs(full * 72.0 / 108 - size) <= 1.0)
        }
    }

    @Test
    fun `the manifest sees launchable apps only, never every package`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.QUERY_ALL_PACKAGES"))
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.contains("android.intent.action.MAIN"))
        assertTrue(queries.contains("android.intent.category.LAUNCHER"))
    }
}

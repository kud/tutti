package io.kud.tutti.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandColoursTest {
    @Test fun `a dark brand fill gets a white glyph`() {
        assertEquals(BrandColours.WHITE, BrandColours.glyphOn(0xFF000000.toInt()))
        assertEquals(BrandColours.WHITE, BrandColours.glyphOn(0xFFE50914.toInt()))
    }

    @Test fun `a light brand fill gets a near-black glyph`() {
        assertEquals(BrandColours.NEAR_BLACK, BrandColours.glyphOn(0xFF1ED760.toInt()))
        assertEquals(BrandColours.NEAR_BLACK, BrandColours.glyphOn(0xFFEBAF00.toInt()))
    }

    @Test fun `every glyph it picks reaches three to one`() {
        for (fill in listOf(0xFFE50914, 0xFFFF0000, 0xFF0064FF, 0xFFFF5E00, 0xFF9146FF, 0xFFA238FF, 0xFF00A4DC, 0xFF17B2E7, 0xFFFF8800)) {
            val glyph = BrandColours.glyphOn(fill.toInt())
            if (glyph != null) assertTrue(BrandColours.contrast(fill.toInt(), glyph) >= BrandColours.MIN_CONTRAST)
        }
    }

    @Test fun `contrast is symmetric and black on white is 21`() {
        assertEquals(21.0, BrandColours.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(BrandColours.contrast(0xFF123456.toInt(), 0xFFABCDEF.toInt()), BrandColours.contrast(0xFFABCDEF.toInt(), 0xFF123456.toInt()), 1e-9)
    }

    @Test fun `square and near-square icons crop, banners do not`() {
        assertTrue(BrandColours.isSquareish(320, 320))
        assertTrue(BrandColours.isSquareish(300, 320))
        assertFalse(BrandColours.isSquareish(320, 180))
        assertFalse(BrandColours.isSquareish(0, 10))
    }

    @Test fun `a banner whose corners agree is filled with their colour`() {
        val c = 0xFF101820.toInt()
        assertEquals(c, BrandColours.bannerFill(intArrayOf(c, 0xFF121A22.toInt(), c, c)))
    }

    @Test fun `a banner whose corners disagree is not filled`() {
        assertNull(BrandColours.bannerFill(intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF3060FF.toInt(), 0xFF000000.toInt())))
    }

    @Test fun `a tv banner with flat corners is filled, one with disagreeing corners gets its backdrop`() {
        val flat = 0xFF101820.toInt()
        assertEquals(BrandColours.TvLook.Fill(flat), BrandColours.tvLook(320, 180) { IntArray(4) { flat } })
        val gradient = intArrayOf(0xFFE6007E.toInt(), 0xFF6A1B9A.toInt(), 0xFFE6007E.toInt(), 0xFF6A1B9A.toInt())
        assertEquals(BrandColours.TvLook.Backdrop, BrandColours.tvLook(320, 180) { gradient })
        assertEquals(BrandColours.TvLook.Backdrop, BrandColours.tvLook(320, 180) { IntArray(4) { 0 } })
    }

    @Test fun `a square tv icon is cropped without reading its corners, and only nothing decoded falls through`() {
        assertEquals(BrandColours.TvLook.Crop, BrandColours.tvLook(256, 256) { error("corners read for a square") })
        assertNull(BrandColours.tvLook(0, 0) { IntArray(4) })
    }

    @Test fun `a transparent banner corner is not a fill`() {
        assertNull(BrandColours.bannerFill(IntArray(4) { 0x00000000 }))
        assertNull(BrandColours.bannerFill(IntArray(0)))
    }
}

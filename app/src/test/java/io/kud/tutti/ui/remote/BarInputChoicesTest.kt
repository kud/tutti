package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class BarInputChoicesTest {
    @Test
    fun `the documented inputs come in their order`() {
        assertEquals(listOf("tv", "hdmi1", "spotify", "bluetooth", "airplay2"), barInputChoices(null))
        assertEquals(listOf("tv", "hdmi1", "spotify", "bluetooth", "airplay2"), barInputChoices("hdmi1"))
    }

    @Test
    fun `an input the bar reports beyond them still gets a chip`() {
        assertEquals("chromecast", barInputChoices("chromecast").last())
        assertEquals(6, barInputChoices("chromecast").size)
    }

    @Test
    fun `each input has its own glyph, and an unknown one the speaker`() {
        val icons = barInputChoices(null).map { barInputIcon(it) }
        assertEquals(icons.size, icons.toSet().size)
        assertEquals(io.kud.tutti.R.drawable.brand_spotify, barInputIcon("spotify"))
        assertEquals(io.kud.tutti.R.drawable.ic_speaker, barInputIcon("chromecast"))
    }

    @Test
    fun `airplay is shortened on its segment only`() {
        assertEquals("AirPlay", barInputSegmentLabel("airplay2", "AirPlay 2"))
        assertEquals("HDMI 1", barInputSegmentLabel("hdmi1", "HDMI 1"))
    }

    @Test
    fun `five inputs fit, a sixth scrolls`() {
        assertEquals(false, barInputScrolls(barInputChoices(null).size))
        assertEquals(true, barInputScrolls(barInputChoices("chromecast").size))
    }
}

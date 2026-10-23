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
}

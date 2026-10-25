package io.kud.tutti.ui.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowOnTvTest {
    private val hdmi2 = "extInput:hdmi?port=2"

    @Test
    fun `offered for a computer whose input is not on screen`() {
        assertTrue(showOnTvOffered(computer = true, hdmiInput = hdmi2, tvInput = "extInput:hdmi?port=1", tvPowered = true, ipControl = true))
        assertTrue(showOnTvOffered(computer = true, hdmiInput = hdmi2, tvInput = null, tvPowered = null, ipControl = true))
    }

    @Test
    fun `offered on the computer's own input when the tv is off`() {
        assertTrue(showOnTvOffered(computer = true, hdmiInput = hdmi2, tvInput = hdmi2, tvPowered = false, ipControl = true))
        assertFalse(showOnTvOffered(computer = true, hdmiInput = hdmi2, tvInput = hdmi2, tvPowered = true, ipControl = true))
    }

    @Test
    fun `never for the tv, a computer without an input, or without ip control`() {
        assertFalse(showOnTvOffered(computer = false, hdmiInput = hdmi2, tvInput = null, tvPowered = true, ipControl = true))
        assertFalse(showOnTvOffered(computer = true, hdmiInput = null, tvInput = null, tvPowered = true, ipControl = true))
        assertFalse(showOnTvOffered(computer = true, hdmiInput = hdmi2, tvInput = null, tvPowered = true, ipControl = false))
    }
}

package io.kud.tutti.ui.remote

import io.kud.tutti.protocol.bravia.ExternalInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvInputsRowTest {
    private val hdmi1 = ExternalInput("extInput:hdmi?port=1", "HDMI 1", "Game console", connected = true)
    private val hdmi2 = ExternalInput("extInput:hdmi?port=2", "HDMI 2", "", connected = false)
    private val hdmi3 = ExternalInput("extInput:hdmi?port=3", "", "", connected = true)

    private fun row(ipControl: Boolean = true, inputs: List<ExternalInput> = listOf(hdmi1, hdmi2, hdmi3), current: String? = hdmi1.uri, rest: Boolean = true, powered: Boolean? = true) =
        tvInputsRow(ipControl, inputs, current, rest, powered, downReason = "down", standbyReason = "standby")

    @Test
    fun `hidden without ip control or before the tv listed its inputs`() {
        assertNull(row(ipControl = false))
        assertNull(row(inputs = emptyList()))
    }

    @Test
    fun `each input is named by its label, then its title, then its port`() {
        assertEquals(listOf("Game console", "HDMI 2", "HDMI 3"), row()!!.inputs.map { it.name })
    }

    @Test
    fun `the input on screen is marked, and only that one`() {
        assertEquals(listOf(false, true, false), row(current = hdmi2.uri)!!.inputs.map { it.current })
        assertEquals(listOf(false, false, false), row(current = null)!!.inputs.map { it.current })
    }

    @Test
    fun `the row goes down with ip control and while the tv is on standby`() {
        assertEquals("down", row(rest = false)!!.reason)
        assertEquals("standby", row(powered = false)!!.reason)
        assertNull(row(powered = null)!!.reason)
        assertNull(row()!!.reason)
    }
}

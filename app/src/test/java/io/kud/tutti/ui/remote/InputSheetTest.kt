package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.bravia.ExternalInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputSheetTest {
    private val hdmi1 = ExternalInput("extInput:hdmi?port=1", "HDMI 1", "Game console", connected = true)
    private val hdmi2 = ExternalInput("extInput:hdmi?port=2", "HDMI 2", "", connected = false)
    private val hdmi3 = ExternalInput("extInput:hdmi?port=3", "", "", connected = true)
    private val all = listOf(hdmi1, hdmi2, hdmi3)

    private fun sheet(ipControl: Boolean = true, inputs: List<ExternalInput> = all, current: String? = hdmi1.uri, rest: Boolean = true, powered: Boolean? = true) =
        tvInputSheet(ipControl, inputs, current, rest, powered, noIpReason = "no ip", downReason = "down", standbyReason = "standby", unlistedReason = "unlisted")

    @Test
    fun `the sheet lists what the apps sheet's row lists, in the same words and order`() {
        val row = tvInputsRow(true, all, hdmi2.uri, true, true, "down", "standby")!!
        val shown = sheet(current = hdmi2.uri)
        assertEquals(row.inputs, shown.inputs)
        assertEquals(listOf("Game console", "HDMI 2", "HDMI 3"), shown.inputs.map { it.name })
        assertEquals(listOf(false, true, false), shown.inputs.map { it.current })
        assertEquals(listOf(true, false, true), shown.inputs.map { it.connected })
        assertNull(shown.reason)
        assertFalse(shown.down)
    }

    @Test
    fun `without ip control it says why and guesses no inputs`() {
        val shown = sheet(ipControl = false)
        assertEquals("no ip", shown.reason)
        assertTrue(shown.inputs.isEmpty())
        assertTrue(shown.down)
    }

    @Test
    fun `ip control down or the tv on standby keeps the list, down, under the apps sheet's reasons`() {
        assertEquals("down", sheet(rest = false).reason)
        assertEquals(3, sheet(rest = false).inputs.size)
        assertEquals("standby", sheet(powered = false).reason)
        assertTrue(sheet(powered = false).down)
        // Power not known yet is no reason to refuse.
        assertNull(sheet(powered = null).reason)
    }

    @Test
    fun `with ip control set but no list yet it says so, or why the tv can't answer`() {
        assertEquals("unlisted", sheet(inputs = emptyList()).reason)
        assertEquals("down", sheet(inputs = emptyList(), rest = false).reason)
        assertEquals("standby", sheet(inputs = emptyList(), powered = false).reason)
        assertTrue(sheet(inputs = emptyList()).inputs.isEmpty())
    }

    @Test
    fun `the tv's own input menu closes the sheet, as every menu key does`() {
        assertTrue(keyClosesSheet(V2Key.TV_INPUT))
    }

    @Test
    fun `input's long press defaults to the tv's input menu`() {
        assertEquals(RowDefault.TV_INPUT_MENU, rowDefault(SecondaryButton.INPUT))
    }
}

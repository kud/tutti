package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class BarInputMenuTest {
    @Test
    fun bluetoothLeadsWithPairingAndPinIsLast() {
        assertEquals(listOf(BarInputItem.PAIR, BarInputItem.RENAME, BarInputItem.HIDE, BarInputItem.PIN), barInputMenu("bluetooth", current = "tv"))
        assertEquals(listOf(BarInputItem.RENAME, BarInputItem.HIDE, BarInputItem.PIN), barInputMenu("hdmi1", current = "tv"))
    }

    @Test
    fun theInputPlayingNowCannotBeHidden() {
        assertEquals(listOf(BarInputItem.RENAME, BarInputItem.PIN), barInputMenu("tv", current = "tv"))
    }

    @Test
    fun hiddenInputsLeaveTheRowButTheCurrentOneStays() {
        val choices = listOf("tv", "hdmi1", "bluetooth", "airplay2")
        assertEquals(listOf("tv", "airplay2"), visibleBarInputs(choices, setOf("hdmi1", "bluetooth"), current = "tv"))
        assertEquals(listOf("tv", "bluetooth", "airplay2"), visibleBarInputs(choices, setOf("hdmi1", "bluetooth"), current = "bluetooth"))
    }

    @Test
    fun hisNameWinsUnlessBlank() {
        assertEquals("Vinyle", barInputName("hdmi1", "HDMI 1", mapOf("hdmi1" to " Vinyle ")))
        assertEquals("HDMI 1", barInputName("hdmi1", "HDMI 1", mapOf("hdmi1" to "  ")))
        assertEquals("AirPlay", barInputName("airplay2", "AirPlay 2", emptyMap()))
    }
}

package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class DockLayoutTest {

    private val night = DockSlot(DockAction.ToggleBar("audio.nightmode"))
    private val voice = DockSlot(DockAction.ToggleBar("audio.voiceenhancer"))
    private val empty = DockSlot()

    @Test
    fun theDefaultDockIsOneRowLedByAppsAndKeys() {
        // Erwann, 2026-10-25: no "+ Add" slots on the remote; 2026-10-28: Apps and Keys are pins.
        val dock = DockConfig()
        assertEquals(DockAction.OpenApps, dock.slot(0).primary)
        assertEquals(DockAction.OpenKeys, dock.slot(1).primary)
        val shown = shownPins(dock)
        assertEquals(listOf(listOf(listOf(0, 1, 2, 3))), shown)
        assertEquals(4, dockSpan(shown))
        assertEquals(1, dockRows(shown))
    }

    @Test
    fun aGapKeepsEachPinItsOwnSlot() {
        val shown = shownPins(DockConfig(slots = listOf(night, empty, voice, empty)))
        assertEquals(listOf(listOf(listOf(0, 2))), shown)
    }

    @Test
    fun theSeventhSlotStartsTheSecondRow() {
        val slots = List(6) { night } + listOf(voice)
        val shown = shownPins(DockConfig(slots = slots))
        assertEquals(listOf(listOf((0..5).toList(), listOf(6))), shown)
        assertEquals(DockConfig.ROW, dockSpan(shown))
        assertEquals(2, dockRows(shown))
    }

    @Test
    fun anEmptyRowIsLeftOutAndTheDockIsOnePage() {
        // Only row two holds a pin: the dock is one row high. Anything past twelve is never drawn.
        val slots = List(6) { empty } + listOf(empty, night) + List(4) { empty } + listOf(voice)
        val shown = shownPins(DockConfig(slots = slots))
        assertEquals(listOf(listOf(listOf(7))), shown)
        assertEquals(1, dockRows(shown))
        assertEquals(1, dockSpan(shown))
        assertEquals(DockConfig.PAGE_SIZE, DockConfig.ROW * DockConfig.ROWS)
    }

    @Test
    fun aLongPressOnlySlotStillShows() {
        val holdOnly = DockSlot(secondary = DockAction.ToggleBar("audio.nightmode"))
        assertEquals(listOf(listOf(listOf(1))), shownPins(DockConfig(slots = listOf(empty, holdOnly))))
    }

    @Test
    fun anEmptyDockShowsNoPins() {
        val shown = shownPins(DockConfig(slots = List(4) { empty }))
        assertEquals(emptyList<List<List<Int>>>(), shown)
        assertEquals(0, dockSpan(shown))
        assertEquals(0, dockRows(shown))
    }
}

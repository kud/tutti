package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import org.junit.Assert.assertEquals
import org.junit.Test

class DockLayoutTest {

    private val night = DockSlot(DockAction.ToggleBar("audio.nightmode"))
    private val voice = DockSlot(DockAction.ToggleBar("audio.voiceenhancer"))

    @Test
    fun theDefaultDockShowsItsTwoPinsAndNoAddSlots() {
        // Erwann, 2026-10-25: no "+ Add" slots on the remote.
        val shown = shownPins(DockConfig())
        assertEquals(listOf(listOf(0, 1)), shown)
        assertEquals(2, dockSpan(shown))
    }

    @Test
    fun aGapKeepsEachPinItsOwnSlot() {
        val shown = shownPins(DockConfig(slots = listOf(night, DockSlot(), voice, DockSlot())))
        assertEquals(listOf(listOf(0, 2)), shown)
    }

    @Test
    fun anEmptyPageIsNotSwipedTo() {
        val slots = listOf(night, DockSlot(), DockSlot(), DockSlot()) + List(4) { DockSlot() } + listOf(DockSlot(), voice, night, DockSlot())
        val shown = shownPins(DockConfig(slots = slots))
        assertEquals(listOf(listOf(0), listOf(9, 10)), shown)
        // The fullest page sets the width, so the doors stay put across a swipe.
        assertEquals(2, dockSpan(shown))
    }

    @Test
    fun aLongPressOnlySlotStillShows() {
        val holdOnly = DockSlot(secondary = DockAction.ToggleBar("audio.nightmode"))
        assertEquals(listOf(listOf(1)), shownPins(DockConfig(slots = listOf(DockSlot(), holdOnly))))
    }

    @Test
    fun anEmptyDockShowsNoPins() {
        val shown = shownPins(DockConfig(slots = List(4) { DockSlot() }))
        assertEquals(emptyList<List<Int>>(), shown)
        assertEquals(0, dockSpan(shown))
    }
}

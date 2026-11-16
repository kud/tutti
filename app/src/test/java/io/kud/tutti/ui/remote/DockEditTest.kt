package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.StoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The layout editor's dock gestures (design.md, Edit mode): swap, remove, undo, place a copy. */
class DockEditTest {

    private val night = DockSlot(DockAction.ToggleBar("audio.nightmode"))
    private val voice = DockSlot(DockAction.ToggleBar("audio.voiceenhancer"))
    private val home = DockSlot(DockAction.Key(3))
    private val empty = DockSlot()
    private fun dock(vararg pins: DockSlot) = DockConfig(slots = pins.toList() + List(DockConfig.PAGE_SIZE - pins.size) { empty }, merged = true)

    @Test
    fun aDropOnAFilledPlaceTradesAndOnAnEmptyOneMoves() {
        val swapped = dock(night, voice, home).withSwapped(0, 2)
        assertEquals(listOf(home, voice, night), swapped.slots.take(3))
        // Across rows: place 2 to place 9, nothing between shifts.
        val moved = dock(night, voice, home).withSwapped(1, 8)
        assertEquals(listOf(night, empty, home), moved.slots.take(3))
        assertEquals(voice, moved.slot(8))
        val same = dock(night)
        assertSame(same, same.withSwapped(0, 0))
        assertSame(same, same.withSwapped(0, DockConfig.PAGE_SIZE))
    }

    @Test
    fun removingLeavesTheGapAndUndoPutsItBack() {
        val removed = dock(night, voice, home).withRemoved(1)
        assertEquals(listOf(night, empty, home), removed.slots.take(3))
        assertEquals(dock(night, voice, home), removed.withPutBack(1, voice))
    }

    @Test
    fun undoNeverOverwritesAPinThatTookThePlace() {
        val taken = dock(night, voice).withRemoved(1).withSwapped(0, 1)
        val back = taken.withPutBack(1, voice)
        assertEquals(night, back.slot(1))
        assertEquals(voice, back.slot(0))
        // A full dock: the pin waits in the spare tray instead.
        val full = DockConfig(slots = List(DockConfig.PAGE_SIZE) { night }, merged = true)
        assertEquals(listOf(voice), full.withPutBack(3, voice).spare)
    }

    @Test
    fun placingACopyDisplacesToTheSpareTrayAndCountsPlaces() {
        val placed = dock(night, voice).withPlaced(1, night)
        assertEquals(listOf(night, night), placed.slots.take(2))
        assertEquals(listOf(voice), placed.spare)
        assertEquals(listOf(0, 1), placed.placesOf(night.primary!!))
        assertSame(placed, placed.withPlaced(2, empty))
    }

    @Test
    fun letGoMovesRemovesOrStays() {
        assertEquals(DockDrop.Move(0, 7), dockDrop(from = 0, over = 7, onDock = true))
        assertEquals(DockDrop.Remove(4), dockDrop(from = 4, over = null, onDock = false))
        assertEquals(DockDrop.Stay, dockDrop(from = 4, over = 4, onDock = true))
        // A gap between places, still over the dock: springs back rather than removing by accident.
        assertEquals(DockDrop.Stay, dockDrop(from = 4, over = null, onDock = true))
    }

    @Test
    fun noStorageChange_aStoredDockReadsTheSameAfterAnEdit() {
        val stored = StoreJson.encodeToString(DockConfig.serializer(), dock(night, voice, home))
        val read = StoreJson.decodeFromString(DockConfig.serializer(), stored)
        val edited = read.withSwapped(0, 2).withSwapped(0, 2)
        assertEquals(stored, StoreJson.encodeToString(DockConfig.serializer(), edited))
    }
}

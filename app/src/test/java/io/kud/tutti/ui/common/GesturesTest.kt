package io.kud.tutti.ui.common

import io.kud.tutti.platform.data.HintMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    @Test
    fun idsAreUnique() {
        val ids = Gestures.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyEntryHasItsOwnWords() {
        // Two gestures sharing a title would read as one row in the sheet and one TalkBack action.
        val titles = Gestures.all.map { it.title }
        assertEquals(titles.size, titles.toSet().size)
        Gestures.all.forEach { assertTrue(it.id, it.title != it.description) }
    }

    @Test
    fun groupedKeepsEveryEntryOnceInSurfaceOrder() {
        val grouped = Gestures.grouped()
        assertEquals(Gestures.all.size, grouped.sumOf { it.second.size })
        val order = grouped.map { it.first.ordinal }
        assertEquals(order.sorted(), order)
    }

    @Test
    fun aHintShowsOnceEver() {
        val after = GestureHints.shown(HintMemory(), "effect_long", today = 10)
        assertFalse(GestureHints.mayShow(after, "effect_long", today = 40))
    }

    @Test
    fun neverTwoHintsTheSameDay() {
        val after = GestureHints.shown(HintMemory(), "effect_long", today = 10)
        assertFalse(GestureHints.mayShow(after, "bar_input_long", today = 10))
        assertTrue(GestureHints.mayShow(after, "bar_input_long", today = 11))
    }

    @Test
    fun anUnregisteredGestureHasNoHint() {
        assertFalse(GestureHints.mayShow(HintMemory(), "double_tap_pad", today = 1))
        assertTrue(GestureHints.mayShow(HintMemory(), "effect_long", today = 1))
    }
}

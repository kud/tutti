package io.kud.tutti.ui.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChipRowsTest {
    @Test
    fun `no chips lay out no rows`() {
        assertTrue(chipRows(emptyList(), width = 300, gap = 8).isEmpty())
    }

    @Test
    fun `chips that fit share one row and the room left`() {
        val rows = chipRows(listOf(100, 80), width = 300, gap = 8)
        assertEquals(listOf(listOf(ChipSlot(0, 156), ChipSlot(1, 136))), rows)
    }

    @Test
    fun `chips that do not fit go two by two, a lone one full width`() {
        val rows = chipRows(listOf(120, 120, 120), width = 200, gap = 8)
        assertEquals(listOf(listOf(ChipSlot(0, 96), ChipSlot(1, 96)), listOf(ChipSlot(2, 200))), rows)
    }
}

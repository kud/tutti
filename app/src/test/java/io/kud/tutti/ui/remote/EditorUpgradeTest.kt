package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.Hand
import io.kud.tutti.platform.data.PadSize
import io.kud.tutti.platform.data.StoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hand row and the pad sizes left the editor (Erwann, 2026-10-30): the pad's side is now a drag,
 * and the pad has one size. Layouts stored before must draw as they did, or as Normal for the size.
 */
class EditorUpgradeTest {
    private fun stored(layout: String) = StoreJson.decodeFromString(AppPrefs.serializer(), """{"layout":$layout}""").layout

    @Test
    fun `a stored left hand still stands the pad on the left`() {
        val layout = stored("""{"scenes":true,"padSize":"NORMAL","hand":"LEFT"}""")
        assertEquals(Hand.LEFT, layout.hand)
        assertFalse(layout.hand.padOnRight)
    }

    @Test
    fun `no stored hand is right-handed`() {
        assertTrue(stored("""{"scenes":true}""").hand.padOnRight)
    }

    @Test
    fun `every stored pad size loads and draws normal`() {
        for (size in PadSize.entries) {
            val layout = stored("""{"padSize":"${size.name}","hand":"LEFT","scenes":false}""")
            assertEquals(PadSize.NORMAL, layout.padOnScreen)
            // The rest of the layout is untouched.
            assertEquals(Hand.LEFT, layout.hand)
            assertFalse(layout.scenes)
        }
    }

    @Test
    fun `dragging the pad across the rail swaps sides, short or backwards springs back`() {
        // Right hand: the pad is on the right, the rail on its left, so towards the rail is negative.
        assertEquals(Hand.LEFT, padSideAfterDrag(Hand.RIGHT, dragX = -60f, threshold = 48f))
        assertEquals(Hand.RIGHT, padSideAfterDrag(Hand.RIGHT, dragX = -30f, threshold = 48f))
        assertEquals(Hand.RIGHT, padSideAfterDrag(Hand.RIGHT, dragX = 80f, threshold = 48f))
        assertEquals(Hand.RIGHT, padSideAfterDrag(Hand.LEFT, dragX = 48f, threshold = 48f))
        assertEquals(Hand.LEFT, padSideAfterDrag(Hand.LEFT, dragX = -80f, threshold = 48f))
    }
}

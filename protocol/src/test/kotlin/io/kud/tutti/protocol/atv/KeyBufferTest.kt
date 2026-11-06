package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.KeyBuffer.Companion.HOLD_WINDOW_MS
import io.kud.tutti.protocol.atv.KeyBuffer.PendingPress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyBufferTest {
    private fun key(code: Int) = PendingPress.Key(code)

    private fun KeyBuffer.tap(code: Int, now: Long) = press(key(code), now).buffer

    @Test
    fun `ten ok taps become one ok`() {
        var buffer = KeyBuffer()
        repeat(10) { buffer = buffer.tap(V2Key.DPAD_CENTER, now = it * 50L) }
        assertEquals(listOf(key(V2Key.DPAD_CENTER)), buffer.flush(now = 600).second)
    }

    @Test
    fun `play pause collapses with itself and with play and pause`() {
        val buffer = KeyBuffer()
            .tap(V2Key.MEDIA_PLAY_PAUSE, 0)
            .tap(V2Key.MEDIA_PLAY_PAUSE, 100)
            .tap(V2Key.MEDIA_PAUSE, 200)
        assertEquals(listOf(key(V2Key.MEDIA_PAUSE)), buffer.flush(300).second)
    }

    @Test
    fun `distinct one-shots replay in first press order`() {
        val buffer = KeyBuffer()
            .tap(V2Key.BACK, 0)
            .tap(V2Key.DPAD_CENTER, 100)
            .tap(V2Key.BACK, 200)
            .tap(V2Key.HOME, 300)
        assertEquals(listOf(key(V2Key.BACK), key(V2Key.DPAD_CENTER), key(V2Key.HOME)), buffer.flush(400).second)
    }

    @Test
    fun `app links share one slot, newest wins`() {
        val buffer = KeyBuffer()
            .press(PendingPress.AppLink("a"), 0).buffer
            .tap(V2Key.HOME, 50)
            .press(PendingPress.AppLink("b"), 100).buffer
        assertEquals(listOf(PendingPress.AppLink("b"), key(V2Key.HOME)), buffer.flush(200).second)
    }

    @Test
    fun `arrows, volume, mute, power and inputs are never held`() {
        val never = listOf(
            V2Key.DPAD_UP, V2Key.DPAD_DOWN, V2Key.DPAD_LEFT, V2Key.DPAD_RIGHT,
            V2Key.VOLUME_UP, V2Key.VOLUME_DOWN, V2Key.VOLUME_MUTE,
            V2Key.POWER, V2Key.TV_INPUT, V2Key.TV_INPUT_HDMI_1, V2Key.TV_INPUT_HDMI_4,
        )
        for (code in never) {
            val step = KeyBuffer().press(key(code), 0)
            assertFalse(step.held, "key $code")
            assertTrue(step.buffer.isEmpty)
        }
    }

    @Test
    fun `a press older than the window is dropped silently on return`() {
        val buffer = KeyBuffer().tap(V2Key.BACK, 0).tap(V2Key.HOME, 1_500)
        assertEquals(listOf(key(V2Key.HOME)), buffer.flush(HOLD_WINDOW_MS).second)
        assertEquals(emptyList(), buffer.flush(1_500 + HOLD_WINDOW_MS).second)
    }

    @Test
    fun `pressing again keeps the press alive`() {
        val buffer = KeyBuffer().tap(V2Key.BACK, 0).tap(V2Key.BACK, 1_900)
        assertEquals(listOf(key(V2Key.BACK)), buffer.flush(3_000).second)
    }

    @Test
    fun `flush empties the buffer`() {
        val (after, sent) = KeyBuffer().tap(V2Key.HOME, 0).flush(10)
        assertEquals(1, sent.size)
        assertTrue(after.isEmpty)
        assertNull(after.emptyInMs(10))
    }

    @Test
    fun `empties when the newest press runs out`() {
        val buffer = KeyBuffer().tap(V2Key.BACK, 0).tap(V2Key.HOME, 500)
        assertEquals(HOLD_WINDOW_MS + 500 - 700, buffer.emptyInMs(700))
        assertTrue(buffer.expire(500 + HOLD_WINDOW_MS).isEmpty)
    }
}

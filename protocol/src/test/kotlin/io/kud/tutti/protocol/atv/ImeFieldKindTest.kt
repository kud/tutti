package io.kud.tutti.protocol.atv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImeFieldKindTest {
    @Test
    fun `int5 read as an input type names the field's kind`() {
        assertEquals("text/plain", ImeFieldKind.of(1))
        assertEquals("text/password", ImeFieldKind.of(129))
        assertEquals("text/email", ImeFieldKind.of(33))
        assertEquals("number/password", ImeFieldKind.of(18))
        assertEquals("phone", ImeFieldKind.of(3))
        assertEquals("none", ImeFieldKind.of(0))
    }

    @Test
    fun `flags beyond the variation do not change the kind`() {
        // TYPE_TEXT_FLAG_NO_SUGGESTIONS (0x80000) on a plain text field.
        assertEquals("text/plain", ImeFieldKind.of(0x80001))
    }

    @Test
    fun `anything else is reported as unknown, never guessed`() {
        assertEquals("unknown class 7", ImeFieldKind.of(7))
        assertEquals("text/variation 0xf0", ImeFieldKind.of(0xF1))
        assertEquals("unknown class 0", ImeFieldKind.of(0x80))
    }

    @Test
    fun `password kinds are told apart from a visible password`() {
        assertTrue(ImeFieldKind.isPassword(129))
        assertTrue(ImeFieldKind.isPassword(0xE1))
        assertTrue(ImeFieldKind.isPassword(18))
        assertFalse(ImeFieldKind.isPassword(0x91))
        assertFalse(ImeFieldKind.isPassword(1))
    }
}

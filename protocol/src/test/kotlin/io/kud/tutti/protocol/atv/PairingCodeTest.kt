package io.kud.tutti.protocol.atv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingCodeTest {
    @Test
    fun `keeps hex only, uppercased, six at most`() {
        assertEquals("AB12CD", PairingCode.normalise("ab 12-cd"))
        assertEquals("1A2B3C", PairingCode.normalise("1a2b3c4d5e"))
        assertEquals("", PairingCode.normalise("xyz!"))
        assertEquals("0F", PairingCode.normalise("g0zf"))
    }

    @Test
    fun `a pasted code with spaces fills every cell`() {
        assertEquals("C0FFEE", PairingCode.normalise(" C0 FF EE\n"))
    }

    @Test
    fun `complete means six hex characters the hash accepts`() {
        assertTrue(PairingCode.isComplete("C0FFEE"))
        assertFalse(PairingCode.isComplete("C0FFE"))
        assertFalse(PairingCode.isComplete("C0FFEG"))
        assertEquals(PairingHash.isWellFormed("C0FFEE"), PairingCode.isComplete(PairingCode.normalise("c0ffee")))
    }
}

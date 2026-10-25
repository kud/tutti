package io.kud.tutti.ui.onboarding

import io.kud.tutti.controller.PairingUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PskRuleTest {
    @Test
    fun `a key is 8 to 32 printable ASCII characters`() {
        assertTrue(pskLooksValid("12345678"))
        assertTrue(pskLooksValid("a".repeat(32)))
        assertTrue(pskLooksValid("p@ss w0rd!~"))
        assertFalse(pskLooksValid(""))
        assertFalse(pskLooksValid("        ".take(7)))
        assertFalse(pskLooksValid("1234567"))
        assertFalse(pskLooksValid("a".repeat(33)))
        assertFalse(pskLooksValid("clé-secrète"))
        assertFalse(pskLooksValid("tab\tinside"))
    }

    @Test
    fun `code with nothing pairing goes back to the TV step`() {
        assertTrue(codeStepOrphaned(IntroStep.CODE, PairingUi.Idle, pairPressed = false))
        assertFalse(codeStepOrphaned(IntroStep.CODE, PairingUi.Idle, pairPressed = true))
        assertFalse(codeStepOrphaned(IntroStep.CODE, PairingUi.AwaitingCode(1), pairPressed = false))
        assertFalse(codeStepOrphaned(IntroStep.TV, PairingUi.Idle, pairPressed = false))
    }
}

package io.kud.tutti.ui.onboarding

import io.kud.tutti.controller.PairingUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PskRuleTest {
    @Test
    fun `code with nothing pairing goes back to the TV step`() {
        assertTrue(codeStepOrphaned(IntroStep.CODE, PairingUi.Idle, pairPressed = false))
        assertFalse(codeStepOrphaned(IntroStep.CODE, PairingUi.Idle, pairPressed = true))
        assertFalse(codeStepOrphaned(IntroStep.CODE, PairingUi.AwaitingCode(1), pairPressed = false))
        assertFalse(codeStepOrphaned(IntroStep.TV, PairingUi.Idle, pairPressed = false))
    }
}

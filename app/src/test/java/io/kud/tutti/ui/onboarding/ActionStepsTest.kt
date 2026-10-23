package io.kud.tutti.ui.onboarding

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PairingError
import io.kud.tutti.controller.PairingUi
import io.kud.tutti.ui.motion.ActionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActionStepsTest {
    @Test
    fun `pairing names its real step`() {
        assertEquals(PairStep.IDLE, pairStep(PairingUi.Idle, codeSent = false, pressed = false))
        assertEquals(PairStep.CONNECTING, pairStep(PairingUi.Connecting, codeSent = false, pressed = true))
        assertEquals(PairStep.CODE_ON_TV, pairStep(PairingUi.AwaitingCode(1), codeSent = false, pressed = true))
        assertEquals(PairStep.CHECKING, pairStep(PairingUi.Connecting, codeSent = true, pressed = true))
        assertEquals(PairStep.DONE, pairStep(PairingUi.Done, codeSent = true, pressed = true))
        assertEquals(PairStep.FAILED, pairStep(PairingUi.Failed(PairingError.REFUSED), codeSent = true, pressed = true))
    }

    @Test
    fun `pending covers connecting, the code and the check`() {
        val pending = PairStep.entries.filter { it.phase == ActionPhase.PENDING }
        assertEquals(listOf(PairStep.CONNECTING, PairStep.CODE_ON_TV, PairStep.CHECKING), pending)
    }

    @Test
    fun `a done left from an earlier visit lets the button pair again`() {
        assertEquals(PairStep.IDLE, pairStep(PairingUi.Done, codeSent = false, pressed = false))
    }

    @Test
    fun `adding the bar waits for that bar's link to settle`() {
        assertNull(barOutcome("192.168.1.20", configured = null, link = Link.ABSENT))
        assertNull(barOutcome("192.168.1.20", configured = "192.168.1.21", link = Link.ONLINE))
        assertNull(barOutcome("192.168.1.20", configured = "192.168.1.20", link = Link.CONNECTING))
        assertEquals(BarOutcome.ONLINE, barOutcome("192.168.1.20", configured = "192.168.1.20", link = Link.ONLINE))
        assertEquals(BarOutcome.UNREACHABLE, barOutcome("192.168.1.20", configured = "192.168.1.20", link = Link.UNREACHABLE))
    }
}

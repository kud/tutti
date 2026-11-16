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

    @Test
    fun `the bar checklist ticks a prefix and stops at a failure`() {
        val w = CheckState.WAITING
        assertEquals(listOf(w, w, w), barChecks(added = false, link = Link.ABSENT, read = false, outcome = null))
        assertEquals(listOf(CheckState.ACTIVE, w, w), barChecks(true, Link.CONNECTING, read = false, outcome = null))
        assertEquals(listOf(CheckState.FAILED, w, w), barChecks(true, Link.UNREACHABLE, read = false, outcome = BarOutcome.UNREACHABLE))
        assertEquals(listOf(CheckState.DONE, CheckState.ACTIVE, w), barChecks(true, Link.ONLINE, read = false, outcome = BarOutcome.ONLINE))
        assertEquals(List(3) { CheckState.DONE }, barChecks(true, Link.ONLINE, read = true, outcome = BarOutcome.ONLINE))
    }

    @Test
    fun `answers landing together still tick one at a time`() {
        val done = List(3) { CheckState.DONE }
        assertEquals(listOf(CheckState.ACTIVE, CheckState.WAITING, CheckState.WAITING), stageChecks(done, 0))
        assertEquals(listOf(CheckState.DONE, CheckState.DONE, CheckState.ACTIVE), stageChecks(done, 2))
        assertEquals(done, stageChecks(done, 3))
        val failed = listOf(CheckState.FAILED, CheckState.WAITING, CheckState.WAITING)
        assertEquals(failed, stageChecks(failed, 0))
    }

    @Test
    fun `only the sixth character sends the code`() {
        assertEquals(false, completesCode("4F2A", "4F2A9"))
        assertEquals(true, completesCode("4F2A9", "4F2A9C"))
        assertEquals(false, completesCode("4F2A9C", "4F2A9C"))
    }

    @Test
    fun `a TV's dot keeps its place on the radar`() {
        val angle = radarAngle("192.168.1.42")
        assertEquals(angle, radarAngle("192.168.1.42"), 0f)
        assertEquals(true, angle in 0f..360f)
        assertEquals(true, radarAngle("") in 0f..360f)
    }
}

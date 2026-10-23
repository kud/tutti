package io.kud.tutti.ui.onboarding

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PairingUi
import io.kud.tutti.ui.motion.ActionPhase

/** What the Pair button is doing, read from the controller's pairing state alone. */
enum class PairStep(val phase: ActionPhase) {
    IDLE(ActionPhase.IDLE),
    CONNECTING(ActionPhase.PENDING),
    CODE_ON_TV(ActionPhase.PENDING),
    CHECKING(ActionPhase.PENDING),
    DONE(ActionPhase.SUCCESS),
    FAILED(ActionPhase.FAILURE),
}

/**
 * [codeSent]: a code went to the TV since the last press, so Connecting now means checking it.
 * [pressed]: Pair was pressed on this screen; a Done left over from an earlier visit reads as
 * idle, so the button can pair again rather than sit as a tick.
 */
fun pairStep(pairing: PairingUi, codeSent: Boolean, pressed: Boolean): PairStep = when (pairing) {
    PairingUi.Idle -> PairStep.IDLE
    PairingUi.Connecting -> if (codeSent) PairStep.CHECKING else PairStep.CONNECTING
    is PairingUi.AwaitingCode -> PairStep.CODE_ON_TV
    PairingUi.Done -> if (pressed) PairStep.DONE else PairStep.IDLE
    is PairingUi.Failed -> PairStep.FAILED
}

/** How adding a bar at [added] ended, once the configured bar is that one and its link settles. */
enum class BarOutcome { ONLINE, UNREACHABLE }

fun barOutcome(added: String, configured: String?, link: Link): BarOutcome? = when {
    configured != added -> null
    link == Link.ONLINE -> BarOutcome.ONLINE
    link == Link.UNREACHABLE -> BarOutcome.UNREACHABLE
    else -> null
}

/** Rule 7 of the motion spec: a wait has an end, then a message, never an endless ring. */
const val BAR_ADD_TIMEOUT_MS = 20_000L

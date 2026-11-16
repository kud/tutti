package io.kud.tutti.ui.onboarding

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PairingUi
import io.kud.tutti.protocol.atv.PairingCode
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

/** The intro's screens. [segment] is the step bar's index, -1 off the bar (Welcome). */
enum class IntroStep(val segment: Int) {
    WELCOME(-1),
    TV(0),
    CODE(1),
    BAR(2),
    READY(3),
}

const val INTRO_SEGMENTS = 4

/** After this long with no TV found, "Enter IP address" comes forward. */
const val SEARCH_NUDGE_MS = 6_000L

/** A bar online this long without reporting its state still counts as read: it answers lazily. */
const val BAR_READ_GRACE_MS = 4_000L

/** Where a found TV's dot sits on the radar, in degrees: stable per address, never random. */
fun radarAngle(host: String): Float = (((host.hashCode() % 360) + 360) % 360).toFloat()

/** The sixth character sends the code: true only on the change that completes it. */
fun completesCode(before: String, after: String): Boolean = PairingCode.isComplete(after) && !PairingCode.isComplete(before)

/** One line of the bar's checklist: waiting is dashed, active turns, done is ticked, failed keeps its cross. */
enum class CheckState { WAITING, ACTIVE, DONE, FAILED }

/**
 * The bar's checklist, from what tutti actually sees: (0) TCP 33336 answered, which needs both the
 * bar on the network and External control on, so one line says both (one signal cannot tell them
 * apart); (1) it reported its state ([read]); (2) ready. Done lines are always a prefix; after a
 * failed line the rest wait.
 */
fun barChecks(added: Boolean, link: Link, read: Boolean, outcome: BarOutcome?): List<CheckState> {
    if (!added) return List(3) { CheckState.WAITING }
    val reach = when {
        link == Link.ONLINE -> CheckState.DONE
        outcome == BarOutcome.UNREACHABLE -> CheckState.FAILED
        else -> CheckState.ACTIVE
    }
    if (reach != CheckState.DONE) return listOf(reach, CheckState.WAITING, CheckState.WAITING)
    return if (read) List(3) { CheckState.DONE } else listOf(CheckState.DONE, CheckState.ACTIVE, CheckState.WAITING)
}

/**
 * The checklist as shown: lines tick one at a time even when the answers land together. Only the
 * first [revealed] done lines show done; the next one is still turning, the rest wait.
 */
fun stageChecks(target: List<CheckState>, revealed: Int): List<CheckState> = target.mapIndexed { i, state ->
    when {
        i < revealed || state != CheckState.DONE -> state
        i == revealed -> CheckState.ACTIVE
        else -> CheckState.WAITING
    }
}

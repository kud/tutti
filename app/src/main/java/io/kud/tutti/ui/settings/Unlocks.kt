package io.kud.tutti.ui.settings

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.protocol.grpc.CloudPlaneState

/**
 * What tutti can do, and what unlocks more (Erwann, 2026-10-24: "assister l'utilisateur pour lui
 * dire ce qu'il doit faire s'il veut plus d'options"). Each step is a setup the user does once, on
 * a device or in tutti, and what it opens up. Pure rules over [TuttiUiState], so the checklist,
 * the Settings summary and the contextual nudges agree and are tested (`UnlocksTest`).
 */
enum class Unlock(val device: UnlockDevice, val optional: Boolean = false) {
    /** The TV remote (Android TV Remote v2, 6466/6467): the base, everything else builds on it. */
    PAIR_TV(UnlockDevice.TV),

    /** IP control + PSK: TV settings, picture and sound readouts, inputs, waking from off (the MAC). */
    IP_CONTROL(UnlockDevice.TV),

    /** Remote start: the TV keeps its network up in standby, so Power can wake it. */
    REMOTE_START(UnlockDevice.TV),

    /** The bar's External control in BRAVIA Connect (TCP 33336): volume rail, Sound sheet, the bar's power. */
    SOUNDBAR(UnlockDevice.BAR),

    /** Developer mode: diagnostics, technical details, Copy details under errors. For the curious. */
    DEVELOPER(UnlockDevice.TUTTI, optional = true),

    /** Sony sign-in for the cloud plane. Not built yet: shown as coming, with nothing to do. */
    SONY(UnlockDevice.SONY, optional = true),
}

enum class UnlockDevice { TV, BAR, TUTTI, SONY }

/**
 * A step's state. Each one is drawn with its own glyph and word, never by colour alone
 * (Erwann is colourblind): done ✓, not yet ○, needs a look ⚠, checking ↻, waiting on an
 * earlier step 🔒, can't tell ?, coming ⏲.
 */
enum class UnlockStatus {
    DONE,
    TODO,

    /** Set up in tutti, but the device refuses or doesn't answer: the steps are worth a second look. */
    ATTENTION,
    CHECKING,

    /** Needs an earlier step first (a TV before its IP control). */
    BLOCKED,

    /** tutti can't read it from here; the steps say how to check on the TV. */
    UNKNOWN,
    COMING,
    ;

    /** Settled and working: nothing to do. */
    val settled: Boolean get() = this == DONE
}

data class UnlockItem(val step: Unlock, val status: UnlockStatus)

/** The checklist, in the order the steps are best done: the base first. */
fun unlocks(state: TuttiUiState): List<UnlockItem> = Unlock.entries.map { UnlockItem(it, statusOf(it, state)) }

fun statusOf(step: Unlock, state: TuttiUiState): UnlockStatus {
    val tv = state.devices.tv
    return when (step) {
        Unlock.PAIR_TV -> when {
            tv == null -> UnlockStatus.TODO
            state.links.tv == Link.NEEDS_PAIRING -> UnlockStatus.ATTENTION
            else -> UnlockStatus.DONE
        }
        Unlock.IP_CONTROL -> when {
            tv == null -> UnlockStatus.BLOCKED
            // Without a key the REST loop never starts (rest stays ABSENT): plainly not done yet.
            !state.pskSet -> UnlockStatus.TODO
            else -> when (state.links.rest) {
                Link.ONLINE -> UnlockStatus.DONE
                Link.UNAUTHORIZED, Link.UNREACHABLE, Link.NEEDS_PAIRING -> UnlockStatus.ATTENTION
                Link.CONNECTING, Link.ABSENT -> UnlockStatus.CHECKING
            }
        }
        Unlock.REMOTE_START -> when {
            tv == null -> UnlockStatus.BLOCKED
            // getWolMode is Sony's name for Remote start; it reads only over IP control.
            state.tv.settings.wol == true -> UnlockStatus.DONE
            state.tv.settings.wol == false -> UnlockStatus.TODO
            else -> UnlockStatus.UNKNOWN
        }
        Unlock.SOUNDBAR -> when {
            state.devices.bar == null -> UnlockStatus.TODO
            else -> when (state.links.bar) {
                Link.ONLINE -> UnlockStatus.DONE
                Link.UNREACHABLE, Link.UNAUTHORIZED, Link.NEEDS_PAIRING -> UnlockStatus.ATTENTION
                Link.CONNECTING, Link.ABSENT -> UnlockStatus.CHECKING
            }
        }
        Unlock.DEVELOPER -> if (state.prefs.developerMode) UnlockStatus.DONE else UnlockStatus.TODO
        Unlock.SONY -> if (state.cloud is CloudPlaneState.Connected) UnlockStatus.DONE else UnlockStatus.COMING
    }
}

/** Whether a switched-off TV can be woken: the MAC is learnt once IP control has answered. */
fun canWakeFromOff(state: TuttiUiState): Boolean = state.devices.tv?.mac != null

/** The Settings summary: how many of the everyday steps are done, and which to do next. */
data class UnlockSummary(val done: Int, val total: Int, val next: Unlock?)

fun unlockSummary(items: List<UnlockItem>): UnlockSummary {
    val core = items.filterNot { it.step.optional }
    val next = core.firstOrNull { it.status == UnlockStatus.ATTENTION }?.step
        ?: core.firstOrNull { it.status == UnlockStatus.TODO }?.step
    return UnlockSummary(done = core.count { it.status.settled }, total = core.size, next = next)
}

/** The one step the checklist opens on: the one asked for, else the next to do, else none. */
fun openStep(items: List<UnlockItem>, focus: Unlock?): Unlock? = focus ?: unlockSummary(items).next

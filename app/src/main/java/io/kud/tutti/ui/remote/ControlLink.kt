package io.kud.tutti.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.atv.V2Key

/** Which device a control talks to. [ANY] is a control that wakes or mixes devices: never down. */
enum class Owner { TV, BAR, ANY }

/**
 * A control as its device's link lets it be. [CONNECTING] and [UNREACHABLE] wear the same down
 * dress (Erwann, 2026-10-25: "non connecté ou en cours de connexion ça devrait être pareil");
 * they differ only in what TalkBack says.
 */
enum class ControlLink {
    NORMAL,
    CONNECTING,
    UNREACHABLE,
    ;

    val down: Boolean get() = this != NORMAL
}

/**
 * One rule for every control on the remote (Iris, 2026-10-25). Per device, never "anything down
 * disables everything": a TV control reads the TV's shown link ([io.kud.tutti.controller.TvLinkRule],
 * so a blip stays normal), a bar control the bar's. A device not added is [ControlLink.NORMAL]:
 * there is nothing to wait for, and the no-device layouts say so themselves.
 */
fun controlLink(
    owner: Owner,
    tvAdded: Boolean,
    tvShown: Link,
    barAdded: Boolean,
    barConnected: Boolean,
    barLink: Link,
): ControlLink = when (owner) {
    Owner.ANY -> ControlLink.NORMAL
    Owner.TV -> when {
        !tvAdded || tvShown == Link.ONLINE -> ControlLink.NORMAL
        // ABSENT with a TV added: the loop has not started yet, which is a connect on its way.
        tvShown == Link.CONNECTING || tvShown == Link.ABSENT -> ControlLink.CONNECTING
        else -> ControlLink.UNREACHABLE
    }
    Owner.BAR -> when {
        !barAdded || barConnected -> ControlLink.NORMAL
        barLink == Link.CONNECTING || barLink == Link.ABSENT -> ControlLink.CONNECTING
        else -> ControlLink.UNREACHABLE
    }
}

fun TuttiUiState.controlLink(owner: Owner): ControlLink = controlLink(
    owner = owner,
    tvAdded = devices.tv != null,
    tvShown = links.tvShown.link,
    barAdded = devices.bar != null,
    barConnected = bar.connected,
    barLink = links.bar,
)

/**
 * Who a Dock pin talks to. Power keys wake the TV, exactly when it is out of reach, and scenes and
 * macros mix devices and wake the TV themselves: those are [Owner.ANY] and never go down.
 */
fun dockOwner(action: DockAction?): Owner = when (action) {
    null, is DockAction.RunScene, is DockAction.Macro -> Owner.ANY
    is DockAction.ToggleBar, is DockAction.SetBar -> Owner.BAR
    is DockAction.Key -> if (action.keyCode in WAKE_KEYS) Owner.ANY else Owner.TV
    is DockAction.LaunchApp, is DockAction.Input, is DockAction.Ircc,
    is DockAction.PictureMode, is DockAction.TvSoundOutput, is DockAction.PictureOff,
    -> Owner.TV
}

private val WAKE_KEYS = setOf(V2Key.POWER)

/** What TalkBack says of a down control: why it doesn't answer. Null when it is normal. */
@Composable
fun controlLinkReason(link: ControlLink, owner: Owner, state: TuttiUiState): String? = when (link) {
    ControlLink.NORMAL -> null
    ControlLink.CONNECTING -> stringResource(if (owner == Owner.BAR) R.string.control_connecting_bar else R.string.control_connecting_tv)
    ControlLink.UNREACHABLE -> stringResource(
        R.string.system_unreachable,
        (if (owner == Owner.BAR) state.devices.bar?.name else state.devices.tv?.name).orEmpty(),
    )
}

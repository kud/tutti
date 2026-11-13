package io.kud.tutti.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.MacroStep
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneKey

/**
 * Which device a control talks to. [TV] is the TV's remote link (keys, voice, the keyboard);
 * [TV_EITHER] is the TV over either plane, its remote link or IP control, for what IP control can
 * also carry (inputs, app launches, picture and sound settings): down only when neither answers.
 * [ANY] is a control that wakes or needs no device: never down.
 */
enum class Owner { TV, TV_EITHER, BAR, ANY }

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
 * One rule for every control on the remote (2026-10-25). Per device, never "anything down
 * disables everything": a TV control reads the TV's shown link ([io.kud.tutti.controller.TvLinkRule],
 * the link as it is, so a blip is a short fade down and back), a bar control the bar's. A device not added is [ControlLink.NORMAL]:
 * there is nothing to wait for, and the no-device layouts say so themselves.
 */
fun controlLink(
    owner: Owner,
    tvAdded: Boolean,
    tvShown: Link,
    barAdded: Boolean,
    barConnected: Boolean,
    barLink: Link,
    /** IP control set up and answering: [Owner.TV_EITHER] stays normal through a remote-link drop. */
    ipControlOnline: Boolean = false,
): ControlLink = when (owner) {
    Owner.ANY -> ControlLink.NORMAL
    Owner.TV_EITHER -> if (ipControlOnline) ControlLink.NORMAL else controlLink(Owner.TV, tvAdded, tvShown, barAdded, barConnected, barLink)
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
    ipControlOnline = ipControlSet && links.rest == Link.ONLINE,
)

/**
 * Who a Dock pin talks to. A Power key wakes the TV, exactly when it is out of reach (the controller
 * turns it into a wake then, `TuttiController.perform`), so it is [Owner.ANY] and never goes down.
 * Apps and Keys open sheets whose every row is the TV's (its apps and inputs, its digits and menu
 * keys): with the TV down nothing in them can act, so they go down with it (Erwann on dev.15,
 * 2026-11-13: « Pourquoi certains boutons ne sont pas désactivés vu la situation ? »). Scenes and
 * macros need every device they touch: [dockOwners].
 */
fun dockOwner(action: DockAction?): Owner = when (action) {
    // The remote's own keys follow their link themselves, exactly as the row did (LiveRowButton).
    null, is DockAction.RunScene, is DockAction.Macro, is DockAction.Remote -> Owner.ANY
    // Apps lists the TV's apps and inputs, both launchable over IP control too; Keys sends remote keys.
    DockAction.OpenApps -> Owner.TV_EITHER
    DockAction.OpenKeys -> Owner.TV
    is DockAction.ToggleBar, is DockAction.SetBar -> Owner.BAR
    is DockAction.Key -> if (action.keyCode in WAKE_KEYS) Owner.ANY else Owner.TV
    is DockAction.LaunchApp, is DockAction.Input, is DockAction.Ircc,
    is DockAction.PictureMode, is DockAction.TvSoundOutput, is DockAction.PictureOff,
    -> Owner.TV
}

/**
 * Every device a pin needs answering to do what it says. A scene or a macro is all of its steps:
 * one whose TV half can't run would apply half a scene and report a failure, so it waits with the
 * TV like the keys do. A scene does not wake a TV that is out of reach (its wake step runs only on a
 * TV known asleep, and sends no Wake-on-LAN), so it gets no exemption; a macro step that is Power
 * stays [Owner.ANY] as on its own pin. [scene] finds a pinned scene; one deleted needs nothing.
 */
fun dockOwners(action: DockAction?, scene: (String) -> Scene?): Set<Owner> = when (action) {
    is DockAction.RunScene -> scene(action.sceneId)?.let(::sceneOwners).orEmpty()
    is DockAction.Macro -> action.steps.flatMapTo(mutableSetOf()) { step ->
        // A macro inside a macro never runs (perform skips it), so it needs nothing.
        if (step is MacroStep.Run && step.action !is DockAction.Macro) dockOwners(step.action, scene) else emptySet()
    }
    else -> setOf(dockOwner(action))
} - Owner.ANY

/** Who a scene's setting is applied by. Exhaustive, so a new key has to say. */
fun sceneKeyOwner(key: SceneKey): Owner = when (key) {
    // Inputs and settings go over IP control, apps over either plane: down only when neither answers.
    SceneKey.TV_INPUT, SceneKey.TV_APP, SceneKey.PICTURE_MODE, SceneKey.TV_AUDIO_OUTPUT, SceneKey.PICTURE_OFF -> Owner.TV_EITHER
    SceneKey.BAR_INPUT, SceneKey.SOUND_FIELD, SceneKey.VOICE, SceneKey.NIGHT, SceneKey.SPATIAL_360,
    SceneKey.VOICE_ZOOM, SceneKey.BASS, SceneKey.VOLUME,
    -> Owner.BAR
    // The cap is tutti's own ceiling, kept whatever answers; the target is where tutti sends keys.
    SceneKey.VOLUME_CAP, SceneKey.TARGET -> Owner.ANY
}

/** The devices [scene] needs: its settings' owners, and the TV for a final app. */
fun sceneOwners(scene: Scene): Set<Owner> = buildSet {
    scene.settings.keys.mapTo(this, ::sceneKeyOwner)
    if (scene.launchApp != null) add(Owner.TV_EITHER)
} - Owner.ANY

/**
 * The first of [owners] whose link is down, TV before bar, with that link; null when every one
 * answers (or there are none). A control that needs several devices is as down as the worst, and
 * says why in that device's words.
 */
fun downOwner(owners: Set<Owner>, link: (Owner) -> ControlLink): Pair<Owner, ControlLink>? =
    listOf(Owner.TV, Owner.TV_EITHER, Owner.BAR).filter { it in owners }.firstNotNullOfOrNull { owner ->
        link(owner).takeIf { it.down }?.let { owner to it }
    }

fun TuttiUiState.downOwner(owners: Set<Owner>): Pair<Owner, ControlLink>? = downOwner(owners) { controlLink(it) }

/** A pin as its devices let it be: the down owner to blame, or null when it can act now. */
fun TuttiUiState.pinDown(action: DockAction?): Pair<Owner, ControlLink>? =
    downOwner(dockOwners(action) { id -> scenes.firstOrNull { it.scene.id == id }?.scene })

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

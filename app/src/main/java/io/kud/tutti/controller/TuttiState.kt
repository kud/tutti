package io.kud.tutti.controller

import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.protocol.atv.RemoteState
import io.kud.tutti.protocol.bravia.Capabilities
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.bravia.SystemInfo
import io.kud.tutti.protocol.bravia.TvApp
import io.kud.tutti.protocol.bravia.TvSetting
import io.kud.tutti.protocol.grpc.CloudPlaneState
import io.kud.tutti.protocol.net.WakeAttempt
import io.kud.tutti.protocol.scenes.AppProfile
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneKey
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState

/**
 * Sony's "Normal" IP-control authentication, as stored on this phone (the cookie itself never
 * leaves the secure store): no cookie, a cookie in use, or one the TV refused even after renewal,
 * so a new code must be shown on the TV (« Contrôle IP : code à renouveler »).
 */
enum class IpCodeStatus { NONE, SET, RENEW }

/** Where the « Code affiché sur la TV » flow stands after a step ([TuttiController.requestTvCode]). */
sealed interface TvCodeStep {
    /** The TV shows its 4-digit code now. */
    data object Shown : TvCodeStep

    /** The cookie is stored and IP control restarts with it. */
    data object Registered : TvCodeStep

    /** The TV refused the code; it can be typed again. */
    data object WrongCode : TvCodeStep

    data class Failed(val why: TvCodeFailure) : TvCodeStep
}

enum class TvCodeFailure {
    NO_TV,

    /** Nothing answered: the TV is off, asleep without Remote start, or on another network. */
    UNREACHABLE,

    /** The TV answered but won't register: IP control off, or authentication not "Normal". */
    NOT_ENABLED,

    /** Sony's 40005: the screen is off, so there is nowhere to show the code. */
    SCREEN_OFF,

    /** Another certificate than the pinned one: the TV was reset, or someone is in between. */
    CERTIFICATE,
    OTHER,
}

/** One channel's health, as the system chip shows it. */
enum class Link {
    /** Not configured. */
    ABSENT,
    CONNECTING,
    ONLINE,
    UNREACHABLE,

    /** The TV forgot us or changed certificate: "Associer à nouveau". */
    NEEDS_PAIRING,

    /** REST answered 401/403: the PSK or the code's cookie is wrong, expired or missing. */
    UNAUTHORIZED,
}

/**
 * The redacted detail behind each channel's last failure ([io.kud.tutti.protocol.diagnostic.FailureReport]),
 * for "Copy details" beside a link that is down. Null once the channel connects again.
 */
data class LinkNotes(val tv: String? = null, val rest: String? = null, val bar: String? = null)

/**
 * tv = remote v2 (6466), rest = BRAVIA REST + PSK, bar = TCP 33336, each as it is. [tvShown] is
 * the TV's link after [TvLinkRule]'s calm rule: what the remote screen shows.
 */
data class Links(
    val tv: Link = Link.ABSENT,
    val rest: Link = Link.ABSENT,
    val bar: Link = Link.ABSENT,
    val tvShown: TvShown = TvShown(Link.ABSENT),
    /** Which generation of sessions these links describe ([TuttiController.retainSession]). */
    val epoch: Int = 0,
) {
    /** A new generation: nothing it has not heard itself counts as up, or as given up on. */
    fun restarted(epoch: Int): Links = copy(tv = tv.restarted(), rest = rest.restarted(), bar = bar.restarted(), epoch = epoch)
}

/** What a link reads once its session has stopped: no longer up, and no longer trying. */
fun Link.stopped(): Link = if (this == Link.ONLINE || this == Link.CONNECTING) Link.UNREACHABLE else this

/** What a link reads as a new session starts: a live or failed link is asked again; a verdict (absent, pairing, key) stands until the loop says otherwise. */
fun Link.restarted(): Link = if (this == Link.ONLINE || this == Link.UNREACHABLE) Link.CONNECTING else this

data class TvSettingsState(
    val loading: Boolean = false,
    val picture: List<TvSetting> = emptyList(),
    val sound: List<TvSetting> = emptyList(),
    val speaker: List<TvSetting> = emptyList(),
    val powerSaving: String? = null,
    /** getPowerSavingMode or setPowerSavingMode listed. */
    val powerSavingSupported: Boolean = false,
    /** Wake-on-LAN (getWolMode); null when the TV does not list it. */
    val wol: Boolean? = null,
    /** Scene select for the current input (videoScreen.getSceneSetting); null when not offered. */
    val scene: TvSetting? = null,
    /**
     * Sony documents setSoundSettings (outputTerminal) but no getter: when the TV lists the setter
     * and not getSoundSettings, the output shows as a write-only choice of the documented values.
     */
    val outputWriteOnly: Boolean = false,
    /** IRCC code for the TV's own sleep timer, when getRemoteControllerInfo lists one. */
    val sleepCode: String? = null,
    /** Every IRCC key getRemoteControllerInfo listed, for the action picker (`Ircc.shortcuts`). */
    val ircc: List<io.kud.tutti.protocol.bravia.IrccCode> = emptyList(),
    /** True once a load finished: an empty list then means the TV listed nothing, not "not asked yet". */
    val loaded: Boolean = false,
    val system: SystemInfo? = null,
)

data class TvState(
    val power: PowerStatus = PowerStatus.UNKNOWN,
    /** Current source URI from getPlayingContentInfo (e.g. `extInput:hdmi?port=2`). */
    val input: String? = null,
    /** The last getPlayingContentInfo answer; null when the TV gave none (an app, standby, no PSK). */
    val playing: io.kud.tutti.protocol.bravia.PlayingContent? = null,
    val inputs: List<ExternalInput> = emptyList(),
    val apps: List<TvApp> = emptyList(),
    val capabilities: Capabilities = Capabilities.EMPTY,
    val pictureMode: String? = null,
    val settings: TvSettingsState = TvSettingsState(),
)

enum class SceneStatus { IDLE, ACTIVE, MODIFIED }

data class SceneUi(
    val scene: Scene,
    val status: SceneStatus = SceneStatus.IDLE,
    /** 0..1 while applying (the pill fills left to right), null otherwise. */
    val progress: Float? = null,
    val modifiedKeys: Set<SceneKey> = emptySet(),
    /** The last run's failed steps, kept until the scene is applied again; null when it all applied. */
    val failure: SceneFailure? = null,
)

/** [failed] of [total] steps did not apply. */
data class SceneFailure(val failed: Int, val total: Int)

/** A scene's run as the controller tracks it: [fraction] while applying, [failure] once it ended short. */
data class SceneRun(val fraction: Float?, val failure: SceneFailure? = null)

sealed interface PairingUi {
    data object Idle : PairingUi

    data object Connecting : PairingUi

    /** The TV shows a 6-character code. attempt > 1 means the previous code was wrong. */
    data class AwaitingCode(val attempt: Int) : PairingUi

    data object Done : PairingUi

    /** [detail] is the redacted technical detail (steps, cause chain, context), for "Copy details". */
    data class Failed(val reason: PairingError, val detail: String? = null) : PairingUi
}

enum class PairingError { UNREACHABLE, BAD_CODE, REFUSED }

sealed interface TuttiEvent {
    /**
     * A scene finished applying: double pulse. [detail] says which steps failed, when any did.
     * [undoable]: what it changed can still be put back ([TuttiController.undoLastScene]).
     */
    data class SceneFinished(val sceneId: String, val failedSteps: Int, val detail: String? = null, val undoable: Boolean = false) : TuttiEvent

    /** The last scene was undone: [failedSteps] of its restoring steps did not apply. */
    data class SceneUndone(val sceneName: String?, val failedSteps: Int, val detail: String? = null) : TuttiEvent

    /** Volume hit 0, 100 or the active scene's cap: soft bump. */
    data object VolumeLimit : TuttiEvent

    /** [detail] is the redacted technical detail, for "Copy details". */
    data class ActionFailed(val detail: String) : TuttiEvent

    /** The phone's mic would not open for TV voice. */
    data class MicUnavailable(val detail: String) : TuttiEvent

    /**
     * Power was pressed while a device was out of reach, and it never came back.
     * [cause] picks the user message: a TV whose MAC was never learnt needs the PSK, not only Remote start.
     */
    data class WakeFailed(val cause: WakeAttempt.Cause, val detail: String) : TuttiEvent

    /** A TV key met a link that wasn't up: held for a moment, or not sent ([TvLinkRule]). */
    data class KeyNotSent(val outcome: KeyOutcome) : TuttiEvent

    /** An app launch tapped in a blip of the TV's link: held, and sent the moment it is back ([io.kud.tutti.protocol.apps.LaunchQueue]). */
    data class AppLaunchHeld(val appName: String) : TuttiEvent

    /**
     * An app launch that didn't bring the app up: [expired] when it was held and the link never
     * came back in time, else every route was tried. [detail] is the redacted trace, for developers.
     */
    data class AppNotOpened(val appName: String, val expired: Boolean, val detail: String?) : TuttiEvent

    /** "Open the TV's own apps screen" finished: what the remote link saw ([AppsScreenOpener]). */
    data class AppsScreen(val verdict: io.kud.tutti.protocol.apps.AppsScreenVerdict) : TuttiEvent
}

data class TuttiUiState(
    /** False until the stores have loaded: do not route to onboarding before that. */
    val loaded: Boolean = false,
    val devices: Devices = Devices(),
    val prefs: AppPrefs = AppPrefs(),
    val dock: DockConfig = DockConfig(),
    val links: Links = Links(),
    val linkNotes: LinkNotes = LinkNotes(),
    val tv: TvState = TvState(),
    val remote: RemoteState = RemoteState(),
    val bar: TheatreState = TheatreState(),
    /** Voice Zoom 3 is shown only if the bar answered the probe. */
    val voiceZoomAvailable: Boolean = false,
    val scenes: List<SceneUi> = emptyList(),
    val profiles: List<AppProfile> = emptyList(),
    /** Soundbar volume 0..100, optimistic. */
    val volume: Int? = null,
    /**
     * The effective ceiling: the lower of the active scene's cap (Nuit: 20) and the cap for tonight
     * ([tonightCap]); null when nothing caps the volume.
     */
    val volumeCap: Int? = null,
    /** The active scene's own cap, for the volume sheet's words. */
    val sceneVolumeCap: Int? = null,
    /** The cap for tonight, while it lasts. */
    val tonightCap: io.kud.tutti.platform.data.TonightCap? = null,
    /** Where the high-volume guard stops a change right now, or null ([VolumeRules.guardLine]). */
    val volumeGuardLine: Int? = null,
    /** The guard's question, while it waits for an answer. */
    val volumeAsk: VolumeAsk? = null,
    /** A quick surface was stopped at the guard: they say going above needs the app. */
    val volumeGuardNotice: Boolean = false,
    /** The last scene can still be undone ([io.kud.tutti.protocol.scenes.SceneUndo]); null once stale. */
    val sceneUndo: io.kud.tutti.protocol.scenes.SceneUndo? = null,
    val powered: Boolean? = null,
    /** Power was pressed with a device out of reach: waking it and waiting for it to answer. */
    val waking: Boolean = false,
    val pairing: PairingUi = PairingUi.Idle,
    val cloud: CloudPlaneState = CloudPlaneState.Disabled,
    /** Every app tutti knows (catalog plus every app the TV ever listed), by name. */
    val apps: List<io.kud.tutti.protocol.apps.KnownApp> = emptyList(),
    /** Cached official icons: package name to a file in the cache dir. */
    val appIcons: Map<String, String> = emptyMap(),
    /** A TV PSK is stored on this phone (the key itself never leaves the secure store). */
    val pskSet: Boolean = false,
    /** The IP-control cookie from the code shown on the TV: none, working, or refused and to renew. */
    val ipCode: IpCodeStatus = IpCodeStatus.NONE,
    /** Readouts fetched when Technical details opens. */
    val technical: TechnicalReadouts = TechnicalReadouts(),
    /** App launches in flight and the app shown optimistically meanwhile ([io.kud.tutti.protocol.apps.LaunchQueue]). */
    val launches: io.kud.tutti.protocol.apps.LaunchBook = io.kud.tutti.protocol.apps.LaunchBook(),
) {
    /** [apps] minus the ones hidden in Settings: what the Apps sheet and pickers offer. */
    val visibleApps: List<io.kud.tutti.protocol.apps.KnownApp>
        get() = apps.filterNot { it.packageName in prefs.hiddenApps }

    /** IP control has a credential that should work: the PSK, or a cookie the TV has not refused. */
    val ipControlSet: Boolean get() = pskSet || ipCode == IpCodeStatus.SET

    /** IP control answers: the TV's own app list, its icons and `setActiveApp` are live. */
    val ipControl: Boolean get() = links.rest == Link.ONLINE

    /**
     * Muted as the bar says now: false once it drops, never the remembered value (the reducer keeps
     * `confirmed` across a drop). The tile, widget, notification and overlay all read this one.
     */
    val muted: Boolean get() = bar.liveOn(TheatreFeature.MUTE) == true

    val activeScene: SceneUi? get() = scenes.firstOrNull { it.status != SceneStatus.IDLE }

    val keyboardField get() = remote.ime
}

/** Where an on-demand read stands. */
enum class Fetch { IDLE, LOADING, DONE, FAILED, UNSUPPORTED }

/**
 * Technical details readouts that are not part of any live session: fetched each time that
 * screen opens, never polled. The bar's TCP readouts live in [TuttiUiState.bar] like every
 * other bar feature.
 */
data class TechnicalReadouts(
    /** HTTP 54545; the last answer is kept while a new read runs. */
    val barHttp: io.kud.tutti.protocol.theatre.BarHttpReadouts? = null,
    val barHttpFetch: Fetch = Fetch.IDLE,
    /** REST `getSystemSupportedFunction`, only reachable with IP control and the PSK. */
    val tvFunctions: List<io.kud.tutti.protocol.bravia.SupportedFunction>? = null,
    val tvFunctionsFetch: Fetch = Fetch.IDLE,
)

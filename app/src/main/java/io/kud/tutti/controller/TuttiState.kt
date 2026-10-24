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

/** One channel's health, as the system chip shows it. */
enum class Link {
    /** Not configured. */
    ABSENT,
    CONNECTING,
    ONLINE,
    UNREACHABLE,

    /** The TV forgot us or changed certificate: "Associer à nouveau". */
    NEEDS_PAIRING,

    /** REST answered 401/403: the PSK is wrong or missing. */
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
)

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
    /** A scene finished applying: double pulse. [detail] says which steps failed, when any did. */
    data class SceneFinished(val sceneId: String, val failedSteps: Int, val detail: String? = null) : TuttiEvent

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
    /** Ceiling from the active scene (e.g. Nuit: 20). */
    val volumeCap: Int? = null,
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
    /** Readouts fetched when Technical details opens. */
    val technical: TechnicalReadouts = TechnicalReadouts(),
) {
    /** [apps] minus the ones hidden in Settings: what the Apps sheet and pickers offer. */
    val visibleApps: List<io.kud.tutti.protocol.apps.KnownApp>
        get() = apps.filterNot { it.packageName in prefs.hiddenApps }

    /** IP control answers: the TV's own app list, its icons and `setActiveApp` are live. */
    val ipControl: Boolean get() = links.rest == Link.ONLINE

    val muted: Boolean get() = bar.isOn(TheatreFeature.MUTE) == true

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

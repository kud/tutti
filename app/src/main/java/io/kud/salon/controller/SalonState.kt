package io.kud.salon.controller

import io.kud.salon.platform.data.AppPrefs
import io.kud.salon.platform.data.Devices
import io.kud.salon.platform.data.DockConfig
import io.kud.salon.protocol.atv.RemoteState
import io.kud.salon.protocol.bravia.Capabilities
import io.kud.salon.protocol.bravia.ExternalInput
import io.kud.salon.protocol.bravia.PowerStatus
import io.kud.salon.protocol.bravia.TvApp
import io.kud.salon.protocol.bravia.TvSetting
import io.kud.salon.protocol.grpc.CloudPlaneState
import io.kud.salon.protocol.scenes.AppProfile
import io.kud.salon.protocol.scenes.Scene
import io.kud.salon.protocol.scenes.SceneKey
import io.kud.salon.protocol.theatre.TheatreFeature
import io.kud.salon.protocol.theatre.TheatreState

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

/** tv = remote v2 (6466), rest = BRAVIA REST + PSK, bar = TCP 33336. */
data class Links(val tv: Link = Link.ABSENT, val rest: Link = Link.ABSENT, val bar: Link = Link.ABSENT)

data class TvSettingsState(
    val loading: Boolean = false,
    val picture: List<TvSetting> = emptyList(),
    val sound: List<TvSetting> = emptyList(),
    val speaker: List<TvSetting> = emptyList(),
    val powerSaving: String? = null,
)

data class TvState(
    val power: PowerStatus = PowerStatus.UNKNOWN,
    /** Current source URI from getPlayingContentInfo (e.g. `extInput:hdmi?port=2`). */
    val input: String? = null,
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
)

sealed interface PairingUi {
    data object Idle : PairingUi

    data object Connecting : PairingUi

    /** The TV shows a 6-character code. attempt > 1 means the previous code was wrong. */
    data class AwaitingCode(val attempt: Int) : PairingUi

    data object Done : PairingUi

    data class Failed(val reason: PairingError) : PairingUi
}

enum class PairingError { UNREACHABLE, BAD_CODE, REFUSED }

sealed interface SalonEvent {
    /** A scene finished applying: double pulse. */
    data class SceneFinished(val sceneId: String, val failedSteps: Int) : SalonEvent

    /** Volume hit 0, 100 or the active scene's cap: soft bump. */
    data object VolumeLimit : SalonEvent

    data object ActionFailed : SalonEvent
}

data class SalonUiState(
    /** False until the stores have loaded: do not route to onboarding before that. */
    val loaded: Boolean = false,
    val devices: Devices = Devices(),
    val prefs: AppPrefs = AppPrefs(),
    val dock: DockConfig = DockConfig(),
    val links: Links = Links(),
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
    val pairing: PairingUi = PairingUi.Idle,
    val cloud: CloudPlaneState = CloudPlaneState.Disabled,
) {
    val muted: Boolean get() = bar.isOn(TheatreFeature.MUTE) == true

    val activeScene: SceneUi? get() = scenes.firstOrNull { it.status != SceneStatus.IDLE }

    val keyboardField get() = remote.ime
}

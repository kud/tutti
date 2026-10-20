package io.kud.salon.protocol.grpc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The Sony-cloud control plane (BRAVIA Connect's own channel), opt-in as
 * "Connexion Sony (expérimental)".
 *
 * What is documented (bravia-quad-homeassistant, MIT, docs/sony-grpc-reference.md and
 * grpc-auth-lifecycle.md): gRPC over h2c on the bar's port 55051, service
 * [SERVICE], Sony Seeds OAuth (PKCE) yielding ~24 h session keys, an HMAC-signed handshake
 * (ConfirmSignin, ConfirmKeys, GetSessionRandom), then GetStatesWithAuth,
 * ExecCommandWithAuth and the StartNotifyStates stream over dotted field paths.
 *
 * What is NOT documented well enough to implement without guessing: the outer envelope
 * byte layout (`serialized_request + hmac`), the HMAC input, and the Seeds OAuth endpoints
 * and client id. The upstream ships hand encoders, not a .proto. So this stops at the
 * interface, and [UnavailableSonyCloudPlane] is what `:app` binds today. A failure here
 * never touches the TCP/REST controls.
 */
interface SonyCloudPlane {
    val state: StateFlow<CloudPlaneState>

    /** Pushed field updates (StartNotifyStates). */
    val updates: Flow<FieldUpdate>

    suspend fun getStates(paths: List<String>): Map<String, GrpcValue>?

    suspend fun exec(path: String, value: GrpcValue): Boolean

    /** "Se déconnecter de Sony": drops the session and wipes tokens and session keys. */
    suspend fun signOut()

    companion object {
        const val SERVICE = "jp.co.sony.hes.ssh.controldevice.v1.ControlDeviceService"
        const val BAR_PORT = 55051

        /** The TV's port is dynamic; it is advertised over mDNS. */
        const val MDNS_SERVICE = "_sonysmarthome._tcp"
    }
}

sealed interface CloudPlaneState {
    /** The user has not opted in. The default. */
    data object Disabled : CloudPlaneState

    data object SignedOut : CloudPlaneState

    data object Connecting : CloudPlaneState

    data class Connected(val sessionExpiresAtMillis: Long) : CloudPlaneState

    /** Shown as "non disponible" with the reason; the LAN controls stay untouched. */
    data class Unavailable(val reason: String) : CloudPlaneState
}

sealed interface GrpcValue {
    data class Bool(val value: Boolean) : GrpcValue

    data class Int32(val value: Int) : GrpcValue

    data class Str(val value: String) : GrpcValue
}

data class FieldUpdate(val path: String, val value: GrpcValue)

/** Field paths with a documented type. Bool paths use `bool_value`, enums `string_value`, levels `int_value`. */
object FieldPaths {
    const val POWER = "power"
    const val VOLUME = "volume"
    const val MUTE = "mute"
    const val FUNCTION = "playback_control.function"
    const val NIGHT_MODE = "sound_setting.night_mode"
    const val SOUND_FIELD = "sound_setting.sound_field"
    const val VOICE_MODE = "sound_setting.voice_mode"
    const val AUTO_VOLUME = "sound_setting.auto_volume"

    /** Writable, but not readable locally on tested firmware (notify-only). */
    const val DRC = "sound_setting.drc"
    const val SUBWOOFER_LEVEL = "sound_setting.volume.subwoofer"
    const val BASS_NO_SUB = "sound_setting.volume.bass"
    const val SSM360_HEIGHT = "speaker_sound_setting.360ssm_height"
    const val DIMMER = "system_setting.dimmer"
    const val HDMI_SIGNAL_FORMAT = "system_setting.hdmi_signal_format"
    const val REAR_LEFT_BATTERY = "battery.life.rl"
    const val REAR_RIGHT_BATTERY = "battery.life.rr"
}

/** Sony Seeds session keys. Secret: stored only Keystore-encrypted, never logged. */
class SessionKeys(
    val deviceId: String,
    val keyId: String,
    val sessionKey: String,
    val hmacKey: String,
    val expiresAtMillis: Long,
) {
    fun isExpired(nowMillis: Long, marginMillis: Long = 10 * 60_000L): Boolean = nowMillis + marginMillis >= expiresAtMillis

    override fun toString(): String = "SessionKeys(deviceId=$deviceId, keyId=<redacted>, sessionKey=<redacted>, hmacKey=<redacted>, expiresAt=$expiresAtMillis)"
}

/** What `:app` binds until the envelope is documented. */
class UnavailableSonyCloudPlane(reason: String = "non disponible") : SonyCloudPlane {
    private val mutableState = MutableStateFlow<CloudPlaneState>(CloudPlaneState.Unavailable(reason))
    override val state: StateFlow<CloudPlaneState> = mutableState.asStateFlow()
    override val updates: Flow<FieldUpdate> = emptyFlow()

    override suspend fun getStates(paths: List<String>): Map<String, GrpcValue>? = null

    override suspend fun exec(path: String, value: GrpcValue): Boolean = false

    override suspend fun signOut() = Unit
}

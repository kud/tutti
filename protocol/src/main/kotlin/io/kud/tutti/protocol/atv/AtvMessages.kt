package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.pairing.PairingConfiguration
import io.kud.tutti.protocol.atv.proto.pairing.PairingEncoding
import io.kud.tutti.protocol.atv.proto.pairing.PairingMessage
import io.kud.tutti.protocol.atv.proto.pairing.PairingOption
import io.kud.tutti.protocol.atv.proto.pairing.PairingRequest
import io.kud.tutti.protocol.atv.proto.pairing.PairingSecret
import io.kud.tutti.protocol.atv.proto.pairing.RoleType
import io.kud.tutti.protocol.atv.proto.remote.RemoteAppLinkLaunchRequest
import io.kud.tutti.protocol.atv.proto.remote.RemoteConfigure
import io.kud.tutti.protocol.atv.proto.remote.RemoteDeviceInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteDirection
import io.kud.tutti.protocol.atv.proto.remote.RemoteEditInfo
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeBatchEdit
import io.kud.tutti.protocol.atv.proto.remote.RemoteImeObject
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyCode
import io.kud.tutti.protocol.atv.proto.remote.RemoteKeyInject
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.atv.proto.remote.RemotePingResponse
import io.kud.tutti.protocol.atv.proto.remote.RemoteSetActive
import okio.ByteString.Companion.toByteString

/** What the TV is told about this client during remote configuration. */
data class ClientDeviceInfo(
    val model: String = "tutti",
    val vendor: String = "kud",
    val packageName: String = "io.kud.tutti",
    val appVersion: String = "1",
)

enum class KeyDirection(internal val wire: RemoteDirection) {
    SHORT(RemoteDirection.SHORT),
    START_LONG(RemoteDirection.START_LONG),
    END_LONG(RemoteDirection.END_LONG),
}

/** Builders for every message we send. The only place, with the reducers, that touches generated types. */
object PairingMessages {
    const val PROTOCOL_VERSION = 2

    private val hex6 = PairingEncoding(type = PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL, symbol_length = 6)

    fun request(serviceName: String, clientName: String) = PairingMessage(
        protocol_version = PROTOCOL_VERSION,
        status = PairingMessage.Status.STATUS_OK,
        pairing_request = PairingRequest(service_name = serviceName, client_name = clientName),
    )

    fun option() = PairingMessage(
        protocol_version = PROTOCOL_VERSION,
        status = PairingMessage.Status.STATUS_OK,
        pairing_option = PairingOption(input_encodings = listOf(hex6), preferred_role = RoleType.ROLE_TYPE_INPUT),
    )

    fun configuration() = PairingMessage(
        protocol_version = PROTOCOL_VERSION,
        status = PairingMessage.Status.STATUS_OK,
        pairing_configuration = PairingConfiguration(encoding = hex6, client_role = RoleType.ROLE_TYPE_INPUT),
    )

    fun secret(secret: ByteArray) = PairingMessage(
        protocol_version = PROTOCOL_VERSION,
        status = PairingMessage.Status.STATUS_OK,
        pairing_secret = PairingSecret(secret = secret.toByteString()),
    )
}

object RemoteMessages {
    /** Magic feature mask echoed by every known client (androidtv-remote, Google's app). */
    const val FEATURES = 622

    fun configure(info: ClientDeviceInfo) = RemoteMessage(
        remote_configure = RemoteConfigure(
            code1 = FEATURES,
            device_info = RemoteDeviceInfo(
                model = info.model,
                vendor = info.vendor,
                unknown1 = 1,
                unknown2 = "1",
                package_name = info.packageName,
                app_version = info.appVersion,
            ),
        ),
    )

    fun setActive() = RemoteMessage(remote_set_active = RemoteSetActive(active = FEATURES))

    fun pingResponse(value: Int) = RemoteMessage(remote_ping_response = RemotePingResponse(val1 = value))

    /** Null when [keyCode] is not a v2 key code. */
    fun key(keyCode: Int, direction: KeyDirection = KeyDirection.SHORT): RemoteMessage? {
        val code = RemoteKeyCode.fromValue(keyCode) ?: return null
        if (code == RemoteKeyCode.KEYCODE_UNKNOWN) return null
        return RemoteMessage(remote_key_inject = RemoteKeyInject(key_code = code, direction = direction.wire))
    }

    /** Replaces the focused field's whole text, cursor at the end. Counters come from the TV. */
    fun text(text: String, imeCounter: Int, fieldCounter: Int) = RemoteMessage(
        remote_ime_batch_edit = RemoteImeBatchEdit(
            ime_counter = imeCounter,
            field_counter = fieldCounter,
            edit_info = listOf(
                RemoteEditInfo(
                    insert = 1,
                    text_field_status = RemoteImeObject(start = text.length, end = text.length, value_ = text),
                ),
            ),
        ),
    )

    fun appLink(link: String) = RemoteMessage(remote_app_link_launch_request = RemoteAppLinkLaunchRequest(app_link = link))
}

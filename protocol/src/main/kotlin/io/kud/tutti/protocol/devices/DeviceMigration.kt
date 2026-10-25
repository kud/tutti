package io.kud.tutti.protocol.devices

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * From the flat `Devices(tv, bar)` (version 1) to a [DeviceList]. Pure, so it is tested on
 * fixtures; the app runs it once, writes the list in one edit and leaves the old key in place for
 * one release, so a downgrade still finds its TV.
 */
object DeviceMigration {
    @Serializable
    private data class LegacyTv(val host: String, val name: String = "BRAVIA 8", val mac: String? = null)

    @Serializable
    private data class LegacyBar(val host: String, val name: String = "Bar 9")

    @Serializable
    private data class Legacy(val tv: LegacyTv? = null, val bar: LegacyBar? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [current] wins when present: running this twice changes nothing. Otherwise [legacyJson] (the
     * old `devices` value) and [hidAddress] (the host the Bluetooth screen remembered; that screen
     * only ever paired the TV) become the TV's and the bar's links. Null when there is nothing.
     */
    fun migrate(current: DeviceList?, legacyJson: String?, hidAddress: String?): DeviceList? {
        if (current != null) return current
        val legacy = legacyJson?.let { runCatching { json.decodeFromString(Legacy.serializer(), it) }.getOrNull() }
        val devices = buildList {
            val bt = hidAddress?.takeIf { it.isNotBlank() }?.let { DeviceLink.Bluetooth(it) }
            legacy?.tv?.let { tv ->
                add(Device(Device.TV_ID, tv.name, DeviceKind.TV, listOfNotNull(DeviceLink.Google(tv.host), DeviceLink.SonyIp(tv.host, tv.mac), bt)))
            }
            legacy?.bar?.let { bar -> add(Device(Device.BAR_ID, bar.name, DeviceKind.SOUNDBAR, listOf(DeviceLink.BarIp(bar.host)))) }
        }
        return if (devices.isEmpty()) null else DeviceList(devices)
    }
}

/**
 * Where each device's secrets live in `secure.preferences_pb`: per device id, in the same file, so
 * the backup exclusions do not change. The flat keys of version 1 belong to [Device.TV_ID].
 */
object SecretKeys {
    fun psk(id: String) = "psk/$id"
    fun remotePin(id: String) = "pin/$id/remote"
    fun restPin(id: String) = "pin/$id/rest"

    /** The IP-control cookie of Sony's "Normal" authentication (a bearer secret), `AuthCookie.encode()`. */
    fun ipCookie(id: String) = "ipcookie/$id"

    /** Version-1 key → its per-device key. */
    val legacy: Map<String, String> = mapOf(
        "tv_psk" to psk(Device.TV_ID),
        "tv_remote_pin" to remotePin(Device.TV_ID),
        "tv_rest_pin" to restPin(Device.TV_ID),
    )

    /**
     * The copies to make, old → new: only where the old key holds something and the new one is
     * still empty. A copy, never a move: the old key stays readable for one release, and a crash
     * between two steps can never leave the PSK nowhere. Encrypted values are copied as they are.
     */
    fun copies(present: Set<String>): Map<String, String> = legacy.filter { (old, new) -> old in present && new !in present }

    /** Every key that holds [id]'s secrets, including the version-1 ones for the TV. */
    fun all(id: String): List<String> =
        listOf(psk(id), remotePin(id), restPin(id), ipCookie(id)) + if (id == Device.TV_ID) legacy.keys else emptySet()
}

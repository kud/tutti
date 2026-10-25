package io.kud.tutti.protocol.devices

import io.kud.tutti.protocol.hid.HidTarget
import io.kud.tutti.protocol.hid.KeyLayout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement

/**
 * What a device is. The kind, never the link, decides behaviour: Back is Esc on a Mac and AC Back
 * on the TV whatever carries it. Stage 1 (Erwann, 2026-10-25): one TV, one bar, any number of Macs.
 */
@Serializable
enum class DeviceKind {
    TV,
    SOUNDBAR,
    MAC,
    ;

    /** The Bluetooth personality ([io.kud.tutti.protocol.hid.HidRoute]) this kind takes; the bar has none. */
    val hidTarget: HidTarget?
        get() = when (this) {
            TV -> HidTarget.TV
            MAC -> HidTarget.MAC
            SOUNDBAR -> null
        }
}

/** One way to reach a device. "HTTP" in Erwann's words is [SonyIp], and only that. */
@Serializable
sealed interface DeviceLink {
    /** Android TV Remote v2 (6466/6467). */
    @Serializable
    @SerialName("google")
    data class Google(val host: String) : DeviceLink

    /** BRAVIA REST JSON-RPC + PSK + IRCC. [mac] is learnt for Wake-on-LAN: a device id, never logged. */
    @Serializable
    @SerialName("sony-ip")
    data class SonyIp(val host: String, val mac: String? = null) : DeviceLink

    /** The Theatre Bar's TCP 33336 (+ reads on :54545). Nothing in common with Sony REST. */
    @Serializable
    @SerialName("bar-ip")
    data class BarIp(val host: String) : DeviceLink

    /**
     * The phone as this device's keyboard and mouse. [layout] is a [KeyLayout] name, kept as text so
     * a layout this build doesn't know never costs the address.
     */
    @Serializable
    @SerialName("bluetooth")
    data class Bluetooth(val address: String, val layout: String? = null) : DeviceLink
}

/** How the TV shows a Mac: its HDMI input (a Sony input uri), and/or AirPlay mirroring. */
@Serializable
data class ShownOn(val hdmiInput: String? = null, val airplay: Boolean = true)

@Serializable
data class Device(
    /** Opaque and stable. Migrated devices get [TV_ID] and [BAR_ID]. */
    val id: String,
    val name: String,
    val kind: DeviceKind,
    @Serializable(with = LinkListSerializer::class)
    val links: List<DeviceLink> = emptyList(),
    /** Macs only. */
    val shownOn: ShownOn? = null,
) {
    inline fun <reified T : DeviceLink> link(): T? = links.filterIsInstance<T>().firstOrNull()

    val bluetooth: DeviceLink.Bluetooth? get() = link()

    /** The layout typed to this device: its own if it suits the kind, else the kind's default. */
    fun layout(): KeyLayout? {
        val target = kind.hidTarget ?: return null
        return target.layoutOr(KeyLayout.entries.firstOrNull { it.name == bluetooth?.layout })
    }

    /** Replaces the link of the same type, or adds it. */
    fun withLink(link: DeviceLink): Device = copy(links = links.filterNot { it::class == link::class } + link)

    inline fun <reified T : DeviceLink> withoutLink(): Device = copy(links = links.filterNot { it is T })

    companion object {
        const val TV_ID = "tv"
        const val BAR_ID = "bar"
    }
}

/** Every configured device. [version] 2 is the first list; the flat `Devices(tv, bar)` was 1. */
@Serializable
data class DeviceList(
    @Serializable(with = DeviceListSerializer::class)
    val devices: List<Device> = emptyList(),
    val version: Int = VERSION,
) {
    val tv: Device? get() = devices.firstOrNull { it.kind == DeviceKind.TV }
    val bar: Device? get() = devices.firstOrNull { it.kind == DeviceKind.SOUNDBAR }
    val macs: List<Device> get() = devices.filter { it.kind == DeviceKind.MAC }
    val isEmpty: Boolean get() = devices.isEmpty()

    operator fun get(id: String): Device? = devices.firstOrNull { it.id == id }

    /**
     * Adds or replaces [device] by id. Stage 1 keeps one TV and one bar: a second one of those
     * replaces the first, keeping the first's place.
     */
    fun put(device: Device): DeviceList {
        val single = device.kind != DeviceKind.MAC
        val index = devices.indexOfFirst { it.id == device.id || (single && it.kind == device.kind) }
        return copy(devices = if (index < 0) devices + device else devices.toMutableList().also { it[index] = device })
    }

    fun remove(id: String): DeviceList = copy(devices = devices.filterNot { it.id == id })

    fun update(id: String, transform: (Device) -> Device): DeviceList =
        copy(devices = devices.map { if (it.id == id) transform(it) else it })

    companion object {
        const val VERSION = 2
    }
}

/** Which links a kind accepts. Anything else (a bar on Bluetooth, a Mac on Google) is refused. */
object DeviceKinds {
    fun allowedLinks(kind: DeviceKind): Set<Class<out DeviceLink>> = when (kind) {
        DeviceKind.TV -> setOf(DeviceLink.Google::class.java, DeviceLink.SonyIp::class.java, DeviceLink.Bluetooth::class.java)
        DeviceKind.SOUNDBAR -> setOf(DeviceLink.BarIp::class.java)
        DeviceKind.MAC -> setOf(DeviceLink.Bluetooth::class.java)
    }

    fun accepts(kind: DeviceKind, link: DeviceLink): Boolean = link.javaClass in allowedLinks(kind)
}

/**
 * Decodes what it can and skips the rest: a link type from a newer build is dropped, never the
 * whole list (losing the TV's links would look like a factory reset).
 */
internal object LinkListSerializer : KSerializer<List<DeviceLink>> by lenientList(DeviceLink.serializer())

internal object DeviceListSerializer : KSerializer<List<Device>> by lenientList(Device.serializer())

private fun <T> lenientList(item: KSerializer<T>): KSerializer<List<T>> = object : KSerializer<List<T>> {
    private val strict = ListSerializer(item)
    override val descriptor: SerialDescriptor = strict.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = strict.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return strict.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element: JsonElement -> runCatching { json.json.decodeFromJsonElement(item, element) }.getOrNull() }
    }
}

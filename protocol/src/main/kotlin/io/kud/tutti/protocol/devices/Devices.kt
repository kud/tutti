package io.kud.tutti.protocol.devices

import io.kud.tutti.protocol.hid.HidTarget
import io.kud.tutti.protocol.hid.KeyLayout
import kotlinx.serialization.ExperimentalSerializationApi
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
import kotlinx.serialization.json.JsonNames

/**
 * What a device is. The kind, never the link, decides behaviour: Back is Esc on a computer and AC
 * Back on the TV whatever carries it. Stage 1 (Erwann, 2026-10-25): one TV, one bar, any number of
 * computers (« c'est pas qu'un Mac, ça peut être n'importe quel type d'ordi »).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class DeviceKind {
    TV,
    SOUNDBAR,

    /** Any computer shown on the TV; its [ComputerOs] picks the layouts. v0.2.0-dev.1/2 stored "MAC". */
    @JsonNames("MAC")
    COMPUTER,
    ;

    /** The Bluetooth personality ([io.kud.tutti.protocol.hid.HidRoute]) this kind takes; the bar has none. */
    val hidTarget: HidTarget?
        get() = when (this) {
            TV -> HidTarget.TV
            COMPUTER -> HidTarget.COMPUTER
            SOUNDBAR -> null
        }
}

/**
 * A computer's system. Key meanings are the same on all of them (Back is Esc, OK is Enter); only
 * the layouts differ, the first being the default: Apple's French on a Mac, the PC AZERTY (AltGr
 * for the third level) elsewhere. AirPlay is a Mac's only.
 */
@Serializable
enum class ComputerOs(val layouts: List<KeyLayout>) {
    MACOS(listOf(KeyLayout.MAC_FR, KeyLayout.MAC_US)),
    WINDOWS(listOf(KeyLayout.FR_AZERTY, KeyLayout.US)),
    LINUX(listOf(KeyLayout.FR_AZERTY, KeyLayout.US)),
    ;

    val defaultLayout: KeyLayout get() = layouts.first()

    val airplay: Boolean get() = this == MACOS
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

/** How the TV shows a computer: its HDMI input (a Sony input uri), and/or AirPlay mirroring (macOS only). */
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
    /** Computers only. */
    val shownOn: ShownOn? = null,
    /** Computers only. Absent on a computer stored before it existed, which was a Mac: see [computerOs]. */
    val os: ComputerOs? = null,
) {
    inline fun <reified T : DeviceLink> link(): T? = links.filterIsInstance<T>().firstOrNull()

    val bluetooth: DeviceLink.Bluetooth? get() = link()

    /** A computer's system; one stored without it was added as a Mac. Null for anything else. */
    val computerOs: ComputerOs? get() = if (kind == DeviceKind.COMPUTER) os ?: ComputerOs.MACOS else null

    /** The layouts offered for this device, the default first; empty for the bar. */
    fun layouts(): List<KeyLayout> = when (kind) {
        DeviceKind.TV -> TV_LAYOUTS
        DeviceKind.COMPUTER -> computerOs?.layouts.orEmpty()
        DeviceKind.SOUNDBAR -> emptyList()
    }

    /** The layout typed to this device: its own if it suits it (a TV layout never types to a Mac), else the default. */
    fun layout(): KeyLayout? {
        val offered = layouts()
        val stored = KeyLayout.entries.firstOrNull { it.name == bluetooth?.layout }
        return stored?.takeIf { it in offered } ?: offered.firstOrNull()
    }

    /** Whether the TV can show it over AirPlay: a Mac's only (Windows and Linux have no AirPlay sender). */
    val airplay: Boolean get() = computerOs?.airplay == true && shownOn?.airplay != false

    /** Replaces the link of the same type, or adds it. */
    fun withLink(link: DeviceLink): Device = copy(links = links.filterNot { it::class == link::class } + link)

    inline fun <reified T : DeviceLink> withoutLink(): Device = copy(links = links.filterNot { it is T })

    companion object {
        const val TV_ID = "tv"
        const val BAR_ID = "bar"

        /** The TV's layouts, US first (Android TV's own default). */
        val TV_LAYOUTS: List<KeyLayout> = listOf(KeyLayout.US, KeyLayout.FR_AZERTY)
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
    val computers: List<Device> get() = devices.filter { it.kind == DeviceKind.COMPUTER }
    val isEmpty: Boolean get() = devices.isEmpty()

    operator fun get(id: String): Device? = devices.firstOrNull { it.id == id }

    /**
     * Adds or replaces [device] by id. Stage 1 keeps one TV and one bar: a second one of those
     * replaces the first, keeping the first's place.
     */
    fun put(device: Device): DeviceList {
        val single = device.kind != DeviceKind.COMPUTER
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

/** Which links a kind accepts. Anything else (a bar on Bluetooth, a computer on Google) is refused. */
object DeviceKinds {
    fun allowedLinks(kind: DeviceKind): Set<Class<out DeviceLink>> = when (kind) {
        DeviceKind.TV -> setOf(DeviceLink.Google::class.java, DeviceLink.SonyIp::class.java, DeviceLink.Bluetooth::class.java)
        DeviceKind.SOUNDBAR -> setOf(DeviceLink.BarIp::class.java)
        DeviceKind.COMPUTER -> setOf(DeviceLink.Bluetooth::class.java)
    }

    fun accepts(kind: DeviceKind, link: DeviceLink): Boolean = link.javaClass in allowedLinks(kind)
}

/**
 * Decodes what it can and skips the rest: a link type from a newer build is dropped, never the
 * whole list (losing the TV's links would look like a factory reset).
 */
internal object LinkListSerializer : KSerializer<List<DeviceLink>> by lenientList(DeviceLink.serializer())

/**
 * The device list, lenient like the links, and a computer stored before [Device.os] existed (kind
 * "MAC", read as [DeviceKind.COMPUTER]) comes back as macOS, so the next write stores it explicitly.
 */
internal object DeviceListSerializer : KSerializer<List<Device>> {
    private val lenient = lenientList(Device.serializer())
    override val descriptor: SerialDescriptor = lenient.descriptor

    override fun serialize(encoder: Encoder, value: List<Device>) = lenient.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<Device> =
        lenient.deserialize(decoder).map { if (it.kind == DeviceKind.COMPUTER && it.os == null) it.copy(os = ComputerOs.MACOS) else it }
}

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

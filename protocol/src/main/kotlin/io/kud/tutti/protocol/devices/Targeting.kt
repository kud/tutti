package io.kud.tutti.protocol.devices

import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.hid.HidAction
import io.kud.tutti.protocol.hid.HidRoute
import io.kud.tutti.protocol.hid.HidTarget
import io.kud.tutti.protocol.hid.KeyLayout
import io.kud.tutti.protocol.hid.KeyStroke
import io.kud.tutti.protocol.hid.KeyUsage
import io.kud.tutti.protocol.hid.RemoteLink
import io.kud.tutti.protocol.hid.Typed

/** A choice made by hand, and the TV input it was made on: it holds until that input changes. */
data class TargetChoice(val manual: String? = null, val inputAtChoice: String? = null)

/** Whether a remote key can do something for the current target, one reason each, said in words. */
enum class KeyAvailability {
    AVAILABLE,

    /** No computer meaning (Iris, 2026-10-25: rewind, subtitles, digits, channels, colours…). */
    NOT_ON_COMPUTER,

    /** The TV over Bluetooth only (its LAN is down): power, inputs, settings can't go that way. */
    NOT_OVER_BLUETOOTH,

    /** The link this key needs is down. */
    LINK_DOWN,
}

/**
 * Where one key goes. [toTv]: the key keeps its Google TV meaning and goes to the TV whatever the
 * target ([Targeting.TV_MEANING]). [action] is the HID report to send when [link] is Bluetooth.
 */
data class KeyRoute(val availability: KeyAvailability, val link: RemoteLink, val toTv: Boolean, val action: HidAction? = null)

/** The remote's target, per device: pure rules the controller reads. */
object Targeting {
    /**
     * Keys that keep their Google TV meaning while a computer is the target (Iris, 2026-10-25):
     * Home, Apps, Settings, inputs, Power. They leave the computer's input on purpose and the target
     * follows. Volume is the room's (the bar, else the TV), never a computer's.
     */
    val TV_MEANING: Set<Int> = setOf(
        V2Key.HOME, V2Key.ALL_APPS, V2Key.SETTINGS, V2Key.POWER, V2Key.APP_SWITCH,
        V2Key.TV_INPUT, V2Key.TV_INPUT_HDMI_1, V2Key.TV_INPUT_HDMI_2, V2Key.TV_INPUT_HDMI_3, V2Key.TV_INPUT_HDMI_4,
        V2Key.VOLUME_UP, V2Key.VOLUME_DOWN, V2Key.VOLUME_MUTE,
    )

    /** Digits have a HID usage but no remote meaning on a computer (Iris, 2026-10-25). */
    private val NOT_FOR_COMPUTER: Set<Int> = (V2Key.NUM_0..V2Key.NUM_9).toSet()

    /** The computer shown on [input], if one is marked for it. */
    fun computerOnInput(input: String?, devices: DeviceList): Device? =
        if (input == null) null else devices.computers.firstOrNull { it.shownOn?.hdmiInput == input }

    /** The target that follows the TV: the computer on its input, else the TV, else the first computer. */
    fun auto(input: String?, devices: DeviceList): Device? =
        computerOnInput(input, devices) ?: devices.tv ?: devices.computers.firstOrNull()

    /** A manual [choice] holds while the input is the one it was made on and the device still exists. */
    fun current(choice: TargetChoice, input: String?, devices: DeviceList): Device? =
        choice.manual?.takeIf { input == choice.inputAtChoice }?.let { devices[it] }?.takeIf { it.kind != DeviceKind.SOUNDBAR }
            ?: auto(input, devices)

    /** The devices the remote can target (the bar never is one). */
    fun targets(devices: DeviceList): List<Device> = devices.devices.filter { it.kind.hidTarget != null }

    /**
     * Who holds the one HID connection while the remote is in front, generalising [HidRoute.hidHost]
     * to several computers: the target when it is a computer; the TV when it is the target with its
     * LAN down; otherwise a computer stays ready, so switching to it is only routing ([lastComputer] first). Null: let
     * the role go. Only devices with a Bluetooth address can hold it.
     */
    fun hidHost(devices: DeviceList, target: Device?, tvLanUp: Boolean, lastComputer: String?): Device? {
        fun Device?.paired() = this?.takeIf { it.bluetooth != null }
        if (target?.kind == DeviceKind.COMPUTER) target.paired()?.let { return it }
        if (target?.kind == DeviceKind.TV && !tvLanUp) target.paired()?.let { return it }
        return lastComputer?.let { devices[it] }?.takeIf { it.kind == DeviceKind.COMPUTER }.paired()
            ?: devices.computers.firstOrNull { it.bluetooth != null }
    }

    /**
     * Where [v2Key] goes for [target]. [hidConnected]: the HID link is up to that target.
     * TV-meaning keys go to the TV over its LAN even while a computer is targeted.
     */
    fun route(v2Key: Int, target: Device?, tvLanUp: Boolean, hidConnected: Boolean): KeyRoute {
        val kind = target?.kind?.hidTarget ?: HidTarget.TV
        if (kind == HidTarget.COMPUTER) {
            if (v2Key in TV_MEANING) {
                return if (tvLanUp) KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.LAN, toTv = true)
                else KeyRoute(KeyAvailability.LINK_DOWN, RemoteLink.NONE, toTv = true)
            }
            val action = HidRoute.action(v2Key, HidTarget.COMPUTER)?.takeIf { v2Key !in NOT_FOR_COMPUTER }
                ?: return KeyRoute(KeyAvailability.NOT_ON_COMPUTER, RemoteLink.NONE, toTv = false)
            return if (hidConnected) KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.BLUETOOTH, toTv = false, action)
            else KeyRoute(KeyAvailability.LINK_DOWN, RemoteLink.NONE, toTv = false, action)
        }
        return when (HidRoute.link(HidTarget.TV, tvLanUp, hidConnected)) {
            RemoteLink.LAN -> KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.LAN, toTv = true)
            RemoteLink.BLUETOOTH -> HidRoute.action(v2Key, HidTarget.TV)
                ?.let { KeyRoute(KeyAvailability.AVAILABLE, RemoteLink.BLUETOOTH, toTv = true, it) }
                ?: KeyRoute(KeyAvailability.NOT_OVER_BLUETOOTH, RemoteLink.NONE, toTv = true)
            RemoteLink.NONE -> KeyRoute(KeyAvailability.LINK_DOWN, RemoteLink.NONE, toTv = true)
        }
    }

    /**
     * The input to preselect when adding a computer: "plug it in (or wake it)", and the HDMI input
     * whose `connected` flipped to true since [before] is the one. Computers send no HDMI-CEC name, so
     * this, confirmed by hand, is how a computer is tied to its input. Null when none or several flipped.
     */
    fun newlyConnected(before: List<ExternalInput>, after: List<ExternalInput>): String? {
        val was = before.filter { it.connected }.map { it.uri }.toSet()
        return after.filter { it.connected && it.uri !in was && it.uri.startsWith("extInput:hdmi") }.singleOrNull()?.uri
    }

    /**
     * The phone's text field reports its whole text on every keystroke; HID can only type and
     * erase. Erase back to the common prefix, then type the rest in [layout]. Characters the layout
     * can't type are reported in [Typed.unsupported], never guessed.
     */
    fun textEdit(before: String, after: String, layout: KeyLayout): Typed {
        val common = before.commonPrefixWith(after).length
        val erase = List(before.length - common) { KeyStroke(KeyUsage.BACKSPACE) }
        val typed = layout.type(after.substring(common))
        return Typed(erase + typed.strokes, typed.unsupported)
    }
}

/**
 * The AirPlay diagnostic (proposal § 5): the last [MAX] distinct foreground packages with when they
 * came to the front, so Erwann can mirror once and tell us Sony's receiver. Packages are not
 * personal data; nothing else is kept.
 */
data class ForegroundLog(val entries: List<Pair<String, Long>> = emptyList()) {
    fun seen(pkg: String?, atMillis: Long): ForegroundLog {
        if (pkg.isNullOrBlank() || entries.lastOrNull()?.first == pkg) return this
        return ForegroundLog((entries.filterNot { it.first == pkg } + (pkg to atMillis)).takeLast(MAX))
    }

    companion object {
        const val MAX = 10
    }
}

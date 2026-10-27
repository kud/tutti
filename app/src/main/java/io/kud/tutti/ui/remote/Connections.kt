package io.kud.tutti.ui.remote

import io.kud.tutti.controller.IpCodeStatus
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TargetState
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.TvShown
import io.kud.tutti.platform.bluetooth.HidPhase
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.theatre.TheatreFeature

/**
 * « Connexions » (Erwann, 2026-10-25: « Est-ce qu'on pourrait être clair quelque part sur comment
 * on est connecté ? »). One sheet, opened from the device pill, lists each device and under it
 * each of its links: the link's name, its state in words, what it gives, and its one next step.
 * Pure rules over state the remote already holds, so no link is asked anything new
 * (`ConnectionsTest`).
 */
enum class ConnLink { GOOGLE_TV, SONY_IP, NETWORK, BLUETOOTH }

/** A link's state in words. Each maps to one glyph ([ConnMark]), never to a colour alone. */
enum class ConnWords {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    STANDBY,
    NOT_SET_UP,
    UNREACHABLE,
    KEY_REFUSED,

    /** The TV refused the cookie from its on-screen code, renewal included: show a new code. */
    CODE_TO_RENEW,
    NEEDS_PAIRING,

    /** Bluetooth: paired, not connected; it connects when the remote controls the device. */
    READY,
    NOT_PAIRED,
    BLUETOOTH_OFF,
    NEEDS_PERMISSION,

    /** Another app holds the phone's keyboard-and-mouse role. */
    BUSY,
    NOT_ADDED,

    /**
     * The bar no longer answers at its address, and a bar of the same model answers at another one
     * (mDNS, then port 33336): an offer, never applied without the user ([ConnAction.USE_NEW_ADDRESS]).
     */
    MOVED,
    ;

    val mark: ConnMark get() = when (this) {
        CONNECTED -> ConnMark.OK
        CONNECTING, RECONNECTING -> ConnMark.WORKING
        STANDBY -> ConnMark.ASLEEP
        READY, NOT_SET_UP, NOT_PAIRED, NOT_ADDED -> ConnMark.TODO
        UNREACHABLE, KEY_REFUSED, CODE_TO_RENEW, NEEDS_PAIRING, BLUETOOTH_OFF, NEEDS_PERMISSION, BUSY, MOVED -> ConnMark.PROBLEM
    }
}

/** The glyph beside a link: ✓ connected, ↻ on its way, ☾ standby, ○ not yet, ⚠ needs a look. */
enum class ConnMark { OK, WORKING, ASLEEP, TODO, PROBLEM }

/** The one next step a link that doesn't work offers. */
enum class ConnAction {
    /** Wake the TV: the sessions retry now, Wake-on-LAN when its address is known. */
    WAKE_TV,

    /** The TV's pairing code (the onboarding), for a TV missing or asking to be paired again. */
    PAIR_TV,

    /** The IP control step of "What tutti can do": the TV's menu path, then the code shown on the TV (or a PSK). */
    SET_UP_IP,

    ADD_BAR,

    /** Move the bar to the address it was found answering at ([TuttiController.useBarAddressOffer]). */
    USE_NEW_ADDRESS,

    /** Point the remote at this computer: the Bluetooth link follows the target. */
    CONTROL,

    /** Pair a computer again: its row in Settings › Devices. */
    PAIR_COMPUTER,

    /** Settings › Devices, where the phone's Bluetooth and its permission are asked for. */
    OPEN_DEVICES,
}

data class ConnLine(val link: ConnLink, val words: ConnWords, val action: ConnAction? = null)

data class ConnDevice(
    val id: String,
    val name: String?,
    val kind: DeviceKind,
    val lines: List<ConnLine>,
    /** The remote's keys go to this device right now. */
    val targeted: Boolean = false,
    /** A sentence under the device's lines, when its state needs more than a word. */
    val note: ConnNote? = null,
)

/**
 * Why a device that is set up may be off the network, said under its lines (Iris, 2026-10-26: dev.16
 * read "connecting…" on both lines while the TV sat in deep standby; the bar's line still offered
 * "Set up" the same way). Never a setup prompt: the device is set up, it simply isn't answering.
 */
enum class ConnNote {
    /** Out of reach, Remote start off or unknown: standby may have dropped its network. */
    TV_OFF_NETWORK,

    /** Out of reach with Remote start on: standby keeps it reachable, so it's unplugged or on another Wi-Fi. */
    TV_OFF_NETWORK_REMOTE_START_ON,

    /** The bar out of reach, Network / Bluetooth standby off or unknown: standby may have dropped its network. */
    BAR_OFF_NETWORK,

    /** The bar out of reach with Network / Bluetooth standby on: it's unplugged or on another Wi-Fi. */
    BAR_OFF_NETWORK_STANDBY_ON,
}

/** A computer as the sheet needs it. */
data class ConnComputer(val id: String, val name: String, val address: String?)

/** Everything the sheet reads, flattened so the rules test without Android. */
data class ConnInput(
    val tvId: String? = null,
    val tvName: String? = null,
    val tvShown: TvShown = TvShown(Link.ABSENT),
    /** The TV is known to be in standby (the bar on its own): not a fault. */
    val tvAsleep: Boolean = false,
    /** The TV's MAC is known, so "Turn on" can send Wake-on-LAN rather than only retry. */
    val tvWakeable: Boolean = false,
    /** Remote start (getWolMode), as last read over IP control; null when unknown. */
    val remoteStart: Boolean? = null,
    /** IP control has a credential that should work: the code's cookie or a PSK. */
    val ipSet: Boolean = false,
    /** The code's cookie was refused and no PSK stands in: a new code is needed. */
    val ipRenew: Boolean = false,
    val rest: Link = Link.ABSENT,
    /** IP control answers but refuses the stored PSK on calls that need it. */
    val pskRefused: Boolean = false,
    val tvBluetooth: String? = null,
    val barId: String? = null,
    val barName: String? = null,
    val bar: Link = Link.ABSENT,
    /** A bar of the same model answers at another address ([TuttiController.barAddressOffer]). */
    val barOffer: Boolean = false,
    /** The bar's Network / Bluetooth standby (`system.netbtstandby`), as last read; null when unknown. */
    val barStandby: Boolean? = null,
    val computers: List<ConnComputer> = emptyList(),
    val hidPhase: HidPhase = HidPhase.STOPPED,
    val hidConnectedTo: String? = null,
    val bonded: Set<String> = emptySet(),
    val targetId: String? = null,
)

fun connInput(state: TuttiUiState, t: TargetState, barOffer: Boolean = false): ConnInput {
    val tv = t.list.tv
    return ConnInput(
        tvId = tv?.id,
        tvName = state.devices.tv?.name,
        tvShown = state.links.tvShown,
        tvAsleep = barAlone(state)?.tvAsleep == true,
        tvWakeable = state.devices.tv?.mac != null,
        remoteStart = state.tv.settings.wol,
        ipSet = state.ipControlSet,
        ipRenew = !state.pskSet && state.ipCode == IpCodeStatus.RENEW,
        rest = state.links.rest,
        pskRefused = state.tv.pskRefused,
        tvBluetooth = tv?.bluetooth?.address,
        barId = t.list.bar?.id,
        barName = state.devices.bar?.name,
        bar = state.links.bar,
        barOffer = barOffer,
        barStandby = state.bar.isOn(TheatreFeature.NET_BT_STANDBY),
        computers = t.list.devices.filter { it.kind == DeviceKind.COMPUTER }.map { ConnComputer(it.id, it.name, it.bluetooth?.address) },
        hidPhase = t.hidPhase,
        hidConnectedTo = t.hidConnectedTo,
        bonded = t.hidBonded,
        targetId = t.target?.id,
    )
}

const val TV_SLOT = "tv"
const val BAR_SLOT = "bar"

/** The TV, the bar, then each computer, in the order the room is set up. Missing ones say so. */
fun connections(input: ConnInput): List<ConnDevice> = buildList {
    if (input.tvName == null) {
        add(ConnDevice(TV_SLOT, null, DeviceKind.TV, listOf(ConnLine(ConnLink.GOOGLE_TV, ConnWords.NOT_ADDED, ConnAction.PAIR_TV))))
    } else {
        val id = input.tvId ?: TV_SLOT
        val google = googleTv(input)
        add(
            ConnDevice(
                id = id,
                name = input.tvName,
                kind = DeviceKind.TV,
                lines = listOfNotNull(
                    google,
                    sonyIp(input, wakeShown = google.action == ConnAction.WAKE_TV),
                    input.tvBluetooth?.let { bluetooth(input, id, it, controllable = false) },
                ),
                targeted = input.targetId == id,
                note = tvNote(input, google),
            ),
        )
    }
    if (input.barName == null) {
        // Only once there is a TV: before that, the TV's own setup comes first.
        if (input.tvName != null) {
            add(ConnDevice(BAR_SLOT, null, DeviceKind.SOUNDBAR, listOf(ConnLine(ConnLink.NETWORK, ConnWords.NOT_ADDED, ConnAction.ADD_BAR))))
        }
    } else {
        val network = barNetwork(input.bar, input.barOffer)
        add(ConnDevice(input.barId ?: BAR_SLOT, input.barName, DeviceKind.SOUNDBAR, listOf(network), note = barNote(input, network)))
    }
    for (computer in input.computers) {
        add(
            ConnDevice(
                id = computer.id,
                name = computer.name,
                kind = DeviceKind.COMPUTER,
                lines = listOf(bluetooth(input, computer.id, computer.address, controllable = input.targetId != computer.id)),
                targeted = input.targetId == computer.id,
            ),
        )
    }
}

private fun googleTv(input: ConnInput): ConnLine = when {
    input.tvAsleep -> ConnLine(ConnLink.GOOGLE_TV, ConnWords.STANDBY, ConnAction.WAKE_TV)
    else -> when (input.tvShown.link) {
        Link.ONLINE -> ConnLine(ConnLink.GOOGLE_TV, ConnWords.CONNECTED)
        Link.CONNECTING, Link.ABSENT ->
            ConnLine(ConnLink.GOOGLE_TV, if (input.tvShown.reconnecting) ConnWords.RECONNECTING else ConnWords.CONNECTING)
        // "Turn on" only when it can: without the MAC it would merely retry, which runs anyway.
        Link.UNREACHABLE -> ConnLine(ConnLink.GOOGLE_TV, ConnWords.UNREACHABLE, ConnAction.WAKE_TV.takeIf { input.tvWakeable })
        Link.NEEDS_PAIRING, Link.UNAUTHORIZED -> ConnLine(ConnLink.GOOGLE_TV, ConnWords.NEEDS_PAIRING, ConnAction.PAIR_TV)
    }
}

/** The TV's note: only when it is out of reach and not known to be asleep. */
private fun tvNote(input: ConnInput, google: ConnLine): ConnNote? = when {
    google.words != ConnWords.UNREACHABLE -> null
    input.remoteStart == true -> ConnNote.TV_OFF_NETWORK_REMOTE_START_ON
    else -> ConnNote.TV_OFF_NETWORK
}

/**
 * IP control. Set up and not answering is the TV being out of reach, never a setup problem: it
 * reads "out of reach", and offers "Turn on" only when the Google TV line doesn't already, so one
 * TV carries one Turn on button (Iris, 2026-10-26).
 */
private fun sonyIp(input: ConnInput, wakeShown: Boolean): ConnLine = when {
    input.ipRenew -> ConnLine(ConnLink.SONY_IP, ConnWords.CODE_TO_RENEW, ConnAction.SET_UP_IP)
    !input.ipSet -> ConnLine(ConnLink.SONY_IP, ConnWords.NOT_SET_UP, ConnAction.SET_UP_IP)
    else -> when (input.rest) {
        // Reads that need no key still work, so the TV is connected; the key is the warning.
        Link.ONLINE ->
            if (input.pskRefused) ConnLine(ConnLink.SONY_IP, ConnWords.KEY_REFUSED, ConnAction.SET_UP_IP) else ConnLine(ConnLink.SONY_IP, ConnWords.CONNECTED)
        Link.UNAUTHORIZED -> ConnLine(ConnLink.SONY_IP, ConnWords.KEY_REFUSED, ConnAction.SET_UP_IP)
        // A certificate mismatch is not an IP-control setup problem: only an explicit TV pairing
        // may renew REST trust. Standby still wins because a sleeping TV is expected not to answer.
        Link.NEEDS_PAIRING ->
            if (input.tvAsleep) ConnLine(ConnLink.SONY_IP, ConnWords.STANDBY) else ConnLine(ConnLink.SONY_IP, ConnWords.NEEDS_PAIRING, ConnAction.PAIR_TV)
        Link.UNREACHABLE ->
            if (input.tvAsleep) {
                ConnLine(ConnLink.SONY_IP, ConnWords.STANDBY)
            } else {
                ConnLine(ConnLink.SONY_IP, ConnWords.UNREACHABLE, ConnAction.WAKE_TV.takeIf { input.tvWakeable && !wakeShown })
            }
        Link.CONNECTING, Link.ABSENT -> ConnLine(ConnLink.SONY_IP, ConnWords.CONNECTING)
    }
}

/**
 * The bar's network line. A bar found at another address wins: that offer is a real step. Otherwise
 * a set-up bar not answering is out of reach, never a setup problem, and offers nothing: tutti has
 * no way to wake a bar that is off the network (no Wake-on-LAN for it), and the connection already
 * retries on its own (Iris, 2026-10-26).
 */
private fun barNetwork(link: Link, offer: Boolean): ConnLine = when {
    link == Link.ONLINE -> ConnLine(ConnLink.NETWORK, ConnWords.CONNECTED)
    offer -> ConnLine(ConnLink.NETWORK, ConnWords.MOVED, ConnAction.USE_NEW_ADDRESS)
    else -> when (link) {
        Link.ONLINE -> ConnLine(ConnLink.NETWORK, ConnWords.CONNECTED)
        Link.CONNECTING, Link.ABSENT -> ConnLine(ConnLink.NETWORK, ConnWords.CONNECTING)
        Link.UNREACHABLE, Link.UNAUTHORIZED, Link.NEEDS_PAIRING -> ConnLine(ConnLink.NETWORK, ConnWords.UNREACHABLE)
    }
}

/** The bar's note: only when it is out of reach, so never beside the new-address offer. */
private fun barNote(input: ConnInput, network: ConnLine): ConnNote? = when {
    network.words != ConnWords.UNREACHABLE -> null
    input.barStandby == true -> ConnNote.BAR_OFF_NETWORK_STANDBY_ON
    else -> ConnNote.BAR_OFF_NETWORK
}

/**
 * A Bluetooth link. The phone holds one HID connection at a time and only while the remote is in
 * front and aimed where Bluetooth is needed, so "paired, not connected" is the normal resting
 * state: it reads « prêt », and a computer offers « Contrôler » to bring it up.
 */
private fun bluetooth(input: ConnInput, id: String, address: String?, controllable: Boolean): ConnLine {
    val registered = input.hidPhase == HidPhase.READY || input.hidPhase == HidPhase.CONNECTING || input.hidPhase == HidPhase.CONNECTED
    val words = when {
        input.hidConnectedTo == id -> ConnWords.CONNECTED
        input.hidPhase == HidPhase.BLUETOOTH_OFF -> ConnWords.BLUETOOTH_OFF
        input.hidPhase == HidPhase.NEEDS_PERMISSION -> ConnWords.NEEDS_PERMISSION
        input.hidPhase == HidPhase.BUSY -> ConnWords.BUSY
        // The bonded list is known only while the role is held; stopped, no claim either way.
        address == null || (registered && address !in input.bonded) -> ConnWords.NOT_PAIRED
        input.hidPhase == HidPhase.CONNECTING && input.targetId == id -> ConnWords.CONNECTING
        else -> ConnWords.READY
    }
    val action = when (words) {
        ConnWords.NOT_PAIRED -> ConnAction.PAIR_COMPUTER
        ConnWords.BLUETOOTH_OFF, ConnWords.NEEDS_PERMISSION -> ConnAction.OPEN_DEVICES
        ConnWords.READY -> ConnAction.CONTROL.takeIf { controllable }
        else -> null
    }
    return ConnLine(ConnLink.BLUETOOTH, words, action)
}

/** One line of the sheet: the device it sits under and its link. */
data class ConnFocus(val deviceId: String, val link: ConnLink)

/**
 * The line the device pill's trouble points at (Erwann, 2026-10-25, Iris's top 10, item 6): a tap
 * on a pill that says something is wrong lands on that line, its one next step ready, rather than
 * on Settings in general. Only the TV and the bar, the devices the pill speaks for. Worst first:
 * a fault with a step, any fault, a step not done yet (IP control, no TV), then a link on its way.
 * Nothing when all is well: the sheet then simply opens at the top.
 */
fun problemLine(devices: List<ConnDevice>): ConnFocus? {
    val room = devices.filter { it.kind != DeviceKind.COMPUTER }
    fun pick(match: (ConnDevice, ConnLine) -> Boolean): ConnFocus? =
        room.firstNotNullOfOrNull { d -> d.lines.firstOrNull { match(d, it) }?.let { ConnFocus(d.id, it.link) } }
    return pick { _, l -> l.words.mark == ConnMark.PROBLEM && l.action != null }
        ?: pick { _, l -> l.words.mark == ConnMark.PROBLEM }
        ?: pick { d, l -> l.words == ConnWords.NOT_SET_UP || (l.words == ConnWords.NOT_ADDED && d.kind == DeviceKind.TV) }
        ?: pick { _, l -> l.words.mark == ConnMark.WORKING }
}

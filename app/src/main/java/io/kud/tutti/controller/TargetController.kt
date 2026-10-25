package io.kud.tutti.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.bluetooth.HidPhase
import io.kud.tutti.platform.bluetooth.HidState
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.atv.KeyHold
import io.kud.tutti.protocol.devices.ComputerOs
import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.DeviceLink
import io.kud.tutti.protocol.devices.DeviceList
import io.kud.tutti.protocol.devices.ForegroundLog
import io.kud.tutti.protocol.devices.HoldPlan
import io.kud.tutti.protocol.devices.KeyAvailability
import io.kud.tutti.protocol.devices.KeyRoute
import io.kud.tutti.protocol.devices.ShownOn
import io.kud.tutti.protocol.devices.TargetChoice
import io.kud.tutti.protocol.devices.Targeting
import io.kud.tutti.protocol.hid.HidAction
import io.kud.tutti.protocol.hid.HidRoute
import io.kud.tutti.protocol.hid.KeyLayout
import io.kud.tutti.protocol.hid.KeyStroke
import io.kud.tutti.protocol.hid.MouseStep
import io.kud.tutti.protocol.hid.RemoteLink
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** How a device is reached right now, for the sheet's "link in words". */
enum class DeviceVia { GOOGLE_TV, SONY_IP, BLUETOOTH, NETWORK, NONE }

/** One device as the remote shows it. */
/** [hdmiInput]: the TV input a computer is shown on (a Sony uri), when known. */
data class TargetDevice(val id: String, val name: String, val kind: DeviceKind, val via: DeviceVia, val hdmiInput: String? = null)

/**
 * The remote's target, for the UI (stage 1: one TV, one bar, any number of computers). Keys ask
 * [route]/[availability]; the chip reads [target]; the sheet lists [present].
 */
data class TargetState(
    /** Every configured device the remote can target (never the bar). */
    val devices: List<TargetDevice> = emptyList(),
    /**
     * Devices present now, for the sheet. Stage 1 has no presence yet (Bonjour, inputs: stage 2),
     * so this is [devices]; the sheet shows only when it holds more than one.
     */
    val present: List<TargetDevice> = emptyList(),
    val target: TargetDevice? = null,
    /** The target was chosen by hand; it holds until the TV's input changes. */
    val manual: Boolean = false,
    /** The computer the TV is showing on its input right now, for the now-showing card's « Contrôler <nom> ». */
    val computerOnInput: TargetDevice? = null,
    val tvLanUp: Boolean = false,
    val hidPhase: HidPhase = HidPhase.STOPPED,
    /** The device the HID connection is up with, if any. */
    val hidConnectedTo: String? = null,
    /** Bluetooth addresses the phone is bonded with, as the HID link lists them (empty while it is stopped). */
    val hidBonded: Set<String> = emptySet(),
    /** The link the target's own keys (D-pad, OK, Back) use right now. */
    val link: RemoteLink = RemoteLink.NONE,
    /** Pointer mode can work: Bluetooth up to the target and a gyroscope in the phone. */
    val gyroPossible: Boolean = false,
    val hasGyroscope: Boolean = false,
    /** Typing layout for the target over Bluetooth; null for the TV on its LAN (Remote v2 IME). */
    val layout: KeyLayout? = null,
    /** TV or bar not configured yet: Settings › Devices offers to set them up. */
    val missing: Set<DeviceKind> = emptySet(),
    internal val list: DeviceList = DeviceList(),
) {
    private val targetDevice: Device? get() = target?.let { list[it.id] }

    fun route(v2Key: Int): KeyRoute =
        Targeting.route(v2Key, targetDevice, tvLanUp, hidConnectedTo != null && hidConnectedTo == target?.id)

    fun availability(v2Key: Int): KeyAvailability = route(v2Key).availability
}

/**
 * Who the remote talks to, and how. Stays out of [TuttiController], which keeps the TV and bar
 * engine: this class only routes, through [HidLink] for Bluetooth and [TuttiController] for the TV.
 *
 * The HID role is held only while tutti is in the foreground and the remote is in front, and only
 * when [Targeting.hidHost] names a device; it is released the moment either stops. Never in the
 * background.
 */
@Singleton
class TargetController @Inject constructor(
    @ApplicationContext context: Context,
    private val controller: TuttiController,
    private val store: TuttiStore,
    private val secure: SecureStore,
    private val hid: HidLink,
) {
    private val scope = controller.scope
    private val choice = MutableStateFlow(TargetChoice())
    private val lastComputer = MutableStateFlow<String?>(null)
    private val remoteShown = MutableStateFlow(false)
    private val appForeground = MutableStateFlow(false)
    private val hasGyroscope = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    private val _foreground = MutableStateFlow(ForegroundLog())

    /** AirPlay diagnostic: the TV's recent foreground packages (no personal data), for one mirror test. */
    val foregroundLog: StateFlow<ForegroundLog> = _foreground.asStateFlow()

    private val tvView = controller.state
        .map { Triple(it.tv.input, it.links.tv == Link.ONLINE, it.remote.currentApp) }
        .distinctUntilChanged()

    val state: StateFlow<TargetState> = combine(store.deviceList, tvView, choice, hid.state) { list, (input, lanUp, _), c, h ->
        build(list, input, lanUp, c, h)
    }.stateIn(scope, SharingStarted.Eagerly, TargetState())

    init {
        scope.launch {
            store.migrateDevices()
            secure.migrate()
        }
        scope.launch {
            tvView.collect { (_, _, app) -> _foreground.update { it.seen(app, System.currentTimeMillis()) } }
        }
        scope.launch {
            state.collect { s -> if (s.target?.kind == DeviceKind.COMPUTER) lastComputer.value = s.target.id }
        }
        // The one HID connection follows Targeting.hidHost, only while the remote is in front.
        scope.launch {
            combine(state, remoteShown, appForeground, lastComputer) { s, shown, fg, last ->
                if (!shown || !fg) null else Targeting.hidHost(s.list, s.target?.let { s.list[it.id] }, s.tvLanUp, last)?.bluetooth?.address ?: ""
            }.distinctUntilChanged().collect { address ->
                when (address) {
                    null, "" -> hid.release(HOLDER)
                    else -> {
                        hid.acquire(HOLDER)
                        hid.hold(address)
                    }
                }
            }
        }
    }

    private fun build(list: DeviceList, input: String?, lanUp: Boolean, c: TargetChoice, h: HidState): TargetState {
        val connected = h.host?.address?.takeIf { h.phase == HidPhase.CONNECTED }
        val connectedTo = list.devices.firstOrNull { connected != null && it.bluetooth?.address == connected }?.id
        fun view(d: Device): TargetDevice = TargetDevice(d.id, d.name, d.kind, via(d, lanUp, connectedTo), d.shownOn?.hdmiInput)
        val target = Targeting.current(c, input, list)
        val link = when {
            target == null -> RemoteLink.NONE
            else -> HidRoute.link(target.kind.hidTarget ?: return@build TargetState(list = list), lanUp, connectedTo == target.id)
        }
        val targets = Targeting.targets(list).map(::view)
        return TargetState(
            devices = targets,
            present = targets,
            target = target?.let(::view),
            manual = c.manual != null && target?.id == c.manual,
            computerOnInput = Targeting.computerOnInput(input, list)?.let(::view),
            tvLanUp = lanUp,
            hidPhase = h.phase,
            hidConnectedTo = connectedTo,
            hidBonded = h.bonded.mapTo(mutableSetOf()) { it.address },
            link = link,
            gyroPossible = HidRoute.gyroPossible(link, hasGyroscope),
            hasGyroscope = hasGyroscope,
            layout = if (link == RemoteLink.BLUETOOTH) target?.layout() else null,
            missing = setOfNotNull(DeviceKind.TV.takeIf { list.tv == null }, DeviceKind.SOUNDBAR.takeIf { list.bar == null }),
            list = list,
        )
    }

    private fun via(d: Device, lanUp: Boolean, connectedTo: String?): DeviceVia = when {
        d.kind == DeviceKind.SOUNDBAR -> DeviceVia.NETWORK
        d.kind == DeviceKind.TV && lanUp -> DeviceVia.GOOGLE_TV
        connectedTo == d.id -> DeviceVia.BLUETOOTH
        else -> DeviceVia.NONE
    }

    // region remote

    /** The remote is on screen (true) or not. With [appForeground], decides whether HID is held. */
    fun remoteShown(shown: Boolean) {
        remoteShown.value = shown
    }

    /** tutti came to the foreground or left it (MainActivity onStart/onStop). */
    fun appForeground(foreground: Boolean) {
        appForeground.value = foreground
    }

    /**
     * "Show on the TV": wake the TV if it sleeps, switch to the computer's input, then point the
     * remote at it. The target is set after the switch, since a choice made before it would be
     * dropped by the input change it holds against.
     */
    fun showOnTv(id: String) {
        val input = state.value.devices.firstOrNull { it.id == id }?.hdmiInput ?: return
        scope.launch { if (controller.showOnTv(input)) setTarget(id) }
    }

    /** The starter scene for a computer just added with its input, under [name] in the phone's language. */
    fun addComputerScene(computerId: String, name: String, input: String) =
        controller.saveScene(io.kud.tutti.protocol.scenes.StarterScenes.computerOnTv(computerId, name, input))

    /** A hand-picked target, holding until the TV's input changes. Null goes back to following the TV. */
    fun setTarget(id: String?) {
        choice.value = if (id == null) TargetChoice() else TargetChoice(id, controller.state.value.tv.input)
    }

    /**
     * Sends one remote key where it belongs, and returns the route so the UI can bump a key that
     * went nowhere and say why. TV-meaning keys go to the TV over its LAN whatever the target.
     */
    fun key(v2Key: Int): KeyRoute {
        val route = state.value.route(v2Key)
        if (route.availability != KeyAvailability.AVAILABLE) return route
        when (route.link) {
            RemoteLink.LAN -> controller.key(v2Key)
            RemoteLink.BLUETOOTH -> when (val action = route.action) {
                is HidAction.Key -> hid.type(listOf(action.stroke))
                is HidAction.Consumer -> hid.consumer(action.usage)
                null -> Unit
            }
            RemoteLink.NONE -> Unit
        }
        return route
    }

    /**
     * OK held on the pad, wherever it belongs ([Targeting.hold]): the TV on its LAN holds the
     * remote key, the TV over Bluetooth holds the keyboard report, a computer gets one plain
     * press. Returns the route, so the UI bumps a hold that went nowhere as it does a tap.
     */
    fun holdKey(v2Key: Int): KeyRoute {
        val route = state.value.route(v2Key)
        when (Targeting.hold(route)) {
            HoldPlan.REMOTE -> {
                heldOn = HoldPlan.REMOTE
                controller.holdKey(v2Key)
            }
            HoldPlan.HID -> {
                val stroke = (route.action as HidAction.Key).stroke
                heldOn = HoldPlan.HID
                scope.launch(start = CoroutineStart.UNDISPATCHED) { hidOrder.withLock { hidHoldNow(stroke) } }
            }
            HoldPlan.PLAIN -> key(v2Key)
            HoldPlan.NONE -> Unit
        }
        return route
    }

    /** The finger left: whatever [holdKey] put down comes up. */
    fun releaseKey() {
        when (heldOn) {
            HoldPlan.REMOTE -> controller.releaseKey()
            HoldPlan.HID -> scope.launch(start = CoroutineStart.UNDISPATCHED) { hidOrder.withLock { hidReleaseNow() } }
            else -> Unit
        }
        heldOn = null
    }

    private var heldOn: HoldPlan? = null
    private val hidOrder = Mutex()
    private var hidSince: Long? = null
    private var hidCap: Job? = null

    private suspend fun hidHoldNow(stroke: KeyStroke) {
        hidReleaseNow()
        hid.press(stroke)
        val since = SystemClock.uptimeMillis()
        hidSince = since
        hidCap = scope.launch {
            delay(KeyHold.MAX_HOLD_MS)
            hidOrder.withLock {
                if (hidSince == since) {
                    hidCap = null
                    hidReleaseNow()
                }
            }
        }
    }

    private suspend fun hidReleaseNow() {
        val since = hidSince ?: return
        hidSince = null
        hidCap?.cancel()
        hidCap = null
        delay(KeyHold.releaseDelay(since, SystemClock.uptimeMillis()))
        hid.releaseKeys()
    }

    private var typed = ""

    /** The phone keyboard opened on an empty field: forget what was typed before. */
    fun textReset() {
        typed = ""
    }

    /**
     * The phone field's whole text, on every change. Over the LAN the TV gets it as is; over
     * Bluetooth it becomes erases and keystrokes in the target's layout. Returns the characters
     * the layout can't type (said, never guessed).
     */
    fun text(text: String): Set<Char> {
        val s = state.value
        val layout = s.layout
        if (layout == null) {
            controller.text(text)
            typed = text
            return emptySet()
        }
        val edit = Targeting.textEdit(typed, text, layout)
        typed = text
        if (edit.strokes.isNotEmpty()) hid.type(edit.strokes)
        return edit.unsupported
    }

    private val hidLive: Boolean get() = state.value.link == RemoteLink.BLUETOOTH

    /** Pointer and touchpad motion, to the target over Bluetooth. */
    fun move(steps: List<MouseStep>, buttons: Int = 0) {
        if (hidLive) hid.move(steps, buttons)
    }

    /** Scroll strip: positive is up, as HID's wheel. */
    fun scroll(wheel: Int) {
        if (hidLive && wheel != 0) hid.move(listOf(MouseStep(0, 0, wheel)))
    }

    /** [button]: 1 left, 2 right, 4 middle (HID bits). */
    fun click(button: Int) {
        if (hidLive) hid.click(button)
    }

    // endregion

    // region settings › devices

    /** Bonded Bluetooth hosts that look like computers, for "add a computer" (needs the role held to refresh). */
    val bonded: StateFlow<List<io.kud.tutti.platform.bluetooth.HidHost>> =
        hid.state.map { s -> s.bonded.filter { it.isComputer || !it.isTv } }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Adds a computer and returns its id. [hdmiInput]: the TV input it is shown on (a Sony uri), if
     * any; AirPlay is offered for a computer only. The layout starts at the system's default.
     */
    suspend fun addComputer(name: String, address: String, os: ComputerOs, hdmiInput: String?): String {
        val id = "computer-" + UUID.randomUUID().toString().take(8)
        store.updateDeviceList {
            it.put(Device(id, name, DeviceKind.COMPUTER, listOf(DeviceLink.Bluetooth(address, os.defaultLayout.name)), ShownOn(hdmiInput, airplay = os.airplay), os))
        }
        return id
    }

    suspend fun setComputerInput(id: String, hdmiInput: String?) =
        store.updateDeviceList { list -> list.update(id) { it.copy(shownOn = (it.shownOn ?: ShownOn()).copy(hdmiInput = hdmiInput)) } }

    suspend fun setLayout(id: String, layout: KeyLayout) = store.updateDeviceList { list ->
        list.update(id) { d -> d.bluetooth?.let { d.withLink(it.copy(layout = layout.name)) } ?: d }
    }

    suspend fun rename(id: String, name: String) = store.updateDeviceList { list -> list.update(id) { it.copy(name = name.trim().ifEmpty { it.name }) } }

    /** "Pair again": point the HID connection at this device now (the role held by Settings meanwhile). */
    fun pairAgain(id: String) {
        val address = state.value.list[id]?.bluetooth?.address ?: return
        hid.acquire(SETTINGS)
        hid.hold(address)
    }

    /** Settings › Devices left: give the role back unless the remote holds it. */
    fun settingsLeft() = hid.release(SETTINGS)

    /** Settings › Devices opened: hold the role so bonded hosts can be listed and paired. */
    fun settingsShown() = hid.acquire(SETTINGS)

    /** "Forget": the device, its secrets, and the HID connection if it was the one. */
    suspend fun forget(id: String) {
        val kind = state.value.list[id]?.kind
        if (state.value.hidConnectedTo == id) hid.disconnect()
        if (choice.value.manual == id) choice.value = TargetChoice()
        store.updateDeviceList { it.remove(id) }
        secure.forgetDevice(id)
        Log.i(TAG, "forgot a ${kind ?: "device"}")
    }

    // endregion

    private companion object {
        const val TAG = "tutti-target"
        const val HOLDER = "remote"
        const val SETTINGS = "settings-devices"
    }
}

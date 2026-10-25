package io.kud.tutti.platform.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.protocol.hid.ConsumerUsage
import io.kud.tutti.protocol.hid.HidDescriptor
import io.kud.tutti.protocol.hid.HidReports
import io.kud.tutti.protocol.hid.KeyStroke
import io.kud.tutti.protocol.hid.MouseStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/** Whether this phone lets an app act as a Bluetooth keyboard and mouse at all. */
enum class HidSupport { UNKNOWN, SUPPORTED, UNSUPPORTED }

/** Where the link stands. One meaning each, so the screen can give every state a way forward. */
enum class HidPhase {
    /** Not registered: the Bluetooth screen isn't open. */
    STOPPED,
    NEEDS_PERMISSION,
    BLUETOOTH_OFF,
    REGISTERING,

    /** Android refused the registration: another app holds the keyboard-and-mouse role. */
    BUSY,

    /** Registered, no TV connected: pair from the TV, or reconnect a known one. */
    READY,
    CONNECTING,
    CONNECTED,
}

data class HidHost(val address: String, val name: String, val isTv: Boolean, val isComputer: Boolean = false)

data class HidState(
    val support: HidSupport = HidSupport.UNKNOWN,
    val phase: HidPhase = HidPhase.STOPPED,
    /** The connected TV, or the one tutti will reconnect to. */
    val host: HidHost? = null,
    val bonded: List<HidHost> = emptyList(),
)

/**
 * Bluetooth mode: the phone as a keyboard and mouse paired straight to the TV, through Android's
 * `BluetoothHidDevice` profile (API 28+). Reports are built in `:protocol` (`HidReports`); this
 * class only registers, connects and sends.
 *
 * Android lets one app at a time hold the HID device role, so tutti registers only while the
 * Bluetooth screen is in front ([start]) and unregisters when it leaves ([stop]): it never holds
 * the profile in the background, and no foreground service keeps it. A refusal reads [HidPhase.BUSY].
 * The TV it last talked to is remembered (an address, not a secret) so the next [start] reconnects.
 */
@Singleton
@SuppressLint("MissingPermission") // Every call that needs BLUETOOTH_CONNECT is behind hasConnect().
class HidLink @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(HidState())
    val state: StateFlow<HidState> = _state.asStateFlow()

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val sends = Executors.newSingleThreadExecutor { Thread(it, "tutti-hid") }
    private val prefs = context.getSharedPreferences("bluetooth_hid", Context.MODE_PRIVATE)

    private var proxy: BluetoothHidDevice? = null
    private var wanted = false
    private var connected: BluetoothDevice? = null

    /** Who wants the role now: the Bluetooth screen, the remote in front. Registered while any does. */
    private val holders = mutableSetOf<String>()

    /** The host the remote wants connected ([hold]); null leaves the choice to the last one used. */
    @Volatile private var desired: String? = null

    init {
        if (adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            _state.update { it.copy(support = HidSupport.UNSUPPORTED) }
        }
    }

    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    fun hasConnect(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Asks for the profile proxy and lets it go again, to learn whether the ROM offers the HID
     * device role. Some ROMs never answer; after [PROBE_MS] that counts as unsupported.
     */
    fun probe() {
        val adapter = adapter ?: return
        if (_state.value.support != HidSupport.UNKNOWN || proxy != null) return
        val asked = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
                _state.update { it.copy(support = HidSupport.SUPPORTED) }
                if (proxy == null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, p)
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }, BluetoothProfile.HID_DEVICE)
        if (!asked) {
            _state.update { it.copy(support = HidSupport.UNSUPPORTED) }
            return
        }
        main.postDelayed({
            if (_state.value.support == HidSupport.UNKNOWN) _state.update { it.copy(support = HidSupport.UNSUPPORTED) }
        }, PROBE_MS)
    }

    /** The Bluetooth screen came to the front. */
    fun start() = acquire(SCREEN)

    /** The Bluetooth screen left. */
    fun stop() = release(SCREEN)

    /** [tag] wants the HID role (the remote in front, the Bluetooth screen). */
    @Synchronized
    fun acquire(tag: String) {
        holders += tag
        register()
    }

    /** [tag] no longer needs it; the role is given back once nobody does. Never kept in the background. */
    @Synchronized
    fun release(tag: String) {
        holders -= tag
        if (holders.isEmpty()) unregister()
    }

    /**
     * Points the one HID connection at [address] (a device's Bluetooth link): disconnects the
     * current host first, then connects, since Android keeps one host at a time. Null keeps what
     * is connected. Effective only while the role is held.
     */
    fun hold(address: String?) {
        desired = address
        val current = connected
        when {
            address == null || proxy == null -> Unit
            current?.address == address -> Unit
            current != null -> disconnect() // onConnectionStateChanged reconnects to [desired].
            _state.value.phase == HidPhase.CONNECTING && _state.value.host?.address == address -> Unit
            else -> reconnect(address)
        }
    }

    private fun register() {
        wanted = true
        val adapter = adapter ?: return
        if (!hasConnect()) return _state.update { it.copy(phase = HidPhase.NEEDS_PERMISSION) }
        refreshBonded()
        if (!adapter.isEnabled) return _state.update { it.copy(phase = HidPhase.BLUETOOTH_OFF) }
        if (proxy != null) return
        _state.update { it.copy(phase = HidPhase.REGISTERING) }
        val asked = adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
        if (!asked) _state.update { it.copy(support = HidSupport.UNSUPPORTED, phase = HidPhase.STOPPED) }
    }

    /** Nobody needs the role: give it back so another app (or none) can have it. */
    private fun unregister() {
        wanted = false
        val p = proxy ?: return _state.update { it.copy(phase = HidPhase.STOPPED) }
        if (hasConnect()) runCatching { p.unregisterApp() }
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, p)
        proxy = null
        connected = null
        _state.update { it.copy(phase = HidPhase.STOPPED) }
    }

    fun reconnect(address: String? = _state.value.host?.address ?: prefs.getString(KEY_HOST, null)) {
        val p = proxy ?: return
        val address = address ?: return
        if (!hasConnect() || !BluetoothAdapter.checkBluetoothAddress(address)) return
        val device = adapter?.getRemoteDevice(address) ?: return
        _state.update { it.copy(phase = HidPhase.CONNECTING, host = host(device)) }
        if (!p.connect(device)) _state.update { it.copy(phase = HidPhase.READY) }
    }

    fun disconnect() {
        val p = proxy ?: return
        val device = connected ?: return
        if (hasConnect()) p.disconnect(device)
    }

    /** Forget the remembered TV. Removing the pairing itself is Android's: Settings › Bluetooth. */
    fun forget() {
        disconnect()
        prefs.edit().remove(KEY_HOST).apply()
        _state.update { it.copy(host = null) }
    }

    fun type(strokes: List<KeyStroke>) = send { p, d ->
        for (stroke in strokes) {
            p.sendReport(d, HidDescriptor.KEYBOARD_ID, HidReports.keyboard(stroke.modifiers, listOf(stroke.usage)))
            p.sendReport(d, HidDescriptor.KEYBOARD_ID, HidReports.keyboardReleased())
        }
    }

    fun key(usage: Int) = type(listOf(KeyStroke(usage)))

    /** [stroke] goes down and stays down, as a held key, until [releaseKeys]. */
    fun press(stroke: KeyStroke) = send { p, d ->
        p.sendReport(d, HidDescriptor.KEYBOARD_ID, HidReports.keyboard(stroke.modifiers, listOf(stroke.usage)))
    }

    /** Every keyboard key comes up. */
    fun releaseKeys() = send { p, d -> p.sendReport(d, HidDescriptor.KEYBOARD_ID, HidReports.keyboardReleased()) }

    fun move(steps: List<MouseStep>, buttons: Int = 0) {
        if (steps.isEmpty()) return
        send { p, d -> steps.forEach { p.sendReport(d, HidDescriptor.MOUSE_ID, HidReports.mouse(buttons, it.dx, it.dy, it.wheel)) } }
    }

    fun click(button: Int) = send { p, d ->
        p.sendReport(d, HidDescriptor.MOUSE_ID, HidReports.mouse(button))
        p.sendReport(d, HidDescriptor.MOUSE_ID, HidReports.mouse(0))
    }

    fun consumer(usage: ConsumerUsage) = send { p, d ->
        p.sendReport(d, HidDescriptor.CONSUMER_ID, HidReports.consumer(usage.usage))
        p.sendReport(d, HidDescriptor.CONSUMER_ID, HidReports.consumer(0))
    }

    private fun send(block: (BluetoothHidDevice, BluetoothDevice) -> Unit) {
        val p = proxy ?: return
        val d = connected ?: return
        if (!hasConnect()) return
        sends.execute { runCatching { block(p, d) } }
    }

    private fun refreshBonded() {
        val devices = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        _state.update { s -> s.copy(bonded = devices.map(::host).sortedByDescending { it.isTv }) }
    }

    private fun host(device: BluetoothDevice): HidHost {
        val name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: device.address
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        return HidHost(device.address, name, major == BluetoothClass.Device.Major.AUDIO_VIDEO, major == BluetoothClass.Device.Major.COMPUTER)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (!registered) {
                connected = null
                // Refused while we wanted it: another app holds the role (or the stack said no).
                _state.update { it.copy(phase = if (wanted && proxy != null) HidPhase.BUSY else HidPhase.STOPPED) }
                return
            }
            _state.update { it.copy(support = HidSupport.SUPPORTED, phase = HidPhase.READY) }
            val target = desired ?: pluggedDevice?.address ?: prefs.getString(KEY_HOST, null)
            if (target != null) reconnect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connected = device
                    prefs.edit().putString(KEY_HOST, device.address).apply()
                    _state.update { it.copy(phase = HidPhase.CONNECTED, host = host(device)) }
                    refreshBonded()
                }
                BluetoothProfile.STATE_CONNECTING -> _state.update { it.copy(phase = HidPhase.CONNECTING, host = host(device)) }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connected == device) connected = null
                    _state.update { if (it.phase == HidPhase.STOPPED || it.phase == HidPhase.BUSY) it else it.copy(phase = HidPhase.READY) }
                    // A host switch: the old one is gone, now the one the remote wants.
                    val next = desired
                    if (next != null && next != device.address && proxy != null && connected == null) main.post { reconnect(next) }
                }
            }
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            val hid = p as? BluetoothHidDevice ?: return
            if (!wanted) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
                return
            }
            proxy = hid
            val sdp = BluetoothHidDeviceAppSdpSettings(
                HidDescriptor.NAME,
                HidDescriptor.DESCRIPTION,
                HidDescriptor.PROVIDER,
                HidDescriptor.SUBCLASS_COMBO,
                HidDescriptor.bytes(),
            )
            val ok = hasConnect() && runCatching { hid.registerApp(sdp, null, null, sends, callback) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(support = HidSupport.SUPPORTED, phase = HidPhase.BUSY) }
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
            connected = null
            _state.update { it.copy(phase = HidPhase.STOPPED) }
        }
    }

    private companion object {
        const val KEY_HOST = "host"
        const val SCREEN = "bluetooth-screen"
        const val PROBE_MS = 3_000L
    }
}

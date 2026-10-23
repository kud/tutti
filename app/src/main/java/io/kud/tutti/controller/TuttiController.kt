package io.kud.tutti.controller

import android.app.UiModeManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.MacroStep
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.platform.net.PinMismatchException
import io.kud.tutti.platform.security.ClientIdentityStore
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.bravia.TvApp
import java.io.File
import io.kud.tutti.protocol.atv.AtvPairing
import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.atv.ClientDeviceInfo
import io.kud.tutti.protocol.atv.HardwareKey
import io.kud.tutti.protocol.atv.KeyMap
import io.kud.tutti.protocol.atv.PairingFailure
import io.kud.tutti.protocol.atv.PairingResult
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.BraviaService
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.bravia.getOrNull
import io.kud.tutti.protocol.grpc.SonyCloudPlane
import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.Redact
import io.kud.tutti.protocol.scenes.AppProfile
import io.kud.tutti.protocol.scenes.AppProfiles
import io.kud.tutti.protocol.scenes.DeviceSnapshot
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneActuator
import io.kud.tutti.protocol.scenes.SceneEngine
import io.kud.tutti.protocol.scenes.SceneKey
import io.kud.tutti.protocol.scenes.SceneMatch
import io.kud.tutti.protocol.scenes.SceneRunner
import io.kud.tutti.protocol.scenes.SceneStep
import io.kud.tutti.protocol.scenes.SceneValue
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreClient
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState
import io.kud.tutti.platform.voice.VoiceCapture
import io.kud.tutti.protocol.atv.VoiceEvent
import io.kud.tutti.protocol.atv.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * The one owner of the three device sessions (remote v2, BRAVIA REST, soundbar TCP).
 *
 * Every action returns at once: the reducers update state before the network, so the UI
 * (and the caller's haptic) responds within a frame. Sessions run while at least one
 * holder has called [retain], and linger [LINGER_MS] after the last [release].
 *
 * Hooks for the widget, Quick Settings tile and media notification: inject this singleton,
 * call [retain]/[release] around their lifetime, read [state] and [events], and use
 * [power], [setVolume], [volumeStep], [toggleMute], [applyScene] and [run].
 */
@Singleton
class TuttiController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: TuttiStore,
    private val secure: SecureStore,
    private val identities: ClientIdentityStore,
    private val tls: io.kud.tutti.protocol.net.TlsConnector,
    tcp: io.kud.tutti.protocol.net.TcpConnector,
    val cloud: SonyCloudPlane,
) {
    /** Application-lifetime scope; widgets may launch into it. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val theatre = TheatreClient(tcp, scope)
    private val atv = AtvRemote(tls, scope, ClientDeviceInfo(model = "Tutti", packageName = context.packageName))

    @Volatile private var psk: String? = null
    @Volatile private var bravia: BraviaClient? = null
    @Volatile private var braviaTransport: OkHttpBraviaTransport? = null

    /** Official icons, cached by package in the app cache dir. */
    private val iconDir: File get() = File(context.cacheDir, "app-icons")
    private val icons = MutableStateFlow<Map<String, String>>(emptyMap())
    private val library = combine(store.apps, icons) { seen, cached -> AppLibrary.all(seen) to cached }

    private val links = MutableStateFlow(Links())
    private val tv = MutableStateFlow(TvState())
    private val voiceZoom = MutableStateFlow(false)
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val activeSceneId = MutableStateFlow<String?>(null)
    private val pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    private val overrides = MutableStateFlow(Overrides())
    private val restart = MutableStateFlow(0)
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val volumeTarget = MutableStateFlow<Int?>(null)

    private val mutableEvents = MutableSharedFlow<TuttiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TuttiEvent> = mutableEvents.asSharedFlow()

    private data class Overrides(val volume: Int? = null, val powered: Boolean? = null)

    private data class Stored(
        val devices: io.kud.tutti.platform.data.Devices,
        val prefs: io.kud.tutti.platform.data.AppPrefs,
        val dock: DockConfig,
        val scenes: List<Scene>,
        val profiles: List<AppProfile>,
    )

    private data class Live(
        val tv: TvState,
        val remote: io.kud.tutti.protocol.atv.RemoteState,
        val bar: TheatreState,
        val links: Links,
        val voiceZoom: Boolean,
    )

    private data class Extra(
        val progress: Map<String, Float>,
        val activeSceneId: String?,
        val pairing: PairingUi,
        val cloud: io.kud.tutti.protocol.grpc.CloudPlaneState,
        val overrides: Overrides,
    )

    private val stored = combine(store.devices, store.prefs, store.dock, store.scenes, store.profiles) { d, p, k, s, f -> Stored(d, p, k, s, f) }
    private val live = combine(tv, atv.state, theatre.state, links, voiceZoom) { t, r, b, l, v -> Live(t, r, b, l, v) }
    private val extra = combine(progress, activeSceneId, pairing, cloud.state, overrides) { p, a, q, c, o -> Extra(p, a, q, c, o) }

    val state: StateFlow<TuttiUiState> = combine(stored, live, extra, library) { s, l, e, a ->
        build(s, l, e).copy(apps = a.first, appIcons = a.second)
    }
        .stateIn(scope, SharingStarted.Eagerly, TuttiUiState())

    /** Set by the remote screen while it is in front: volume rocker and hardware keyboard go to the devices. */
    @Volatile var remoteInFront: Boolean = false

    /** Set while the keyboard mirror field has focus: printable hardware keys stay in the field. */
    @Volatile var typing: Boolean = false

    private val lock = Any()
    private var holders = 0
    private var sessionsJob: Job? = null
    private var lingerJob: Job? = null
    private var sceneJob: Job? = null
    private var pairingJob: Job? = null
    private var overrideJob: Job? = null
    @Volatile private var codeAnswer: CompletableDeferred<String?>? = null

    init {
        scope.launch(Dispatchers.IO) {
            val cached = iconDir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }
            icons.update { current -> cached.associate { it.name to it.path } + current }
        }
        scope.launch {
            volumeTarget.filterNotNull().conflate().collect { level ->
                theatre.setLevel(TheatreFeature.VOLUME, level)
                delay(VOLUME_SEND_INTERVAL_MS)
            }
        }
        scope.launch {
            // The mic closes the moment the session stops wanting audio: release, cap, failure, drop.
            atv.voice.map { it.capturing }.distinctUntilChanged().collect { capturing ->
                if (!capturing) micJob?.cancel()
            }
        }
        scope.launch {
            // Per-app profiles: "Quand Netflix est au premier plan, appliquer Cinéma" (while connected).
            atv.state.map { it.currentApp }.distinctUntilChanged().filterNotNull().collect { app ->
                val profiles = store.profiles.first()
                val sceneId = AppProfiles.sceneFor(app, profiles) ?: return@collect
                if (sceneId != activeSceneId.value) applyScene(sceneId)
            }
        }
        runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    wake.tryEmit(Unit)
                }
            })
        }
    }

    // region lifecycle

    /** Starts (or keeps) the sessions. Pair every call with [release]. */
    fun retain() {
        synchronized(lock) {
            holders++
            lingerJob?.cancel()
            lingerJob = null
            if (sessionsJob?.isActive != true) sessionsJob = scope.launch { runSessions() }
        }
        wake.tryEmit(Unit)
    }

    fun release() {
        synchronized(lock) {
            holders = (holders - 1).coerceAtLeast(0)
            if (holders > 0) return
            lingerJob?.cancel()
            lingerJob = scope.launch {
                delay(LINGER_MS)
                stopIfIdle()
            }
        }
    }

    private fun stopIfIdle() {
        synchronized(lock) {
            if (holders > 0) return
            sessionsJob?.cancel()
            sessionsJob = null
        }
    }

    private suspend fun runSessions() {
        psk = secure.tvPsk()
        combine(store.devices, restart) { devices, _ -> devices }.collectLatest { devices ->
            coroutineScope {
                launch { barLoop(devices.bar) }
                launch { tvRemoteLoop(devices.tv) }
                launch { tvRestLoop(devices.tv) }
            }
        }
    }

    private suspend fun backoff(millis: Long) {
        withTimeoutOrNull(millis) { wake.first() }
    }

    private suspend fun barLoop(bar: BarDevice?) {
        if (bar == null) {
            links.update { it.copy(bar = Link.ABSENT) }
            return
        }
        var wait = MIN_BACKOFF_MS
        try {
            while (scope.isActive) {
                links.update { it.copy(bar = Link.CONNECTING) }
                val connected = try {
                    theatre.connect(bar.host)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (connected) {
                    links.update { it.copy(bar = Link.ONLINE) }
                    wait = MIN_BACKOFF_MS
                    coroutineScope {
                        val probe = launch { voiceZoom.value = theatre.probe(TheatreFeature.VOICE_ZOOM) }
                        val poll = launch {
                            // DRC and Auto Volume never notify; polling also proves the link is alive.
                            while (isActive) {
                                delay(POLL_BAR_MS)
                                theatre.get(TheatreFeature.DRC)
                                theatre.get(TheatreFeature.AUTO_VOLUME)
                                theatre.get(TheatreFeature.POWER)
                            }
                        }
                        theatre.state.first { !it.connected }
                        probe.cancel()
                        poll.cancel()
                    }
                }
                links.update { it.copy(bar = Link.UNREACHABLE) }
                backoff(wait)
                wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        } finally {
            theatre.disconnect()
        }
    }

    private suspend fun tvRemoteLoop(device: TvDevice?) {
        if (device == null) {
            links.update { it.copy(tv = Link.ABSENT) }
            return
        }
        val pin = secure.tvRemotePin()
        if (pin == null) {
            links.update { it.copy(tv = Link.NEEDS_PAIRING) }
            return
        }
        val identity = identities.getOrCreate()
        var wait = MIN_BACKOFF_MS
        var handshakeFailures = 0
        try {
            while (scope.isActive) {
                links.update { it.copy(tv = Link.CONNECTING) }
                val outcome = try {
                    atv.connect(device.host, identity, pin)
                    if (withTimeoutOrNull(READY_TIMEOUT_MS) { atv.state.first { it.ready } } != null) Link.ONLINE else Link.UNREACHABLE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when {
                        e.hasCause<PinMismatchException>() -> Link.NEEDS_PAIRING
                        e is ConnectException || e is SocketTimeoutException || e is NoRouteToHostException -> Link.UNREACHABLE
                        e is SSLException -> if (++handshakeFailures >= 2) Link.NEEDS_PAIRING else Link.UNREACHABLE
                        else -> Link.UNREACHABLE
                    }
                }
                if (outcome == Link.NEEDS_PAIRING) {
                    atv.disconnect()
                    links.update { it.copy(tv = Link.NEEDS_PAIRING) }
                    return
                }
                if (outcome == Link.ONLINE) {
                    handshakeFailures = 0
                    wait = MIN_BACKOFF_MS
                    links.update { it.copy(tv = Link.ONLINE) }
                    atv.state.first { !it.ready }
                }
                atv.disconnect()
                links.update { it.copy(tv = Link.UNREACHABLE) }
                backoff(wait)
                wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        } finally {
            atv.disconnect()
        }
    }

    private suspend fun tvRestLoop(device: TvDevice?) {
        if (device == null) {
            bravia = null
            links.update { it.copy(rest = Link.ABSENT) }
            tv.value = TvState()
            return
        }
        val transport = OkHttpBraviaTransport(device.host, secure.tvRestPin()) { secure.setTvRestPin(it) }
        val client = BraviaClient(transport, psk = { psk })
        braviaTransport = transport
        bravia = client
        var wait = MIN_BACKOFF_MS
        while (scope.isActive) {
            links.update { it.copy(rest = Link.CONNECTING) }
            when (val found = client.discover()) {
                is BraviaResult.Ok -> {
                    wait = MIN_BACKOFF_MS
                    links.update { it.copy(rest = Link.ONLINE) }
                    tv.update { it.copy(capabilities = found.value) }
                    while (scope.isActive) {
                        if (!refreshTv(client)) break
                        delay(POLL_TV_MS)
                    }
                    links.update { it.copy(rest = Link.UNREACHABLE) }
                }
                is BraviaResult.Failure -> {
                    val link = if (found.error == BraviaError.Unauthorized) Link.UNAUTHORIZED else Link.UNREACHABLE
                    links.update { it.copy(rest = link) }
                    // A wrong PSK will not fix itself: wait for a new one (restart) or a long while.
                    if (link == Link.UNAUTHORIZED) wait = MAX_BACKOFF_MS
                }
            }
            backoff(wait)
            wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** False when the TV stopped answering. */
    private suspend fun refreshTv(client: BraviaClient): Boolean {
        val power = when (val result = client.powerStatus()) {
            is BraviaResult.Ok -> result.value
            is BraviaResult.Failure -> if (result.error is BraviaError.Transport) return false else PowerStatus.UNKNOWN
        }
        val caps = client.capabilities
        val active = power == PowerStatus.ACTIVE
        val input = if (active) client.playingContent().getOrNull()?.uri else null
        val pictureMode = if (active && caps.supports(BraviaService.VIDEO, "getPictureQualitySettings")) {
            client.pictureSettings("pictureMode").getOrNull()?.firstOrNull { it.target == "pictureMode" }?.currentValue
        } else {
            null
        }
        val current = tv.value
        val inputs = if (current.inputs.isEmpty() && active) client.externalInputs().getOrNull().orEmpty() else current.inputs
        val apps = if (current.apps.isEmpty() && active) client.applications().getOrNull().orEmpty() else current.apps
        tv.update { it.copy(power = power, input = input ?: it.input, pictureMode = pictureMode ?: it.pictureMode, inputs = inputs, apps = apps) }
        if (current.apps.isEmpty() && apps.isNotEmpty()) scope.launch { rememberApps(apps) }
        return true
    }

    // endregion

    // region state

    private fun build(s: Stored, l: Live, e: Extra): TuttiUiState {
        val snapshot = snapshotOf(l.tv, l.remote, l.bar)
        val active = e.activeSceneId
        val scenes = s.scenes.map { scene ->
            val running = e.progress[scene.id]
            if (scene.id != active) {
                SceneUi(scene, SceneStatus.IDLE, running)
            } else {
                when (val match = SceneEngine.match(scene, snapshot)) {
                    SceneMatch.Matches -> SceneUi(scene, SceneStatus.ACTIVE, running)
                    is SceneMatch.Modified -> SceneUi(scene, SceneStatus.MODIFIED, running, match.keys)
                }
            }
        }
        val cap = s.scenes.firstOrNull { it.id == active }?.settings?.get(SceneKey.VOLUME_CAP)?.let { (it as? SceneValue.Level)?.value }
        val tvPowered = l.remote.powered ?: when (l.tv.power) {
            PowerStatus.ACTIVE -> true
            PowerStatus.STANDBY -> false
            PowerStatus.UNKNOWN -> null
        }
        val barPowered = l.bar.isOn(TheatreFeature.POWER)
        val powered = e.overrides.powered ?: when {
            tvPowered == true || barPowered == true -> true
            tvPowered == false || barPowered == false -> false
            else -> null
        }
        return TuttiUiState(
            loaded = true,
            devices = s.devices,
            prefs = s.prefs,
            dock = s.dock,
            links = l.links,
            tv = l.tv,
            remote = l.remote,
            bar = l.bar,
            voiceZoomAvailable = l.voiceZoom,
            scenes = scenes,
            profiles = s.profiles,
            volume = e.overrides.volume ?: l.bar.level(TheatreFeature.VOLUME),
            volumeCap = cap,
            powered = powered,
            pairing = e.pairing,
            cloud = e.cloud,
        )
    }

    private fun snapshotOf(tv: TvState, remote: io.kud.tutti.protocol.atv.RemoteState, bar: TheatreState): DeviceSnapshot {
        val tvPowered = remote.powered ?: when (tv.power) {
            PowerStatus.ACTIVE -> true
            PowerStatus.STANDBY -> false
            PowerStatus.UNKNOWN -> null
        }
        return DeviceSnapshot.from(
            theatre = bar.takeIf { it.connected },
            tvInput = tv.input,
            currentApp = remote.currentApp,
            pictureMode = tv.pictureMode,
            tvPowered = tvPowered,
        )
    }

    private fun snapshot(): DeviceSnapshot = snapshotOf(tv.value, atv.state.value, theatre.state.value)

    // endregion

    // region remote

    fun key(keyCode: Int) {
        scope.launch { atv.sendKey(keyCode) }
    }

    /** Long-press Select is reserved for the TV: a held key. */
    fun longPress(keyCode: Int) {
        scope.launch { atv.longPress(keyCode) }
    }

    /** Replaces the TV field's text (each keystroke, live). */
    fun text(text: String) {
        scope.launch { atv.sendText(text) }
    }

    fun dismissKeyboard() = atv.dismissIme()

    // region voice

    private val mic = VoiceCapture(context)
    private var micJob: Job? = null

    /** Hold-to-talk to the TV's assistant. The mic is open only while [VoiceState.capturing]. */
    val voice: StateFlow<VoiceState> get() = atv.voice
    val voiceEvents: SharedFlow<VoiceEvent> get() = atv.voiceEvents

    fun micGranted(): Boolean = mic.granted()

    /** Press: the mic opens at once, before the TV answers, so the first words are kept. */
    fun startVoice() {
        scope.launch {
            atv.startVoice()
            if (!atv.voice.value.capturing) return@launch
            micJob?.cancel()
            micJob = scope.launch {
                try {
                    mic.stream().collect { atv.voiceAudio(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    atv.cancelVoice()
                    mutableEvents.tryEmit(TuttiEvent.MicUnavailable)
                }
            }
        }
    }

    /** Release over the button: what was said goes to the TV. */
    fun finishVoice() {
        scope.launch { atv.finishVoice() }
    }

    /** Slide off, background, or leaving the screen. */
    fun cancelVoice() {
        micJob?.cancel()
        scope.launch { atv.cancelVoice() }
    }

    fun clearVoiceFailure() = atv.clearVoiceFailure()

    // endregion

    fun launchApp(packageName: String) {
        scope.launch { launchLink(packageName) }
    }

    /** Through IP control when the TV ever listed the app, else (or on failure) over the remote link. */
    private suspend fun launchLink(packageName: String): Boolean {
        val client = bravia
        if (client != null && links.value.rest == Link.ONLINE) {
            val uri = tv.value.apps.firstOrNull { AppLibrary.packageOf(it.uri) == packageName }?.uri
                ?: store.apps.first().firstOrNull { it.packageName == packageName }?.tvUri
            if (uri != null && client.setActiveApp(uri) is BraviaResult.Ok) return true
        }
        val link = AppCatalog.byPackage(packageName)?.launchLink ?: "market://launch?id=$packageName"
        return atv.launch(link)
    }

    /** Keeps what the TV listed and fetches the icons not cached yet, one at a time. */
    private suspend fun rememberApps(fresh: List<TvApp>) {
        store.updateApps { AppLibrary.remember(it, fresh) }
        val transport = braviaTransport ?: return
        val dir = iconDir.apply { mkdirs() }
        for (app in store.apps.first()) {
            val url = app.iconUrl ?: continue
            val name = iconFileName(app.packageName) ?: continue
            if (icons.value.containsKey(name)) continue
            val bytes = transport.fetchIcon(url) ?: continue
            val path = withContextIO {
                val part = File(dir, "$name.part")
                part.writeBytes(bytes)
                val target = File(dir, name)
                if (part.renameTo(target)) target.path else null
            } ?: continue
            icons.update { it + (name to path) }
        }
    }

    private suspend fun <T> withContextIO(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    /** The TV's own apps screen, over the remote link (no IP control needed). */
    fun openAllApps() = key(V2Key.ALL_APPS)

    fun switchInput(uri: String) {
        scope.launch {
            tv.update { it.copy(input = uri) }
            if (bravia?.setPlayContent(uri) !is BraviaResult.Ok) mutableEvents.tryEmit(TuttiEvent.ActionFailed)
        }
    }

    /** One Power for both. Optimistic for a few seconds, then the devices' own reports win. */
    fun power() {
        val on = state.value.powered != true
        setOverride { it.copy(powered = on) }
        scope.launch { tvPower(on) }
        scope.launch { theatre.setToggle(TheatreFeature.POWER, on) }
    }

    private suspend fun tvPower(on: Boolean): Boolean {
        val remote = atv.state.value
        if (remote.ready && remote.powered != null) {
            return if (remote.powered != on) atv.sendKey(V2Key.POWER) else true
        }
        val client = bravia
        if (client != null && links.value.rest == Link.ONLINE) return client.setPower(on) is BraviaResult.Ok
        return if (remote.ready) atv.sendKey(V2Key.POWER) else false
    }

    private fun setOverride(transform: (Overrides) -> Overrides) {
        overrides.update(transform)
        overrideJob?.cancel()
        overrideJob = scope.launch {
            delay(OVERRIDE_MS)
            overrides.value = Overrides()
        }
    }

    /** Hardware keyboard while the remote is in front. Returns true when consumed. */
    fun hardwareKey(keyCode: Int, typing: Boolean): Boolean = when (val action = KeyMap.hardwareKeyboard(keyCode, typing)) {
        is HardwareKey.Send -> {
            if (action.keyCode == V2Key.VOLUME_UP || action.keyCode == V2Key.VOLUME_DOWN) {
                volumeStep(if (action.keyCode == V2Key.VOLUME_UP) 1 else -1)
            } else if (action.keyCode == V2Key.VOLUME_MUTE) {
                toggleMute()
            } else {
                key(action.keyCode)
            }
            true
        }
        HardwareKey.Text -> false
        HardwareKey.Ignore -> false
    }

    // endregion

    // region bar

    /** Absolute soundbar volume, clamped to the active scene's cap. Sent at most every 60 ms, latest wins. */
    fun setVolume(level: Int) {
        val ceiling = state.value.volumeCap ?: MAX_VOLUME
        val clamped = level.coerceIn(0, ceiling)
        if (clamped == 0 || clamped == ceiling) mutableEvents.tryEmit(TuttiEvent.VolumeLimit)
        setOverride { it.copy(volume = clamped) }
        volumeTarget.value = clamped
    }

    fun volumeStep(delta: Int) {
        val current = state.value.volume ?: return
        setVolume(current + delta)
    }

    fun toggleMute() {
        scope.launch { theatre.setToggle(TheatreFeature.MUTE, !state.value.muted) }
    }

    fun toggleBar(feature: TheatreFeature) {
        val on = theatre.state.value.isOn(feature) ?: false
        scope.launch { theatre.setToggle(feature, !on) }
    }

    fun setBarToggle(feature: TheatreFeature, on: Boolean) {
        scope.launch { theatre.setToggle(feature, on) }
    }

    fun setBarLevel(feature: TheatreFeature, level: Int) {
        scope.launch { theatre.setLevel(feature, level) }
    }

    fun setBarChoice(feature: TheatreFeature, value: String) {
        if (feature.kind !is FeatureKind.Choice) return
        scope.launch { theatre.set(feature, value) }
    }

    /** Bluetooth pairing mode on the bar (observed sequence, unverified on the Bar 9). */
    fun startBarPairing() {
        scope.launch { theatre.startBluetoothPairing() }
    }

    fun refreshBar() {
        scope.launch { theatre.refreshAll() }
    }

    // endregion

    // region scenes

    fun applyScene(sceneId: String) {
        val scene = state.value.scenes.firstOrNull { it.scene.id == sceneId }?.scene ?: return
        activeSceneId.value = sceneId
        val steps = SceneEngine.plan(scene, snapshot())
        sceneJob?.cancel()
        sceneJob = scope.launch {
            var failed = 0
            SceneRunner(actuator).run(steps).collect { p ->
                failed = p.failed.size
                progress.update { it + (sceneId to p.fraction) }
            }
            progress.update { it - sceneId }
            mutableEvents.emit(TuttiEvent.SceneFinished(sceneId, failed))
        }
    }

    /** "Capturer l'état actuel": every known value, each one a removable chip. */
    fun captureCurrent(): Map<SceneKey, SceneValue> = SceneEngine.capture(snapshot())

    fun saveScene(scene: Scene) {
        scope.launch {
            store.updateScenes { scenes ->
                if (scenes.any { it.id == scene.id }) scenes.map { if (it.id == scene.id) scene else it } else scenes + scene
            }
        }
    }

    fun deleteScene(sceneId: String) {
        if (activeSceneId.value == sceneId) activeSceneId.value = null
        scope.launch {
            store.updateScenes { scenes -> scenes.filterNot { it.id == sceneId } }
            store.updateProfiles { profiles -> profiles.filterNot { it.sceneId == sceneId } }
        }
    }

    fun newSceneId(): String = "scene-" + UUID.randomUUID().toString().take(8)

    /** Null [sceneId] removes the profile for that app. */
    fun setProfile(appPackage: String, sceneId: String?) {
        scope.launch {
            store.updateProfiles { profiles ->
                val others = profiles.filterNot { it.appPackage == appPackage }
                if (sceneId == null) others else others + AppProfile(appPackage, sceneId)
            }
        }
    }

    private val actuator: SceneActuator by lazy { SceneActuatorImpl() }

    private inner class SceneActuatorImpl : SceneActuator {
        override suspend fun perform(step: SceneStep): Boolean = when (step) {
            SceneStep.WakeUp -> {
                tvPower(true)
                theatre.setToggle(TheatreFeature.POWER, true)
                delay(WAKE_SETTLE_MS)
                true
            }
            is SceneStep.TvInput -> bravia?.setPlayContent(step.uri) is BraviaResult.Ok
            is SceneStep.TvApp -> launchLink(step.appPackage)
            is SceneStep.PictureMode -> bravia?.setPictureSetting("pictureMode", step.mode) is BraviaResult.Ok
            is SceneStep.Bar -> {
                theatre.set(step.feature, step.value)
                theatre.state.value.connected
            }
            is SceneStep.LaunchApp -> launchLink(step.appPackage)
        }
    }

    // endregion

    // region dock and shortcuts

    fun run(action: DockAction) {
        scope.launch { perform(action) }
    }

    private suspend fun perform(action: DockAction) {
        when (action) {
            is DockAction.LaunchApp -> launchLink(action.packageName)
            is DockAction.Input -> switchInput(action.uri)
            is DockAction.ToggleBar -> TheatreFeature.fromKey(action.featureKey)?.let(::toggleBar)
            is DockAction.RunScene -> applyScene(action.sceneId)
            is DockAction.Key -> atv.sendKey(action.keyCode)
            is DockAction.Macro -> for (step in action.steps) {
                when (step) {
                    is MacroStep.Wait -> delay(step.millis.coerceIn(0, MAX_MACRO_WAIT_MS))
                    is MacroStep.Run -> if (step.action !is DockAction.Macro) perform(step.action)
                }
            }
        }
    }

    fun assignDock(index: Int, primary: DockAction?, secondary: DockAction?) {
        scope.launch {
            store.updateDock { dock ->
                val slots = (dock.slots + List(DockConfig.SIZE) { DockSlot() }).take(DockConfig.SIZE).toMutableList()
                slots[index] = DockSlot(primary, secondary)
                dock.copy(slots = slots)
            }
        }
    }

    fun assignLongPress(button: SecondaryButton, action: DockAction?) {
        scope.launch {
            store.updateDock { dock ->
                dock.copy(longPress = if (action == null) dock.longPress - button else dock.longPress + (button to action))
            }
        }
    }

    // endregion

    // region devices and pairing

    /** Starts pairing; the UI follows [TuttiUiState.pairing] and answers with [submitPairingCode]. */
    fun pairTv(host: String) {
        val address = host.trim()
        if (!LanAddress.isPrivate(address)) {
            pairing.value = PairingUi.Failed(PairingError.UNREACHABLE)
            return
        }
        pairingJob?.cancel()
        pairingJob = scope.launch {
            pairing.value = PairingUi.Connecting
            atv.disconnect()
            val steps = mutableListOf<String>()
            var session: AtvPairing? = null
            fun report() = (steps + (session?.trace ?: emptyList())).joinToString(" · ")
            var watchdog: Job? = null
            suspend fun pairWith(identity: ClientIdentity): PairingResult {
                val attempt = AtvPairing(tls, serviceName = "tutti", clientName = "Tutti").also { session = it }
                return attempt.pair(address, identity) { tries ->
                    val answer = CompletableDeferred<String?>()
                    codeAnswer = answer
                    pairing.value = PairingUi.AwaitingCode(tries)
                    answer.await().also { code ->
                        if (code == null) return@also
                        // The TV answers a right code at once; past this, something is stuck: say what we saw.
                        pairing.value = PairingUi.Connecting
                        watchdog?.cancel()
                        watchdog = scope.launch {
                            delay(SECRET_ACK_TIMEOUT_MS)
                            pairingJob?.cancel()
                            pairing.value = PairingUi.Failed(PairingError.REFUSED, Redact.text("no answer after the code: ${report()}", listOf(address)))
                        }
                    }
                }
            }
            val result = try {
                try {
                    pairWith(identities.getOrCreate())
                } catch (e: SSLException) {
                    // The keystore key couldn't sign in the handshake: retry once with the software key.
                    if (e.message?.contains("RSA routines") != true) throw e
                    steps += "keystore key refused, software key"
                    pairWith(identities.replaceWithSoftware())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error here (a missing class, a provider failure) would otherwise kill the app.
                watchdog?.cancel()
                pairing.value = PairingUi.Failed(PairingError.UNREACHABLE, pairingCause(e, address) + "\n" + Redact.text(report(), listOf(address)))
                return@launch
            }
            when (result) {
                is PairingResult.Paired -> {
                    steps += "paired, saving pin"
                    secure.setTvRemotePin(result.serverPin)
                    steps += "saving device"
                    store.updateDevices { it.copy(tv = (it.tv ?: TvDevice(address)).copy(host = address)) }
                    restart.update { it + 1 }
                    watchdog?.cancel()
                    pairing.value = PairingUi.Done
                }
                is PairingResult.Failed -> pairing.value = when (result.failure.also { watchdog?.cancel() }) {
                    PairingFailure.Cancelled -> PairingUi.Idle
                    PairingFailure.BadCode -> PairingUi.Failed(PairingError.BAD_CODE, Redact.text(report(), listOf(address)))
                    PairingFailure.Closed -> PairingUi.Failed(PairingError.UNREACHABLE, Redact.text("closed before pairing finished: ${report()}", listOf(address)))
                    else -> PairingUi.Failed(PairingError.REFUSED, Redact.text("${result.failure}: ${report()}", listOf(address)))
                }
            }
        }
    }

    /** Exception classes and messages down the cause chain, with the address and anything identifying scrubbed. */
    /**
     * Exception classes and messages down the cause chain, then where the deepest one was thrown,
     * with the address and anything identifying scrubbed.
     */
    private fun pairingCause(error: Throwable, address: String): String {
        val chain = generateSequence(error) { it.cause }.take(4).toList()
        val causes = chain.joinToString(" ← ") { cause -> listOfNotNull(cause::class.java.simpleName, cause.message).joinToString(": ") }
        val frames = chain.last().stackTrace.take(6).joinToString("\n") { "  at $it" }
        return Redact.text("$causes\n$frames", listOf(address))
    }

    fun submitPairingCode(code: String?) {
        codeAnswer?.complete(code)
        codeAnswer = null
    }

    fun resetPairing() {
        submitPairingCode(null)
        pairing.value = PairingUi.Idle
    }

    /** Returns false for anything but a private LAN address. */
    fun addBar(host: String): Boolean {
        val address = host.trim()
        if (!LanAddress.isPrivate(address)) return false
        scope.launch { store.updateDevices { it.copy(bar = BarDevice(address)) } }
        return true
    }

    fun setPsk(value: String?) {
        scope.launch {
            secure.setTvPsk(value)
            psk = secure.tvPsk()
            restart.update { it + 1 }
        }
    }

    suspend fun hasPsk(): Boolean = secure.tvPsk() != null

    /** "Oublier l'appareil" for the TV: wipes the PSK, both pins and the client identity. */
    fun forgetTv() {
        scope.launch {
            atv.disconnect()
            secure.forgetTv()
            identities.forget()
            psk = null
            activeSceneId.value = null
            store.updateDevices { it.copy(tv = null) }
            tv.value = TvState()
        }
    }

    fun forgetBar() {
        scope.launch {
            theatre.disconnect()
            store.updateDevices { it.copy(bar = null) }
        }
    }

    fun setOnboarded() {
        scope.launch { store.updatePrefs { it.copy(onboarded = true) } }
    }

    // endregion

    // region tv settings

    fun loadTvSettings() {
        val client = bravia ?: return
        scope.launch {
            tv.update { it.copy(settings = it.settings.copy(loading = true)) }
            val caps = client.capabilities
            val picture = if (caps.supports(BraviaService.VIDEO, "getPictureQualitySettings")) client.pictureSettings().getOrNull() else null
            val sound = if (caps.supports(BraviaService.AUDIO, "getSoundSettings")) client.soundSettings().getOrNull() else null
            val speaker = if (caps.supports(BraviaService.AUDIO, "getSpeakerSettings")) client.speakerSettings().getOrNull() else null
            val saving = if (caps.supports(BraviaService.SYSTEM, "getPowerSavingMode")) client.powerSavingMode().getOrNull() else null
            val wol = if (caps.supports(BraviaService.SYSTEM, "getWolMode")) client.wolMode().getOrNull() else null
            val scene = if (caps.supports(BraviaService.VIDEO_SCREEN, "getSceneSetting")) client.sceneSetting().getOrNull() else null
            val sleep = if (caps.supports(BraviaService.SYSTEM, "getRemoteControllerInfo")) {
                client.remoteControllerInfo().getOrNull()?.let(io.kud.tutti.protocol.bravia.Ircc::sleepTimerCode)
            } else {
                null
            }
            val system = if (caps.supports(BraviaService.SYSTEM, "getSystemInformation")) client.systemInformation().getOrNull() else null
            val inputs = client.externalInputs().getOrNull()
            tv.update {
                it.copy(
                    inputs = inputs ?: it.inputs,
                    settings = TvSettingsState(
                        loading = false,
                        picture = picture.orEmpty(),
                        sound = sound.orEmpty(),
                        speaker = speaker.orEmpty(),
                        powerSaving = saving,
                        powerSavingSupported = saving != null || caps.supports(BraviaService.SYSTEM, "setPowerSavingMode"),
                        wol = wol,
                        scene = scene,
                        outputWriteOnly = sound == null && caps.supports(BraviaService.AUDIO, "setSoundSettings"),
                        sleepCode = sleep,
                        system = system,
                    ),
                )
            }
        }
    }

    enum class TvSettingGroup { PICTURE, SOUND, SPEAKER }

    fun setTvSetting(group: TvSettingGroup, target: String, value: String) {
        val client = bravia ?: return
        tv.update { state ->
            fun List<io.kud.tutti.protocol.bravia.TvSetting>.withValue() = map { if (it.target == target) it.copy(currentValue = value) else it }
            val s = state.settings
            state.copy(
                settings = when (group) {
                    TvSettingGroup.PICTURE -> s.copy(picture = s.picture.withValue())
                    TvSettingGroup.SOUND -> s.copy(sound = s.sound.withValue())
                    TvSettingGroup.SPEAKER -> s.copy(speaker = s.speaker.withValue())
                },
                pictureMode = if (group == TvSettingGroup.PICTURE && target == "pictureMode") value else state.pictureMode,
            )
        }
        scope.launch {
            val result = when (group) {
                TvSettingGroup.PICTURE -> client.setPictureSetting(target, value)
                TvSettingGroup.SOUND -> client.setSoundSetting(target, value)
                TvSettingGroup.SPEAKER -> client.setSpeakerSetting(target, value)
            }
            if (result !is BraviaResult.Ok) {
                mutableEvents.tryEmit(TuttiEvent.ActionFailed)
                loadTvSettings()
            }
        }
    }

    fun setWol(enabled: Boolean) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(wol = enabled)) }
        scope.launch {
            if (client.setWolMode(enabled) !is BraviaResult.Ok) {
                mutableEvents.tryEmit(TuttiEvent.ActionFailed)
                loadTvSettings()
            }
        }
    }

    fun setScene(value: String) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(scene = it.settings.scene?.copy(currentValue = value))) }
        scope.launch {
            if (client.setSceneSetting(value) !is BraviaResult.Ok) {
                mutableEvents.tryEmit(TuttiEvent.ActionFailed)
                loadTvSettings()
            }
        }
    }

    /** Write-only: no documented getter, so nothing to roll back to on failure beyond the event. */
    fun setOutputTerminal(value: String) {
        val client = bravia ?: return
        scope.launch {
            if (client.setSoundSetting(io.kud.tutti.protocol.bravia.TvSetting.OUTPUT_TERMINAL, value) !is BraviaResult.Ok) {
                mutableEvents.tryEmit(TuttiEvent.ActionFailed)
            }
        }
    }

    /** One press of the TV's own sleep-timer key: the TV cycles its steps and shows the choice. */
    fun pressSleepTimer() {
        val client = bravia ?: return
        val code = tv.value.settings.sleepCode ?: return
        scope.launch {
            if (client.sendIrcc(code) !is BraviaResult.Ok) mutableEvents.tryEmit(TuttiEvent.ActionFailed)
        }
    }

    fun setPowerSaving(mode: String) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(powerSaving = mode)) }
        scope.launch {
            if (client.setPowerSavingMode(mode) !is BraviaResult.Ok) {
                mutableEvents.tryEmit(TuttiEvent.ActionFailed)
                loadTvSettings()
            }
        }
    }

    // endregion

    // region preferences

    fun setHaptics(enabled: Boolean) {
        scope.launch { store.updatePrefs { it.copy(haptics = enabled) } }
    }

    fun setTheme(mode: ThemeMode) {
        scope.launch { store.updatePrefs { it.copy(theme = mode) } }
        // Android 12+ keeps this per app across restarts, and it reaches what Compose can't: the
        // launch screen, the system bars and the quick overlay. Set only on a choice, never from
        // state, so a start before prefs load can't flip it and recreate the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(
                when (mode) {
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        }
    }

    fun setTrueBlack(enabled: Boolean) {
        scope.launch { store.updatePrefs { it.copy(trueBlack = enabled) } }
    }

    fun setPadMode(mode: PadMode) {
        scope.launch { store.updatePrefs { it.copy(padMode = mode) } }
    }

    // endregion

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    companion object {
        const val MAX_VOLUME = 100
        private const val LINGER_MS = 30_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val READY_TIMEOUT_MS = 8_000L
        private const val POLL_BAR_MS = 20_000L
        private const val POLL_TV_MS = 10_000L
        private const val OVERRIDE_MS = 2_500L
        private const val VOLUME_SEND_INTERVAL_MS = 60L
        private const val WAKE_SETTLE_MS = 1_500L
        private const val MAX_MACRO_WAIT_MS = 30_000L
        private const val SECRET_ACK_TIMEOUT_MS = 12_000L
    }
}

/** The cache file for a package's icon: the package name itself, or null for anything that is not one. */
internal fun iconFileName(packageName: String): String? =
    packageName.takeIf { Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) }

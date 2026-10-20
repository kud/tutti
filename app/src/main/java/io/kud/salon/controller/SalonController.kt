package io.kud.salon.controller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.salon.platform.data.DockAction
import io.kud.salon.platform.data.DockConfig
import io.kud.salon.platform.data.DockSlot
import io.kud.salon.platform.data.MacroStep
import io.kud.salon.platform.data.PadMode
import io.kud.salon.platform.data.SalonStore
import io.kud.salon.platform.data.SecondaryButton
import io.kud.salon.platform.data.TvDevice
import io.kud.salon.platform.data.BarDevice
import io.kud.salon.platform.net.OkHttpBraviaTransport
import io.kud.salon.platform.net.PinMismatchException
import io.kud.salon.platform.security.ClientIdentityStore
import io.kud.salon.platform.security.SecureStore
import io.kud.salon.protocol.atv.AppCatalog
import io.kud.salon.protocol.atv.AtvPairing
import io.kud.salon.protocol.atv.AtvRemote
import io.kud.salon.protocol.atv.ClientDeviceInfo
import io.kud.salon.protocol.atv.HardwareKey
import io.kud.salon.protocol.atv.KeyMap
import io.kud.salon.protocol.atv.PairingFailure
import io.kud.salon.protocol.atv.PairingResult
import io.kud.salon.protocol.atv.V2Key
import io.kud.salon.protocol.bravia.BraviaClient
import io.kud.salon.protocol.bravia.BraviaError
import io.kud.salon.protocol.bravia.BraviaResult
import io.kud.salon.protocol.bravia.BraviaService
import io.kud.salon.protocol.bravia.PowerStatus
import io.kud.salon.protocol.bravia.getOrNull
import io.kud.salon.protocol.grpc.SonyCloudPlane
import io.kud.salon.protocol.net.LanAddress
import io.kud.salon.protocol.scenes.AppProfile
import io.kud.salon.protocol.scenes.AppProfiles
import io.kud.salon.protocol.scenes.DeviceSnapshot
import io.kud.salon.protocol.scenes.Scene
import io.kud.salon.protocol.scenes.SceneActuator
import io.kud.salon.protocol.scenes.SceneEngine
import io.kud.salon.protocol.scenes.SceneKey
import io.kud.salon.protocol.scenes.SceneMatch
import io.kud.salon.protocol.scenes.SceneRunner
import io.kud.salon.protocol.scenes.SceneStep
import io.kud.salon.protocol.scenes.SceneValue
import io.kud.salon.protocol.theatre.FeatureKind
import io.kud.salon.protocol.theatre.TheatreClient
import io.kud.salon.protocol.theatre.TheatreFeature
import io.kud.salon.protocol.theatre.TheatreState
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
class SalonController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SalonStore,
    private val secure: SecureStore,
    private val identities: ClientIdentityStore,
    private val tls: io.kud.salon.protocol.net.TlsConnector,
    tcp: io.kud.salon.protocol.net.TcpConnector,
    val cloud: SonyCloudPlane,
) {
    /** Application-lifetime scope; widgets may launch into it. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val theatre = TheatreClient(tcp, scope)
    private val atv = AtvRemote(tls, scope, ClientDeviceInfo(model = "Salon", packageName = context.packageName))

    @Volatile private var psk: String? = null
    @Volatile private var bravia: BraviaClient? = null

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

    private val mutableEvents = MutableSharedFlow<SalonEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<SalonEvent> = mutableEvents.asSharedFlow()

    private data class Overrides(val volume: Int? = null, val powered: Boolean? = null)

    private data class Stored(
        val devices: io.kud.salon.platform.data.Devices,
        val prefs: io.kud.salon.platform.data.AppPrefs,
        val dock: DockConfig,
        val scenes: List<Scene>,
        val profiles: List<AppProfile>,
    )

    private data class Live(
        val tv: TvState,
        val remote: io.kud.salon.protocol.atv.RemoteState,
        val bar: TheatreState,
        val links: Links,
        val voiceZoom: Boolean,
    )

    private data class Extra(
        val progress: Map<String, Float>,
        val activeSceneId: String?,
        val pairing: PairingUi,
        val cloud: io.kud.salon.protocol.grpc.CloudPlaneState,
        val overrides: Overrides,
    )

    private val stored = combine(store.devices, store.prefs, store.dock, store.scenes, store.profiles) { d, p, k, s, f -> Stored(d, p, k, s, f) }
    private val live = combine(tv, atv.state, theatre.state, links, voiceZoom) { t, r, b, l, v -> Live(t, r, b, l, v) }
    private val extra = combine(progress, activeSceneId, pairing, cloud.state, overrides) { p, a, q, c, o -> Extra(p, a, q, c, o) }

    val state: StateFlow<SalonUiState> = combine(stored, live, extra) { s, l, e -> build(s, l, e) }
        .stateIn(scope, SharingStarted.Eagerly, SalonUiState())

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
        scope.launch {
            volumeTarget.filterNotNull().conflate().collect { level ->
                theatre.setLevel(TheatreFeature.VOLUME, level)
                delay(VOLUME_SEND_INTERVAL_MS)
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
        val client = BraviaClient(
            OkHttpBraviaTransport(device.host, secure.tvRestPin()) { secure.setTvRestPin(it) },
            psk = { psk },
        )
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
        return true
    }

    // endregion

    // region state

    private fun build(s: Stored, l: Live, e: Extra): SalonUiState {
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
        return SalonUiState(
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

    private fun snapshotOf(tv: TvState, remote: io.kud.salon.protocol.atv.RemoteState, bar: TheatreState): DeviceSnapshot {
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

    fun launchApp(packageName: String) {
        scope.launch { launchLink(packageName) }
    }

    private suspend fun launchLink(packageName: String): Boolean {
        val link = AppCatalog.byPackage(packageName)?.launchLink ?: "market://launch?id=$packageName"
        return atv.launch(link)
    }

    fun switchInput(uri: String) {
        scope.launch {
            tv.update { it.copy(input = uri) }
            if (bravia?.setPlayContent(uri) !is BraviaResult.Ok) mutableEvents.tryEmit(SalonEvent.ActionFailed)
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
        if (clamped == 0 || clamped == ceiling) mutableEvents.tryEmit(SalonEvent.VolumeLimit)
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
            mutableEvents.emit(SalonEvent.SceneFinished(sceneId, failed))
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

    /** Starts pairing; the UI follows [SalonUiState.pairing] and answers with [submitPairingCode]. */
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
            val result = try {
                val identity = identities.getOrCreate()
                AtvPairing(tls, serviceName = "salon", clientName = "Salon").pair(address, identity) { attempt ->
                    val answer = CompletableDeferred<String?>()
                    codeAnswer = answer
                    pairing.value = PairingUi.AwaitingCode(attempt)
                    answer.await()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pairing.value = PairingUi.Failed(PairingError.UNREACHABLE)
                return@launch
            }
            when (result) {
                is PairingResult.Paired -> {
                    secure.setTvRemotePin(result.serverPin)
                    store.updateDevices { it.copy(tv = (it.tv ?: TvDevice(address)).copy(host = address)) }
                    restart.update { it + 1 }
                    pairing.value = PairingUi.Done
                }
                is PairingResult.Failed -> pairing.value = when (result.failure) {
                    PairingFailure.Cancelled -> PairingUi.Idle
                    PairingFailure.BadCode -> PairingUi.Failed(PairingError.BAD_CODE)
                    PairingFailure.Closed -> PairingUi.Failed(PairingError.UNREACHABLE)
                    else -> PairingUi.Failed(PairingError.REFUSED)
                }
            }
        }
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
                    ),
                )
            }
        }
    }

    enum class TvSettingGroup { PICTURE, SOUND, SPEAKER }

    fun setTvSetting(group: TvSettingGroup, target: String, value: String) {
        val client = bravia ?: return
        tv.update { state ->
            fun List<io.kud.salon.protocol.bravia.TvSetting>.withValue() = map { if (it.target == target) it.copy(currentValue = value) else it }
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
                mutableEvents.tryEmit(SalonEvent.ActionFailed)
                loadTvSettings()
            }
        }
    }

    fun setPowerSaving(mode: String) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(powerSaving = mode)) }
        scope.launch {
            if (client.setPowerSavingMode(mode) !is BraviaResult.Ok) {
                mutableEvents.tryEmit(SalonEvent.ActionFailed)
                loadTvSettings()
            }
        }
    }

    // endregion

    // region preferences

    fun setHaptics(enabled: Boolean) {
        scope.launch { store.updatePrefs { it.copy(haptics = enabled) } }
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
    }
}

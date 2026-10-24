package io.kud.tutti.controller

import io.kud.tutti.platform.data.VibrationLevel
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
import io.kud.tutti.platform.data.RemoteLayout
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.platform.diagnostic.AppVersion
import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.platform.net.PinMismatchException
import io.kud.tutti.platform.net.WakeOnLanSender
import io.kud.tutti.platform.security.ClientIdentityStore
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.apps.AppLauncher
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.protocol.apps.LaunchPlan
import io.kud.tutti.protocol.apps.LaunchResult
import io.kud.tutti.protocol.apps.LaunchRoute
import io.kud.tutti.protocol.net.Redact
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
import io.kud.tutti.protocol.diagnostic.ErrorClass
import io.kud.tutti.protocol.diagnostic.FailureReport
import io.kud.tutti.protocol.net.WakeAttempt
import io.kud.tutti.protocol.net.WakeOnLan
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
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
    private val linkNotes = MutableStateFlow(LinkNotes())
    private val tv = MutableStateFlow(TvState())
    private val voiceZoom = MutableStateFlow(false)
    private val progress = MutableStateFlow<Map<String, SceneRun>>(emptyMap())
    private val activeSceneId = MutableStateFlow<String?>(null)
    private val pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    private val overrides = MutableStateFlow(Overrides())
    private val restart = MutableStateFlow(0)
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val volumeTarget = MutableStateFlow<Int?>(null)
    private val waking = MutableStateFlow(false)
    private val wakeOnLan = WakeOnLanSender(context)

    /** How the remote's last connect failed (`ErrorClass`), so a failed wake can tell "nothing there" from "refused". */
    @Volatile
    private var tvLastConnect: String? = null

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
        val progress: Map<String, SceneRun>,
        val activeSceneId: String?,
        val pairing: PairingUi,
        val cloud: io.kud.tutti.protocol.grpc.CloudPlaneState,
        val overrides: Overrides,
        val waking: Boolean,
        val linkNotes: LinkNotes,
    )

    private val stored = combine(store.devices, store.prefs, store.dock, store.scenes, store.profiles) { d, p, k, s, f -> Stored(d, p, k, s, f) }
    private val live = combine(tv, atv.state, theatre.state, links, voiceZoom) { t, r, b, l, v -> Live(t, r, b, l, v) }
    private val extra = combine(progress, activeSceneId, pairing, cloud.state, combine(overrides, waking, linkNotes, ::Triple)) { p, a, q, c, o ->
        Extra(p, a, q, c, o.first, o.second, o.third)
    }

    val state: StateFlow<TuttiUiState> = combine(stored, live, extra, library) { s, l, e, a ->
        build(s, l, e).copy(apps = a.first, appIcons = a.second, linkNotes = e.linkNotes)
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
        // A learnt MAC is not a reason to drop and reopen every session.
        val hosts = store.devices.map { it.copy(tv = it.tv?.copy(mac = null)) }.distinctUntilChanged()
        combine(hosts, restart) { devices, _ -> devices }.collectLatest { devices ->
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
                    linkNotes.update { it.copy(bar = failureDetail("soundbar: connect (TCP 33336)", outcome = "unreachable", error = e)) }
                    false
                }
                if (connected) {
                    links.update { it.copy(bar = Link.ONLINE) }
                    linkNotes.update { it.copy(bar = null) }
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
                    linkNotes.update { it.copy(bar = failureDetail("soundbar: session", outcome = "connected, then the bar closed the link or stopped answering")) }
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
            linkNotes.update { it.copy(tv = failureDetail("tv remote: connect (TLS 6466)", outcome = "no pairing stored on this phone: pair the TV")) }
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
                    if (withTimeoutOrNull(READY_TIMEOUT_MS) { atv.state.first { it.ready } } != null) {
                        Link.ONLINE
                    } else {
                        linkNotes.update { it.copy(tv = failureDetail("tv remote: connect (TLS 6466)", outcome = "connected, not ready after $READY_TIMEOUT_MS ms")) }
                        Link.UNREACHABLE
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tvLastConnect = ErrorClass.of(e)
                    linkNotes.update { it.copy(tv = failureDetail("tv remote: connect (TLS 6466)", outcome = "handshake failures in a row: $handshakeFailures", error = e)) }
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
                    tvLastConnect = null
                    wait = MIN_BACKOFF_MS
                    links.update { it.copy(tv = Link.ONLINE) }
                    linkNotes.update { it.copy(tv = null) }
                    atv.state.first { !it.ready }
                    linkNotes.update { it.copy(tv = failureDetail("tv remote: session", outcome = "connected, then the link dropped")) }
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
        if (psk == null) {
            // IP control needs the PSK for everything past discovery, so without one there is
            // nothing to poll: retrying only repeated the same failure (Erwann, 2026-10-24). TV
            // settings explain how to set it up; setPsk restarts this loop.
            bravia = null
            braviaTransport = null
            links.update { it.copy(rest = Link.ABSENT) }
            linkNotes.update { it.copy(rest = null) }
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
                    linkNotes.update { it.copy(rest = null) }
                    tv.update { it.copy(capabilities = found.value) }
                    learnMac(client, device.host)
                    while (scope.isActive) {
                        if (!refreshTv(client)) break
                        delay(POLL_TV_MS)
                    }
                    links.update { it.copy(rest = Link.UNREACHABLE) }
                    linkNotes.update { it.copy(rest = failureDetail("tv ip control: poll", outcome = "answered, then stopped answering getPowerStatus")) }
                }
                is BraviaResult.Failure -> {
                    val link = if (found.error == BraviaError.Unauthorized) Link.UNAUTHORIZED else Link.UNREACHABLE
                    links.update { it.copy(rest = link) }
                    linkNotes.update { it.copy(rest = failureDetail("tv ip control: discover (HTTPS/HTTP + PSK)", outcome = "$link: ${found.error}")) }
                    // A wrong PSK will not fix itself: wait for a new one (restart) or a long while.
                    if (link == Link.UNAUTHORIZED) wait = MAX_BACKOFF_MS
                }
            }
            backoff(wait)
            wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Keeps the TV's MAC for Wake-on-LAN: the interface that carries [host], else the system one. Never logged. */
    private suspend fun learnMac(client: BraviaClient, host: String) {
        val interfaces = client.networkSettings().getOrNull().orEmpty()
        val systemMac = if (interfaces.isEmpty()) client.systemInformation().getOrNull()?.mac else null
        val mac = WakeOnLan.macFor(host, interfaces.map { it.ipv4 to it.mac }, systemMac) ?: return
        if (store.devices.first().tv?.mac == mac) return
        store.updateDevices { devices ->
            val current = devices.tv
            if (current == null || current.host != host) devices else devices.copy(tv = current.copy(mac = mac))
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
        // getPlayingContentInfo is a `private` method: without a PSK it can only be refused, so it is
        // not asked. It rides this poll (POLL_TV_MS), never a loop of its own. A failed or empty
        // answer (what an app playing is expected to give) clears [TvState.playing], so the chip
        // falls back to the app name rather than keeping a stale channel.
        val playing = if (active && !psk.isNullOrEmpty()) client.playingContent().getOrNull() else null
        val input = playing?.uri
        val pictureMode = if (active && caps.supports(BraviaService.VIDEO, "getPictureQualitySettings")) {
            client.pictureSettings("pictureMode").getOrNull()?.firstOrNull { it.target == "pictureMode" }?.currentValue
        } else {
            null
        }
        val current = tv.value
        val inputs = if (current.inputs.isEmpty() && active) client.externalInputs().getOrNull().orEmpty() else current.inputs
        val apps = if (current.apps.isEmpty() && active) client.applications().getOrNull().orEmpty() else current.apps
        tv.update { it.copy(power = power, input = input ?: it.input, playing = playing, pictureMode = pictureMode ?: it.pictureMode, inputs = inputs, apps = apps) }
        if (current.apps.isEmpty() && apps.isNotEmpty()) scope.launch { rememberApps(apps) }
        return true
    }

    // endregion

    // region state

    private fun build(s: Stored, l: Live, e: Extra): TuttiUiState {
        val snapshot = snapshotOf(l.tv, l.remote, l.bar)
        val active = e.activeSceneId
        val scenes = s.scenes.map { scene ->
            val run = e.progress[scene.id]
            val running = run?.fraction
            val failure = run?.failure
            if (scene.id != active) {
                SceneUi(scene, SceneStatus.IDLE, running, failure = failure)
            } else {
                when (val match = SceneEngine.match(scene, snapshot)) {
                    SceneMatch.Matches -> SceneUi(scene, SceneStatus.ACTIVE, running, failure = failure)
                    is SceneMatch.Modified -> SceneUi(scene, SceneStatus.MODIFIED, running, match.keys, failure)
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
            volume = volumeShown(e.overrides.volume, l.bar.level(TheatreFeature.VOLUME), s.prefs.lastBarVolume, l.bar.connected),
            volumeCap = cap,
            powered = powered,
            waking = e.waking,
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
                    mutableEvents.tryEmit(TuttiEvent.MicUnavailable(failureDetail("voice: stream the phone's mic", error = e)))
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

    /**
     * Runs in the controller's scope, so leaving the sheet never cancels a launch half way; the
     * caller awaits the [LaunchResult] to show it.
     */
    fun launchApp(packageName: String): Deferred<LaunchResult> = scope.async { launchWithResult(packageName) }

    /** The last launch's redacted trace, for the developer diagnostic. */
    @Volatile var lastLaunchTrace: String? = null
        private set

    private suspend fun launchLink(packageName: String): Boolean = launchWithResult(packageName).ok

    /**
     * Through IP control when it answers and the TV ever listed the app, then each app link the
     * catalogue knows, watching the remote link's foreground app between them ([AppLauncher]).
     */
    private suspend fun launchWithResult(packageName: String): LaunchResult {
        val client = bravia
        val restOnline = client != null && links.value.rest == Link.ONLINE
        val restUri = if (!restOnline) {
            null
        } else {
            tv.value.apps.firstOrNull { AppLibrary.packageOf(it.uri) == packageName }?.uri
                ?: store.apps.first().firstOrNull { it.packageName == packageName }?.tvUri
        }
        val launcher = AppLauncher(
            send = { route ->
                when (route) {
                    is LaunchRoute.Rest -> client?.setActiveApp(route.uri) is BraviaResult.Ok
                    is LaunchRoute.AppLink -> atv.launch(route.link)
                }
            },
            foreground = atv.state.map { it.currentApp },
            // app_info arrives over the remote link only; without it nothing can be confirmed.
            canObserve = { atv.state.value.ready },
        )
        val result = launcher.launch(packageName, LaunchPlan.routes(packageName, restOnline, restUri))
        lastLaunchTrace = Redact.text(result.trace())
        return result
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
            bravia?.setPlayContent(uri).let { if (it !is BraviaResult.Ok) actionFailed("switch input to $uri", outcomeOf(it)) }
        }
    }

    /**
     * One Power for both. A device in reach gets the command at once, optimistic for a few
     * seconds, then its own reports win. Turning on with one out of reach (the TV in standby)
     * wakes it instead: the sessions retry now rather than at the end of their backoff, the
     * TV gets a Wake-on-LAN packet when its MAC is known, [TuttiUiState.waking] shows, and the
     * command follows once it answers, or [TuttiEvent.WakeFailed] if it never does.
     */
    fun power() {
        if (waking.value) return
        val current = state.value
        val on = current.powered != true
        val tvDevice = current.devices.tv
        val tvNow = tvDevice != null && tvReachable()
        val barNow = current.devices.bar != null && theatre.state.value.connected
        if (tvNow || barNow) {
            setOverride { it.copy(powered = on) }
            if (tvNow) scope.launch { tvPower(on) }
            if (barNow) scope.launch { theatre.setToggle(TheatreFeature.POWER, on) }
        }
        // Turning off: whatever is out of reach is asleep already.
        if (!on) return
        val tvAway = tvDevice?.takeIf { !tvNow }
        val barAway = current.devices.bar != null && !barNow
        if (tvAway == null && !barAway) return
        waking.value = true
        scope.launch {
            try {
                wakeAndPower(tvAway, barAway)
            } finally {
                waking.value = false
            }
        }
    }

    private fun tvReachable(): Boolean = atv.state.value.ready || (bravia != null && links.value.rest == Link.ONLINE)

    private suspend fun wakeAndPower(tvDevice: TvDevice?, bar: Boolean) {
        val mac = tvDevice?.mac
        val sent = AtomicInteger(0)
        val (tvReach, barReach) = coroutineScope {
            val kick = launch {
                // The sessions may sit in backoff for up to MAX_BACKOFF_MS: retry now, and again while waiting.
                while (isActive) {
                    wake.tryEmit(Unit)
                    delay(WAKE_RETRY_MS)
                }
            }
            val packets = if (tvDevice != null && mac != null) {
                launch {
                    repeat(WOL_SENDS) { i ->
                        if (i > 0) delay(WOL_RESEND_MS)
                        if (wakeOnLan.send(tvDevice.host, mac)) sent.incrementAndGet()
                    }
                }
            } else {
                null
            }
            val tvJob = async {
                if (tvDevice == null) return@async WakeAttempt.Reach.NOT_CONFIGURED
                val reached = withTimeoutOrNull(WAKE_TIMEOUT_MS) {
                    combine(atv.state, links) { _, _ -> tvReachable() }.first { it }
                } != null
                if (reached) {
                    setOverride { it.copy(powered = true) }
                    if (!tvPowerAfterWake(packetsSent = packets != null)) actionFailed("power: switch the tv on after waking it", "tv answered, power-on not confirmed")
                }
                if (reached) WakeAttempt.Reach.ANSWERED else WakeAttempt.Reach.SILENT
            }
            val barJob = async {
                if (!bar) return@async WakeAttempt.Reach.NOT_CONFIGURED
                // The bar often wakes with the TV over HDMI-CEC; it gets the command either way.
                val reached = withTimeoutOrNull(WAKE_TIMEOUT_MS) { theatre.state.first { it.connected } } != null
                if (reached) {
                    setOverride { it.copy(powered = true) }
                    theatre.setToggle(TheatreFeature.POWER, true)
                }
                if (reached) WakeAttempt.Reach.ANSWERED else WakeAttempt.Reach.SILENT
            }
            val result = tvJob.await() to barJob.await()
            kick.cancel()
            packets?.cancel()
            result
        }
        val attempt = WakeAttempt(
            tv = tvReach,
            bar = barReach,
            macKnown = mac != null,
            packetsSent = sent.get(),
            timeoutMs = WAKE_TIMEOUT_MS,
            tvLastConnect = tvLastConnect,
        )
        val cause = attempt.cause ?: return
        mutableEvents.tryEmit(TuttiEvent.WakeFailed(cause, failureDetail("power: wake the devices", outcome = attempt.outcome())))
    }

    /**
     * The TV just came back: it states its power right after the handshake, so wait for that
     * rather than send a blind Power key. If it never says and a magic packet went out, the
     * packet already switched it on, and a blind toggle would switch it off again.
     */
    private suspend fun tvPowerAfterWake(packetsSent: Boolean): Boolean {
        val known = withTimeoutOrNull(POWER_KNOWN_MS) {
            combine(atv.state, links) { remote, link -> remote.powered != null || link.rest == Link.ONLINE }.first { it }
        } != null
        if (!known && packetsSent) return true
        return tvPower(true)
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

    /** The last level the bar reported or was asked for, so the rail keeps it (see [volumeShown]). */
    @Volatile
    private var lastVolume: Int? = null

    /**
     * The volume the UI shows (Erwann, 2026-10-24: "je veux que le niveau de volume reste affiché"):
     * the fresh level ([current], an override or the bar's report) when there is one, else, while the
     * bar is connected, the last one, so the rail's level and number never drop to nothing when an
     * override expires before the bar has echoed the level. Unreachable: null.
     */
    private fun volumeShown(override: Int?, reported: Int?, stored: Int?, connected: Boolean): Int? {
        if (reported != null && reported != stored) persistVolume(reported)
        val current = override ?: reported
        if (current != null) lastVolume = current
        // Never reported this session: the level from the last one, even if the bar moved since.
        return current ?: (lastVolume ?: stored).takeIf { connected }
    }

    @Volatile
    private var persistVolumeJob: Job? = null

    /** Keeps the bar's reported level for the next session, at most once a second. */
    private fun persistVolume(level: Int) {
        persistVolumeJob?.cancel()
        persistVolumeJob = scope.launch {
            delay(PERSIST_VOLUME_MS)
            store.updatePrefs { it.copy(lastBarVolume = level) }
        }
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
        // Level not known yet (the bar has not reported it): ask for it rather than guess a level to set.
        // The bar hasn't reported its level this session: ask for it, and step from the level
        // shown meanwhile (possibly last session's) rather than doing nothing.
        if (theatre.state.value.level(TheatreFeature.VOLUME) == null) scope.launch { theatre.get(TheatreFeature.VOLUME) }
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
            var failed = emptyList<SceneStep>()
            var finished = false
            try {
                SceneRunner(actuator).run(steps).collect { p ->
                    failed = p.failed
                    progress.update { it + (sceneId to SceneRun(p.fraction)) }
                }
                finished = true
            } finally {
                // A failed run keeps its count on the card and pill until the scene is applied again;
                // a cancelled one (another scene took over) clears, so no fill is left stuck half-way.
                progress.update {
                    if (!finished || failed.isEmpty()) it - sceneId else it + (sceneId to SceneRun(null, SceneFailure(failed.size, steps.size)))
                }
            }
            val detail = if (failed.isEmpty()) {
                null
            } else {
                failureDetail("scene: apply", outcome = "${failed.size} of ${steps.size} steps failed", steps = failed.map { "failed $it" })
            }
            mutableEvents.emit(TuttiEvent.SceneFinished(sceneId, failed.size, detail))
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
            is DockAction.SetBar -> setBarValue(action.featureKey, action.value)
            is DockAction.Key -> if (action.long) atv.longPress(action.keyCode) else atv.sendKey(action.keyCode)
            is DockAction.Ircc -> {
                val result = bravia?.sendIrcc(action.code)
                if (result !is BraviaResult.Ok) actionFailed("shortcut: IRCC key", outcomeOf(result))
            }
            is DockAction.PictureMode -> if (bravia == null) actionFailed("shortcut: picture mode ${action.mode}", outcomeOf(null)) else setTvSetting(TvSettingGroup.PICTURE, "pictureMode", action.mode)
            is DockAction.TvSoundOutput -> when {
                bravia == null -> actionFailed("shortcut: sound output ${action.terminal}", outcomeOf(null))
                // Readable on this TV: the optimistic setter, which the settings screen also reads back.
                tv.value.settings.sound.any { it.target == io.kud.tutti.protocol.bravia.TvSetting.OUTPUT_TERMINAL } ->
                    setTvSetting(TvSettingGroup.SOUND, io.kud.tutti.protocol.bravia.TvSetting.OUTPUT_TERMINAL, action.terminal)
                else -> setOutputTerminal(action.terminal)
            }
            is DockAction.Macro -> for (step in action.steps) {
                when (step) {
                    is MacroStep.Wait -> delay(step.millis.coerceIn(0, MAX_MACRO_WAIT_MS))
                    is MacroStep.Run -> if (step.action !is DockAction.Macro) perform(step.action)
                }
            }
        }
    }

    /** A shortcut's "set to a value" on the bar: a level as its number, anything else as the wire string. */
    private suspend fun setBarValue(featureKey: String, value: String) {
        val feature = TheatreFeature.fromKey(featureKey) ?: return
        when (feature.kind) {
            is FeatureKind.Level -> value.toIntOrNull()?.let { theatre.setLevel(feature, it) }
            is FeatureKind.Toggle, is FeatureKind.Choice -> theatre.set(feature, value)
            FeatureKind.ReadOnly -> Unit
        }
    }

    /** Replaces pin [index] whole (on any page): action, long press, name and icon. */
    fun saveSlot(index: Int, slot: DockSlot) {
        scope.launch { store.updateDock { it.withSlot(index, slot) } }
    }

    fun assignDock(index: Int, primary: DockAction?, secondary: DockAction?) {
        scope.launch { store.updateDock { it.withSlot(index, DockSlot(primary, secondary)) } }
    }

    /** Pin [from] moved to [to] (flat index across pages), from the layout editor. */
    fun moveDockSlot(from: Int, to: Int) {
        scope.launch { store.updateDock { it.withSlotMoved(from, to) } }
    }

    /** The remote screen's layout, changed against what is stored now (never a stale copy). */
    fun updateLayout(transform: (RemoteLayout) -> RemoteLayout) {
        scope.launch { store.updatePrefs { it.copy(layout = transform(it.layout)) } }
    }

    /** Back to the screen as it shipped; pins and their pages are his and stay. */
    fun resetLayout() {
        scope.launch { store.updatePrefs { it.copy(layout = RemoteLayout()) } }
    }

    /** A new empty page of pins at the end (three at most). */
    fun addDockPage() {
        scope.launch { store.updateDock { it.withPageAdded() } }
    }

    /** Page [page] goes, with its pins; the first page left always stays. */
    fun removeDockPage(page: Int) {
        scope.launch { store.updateDock { it.withPageRemoved(page) } }
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
            pairing.value = PairingUi.Failed(PairingError.UNREACHABLE, failureDetail("pair tv", outcome = "not a private LAN address", sensitive = listOf(address)))
            return
        }
        pairingJob?.cancel()
        pairingJob = scope.launch {
            pairing.value = PairingUi.Connecting
            atv.disconnect()
            val steps = mutableListOf<String>()
            var session: AtvPairing? = null
            fun trace() = steps + (session?.trace ?: emptyList())
            fun detail(outcome: String, error: Throwable? = null) =
                failureDetail("pair tv (TLS 6467)", outcome = outcome, steps = trace(), error = error, sensitive = listOf(address))
            var watchdog: Job? = null
            // The watchdog lives in the app scope: it must stop with this attempt and only ever
            // cancel this attempt, never a newer one started after a cancel.
            val attemptJob = coroutineContext.job
            attemptJob.invokeOnCompletion { watchdog?.cancel() }
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
                            pairing.value = PairingUi.Failed(PairingError.REFUSED, detail("no answer after the code"))
                            attemptJob.cancel()
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
                pairing.value = PairingUi.Failed(PairingError.UNREACHABLE, detail("failed before pairing finished", e))
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
                    PairingFailure.BadCode -> PairingUi.Failed(PairingError.BAD_CODE, detail("the TV rejected the code"))
                    PairingFailure.Closed -> PairingUi.Failed(PairingError.UNREACHABLE, detail("closed before pairing finished"))
                    else -> PairingUi.Failed(PairingError.REFUSED, detail("refused: ${result.failure}"))
                }
            }
        }
    }

    /**
     * The redacted detail behind a failure the UI shows, for "Copy details": [FailureReport] with
     * the app version, Android, live link states and what is configured (yes or no, never values).
     * Hosts, the MAC and the PSK are scrubbed by value, on top of [FailureReport]'s own patterns.
     */
    fun failureDetail(
        what: String,
        outcome: String? = null,
        steps: List<String> = emptyList(),
        error: Throwable? = null,
        sensitive: List<String> = emptyList(),
    ): String {
        val devices = state.value.devices
        val l = links.value
        return FailureReport.render(
            what = what,
            outcome = outcome,
            steps = steps,
            error = error,
            context = listOf(
                "app" to AppVersion.of(context),
                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "links" to "remote ${l.tv}, rest ${l.rest}, bar ${l.bar}",
                "configured" to "tv ${devices.tv != null}, bar ${devices.bar != null}, psk ${psk != null}",
            ),
            sensitive = sensitive + listOfNotNull(devices.tv?.host, devices.tv?.mac, devices.bar?.host, psk),
        )
    }

    private fun actionFailed(what: String, outcome: String) {
        mutableEvents.tryEmit(TuttiEvent.ActionFailed(failureDetail(what, outcome)))
    }

    private fun outcomeOf(result: BraviaResult<*>?): String = when (result) {
        null -> "no IP control session (rest ${links.value.rest})"
        is BraviaResult.Failure -> result.error.toString()
        is BraviaResult.Ok -> "ok"
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
            val ircc = if (caps.supports(BraviaService.SYSTEM, "getRemoteControllerInfo")) {
                client.remoteControllerInfo().getOrNull().orEmpty()
            } else {
                emptyList()
            }
            val sleep = io.kud.tutti.protocol.bravia.Ircc.sleepTimerCode(ircc)
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
                        ircc = io.kud.tutti.protocol.bravia.Ircc.shortcuts(ircc),
                        loaded = true,
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
                actionFailed("tv setting: $group $target = $value", outcomeOf(result))
                loadTvSettings()
            }
        }
    }

    fun setWol(enabled: Boolean) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(wol = enabled)) }
        scope.launch {
            val result = client.setWolMode(enabled)
            if (result !is BraviaResult.Ok) {
                actionFailed("tv setting: wake-on-lan = $enabled", outcomeOf(result))
                loadTvSettings()
            }
        }
    }

    fun setScene(value: String) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(scene = it.settings.scene?.copy(currentValue = value))) }
        scope.launch {
            val result = client.setSceneSetting(value)
            if (result !is BraviaResult.Ok) {
                actionFailed("tv setting: scene = $value", outcomeOf(result))
                loadTvSettings()
            }
        }
    }

    /** Write-only: no documented getter, so nothing to roll back to on failure beyond the event. */
    fun setOutputTerminal(value: String) {
        val client = bravia ?: return
        scope.launch {
            val result = client.setSoundSetting(io.kud.tutti.protocol.bravia.TvSetting.OUTPUT_TERMINAL, value)
            if (result !is BraviaResult.Ok) {
                actionFailed("tv setting: sound output = $value", outcomeOf(result))
            }
        }
    }

    /** One press of the TV's own sleep-timer key: the TV cycles its steps and shows the choice. */
    fun pressSleepTimer() {
        val client = bravia ?: return
        val code = tv.value.settings.sleepCode ?: return
        scope.launch {
            val result = client.sendIrcc(code)
            if (result !is BraviaResult.Ok) actionFailed("tv: sleep timer key", outcomeOf(result))
        }
    }

    fun setPowerSaving(mode: String) {
        val client = bravia ?: return
        tv.update { it.copy(settings = it.settings.copy(powerSaving = mode)) }
        scope.launch {
            val result = client.setPowerSavingMode(mode)
            if (result !is BraviaResult.Ok) {
                actionFailed("tv setting: power saving = $mode", outcomeOf(result))
                loadTvSettings()
            }
        }
    }

    // endregion

    // region preferences

    /** Keeps the old [AppPrefs.haptics] in step, so a downgrade reads the same switch. */
    fun setVibration(level: VibrationLevel) {
        scope.launch { store.updatePrefs { it.copy(vibration = level, haptics = level != VibrationLevel.OFF) } }
    }

    fun setKeySounds(enabled: Boolean) {
        scope.launch { store.updatePrefs { it.copy(keySounds = enabled) } }
    }

    fun setDeveloperMode(enabled: Boolean) {
        scope.launch { store.updatePrefs { it.copy(developerMode = enabled) } }
    }

    fun setAppShown(packageName: String, shown: Boolean) {
        scope.launch {
            store.updatePrefs { it.copy(hiddenApps = if (shown) it.hiddenApps - packageName else it.hiddenApps + packageName) }
        }
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
        private const val PERSIST_VOLUME_MS = 1_000L
        /** How long Power waits for a sleeping device: a TV woken by a magic packet needs a few seconds for its network. */
        private const val WAKE_TIMEOUT_MS = 12_000L
        private const val WAKE_RETRY_MS = 1_500L
        private const val WOL_SENDS = 3
        private const val WOL_RESEND_MS = 2_000L
        private const val POWER_KNOWN_MS = 2_000L
        private const val VOLUME_SEND_INTERVAL_MS = 60L
        private const val WAKE_SETTLE_MS = 1_500L
        private const val MAX_MACRO_WAIT_MS = 30_000L
        private const val SECRET_ACK_TIMEOUT_MS = 12_000L
    }
}

/** The cache file for a package's icon: the package name itself, or null for anything that is not one. */
internal fun iconFileName(packageName: String): String? =
    packageName.takeIf { Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(it) }

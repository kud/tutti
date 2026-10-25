package io.kud.tutti.controller

import io.kud.tutti.platform.data.VibrationLevel
import android.app.UiModeManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
import io.kud.tutti.platform.net.OkHttpBarHttpTransport
import io.kud.tutti.platform.net.OkHttpBraviaTransport
import io.kud.tutti.platform.net.PinMismatchException
import io.kud.tutti.platform.net.WakeOnLanSender
import io.kud.tutti.platform.security.ClientIdentityStore
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.apps.AppIcons
import io.kud.tutti.protocol.apps.AppLauncher
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.apps.AppsScreenOpener
import io.kud.tutti.protocol.apps.AppsScreenRoute
import io.kud.tutti.protocol.apps.IconAttempt
import io.kud.tutti.protocol.apps.IconBook
import io.kud.tutti.protocol.apps.IconOutcome
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.protocol.apps.LaunchBook
import io.kud.tutti.protocol.apps.LaunchEffect
import io.kud.tutti.protocol.apps.LaunchLink
import io.kud.tutti.protocol.apps.LaunchPlan
import io.kud.tutti.protocol.apps.AppHistory
import io.kud.tutti.protocol.apps.LaunchQueue
import io.kud.tutti.protocol.apps.LaunchStep
import io.kud.tutti.protocol.apps.LaunchResult
import io.kud.tutti.protocol.apps.LaunchRoute
import io.kud.tutti.protocol.net.Redact
import io.kud.tutti.protocol.bravia.TvApp
import java.io.File
import io.kud.tutti.protocol.atv.AtvPairing
import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.atv.ClientDeviceInfo
import io.kud.tutti.protocol.atv.HardwareKey
import io.kud.tutti.protocol.atv.KeyHold
import io.kud.tutti.protocol.atv.KeyMap
import io.kud.tutti.protocol.atv.PairingFailure
import io.kud.tutti.protocol.atv.PairingResult
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.protocol.bravia.AuthCookie
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.BraviaService
import io.kud.tutti.protocol.bravia.IpRegistration
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.bravia.Registration
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
import io.kud.tutti.protocol.scenes.SceneUndo
import io.kud.tutti.protocol.scenes.SceneValue
import io.kud.tutti.protocol.theatre.BarHttpClient
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.SilenceWatch
import io.kud.tutti.protocol.theatre.TheatreClient
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState
import io.kud.tutti.platform.voice.VoiceCapture
import io.kud.tutti.protocol.atv.VoiceEvent
import io.kud.tutti.protocol.atv.VoiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
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
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + loggingHandler { Log.e(TAG, it) })

    private val theatre = TheatreClient(tcp, scope)
    private val atv = AtvRemote(tls, scope, ClientDeviceInfo(model = "tutti", packageName = context.packageName))

    @Volatile private var psk: String? = null

    /** Whether a PSK is stored, for the setup checklist; never the key itself. */
    private val pskSet = MutableStateFlow(false)

    /** The IP-control cookie (Sony's "Normal" auth, from the code shown on the TV). Never logged. */
    @Volatile private var ipCookie: AuthCookie? = null
    private val ipCode = MutableStateFlow(IpCodeStatus.NONE)

    /** When renewal was last tried (epoch ms), so a TV that renews without a new cookie, or can't be reached, is not asked every poll. */
    @Volatile private var renewTriedAt = 0L

    /** The transport and client id between asking the TV for its code and sending it back. */
    @Volatile private var codeSession: Pair<OkHttpBraviaTransport, String>? = null
    private val technical = MutableStateFlow(TechnicalReadouts())
    @Volatile private var bravia: BraviaClient? = null
    @Volatile private var braviaTransport: OkHttpBraviaTransport? = null

    /** Official icons, cached by package in the app cache dir. */
    private val iconDir: File get() = File(context.cacheDir, "app-icons")
    private val icons = MutableStateFlow<Map<String, String>>(emptyMap())
    private val iconBook = MutableStateFlow(IconBook())
    private val iconPass = Mutex()
    @Volatile private var tvHostForIcons: String? = null
    private val library = combine(store.apps, icons) { seen, cached -> AppLibrary.all(seen) to cached }

    private val links = MutableStateFlow(Links())
    private val linkNotes = MutableStateFlow(LinkNotes())
    private val tv = MutableStateFlow(TvState())
    private val voiceZoom = MutableStateFlow(false)
    private val progress = MutableStateFlow<Map<String, SceneRun>>(emptyMap())
    private val activeSceneId = MutableStateFlow<String?>(null)
    /** One level of "undo the last scene", taken just before a scene runs ([SceneUndo]). */
    private val sceneUndo = MutableStateFlow<SceneUndo?>(null)
    /** The high-volume guard's runtime: unlocked, its pending question, the quick surfaces' notice. */
    private val guard = MutableStateFlow(GuardRuntime())
    /** Bumped when a cap for tonight runs out, so the state drops it on time. */
    private val capClock = MutableStateFlow(0)
    private val pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    private val overrides = MutableStateFlow(Overrides())
    /** App launches, optimistic ([LaunchQueue]): what is in flight and the app shown meanwhile. */
    private val launches = MutableStateFlow(LaunchBook())
    private val restart = MutableStateFlow(0)
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Latest level to send; a Channel, not a StateFlow, so setting the level it already holds still sends it. */
    private val volumeTarget = Channel<Int>(Channel.CONFLATED)
    private val waking = MutableStateFlow(false)
    private val wakeOnLan = WakeOnLanSender(context)

    /** How the remote's last connect failed (`ErrorClass`), so a failed wake can tell "nothing there" from "refused". */
    @Volatile
    private var tvLastConnect: String? = null

    /** The TV's remote link was up this session, and went down at [tvDownSince] (elapsedRealtime). */
    @Volatile private var tvWasUp = false

    @Volatile private var tvDownSince: Long? = null

    private val mutableEvents = MutableSharedFlow<TuttiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<TuttiEvent> = mutableEvents.asSharedFlow()

    private data class Overrides(val volume: Int? = null, val powered: Boolean? = null)

    private data class GuardRuntime(val unlocked: Boolean = false, val ask: VolumeAsk? = null, val notice: Boolean = false)

    private data class VolumeExtra(val guard: GuardRuntime, val undo: SceneUndo?, val capClock: Int)

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
        val launches: LaunchBook,
    )

    private val stored = combine(store.devices, store.prefs, store.dock, store.scenes, store.profiles) { d, p, k, s, f -> Stored(d, p, k, s, f) }
    private val live = combine(tv, atv.state, theatre.state, links, voiceZoom) { t, r, b, l, v -> Live(t, r, b, l, v) }
    private val extra = combine(progress, activeSceneId, pairing, cloud.state, combine(overrides, waking, linkNotes, launches) { o, w, n, l -> Quad(o, w, n, l) }) { p, a, q, c, o ->
        Extra(p, a, q, c, o.overrides, o.waking, o.linkNotes, o.launches)
    }

    private data class Quad(val overrides: Overrides, val waking: Boolean, val linkNotes: LinkNotes, val launches: LaunchBook)

    private val volumeExtra = combine(guard, sceneUndo, capClock) { g, u, c -> VolumeExtra(g, u, c) }

    private val credentials = combine(pskSet, ipCode, ::Pair)

    val state: StateFlow<TuttiUiState> = combine(stored, live, extra, library, combine(credentials, technical, volumeExtra, ::Triple)) { s, l, e, a, k ->
        build(s, l, e, k.third).copy(apps = a.first, appIcons = a.second, linkNotes = e.linkNotes, pskSet = k.first.first, ipCode = k.first.second, technical = k.second)
    }
        .stateIn(scope, SharingStarted.Eagerly, TuttiUiState())

    /** Set by the remote screen while it is in front: volume rocker and hardware keyboard go to the devices. */
    @Volatile var remoteInFront: Boolean = false

    /** Set while the keyboard mirror field has focus: printable hardware keys stay in the field. */
    @Volatile var typing: Boolean = false

    private val lock = Any()
    private var holders = 0
    private var sessionsJob: Job? = null
    private var epoch = 0
    private var lingerJob: Job? = null
    private var sceneJob: Job? = null
    private var pairingJob: Job? = null
    private var overrideJob: Job? = null
    @Volatile private var codeAnswer: CompletableDeferred<String?>? = null

    init {
        scope.launch {
            pskSet.value = secure.tvPsk() != null
            ipCookie = secure.tvIpCookie()
            if (ipCookie != null && ipCode.value == IpCodeStatus.NONE) ipCode.value = IpCodeStatus.SET
        }
        scope.launch(Dispatchers.IO) {
            // A cached file that is not an image (an error page saved by an older build) would
            // count as cached forever and never be fetched again: drop it instead.
            val files = iconDir.listFiles().orEmpty().filter { it.isFile }
            val (cached, broken) = files.partition { file ->
                !file.name.endsWith(".part") && file.length() > 0 &&
                    runCatching { file.inputStream().use { input -> ByteArray(16).let { head -> AppIcons.looksLikeImage(head.copyOf(input.read(head).coerceAtLeast(0))) } } }.getOrDefault(false)
            }
            broken.forEach { it.delete() }
            icons.update { current -> cached.associate { it.name to it.path } + current }
        }
        scope.launch {
            for (level in volumeTarget) {
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
            // The TV's own report replaces an optimistic app once no launch is in flight (LaunchQueue).
            atv.state.map { it.currentApp }.distinctUntilChanged().collect { app ->
                launchStep { LaunchQueue.foreground(it, app) }
            }
        }
        scope.launch {
            // The app before the one in front, for long-press Back (AppHistory).
            atv.state.map { it.currentApp }.distinctUntilChanged().collect { app -> appHistory.update { it.seen(app) } }
        }
        scope.launch {
            // The high-volume guard re-arms once the bar is below its line again, or once the bar's
            // link drops (a new session); a quick surface's notice goes with it.
            state.map { Triple(it.volume, it.bar.connected, VolumeRules.threshold(it.prefs.volumeGuardLevel)) }.distinctUntilChanged().collect { (level, connected, line) ->
                guard.update { g ->
                    val unlocked = connected && VolumeRules.stillUnlocked(g.unlocked, level, line)
                    val notice = g.notice && connected && (level == null || level >= line)
                    if (unlocked == g.unlocked && notice == g.notice) g else g.copy(unlocked = unlocked, notice = notice)
                }
            }
        }
        scope.launch {
            // A cap for tonight ends on time, and is then forgotten.
            store.prefs.map { it.tonightCap }.distinctUntilChanged().collectLatest { cap ->
                if (cap == null) return@collectLatest
                val left = cap.until - System.currentTimeMillis()
                if (left > 0) delay(left)
                capClock.update { it + 1 }
                store.updatePrefs { prefs -> if (prefs.tonightCap == cap) prefs.copy(tonightCap = null) else prefs }
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
        scope.launch {
            // The calm rule (TvLinkRule): what the remote shows of the TV's link, judged again at
            // each threshold. UNREACHABLE and CONNECTING alternating in a reconnect keep the clock.
            links.map { it.tv }.distinctUntilChanged().collectLatest { raw ->
                when (raw) {
                    Link.ONLINE -> {
                        tvWasUp = true
                        tvDownSince = null
                    }
                    Link.ABSENT, Link.NEEDS_PAIRING, Link.UNAUTHORIZED -> {
                        tvWasUp = false
                        tvDownSince = null
                    }
                    Link.CONNECTING, Link.UNREACHABLE -> if (tvWasUp && tvDownSince == null) tvDownSince = SystemClock.elapsedRealtime()
                }
                while (true) {
                    val down = tvDownSince?.let { SystemClock.elapsedRealtime() - it }
                    val shown = TvLinkRule.shown(raw, down)
                    links.update { if (it.tvShown == shown) it else it.copy(tvShown = shown) }
                    delay(TvLinkRule.nextChangeInMs(down) ?: break)
                }
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
        retainSession()
    }

    /**
     * [retain], returning the session epoch ([Links.epoch]) whose links the caller may trust:
     * a link reported under an older epoch belongs to sessions that have stopped. A new
     * generation starts only once the old one has finished, so its `finally` blocks can never
     * clear what the new one set.
     */
    fun retainSession(): Int {
        val current = synchronized(lock) {
            holders++
            lingerJob?.cancel()
            lingerJob = null
            if (sessionsJob?.isActive != true) {
                val previous = sessionsJob
                val next = ++epoch
                sessionsJob = scope.launch {
                    previous?.cancelAndJoin()
                    runSessions(next)
                }
            }
            epoch
        }
        wake.tryEmit(Unit)
        return current
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

    private suspend fun runSessions(epoch: Int) {
        // Whatever the stopped sessions left is not news: every link is asked again under this epoch.
        links.update { it.restarted(epoch) }
        // Coming back to the app is a fresh blip, not the minutes spent away.
        if (tvWasUp) tvDownSince = SystemClock.elapsedRealtime()
        psk = secure.tvPsk()
        pskSet.value = psk != null
        ipCookie = secure.tvIpCookie()
        if (ipCookie == null) ipCode.value = IpCodeStatus.NONE else if (ipCode.value == IpCodeStatus.NONE) ipCode.value = IpCodeStatus.SET
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
                            // DRC and Auto Volume never notify; polling also proves the link is alive:
                            // a bar that died without closing the socket answers nothing (SilenceWatch).
                            var polls = 0
                            val watch = SilenceWatch()
                            while (isActive) {
                                delay(POLL_BAR_MS)
                                val before = theatre.heard
                                theatre.get(TheatreFeature.DRC)
                                theatre.get(TheatreFeature.AUTO_VOLUME)
                                theatre.get(TheatreFeature.POWER)
                                if (watch.afterPoll(before, theatre.heard)) {
                                    linkNotes.update { it.copy(bar = failureDetail("soundbar: poll", outcome = "two polls in a row went unanswered")) }
                                    theatre.disconnect()
                                    break
                                }
                                // Every third poll, ask again (paced) for what the bar has neither
                                // reported nor refused: a missed answer is not a missing feature.
                                if (++polls % REASK_EVERY_POLLS == 0) theatre.refreshMissing()
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
            links.update { it.copy(bar = it.bar.stopped()) }
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
        val identity = try {
            identities.getOrCreate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The Keystore can fail (locked, wiped, a vendor bug): the remote is down, not the app.
            links.update { it.copy(tv = Link.UNREACHABLE) }
            linkNotes.update { it.copy(tv = failureDetail("tv remote: client identity (Keystore)", outcome = "could not load or create", error = e)) }
            return
        }
        var wait = MIN_BACKOFF_MS
        var handshakeFailures = 0
        try {
            while (scope.isActive) {
                // Once the TV has refused our certificate twice, it keeps reading "pair again" between tries.
                if (!HandshakeRule.needsPairing(handshakeFailures)) links.update { it.copy(tv = Link.CONNECTING) }
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
                    if (e.hasCause<PinMismatchException>()) {
                        Link.NEEDS_PAIRING
                    } else {
                        // Only the TV refusing our certificate counts towards "pair again"; a reset,
                        // timeout or closed port is the network, and clears the count.
                        handshakeFailures = HandshakeRule.next(handshakeFailures, e)
                        if (HandshakeRule.needsPairing(handshakeFailures)) Link.NEEDS_PAIRING else Link.UNREACHABLE
                    }
                }
                if (outcome == Link.NEEDS_PAIRING) {
                    atv.disconnect()
                    links.update { it.copy(tv = Link.NEEDS_PAIRING) }
                    if (!HandshakeRule.needsPairing(handshakeFailures)) return
                    // Refused certificates may be a TV still waking its TLS stack: keep trying, slowly.
                    backoff(HandshakeRule.RETRY_MS)
                    continue
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
            links.update { it.copy(tv = it.tv.stopped()) }
        }
    }

    private suspend fun tvRestLoop(device: TvDevice?) {
        if (device == null) {
            bravia = null
            links.update { it.copy(rest = Link.ABSENT) }
            tv.value = TvState()
            return
        }
        if (psk == null && ipCookie == null) {
            // IP control needs the PSK or the code's cookie for everything past discovery, so
            // without one there is nothing to poll: retrying only repeated the same failure
            // (Erwann, 2026-10-24). TV settings explain how to set it up; setPsk and
            // submitTvCode restart this loop.
            bravia = null
            braviaTransport = null
            links.update { it.copy(rest = Link.ABSENT) }
            linkNotes.update { it.copy(rest = null) }
            return
        }
        val transport = OkHttpBraviaTransport(device.host, secure.tvRestPin()) { secure.setTvRestPin(it) }
        // The cookie wins when both exist (BraviaClient sends one credential, never both).
        val client = BraviaClient(transport, cookie = { ipCookie?.value }, psk = { psk })
        braviaTransport = transport
        tvHostForIcons = device.host
        bravia = client
        try {
            restSession(client, device)
        } finally {
            // A stopped session has no TV to talk to and no power reading to stand on.
            if (bravia === client) {
                bravia = null
                braviaTransport = null
            }
            links.update { it.copy(rest = it.rest.stopped()) }
            tv.update { it.copy(power = PowerStatus.UNKNOWN) }
        }
    }

    private suspend fun restSession(client: BraviaClient, device: TvDevice) {
        var wait = MIN_BACKOFF_MS
        while (scope.isActive) {
            if (psk == null && !keepCookieFresh(client)) {
                // The TV refused the cookie, renewal included: only a new code helps, which restarts this loop.
                links.update { it.copy(rest = Link.UNAUTHORIZED) }
                linkNotes.update { it.copy(rest = failureDetail("tv ip control: code cookie", outcome = "expired or refused, and not renewed")) }
                backoff(MAX_BACKOFF_MS)
                continue
            }
            links.update { it.copy(rest = Link.CONNECTING) }
            when (val found = client.discover()) {
                is BraviaResult.Ok -> {
                    wait = MIN_BACKOFF_MS
                    links.update { it.copy(rest = Link.ONLINE) }
                    linkNotes.update { it.copy(rest = null) }
                    tv.update { it.copy(capabilities = found.value) }
                    // A reconnect may follow installs made meanwhile: ask for the app list again.
                    tvWasActive = false
                    learnMac(client, device.host)
                    while (scope.isActive) {
                        if (!refreshTv(client)) break
                        if (psk == null && !keepCookieFresh(client)) break
                        delay(POLL_TV_MS)
                    }
                    links.update { it.copy(rest = Link.UNREACHABLE) }
                    linkNotes.update { it.copy(rest = failureDetail("tv ip control: poll", outcome = "answered, then stopped answering getPowerStatus")) }
                }
                is BraviaResult.Failure -> {
                    val link = when (found.error) {
                        BraviaError.Unauthorized -> Link.UNAUTHORIZED
                        // Another certificate than the pinned one: the TV was reset, or someone is in between.
                        BraviaError.PinMismatch -> Link.NEEDS_PAIRING
                        else -> Link.UNREACHABLE
                    }
                    links.update { it.copy(rest = link) }
                    linkNotes.update { it.copy(rest = failureDetail("tv ip control: discover (HTTPS/HTTP + PSK)", outcome = "$link: ${found.error}")) }
                    // A wrong PSK will not fix itself: wait for a new one (restart) or a long while.
                    if (link == Link.UNAUTHORIZED || link == Link.NEEDS_PAIRING) wait = MAX_BACKOFF_MS
                }
            }
            backoff(wait)
            wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /**
     * Before IP control is used with the code's cookie: renews it when due ([IpRegistration.needsRenewal])
     * or once the TV refused it ([IpCodeStatus.RENEW] pending a check). False only when the TV
     * refused the renewal too, so a new code is needed; a renewal that could not reach the TV keeps
     * the cookie for the next try. Renewal itself is unverified on a real TV ([IpRegistration]).
     */
    private suspend fun keepCookieFresh(client: BraviaClient, refused: Boolean = false): Boolean {
        val current = ipCookie ?: return true
        if (ipCode.value == IpCodeStatus.RENEW) return false
        val now = System.currentTimeMillis()
        if (!refused && !IpRegistration.needsRenewal(current, now)) return true
        if (!refused && now - renewTriedAt < RENEW_RETRY_MS && !IpRegistration.expired(current, now)) return true
        // Refused again right after a renewal the TV accepted: the cookie it kept is no good either.
        if (refused && now - renewTriedAt < REFUSED_AFTER_RENEW_MS) {
            ipCode.value = IpCodeStatus.RENEW
            return false
        }
        renewTriedAt = now
        return when (val renewed = client.renew(current, nowMs = now)) {
            is BraviaResult.Ok -> when (val outcome = renewed.value) {
                is Registration.Registered -> {
                    if (outcome.cookie != current) secure.setTvIpCookie(outcome.cookie)
                    ipCookie = outcome.cookie
                    ipCode.value = IpCodeStatus.SET
                    true
                }
                Registration.Refused, Registration.CodeShown -> {
                    ipCode.value = IpCodeStatus.RENEW
                    false
                }
            }
            is BraviaResult.Failure -> !refused && !IpRegistration.expired(current, now)
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

    /** The TV was on at the last poll since REST (re)connected; read and written by the poll only. */
    @Volatile
    private var tvWasActive = false

    /** When the poll last asked for the app list (elapsedRealtime), for [AppListRefresh]. */
    @Volatile
    private var appsAskedAt: Long? = null

    /** False when the TV stopped answering. */
    private suspend fun refreshTv(client: BraviaClient): Boolean {
        val power = when (val result = client.powerStatus()) {
            is BraviaResult.Ok -> result.value
            is BraviaResult.Failure -> when {
                result.error is BraviaError.Transport || result.error == BraviaError.PinMismatch -> return false
                // A refused cookie: renew it once, or stop polling until a new code.
                result.error == BraviaError.Unauthorized && psk == null && ipCookie != null -> {
                    keepCookieFresh(client, refused = true)
                    return false
                }
                else -> PowerStatus.UNKNOWN
            }
        }
        val caps = client.capabilities
        val active = power == PowerStatus.ACTIVE
        // getPlayingContentInfo is a `private` method: without a PSK it can only be refused, so it is
        // not asked. It rides this poll (POLL_TV_MS), never a loop of its own. A failed or empty
        // answer (what an app playing is expected to give) clears [TvState.playing], so the chip
        // falls back to the app name rather than keeping a stale channel.
        val playing = if (active && (!psk.isNullOrEmpty() || ipCookie != null)) client.playingContent().getOrNull() else null
        val input = playing?.uri
        val pictureMode = if (active && caps.supports(BraviaService.VIDEO, "getPictureQualitySettings")) {
            client.pictureSettings("pictureMode").getOrNull()?.firstOrNull { it.target == "pictureMode" }?.currentValue
        } else {
            null
        }
        val current = tv.value
        val inputs = if (current.inputs.isEmpty() && active) client.externalInputs().getOrNull().orEmpty() else current.inputs
        // The app list rides this poll too ([AppListRefresh]): until it is known, on every wake and
        // reconnect, and every AppListRefresh.EVERY_MS while on, so an app installed later shows up.
        val now = SystemClock.elapsedRealtime()
        val askApps = AppListRefresh.due(active, tvWasActive, current.apps.isNotEmpty(), appsAskedAt, now)
        tvWasActive = active
        val fetched = if (askApps) client.applications().getOrNull().also { appsAskedAt = now } else null
        // A failed or empty answer keeps the list we had.
        val apps = fetched?.takeIf { it.isNotEmpty() } ?: current.apps
        tv.update { it.copy(power = power, input = input ?: it.input, playing = playing, pictureMode = pictureMode ?: it.pictureMode, inputs = inputs, apps = apps) }
        if (apps != current.apps) {
            // New or changed apps go into the library, whose icon pass then fetches what is missing.
            scope.launch { rememberApps(apps) }
        } else if (active && apps.isNotEmpty()) {
            // Icons that failed are asked again on later polls, each on its own backoff ([IconBook]).
            scope.launch { fetchMissingIcons() }
        }
        return true
    }

    // endregion

    // region state

    private fun build(s: Stored, l: Live, e: Extra, v: VolumeExtra): TuttiUiState {
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
        val sceneCap = s.scenes.firstOrNull { it.id == active }?.settings?.get(SceneKey.VOLUME_CAP)?.let { (it as? SceneValue.Level)?.value }
        val wall = System.currentTimeMillis()
        val tonight = VolumeRules.tonightActive(s.prefs.tonightCap, wall)
        val cap = VolumeRules.cap(sceneCap, tonight, wall, MAX_VOLUME)
        val tvPowered = l.remote.powered ?: when (l.tv.power) {
            PowerStatus.ACTIVE -> true
            PowerStatus.STANDBY -> false
            PowerStatus.UNKNOWN -> null
        }
        // Live only: a bar that dropped has no power to report, whatever it said before.
        val barPowered = l.bar.liveOn(TheatreFeature.POWER)
        val powered = e.overrides.powered ?: when {
            tvPowered == true || barPowered == true -> true
            tvPowered == false || barPowered == false -> false
            else -> null
        }
        val volume = volumeShown(e.overrides.volume, l.bar.liveLevel(TheatreFeature.VOLUME), s.prefs.lastBarVolume, l.bar.connected)
        val guardState = VolumeGuardState(s.prefs.volumeGuard, VolumeRules.threshold(s.prefs.volumeGuardLevel), v.guard.unlocked)
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
            volume = volume,
            volumeCap = cap,
            sceneVolumeCap = sceneCap,
            tonightCap = tonight,
            volumeGuardLine = VolumeRules.guardLine(guardState, volume, cap ?: MAX_VOLUME).takeIf { l.bar.connected },
            volumeLandmarks = VolumeRules.landmarks(s.prefs.volumePresets, guardState, tonight?.level),
            volumeAsk = v.guard.ask,
            volumeGuardNotice = v.guard.notice,
            sceneUndo = v.undo?.takeIf { !it.stale(SystemClock.elapsedRealtime(), active, powered) },
            powered = powered,
            waking = e.waking,
            pairing = e.pairing,
            cloud = e.cloud,
            launches = e.launches,
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
        scope.launch { sendTvKey(keyCode, long = false) }
    }

    /** The TV's foreground app and the one before it, from the remote v2's reports. */
    private val appHistory = MutableStateFlow(AppHistory())

    /**
     * Long-press Back: back to the app before the one in front, through the optimistic launch
     * path ([launchApp], [LaunchQueue]), so a blip holds it like any tap. False when there is none yet.
     */
    fun launchPreviousApp(): Boolean {
        val target = appHistory.value.back() ?: return false
        launchApp(target)
        return true
    }

    /**
     * Long-press Home: the TV's recent apps (APP_SWITCH, 187). Google TV may ignore it, so the
     * outcome is logged for the check on the BRAVIA: whether it went out, and whether the
     * foreground app moved within [RECENTS_WATCH_MS]. No package or text is logged.
     */
    fun recentApps() {
        scope.launch {
            val before = atv.state.value.currentApp
            val sent = sendTvKey(V2Key.APP_SWITCH, long = false)
            delay(RECENTS_WATCH_MS)
            Log.i(TAG, "APP_SWITCH sent=$sent foregroundChanged=${before != atv.state.value.currentApp}")
        }
    }

    /** Long-press Select is reserved for the TV: a held key, for [KeyHold.MIN_HOLD_MS] (TalkBack, pins). */
    fun longPress(keyCode: Int) {
        scope.launch { sendTvKey(keyCode, long = true) }
    }

    /**
     * A key held by a finger (OK held on the pad): down on the TV now (START_LONG), up on
     * [releaseKey] (END_LONG), never before [KeyHold.MIN_HOLD_MS] nor after [KeyHold.MAX_HOLD_MS].
     * Hold and release run in the order they were made, so a quick hold-and-lift never lifts first.
     * A hold the link can't carry is dropped and said, as a long press is ([TvLinkRule.whileDown]).
     */
    fun holdKey(keyCode: Int) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holdOrder.withLock { holdNow(keyCode) } }
    }

    /** The finger left: the held key comes up. Nothing when nothing is held. */
    fun releaseKey() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holdOrder.withLock { releaseNow() } }
    }

    private val holdOrder = Mutex()
    private var holding: HeldTvKey? = null
    private var holdCap: Job? = null

    private class HeldTvKey(val keyCode: Int, val since: Long)

    private suspend fun holdNow(keyCode: Int) {
        releaseNow()
        if (atv.state.value.ready && atv.holdKey(keyCode)) {
            val held = HeldTvKey(keyCode, SystemClock.uptimeMillis())
            holding = held
            holdCap = scope.launch {
                delay(KeyHold.MAX_HOLD_MS)
                // Not cancelled by the release it makes: it is the cap job itself.
                holdOrder.withLock {
                    if (holding === held) {
                        holdCap = null
                        releaseNow()
                    }
                }
            }
            return
        }
        val current = links.value
        mutableEvents.tryEmit(TuttiEvent.KeyNotSent(TvLinkRule.whileDown(keyCode, long = true, current.tv, current.tvShown)))
    }

    private suspend fun releaseNow() {
        val held = holding ?: return
        holding = null
        holdCap?.cancel()
        holdCap = null
        delay(KeyHold.releaseDelay(held.since, SystemClock.uptimeMillis()))
        atv.releaseKey(held.keyCode)
    }

    /** Bumped by every held key, so only the newest one waiting is sent. */
    private val heldKey = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Sends a TV key, never dropping it silently: with the remote link down, [TvLinkRule.whileDown]
     * holds a one-shot key for [TvLinkRule.HOLD_MS] or drops it, and either way says so
     * ([TuttiEvent.KeyNotSent]). Before this, `AtvRemote.send` returned false and nothing listened.
     */
    private suspend fun sendTvKey(keyCode: Int, long: Boolean): Boolean {
        val send: suspend () -> Boolean = { if (long) atv.longPress(keyCode) else atv.sendKey(keyCode) }
        if (atv.state.value.ready && send()) return true
        val current = links.value
        val outcome = TvLinkRule.whileDown(keyCode, long, current.tv, current.tvShown)
        mutableEvents.tryEmit(TuttiEvent.KeyNotSent(outcome))
        if (outcome != KeyOutcome.HELD) return false
        val mine = heldKey.incrementAndGet()
        val back = withTimeoutOrNull(TvLinkRule.HOLD_MS) { atv.state.first { it.ready } } != null
        if (mine != heldKey.get()) return false
        if (back && send()) return true
        mutableEvents.tryEmit(TuttiEvent.KeyNotSent(KeyOutcome.EXPIRED))
        return false
    }

    /** Replaces the TV field's text (each keystroke, live). */
    fun text(text: String) {
        atv.type(text)
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) { voiceOrder.withLock { startVoiceNow() } }
    }

    /** Press, release and cancel run in the order they were made: a quick tap never finishes before it started. */
    private val voiceOrder = Mutex()

    private suspend fun startVoiceNow() {
        run {
            atv.startVoice()
            if (!atv.voice.value.capturing) return
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) { voiceOrder.withLock { atv.finishVoice() } }
    }

    /** Slide off, background, or leaving the screen. */
    fun cancelVoice() {
        micJob?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { voiceOrder.withLock { atv.cancelVoice() } }
    }

    fun clearVoiceFailure() = atv.clearVoiceFailure()

    // endregion

    /**
     * Opens an app, optimistically (Erwann, 2026-10-25: "faut un click optimiste"). The remote
     * shows the app at once ([TuttiUiState.launches]); the launch goes out now when a route can,
     * waits up to [LaunchQueue.HOLD_MS] for a link that is only blipping, or is refused with the
     * words a key gets ([TvLinkRule.launchWhileDown]). A repeat tap of the app in flight sends
     * nothing; a different app replaces it. Runs in the controller's scope, so leaving the sheet
     * never cancels a launch half way.
     */
    fun launchApp(packageName: String): Long? {
        val (id, effects) = tapLaunch(packageName, await = false)
        handleLaunch(effects)
        return id
    }

    /** [launchApp], awaiting the outcome: true once the launch went out (confirmed, or sent unobserved). */
    private suspend fun requestLaunch(packageName: String): Boolean {
        val (id, effects) = tapLaunch(packageName, await = true)
        handleLaunch(effects)
        return id?.let { launchWaiters[it]?.await() } ?: false
    }

    /**
     * One tap through [LaunchQueue.tap], synchronous so the book has changed before the caller
     * looks. Returns the launch the tap started or joined (null when refused), and the effects.
     */
    private fun tapLaunch(packageName: String, await: Boolean): Pair<Long?, List<LaunchEffect>> {
        val link = launchReadiness(packageName)
        var id: Long? = null
        val effects = launchStep { book ->
            LaunchQueue.tap(book, packageName, link, SystemClock.elapsedRealtime()).also { step ->
                id = step.book.inFlightFor(packageName)
                // Inside the step, so a launch that settles right after can't finish before its waiter exists.
                if (await) id?.let { launchWaiters.getOrPut(it) { CompletableDeferred() } }
            }
        }
        return id to effects
    }

    /** Callers awaiting a launch's end ([requestLaunch]), by launch id. */
    private val launchWaiters = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    /** The job sending or holding each launch, by id, so a newer tap can stop the one it replaced. */
    private val launchJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    /** Applies one [LaunchQueue] step atomically and returns what the caller must do. */
    private fun launchStep(transition: (LaunchBook) -> LaunchStep): List<LaunchEffect> {
        var effects: List<LaunchEffect> = emptyList()
        launches.update { book ->
            val step = transition(book)
            effects = step.effects
            step.book
        }
        return effects
    }

    private fun handleLaunch(effects: List<LaunchEffect>) {
        for (effect in effects) {
            when (effect) {
                is LaunchEffect.Send -> launchJobs[effect.id] = scope.launch {
                    val result = launchWithResult(effect.packageName)
                    launchJobs.remove(effect.id)
                    handleLaunch(launchStep { LaunchQueue.settled(it, effect.id, result.verdict, SystemClock.elapsedRealtime(), Redact.text(result.trace())) })
                }
                is LaunchEffect.Held -> {
                    mutableEvents.tryEmit(TuttiEvent.AppLaunchHeld(appLabel(effect.packageName)))
                    launchJobs[effect.id] = scope.launch {
                        val wait = (effect.until - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                        val back = withTimeoutOrNull(wait) { atv.state.first { it.ready } } != null
                        launchJobs.remove(effect.id)
                        val now = SystemClock.elapsedRealtime()
                        handleLaunch(launchStep { if (back) LaunchQueue.linkReady(it, effect.id, now) else LaunchQueue.tick(it, now) })
                    }
                }
                is LaunchEffect.Superseded -> launchJobs.remove(effect.id)?.cancel()
                is LaunchEffect.Done -> {
                    launchWaiters.remove(effect.id)?.complete(effect.ok)
                    if (effect.ok) scheduleLaunchTick()
                }
                is LaunchEffect.Failed -> mutableEvents.tryEmit(
                    TuttiEvent.AppNotOpened(appLabel(effect.failure.packageName), effect.failure.expired, effect.failure.detail),
                )
                is LaunchEffect.Refused -> {
                    val current = links.value
                    mutableEvents.tryEmit(TuttiEvent.KeyNotSent(TvLinkRule.launchWhileDown(current.tv, current.tvShown)))
                }
                is LaunchEffect.Repeat -> Unit
            }
        }
    }

    /** Lets an unconfirmed launch's app leave the screen once its grace is over. */
    private fun scheduleLaunchTick() {
        scope.launch {
            delay(LaunchQueue.SENT_GRACE_MS)
            launchStep { LaunchQueue.tick(it, SystemClock.elapsedRealtime()) }
        }
    }

    /** Forgets the last failed launch once its tile has shown it. */
    fun clearLaunchFailure() = launches.update(LaunchQueue::clearFailure)

    /**
     * Whether a launch can go now: the remote link ready, or IP control up with a uri the TV
     * listed. Else the shown link decides ([TvLinkRule.launchWhileDown]): held through a blip only.
     */
    private fun launchReadiness(packageName: String): LaunchLink {
        if (atv.state.value.ready) return LaunchLink.READY
        // IP control can launch without the remote link, with a uri the TV listed (now or before).
        val listed = tv.value.apps.any { AppLibrary.packageOf(it.uri) == packageName } ||
            state.value.apps.any { it.packageName == packageName && it.tvUri != null }
        if (bravia != null && links.value.rest == Link.ONLINE && listed) return LaunchLink.READY
        val current = links.value
        return if (TvLinkRule.launchWhileDown(current.tv, current.tvShown) == KeyOutcome.HELD) LaunchLink.ON_ITS_WAY else LaunchLink.DOWN
    }

    private suspend fun restUriFor(packageName: String): String? =
        tv.value.apps.firstOrNull { AppLibrary.packageOf(it.uri) == packageName }?.uri
            ?: store.apps.first().firstOrNull { it.packageName == packageName }?.tvUri

    /** An app's name for the words on screen: what the TV listed, else the catalogue's, else the package. */
    private fun appLabel(packageName: String): String =
        state.value.apps.firstOrNull { it.packageName == packageName }?.name
            ?: io.kud.tutti.protocol.atv.AppCatalog.byPackage(packageName)?.name
            ?: packageName

    /** The last launch's redacted trace, for the developer diagnostic. */
    @Volatile var lastLaunchTrace: String? = null
        private set

    /** A scene's launch: straight to the routes, since the scene has woken the TV and shows its own progress. */
    private suspend fun launchDirect(packageName: String): Boolean = launchWithResult(packageName).ok

    /**
     * Through IP control when it answers and the TV ever listed the app, then each app link the
     * catalogue knows, watching the remote link's foreground app between them ([AppLauncher]).
     */
    private suspend fun launchWithResult(packageName: String): LaunchResult {
        val client = bravia
        val restOnline = client != null && links.value.rest == Link.ONLINE
        val restUri = if (!restOnline) null else restUriFor(packageName)
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

    /** Keeps what the TV listed, then fetches the icons not cached yet. */
    private suspend fun rememberApps(fresh: List<TvApp>) {
        store.updateApps { AppLibrary.remember(it, fresh) }
        fetchMissingIcons()
    }

    /**
     * Fetches every listed icon not cached yet and due by [IconBook], one at a time, through
     * the transport current at each fetch (a reconnect mid-loop must not fail the rest). One
     * pass at a time; every outcome is kept for the diagnostic report.
     */
    private suspend fun fetchMissingIcons() {
        if (!iconPass.tryLock()) return
        try {
            val dir = iconDir.apply { mkdirs() }
            for (app in store.apps.first()) {
                val transport = braviaTransport ?: return
                val host = tvHostForIcons ?: return
                val name = iconFileName(app.packageName) ?: continue
                val now = System.currentTimeMillis()
                if (icons.value.containsKey(name)) {
                    if (iconBook.value.entries[app.packageName]?.last?.outcome != IconOutcome.CACHED) {
                        iconBook.update { it.record(app.packageName, app.iconUrl, "cache", IconAttempt(IconOutcome.CACHED), now) }
                    }
                    continue
                }
                if (!iconBook.value.due(app.packageName, app.iconUrl, now)) continue
                val attempt = when (val plan = AppIcons.plan(app.iconUrl, host)) {
                    is AppIcons.Plan.Skip -> plan.outcome.let { IconAttempt(it) } to plan.shape
                    is AppIcons.Plan.Fetch -> when (val got = transport.fetchIcon(plan.url, psk, cookie = ipCookie?.value)) {
                        is OkHttpBraviaTransport.IconFetch.Failed -> got.attempt to plan.shape
                        is OkHttpBraviaTransport.IconFetch.Image -> {
                            // A full disk or a vanished cache dir is a missing icon, never a crash.
                            val path = withContextIO {
                                runCatching {
                                    val part = File(dir, "$name.part")
                                    part.writeBytes(got.bytes)
                                    val target = File(dir, name)
                                    if (part.renameTo(target)) target.path else null
                                }.getOrNull()
                            }
                            if (path != null) icons.update { it + (name to path) }
                            (if (path != null) IconAttempt(IconOutcome.CACHED) else IconAttempt(IconOutcome.NETWORK, "cache write")) to plan.shape
                        }
                    }
                }
                iconBook.update { it.record(app.packageName, app.iconUrl, attempt.second, attempt.first, System.currentTimeMillis()) }
            }
        } finally {
            iconPass.unlock()
        }
    }

    /** Per-app icon outcomes this session, for the diagnostic report: package names and URL shapes, never a host. */
    fun iconReport(shown: (String) -> String? = { null }): List<String> = iconBook.value.report(shown).map { Redact.text(it) }

    private suspend fun <T> withContextIO(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    /**
     * The TV's own apps screen, over the remote link (no IP control needed). KEYCODE_ALL_APPS
     * alone did nothing on the XR80 (dev.71), so [AppsScreenOpener] tries an intent link first
     * and watches the foreground app; [TuttiEvent.AppsScreen] says what it saw. With the link
     * down it waits like a one-shot key ([TvLinkRule]) and says so.
     */
    /**
     * Closes every app on the TV through IP control (`appControl.terminateApps`, PSK set): Sony
     * offers no way to close one app by name. The now-showing card's long press asks first.
     */
    fun closeTvApps() {
        val client = bravia
        if (client == null || links.value.rest != Link.ONLINE) {
            mutableEvents.tryEmit(TuttiEvent.ActionFailed("terminateApps: IP control not online"))
            return
        }
        scope.launch {
            val result = client.terminateApps()
            if (result !is BraviaResult.Ok) mutableEvents.tryEmit(TuttiEvent.ActionFailed(Redact.text("terminateApps: $result")))
        }
    }

    fun openAllApps() {
        scope.launch {
            if (!atv.state.value.ready) {
                val current = links.value
                val outcome = TvLinkRule.whileDown(V2Key.ALL_APPS, false, current.tv, current.tvShown)
                mutableEvents.tryEmit(TuttiEvent.KeyNotSent(outcome))
                if (outcome != KeyOutcome.HELD) return@launch
                val mine = heldKey.incrementAndGet()
                val back = withTimeoutOrNull(TvLinkRule.HOLD_MS) { atv.state.first { it.ready } } != null
                if (mine != heldKey.get()) return@launch
                if (!back) {
                    mutableEvents.tryEmit(TuttiEvent.KeyNotSent(KeyOutcome.EXPIRED))
                    return@launch
                }
            }
            val opener = AppsScreenOpener(
                send = { route ->
                    when (route) {
                        is AppsScreenRoute.Link -> atv.launch(route.link)
                        is AppsScreenRoute.Key -> atv.sendKey(route.keyCode)
                    }
                },
                foreground = atv.state.map { it.currentApp },
                current = { atv.state.value.currentApp },
            )
            val result = opener.open()
            lastLaunchTrace = Redact.text(result.trace())
            mutableEvents.tryEmit(TuttiEvent.AppsScreen(result.verdict))
        }
    }

    /**
     * Wakes the TV when it isn't known to be on (the scene's own wake, bar included), then switches
     * to [uri]. True once the TV took the input.
     */
    suspend fun showOnTv(uri: String): Boolean {
        val s = state.value
        val powered = s.remote.powered ?: when (s.tv.power) {
            PowerStatus.ACTIVE -> true
            PowerStatus.STANDBY -> false
            PowerStatus.UNKNOWN -> null
        }
        if (powered != true) actuator.perform(SceneStep.WakeUp)
        val ok = actuator.perform(SceneStep.TvInput(uri))
        if (ok) tv.update { it.copy(input = uri) } else actionFailed("show computer on the tv: $uri", outcomeOf(null))
        return ok
    }

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
    fun power() = power(PowerTarget.BOTH, state.value.powered != true)

    /**
     * Power for one device or both (the long press on Power, and the card's "Turn on the TV"),
     * through the same wake machinery as [power()]: [powerPlan] decides, per device, whether the
     * command goes now, waits on a wake, or has nothing to do. A wake tries only the devices the
     * target names, so [TuttiEvent.WakeFailed] blames only a device that was asked to wake.
     */
    fun power(target: PowerTarget, on: Boolean) {
        if (waking.value) return
        val current = state.value
        val tvDevice = current.devices.tv
        val plan = powerPlan(
            target = target,
            on = on,
            tvConfigured = tvDevice != null,
            tvReachable = tvDevice != null && tvReachable(),
            barConfigured = current.devices.bar != null,
            barReachable = current.devices.bar != null && theatre.state.value.connected,
        )
        plan.overridePowered?.let { powered -> setOverride { it.copy(powered = powered) } }
        if (plan.sendTv) scope.launch { tvPower(on) }
        if (plan.sendBar) scope.launch { theatre.setToggle(TheatreFeature.POWER, on) }
        if (!plan.waking) return
        waking.value = true
        scope.launch {
            try {
                wakeAndPower(tvDevice?.takeIf { plan.wakeTv }, plan.wakeBar)
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

    /** Absolute soundbar volume from the app: see [setVolume] with a source. */
    fun setVolume(level: Int) = setVolume(level, VolumeSource.APP)

    /**
     * Absolute soundbar volume, clamped to the ceiling (the active scene's cap and the cap for
     * tonight, the lower wins) and stopped at the high-volume guard ([VolumeRules.decide]): from the
     * app it asks ([TuttiUiState.volumeAsk]), elsewhere it says going above needs the app. Sent at
     * most every 60 ms, latest wins.
     */
    fun setVolume(level: Int, source: VolumeSource) {
        val s = state.value
        val ceiling = s.volumeCap ?: MAX_VOLUME
        // The newest level asked for, not the state's, which may lag a burst of presses by a frame.
        val current = overrides.value.volume ?: s.volume
        val line = VolumeRules.threshold(s.prefs.volumeGuardLevel)
        val armed = VolumeGuardState(s.prefs.volumeGuard, line, guard.value.unlocked)
        when (val decision = VolumeRules.decide(current, level, ceiling, armed, source)) {
            is VolumeDecision.Send -> sendVolume(decision.level, ceiling, line)
            is VolumeDecision.Held -> {
                // The guard is a wall too: the bump, and the level stops exactly on it.
                mutableEvents.tryEmit(TuttiEvent.VolumeLimit)
                decision.send?.let { sendVolume(it, ceiling, line, bump = false) }
                if (decision.ask) {
                    guard.update { it.copy(ask = VolumeAsk(decision.threshold, decision.wanted)) }
                } else {
                    // The quick surfaces read the notice and say going above needs the app.
                    guard.update { it.copy(notice = true) }
                }
            }
        }
    }

    private fun sendVolume(clamped: Int, ceiling: Int, line: Int, bump: Boolean = true) {
        if (bump && (clamped == 0 || clamped == ceiling)) mutableEvents.tryEmit(TuttiEvent.VolumeLimit)
        // Below the line again: the guard re-arms at once, before a lagging state could let a step through.
        if (clamped < line) guard.update { if (it.unlocked || it.notice) it.copy(unlocked = false, notice = false) else it }
        setOverride { it.copy(volume = clamped) }
        volumeTarget.trySend(clamped)
    }

    /** "Go above": the guard opens until the level is below its line again, and the asked level goes. */
    fun confirmLoud() {
        val ask = guard.value.ask ?: return
        guard.update { it.copy(unlocked = true, ask = null, notice = false) }
        setVolume(ask.wanted, VolumeSource.APP)
    }

    /** "Stay": the level stays on the line. */
    fun stayBelowGuard() {
        guard.update { it.copy(ask = null) }
    }

    fun volumeStep(delta: Int) = volumeStep(delta, VolumeSource.APP)

    fun volumeStep(delta: Int, source: VolumeSource) {
        // Level not known yet (the bar has not reported it): ask for it rather than guess a level to set.
        // The bar hasn't reported its level this session: ask for it, and step from the level
        // shown meanwhile (possibly last session's) rather than doing nothing.
        if (theatre.state.value.level(TheatreFeature.VOLUME) == null) scope.launch { theatre.get(TheatreFeature.VOLUME) }
        val current = overrides.value.volume ?: state.value.volume ?: return
        setVolume(current + delta, source)
    }

    /** The high-volume guard's settings (Settings › Volume). */
    fun setVolumeGuard(enabled: Boolean, level: Int) {
        scope.launch { store.updatePrefs { it.copy(volumeGuard = enabled, volumeGuardLevel = VolumeRules.threshold(level)) } }
    }

    /**
     * A cap for tonight at [level] until [until] (wall clock): enforced like a scene's cap, the lower
     * wins. A louder bar comes down to it at once.
     */
    fun setTonightCap(level: Int, until: Long) {
        val cap = io.kud.tutti.platform.data.TonightCap(level.coerceIn(0, MAX_VOLUME), until)
        scope.launch { store.updatePrefs { it.copy(tonightCap = cap) } }
        val current = overrides.value.volume ?: state.value.volume
        if (current != null && current > cap.level && theatre.state.value.connected) {
            setOverride { it.copy(volume = cap.level) }
            volumeTarget.trySend(cap.level)
        }
    }

    fun clearTonightCap() {
        scope.launch { store.updatePrefs { it.copy(tonightCap = null) } }
    }

    /** A saved level in the volume sheet: [level] in [slot], or null to empty it. */
    fun saveVolumePreset(slot: Int, level: Int?) {
        scope.launch { store.updatePrefs { it.copy(volumePresets = VolumeRules.withPreset(it.volumePresets, slot, level)) } }
    }

    fun toggleMute() {
        scope.launch { theatre.setToggle(TheatreFeature.MUTE, !state.value.muted) }
    }

    /**
     * Flips a bar toggle ([TheatreState.toggleTarget]): unreported but connected turns it on; an
     * unreachable bar sends nothing and returns false, so the caller bumps as the Sound tile does.
     */
    fun toggleBar(feature: TheatreFeature): Boolean {
        val on = theatre.state.value.toggleTarget(feature) ?: return false
        scope.launch { theatre.setToggle(feature, on) }
        return true
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

    /** His own name for bar input [value] (null: back to the bar's), shown only in tutti. */
    fun renameBarInput(value: String, name: String?) {
        scope.launch { store.updatePrefs { it.copy(barInputNames = if (name == null) it.barInputNames - value else it.barInputNames + (value to name)) } }
    }

    /** Takes bar input [value] out of the Son sheet's row, or puts it back. */
    fun setBarInputHidden(value: String, hidden: Boolean) {
        scope.launch { store.updatePrefs { it.copy(hiddenBarInputs = if (hidden) it.hiddenBarInputs + value else it.hiddenBarInputs - value) } }
    }

    /**
     * True when the one-time hint for gesture [id] may show now, and records it as shown: once
     * ever, never two the same day (discoverability rule 3, [io.kud.tutti.ui.common.GestureHints]).
     */
    fun takeHint(id: String): Boolean {
        val today = java.time.LocalDate.now().toEpochDay()
        if (!io.kud.tutti.ui.common.GestureHints.mayShow(state.value.prefs.hints, id, today)) return false
        scope.launch { store.updatePrefs { it.copy(hints = io.kud.tutti.ui.common.GestureHints.shown(it.hints, id, today)) } }
        return true
    }

    private val keyboardAsk = MutableStateFlow(false)

    /** A launcher shortcut asked for the keyboard view; the remote opens it and calls [keyboardAnswered]. */
    val keyboardAsked: StateFlow<Boolean> = keyboardAsk

    fun askKeyboard() {
        keyboardAsk.value = true
    }

    fun keyboardAnswered() {
        keyboardAsk.value = false
    }

    /** The last exchanges on the bar's TCP 33336, scrubbed, for the diagnostic report. */
    val barWire: List<String> get() = theatre.wire.lines()

    fun refreshBar() {
        scope.launch { theatre.refreshAll() }
    }

    /**
     * Technical details opened: the bar's detail readouts on 33336 (paced, queued behind any
     * refresh), one read-only `http_get` on 54545, and the TV's supported functions over REST
     * when IP control is up. Each runs once per opening; nothing here is polled.
     */
    fun loadTechnical() {
        scope.launch { theatre.refreshDetails() }
        val bar = state.value.devices.bar
        if (bar != null) {
            scope.launch {
                technical.update { it.copy(barHttpFetch = Fetch.LOADING) }
                val readouts = try {
                    BarHttpClient(OkHttpBarHttpTransport(bar.host)).readouts()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                technical.update {
                    if (readouts != null) it.copy(barHttp = readouts, barHttpFetch = Fetch.DONE) else it.copy(barHttpFetch = Fetch.FAILED)
                }
            }
        }
        val client = bravia
        if (client != null && links.value.rest == Link.ONLINE) {
            scope.launch {
                technical.update { it.copy(tvFunctionsFetch = Fetch.LOADING) }
                val result = client.supportedFunctions()
                technical.update {
                    when {
                        result is BraviaResult.Ok -> it.copy(tvFunctions = result.value, tvFunctionsFetch = Fetch.DONE)
                        result is BraviaResult.Failure &&
                            (result.error is BraviaError.Unsupported || (result.error as? BraviaError.Rpc)?.isUnsupported == true) ->
                            it.copy(tvFunctionsFetch = Fetch.UNSUPPORTED)
                        else -> it.copy(tvFunctionsFetch = Fetch.FAILED)
                    }
                }
            }
        }
    }

    // endregion

    // region scenes

    fun applyScene(sceneId: String) {
        val scene = state.value.scenes.firstOrNull { it.scene.id == sceneId }?.scene ?: return
        val before = snapshot()
        // One level of undo, taken before anything moves; a newer scene replaces it.
        sceneUndo.value = SceneUndo.capture(scene, before, activeSceneId.value, SystemClock.elapsedRealtime())
        scheduleUndoExpiry()
        activeSceneId.value = sceneId
        val steps = SceneEngine.plan(scene, before)
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
            val undoable = sceneUndo.value?.sceneId == sceneId && activeSceneId.value == sceneId
            mutableEvents.emit(TuttiEvent.SceneFinished(sceneId, failed.size, detail, undoable))
        }
    }

    private var undoExpiryJob: Job? = null

    private fun scheduleUndoExpiry() {
        val undo = sceneUndo.value ?: return
        undoExpiryJob?.cancel()
        undoExpiryJob = scope.launch {
            delay(SceneUndo.WINDOW_MS)
            sceneUndo.compareAndSet(undo, null)
        }
    }

    /**
     * Puts back what the last scene changed ([SceneUndo]), and the scene that was active before it.
     * One level: the undo is spent. A run still going is stopped first. Restoring is a scene run of
     * its own (the ceiling applies, the volume guard doesn't: it returns to a level he had).
     */
    fun undoLastScene(): Boolean {
        val undo = state.value.sceneUndo ?: return false
        sceneUndo.value = null
        undoExpiryJob?.cancel()
        sceneJob?.cancel()
        val name = state.value.scenes.firstOrNull { it.scene.id == undo.sceneId }?.scene?.name
        activeSceneId.value = undo.previousSceneId
        val steps = undo.plan(snapshot())
        sceneJob = scope.launch {
            var failed = emptyList<SceneStep>()
            SceneRunner(actuator).run(steps).collect { failed = it.failed }
            val detail = if (failed.isEmpty()) null else failureDetail("scene: undo", outcome = "${failed.size} of ${steps.size} steps failed", steps = failed.map { "failed $it" })
            mutableEvents.emit(TuttiEvent.SceneUndone(name, failed.size, detail))
        }
        return true
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
            is SceneStep.TvApp -> launchDirect(step.appPackage)
            is SceneStep.PictureMode -> bravia?.setPictureSetting("pictureMode", step.mode) is BraviaResult.Ok
            is SceneStep.Bar -> {
                // The cap for tonight binds scenes too: the lower cap wins.
                val tonight = VolumeRules.tonightActive(store.prefs.first().tonightCap, System.currentTimeMillis())
                val value = if (step.feature == TheatreFeature.VOLUME && tonight != null) {
                    step.value.toIntOrNull()?.let { minOf(it, tonight.level).toString() } ?: step.value
                } else {
                    step.value
                }
                theatre.set(step.feature, value)
                theatre.state.value.connected
            }
            is SceneStep.LaunchApp -> launchDirect(step.appPackage)
            is SceneStep.PictureOff -> bravia?.setPowerSavingMode(pictureOffMode(step.on)) is BraviaResult.Ok
        }
    }

    // endregion

    // region dock and shortcuts

    fun run(action: DockAction) {
        scope.launch { perform(action) }
    }

    private suspend fun perform(action: DockAction) {
        when (action) {
            is DockAction.LaunchApp -> requestLaunch(action.packageName)
            is DockAction.Input -> switchInput(action.uri)
            is DockAction.ToggleBar -> TheatreFeature.fromKey(action.featureKey)?.let { toggleBar(it) }
            is DockAction.RunScene -> applyScene(action.sceneId)
            is DockAction.SetBar -> setBarValue(action.featureKey, action.value)
            is DockAction.Key -> sendTvKey(action.keyCode, action.long)
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
            is DockAction.PictureOff -> if (bravia == null) actionFailed("shortcut: picture off", outcomeOf(null)) else setPictureOff(action.on)
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
                val attempt = AtvPairing(tls, serviceName = "tutti", clientName = "tutti").also { session = it }
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
                    // A TV at another address is another certificate: its REST pin starts again.
                    if (!restPinSurvives(store.devices.first().tv?.host, address)) secure.setTvRestPin(null)
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
                "configured" to "tv ${devices.tv != null}, bar ${devices.bar != null}, psk ${psk != null}, code ${ipCode.value.name.lowercase()}",
            ),
            sensitive = sensitive + listOfNotNull(devices.tv?.host, devices.tv?.mac, devices.bar?.host, psk, ipCookie?.value),
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

    /**
     * Stores [value] (null clears it). False, and nothing stored, when it breaks [PskRules]. A new
     * key replaces the code's cookie: IP control uses one method at a time.
     */
    fun setPsk(value: String?): Boolean {
        if (value != null && !PskRules.valid(value)) return false
        scope.launch {
            secure.setTvPsk(value)
            psk = secure.tvPsk()
            pskSet.value = psk != null
            if (psk != null) dropIpCookie()
            restart.update { it + 1 }
        }
        return true
    }

    private suspend fun dropIpCookie() {
        secure.setTvIpCookie(null)
        ipCookie = null
        ipCode.value = IpCodeStatus.NONE
    }

    /**
     * Step one of « Code affiché sur la TV »: asks the TV to show its 4-digit code (`actRegister`
     * without credentials, [BraviaClient.requestCode]). The transport is kept for [submitTvCode], so
     * both go the same way (HTTPS pinned, or HTTP to a private address after the probe).
     */
    suspend fun requestTvCode(): TvCodeStep {
        val host = store.devices.first().tv?.host ?: return TvCodeStep.Failed(TvCodeFailure.NO_TV)
        val transport = OkHttpBraviaTransport(host, secure.tvRestPin()) { secure.setTvRestPin(it) }
        // Same entry in the TV's list of remote devices as last time, when there was one.
        val clientId = (ipCookie ?: secure.tvIpCookie())?.clientId ?: IpRegistration.clientId(UUID.randomUUID().toString())
        codeSession = transport to clientId
        return when (val result = BraviaClient(transport) { null }.requestCode(clientId)) {
            is BraviaResult.Ok -> when (val outcome = result.value) {
                Registration.CodeShown -> TvCodeStep.Shown
                is Registration.Registered -> registered(outcome.cookie)
                Registration.Refused -> TvCodeStep.Failed(TvCodeFailure.NOT_ENABLED)
            }
            is BraviaResult.Failure -> TvCodeStep.Failed(codeFailure(result.error))
        }
    }

    /** Step two: the code as typed. A wrong one can be typed again without asking for a new one. */
    suspend fun submitTvCode(code: String): TvCodeStep {
        val (transport, clientId) = codeSession ?: return TvCodeStep.Failed(TvCodeFailure.OTHER)
        if (!IpRegistration.validCode(code)) return TvCodeStep.WrongCode
        return when (val result = BraviaClient(transport) { null }.submitCode(clientId, code)) {
            is BraviaResult.Ok -> when (val outcome = result.value) {
                is Registration.Registered -> registered(outcome.cookie)
                Registration.Refused, Registration.CodeShown -> TvCodeStep.WrongCode
            }
            is BraviaResult.Failure -> TvCodeStep.Failed(codeFailure(result.error))
        }
    }

    /** Leaving the code dialog: nothing kept but what was stored. */
    fun cancelTvCode() {
        codeSession = null
    }

    /** The cookie replaces the PSK: one method at a time, the one just set up. */
    private suspend fun registered(cookie: AuthCookie): TvCodeStep {
        secure.setTvIpCookie(cookie)
        secure.setTvPsk(null)
        psk = null
        pskSet.value = false
        ipCookie = cookie
        ipCode.value = IpCodeStatus.SET
        codeSession = null
        restart.update { it + 1 }
        return TvCodeStep.Registered
    }

    private fun codeFailure(error: BraviaError): TvCodeFailure = when (error) {
        is BraviaError.Transport -> TvCodeFailure.UNREACHABLE
        BraviaError.PinMismatch -> TvCodeFailure.CERTIFICATE
        BraviaError.Unauthorized, is BraviaError.Http, is BraviaError.Unsupported -> TvCodeFailure.NOT_ENABLED
        // Sony's 40005, "Display Is Turned off": the TV can't show a code with its screen off.
        is BraviaError.Rpc -> if (error.code == 40005) TvCodeFailure.SCREEN_OFF else TvCodeFailure.NOT_ENABLED
        is BraviaError.Malformed -> TvCodeFailure.OTHER
    }

    suspend fun hasPsk(): Boolean = secure.tvPsk() != null

    /** "Oublier l'appareil" for the TV: wipes the PSK, both pins and the client identity. */
    fun forgetTv() {
        scope.launch {
            atv.disconnect()
            secure.forgetTv()
            identities.forget()
            psk = null
            pskSet.value = false
            ipCookie = null
            ipCode.value = IpCodeStatus.NONE
            codeSession = null
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

    /**
     * The TV's inputs again, for the add flow's polling while a computer is plugged in or woken: one
     * call, and only each known input's `connected` flag changes (the full list when none is known
     * yet). [loadTvSettings] stays the whole sweep.
     */
    fun refreshInputs() {
        val client = bravia ?: return
        scope.launch {
            val fresh = client.externalInputs().getOrNull() ?: return@launch
            tv.update { state ->
                val inputs = if (state.inputs.isEmpty()) {
                    fresh
                } else {
                    state.inputs.map { old -> fresh.firstOrNull { it.uri == old.uri }?.let { old.copy(connected = it.connected) } ?: old }
                }
                state.copy(inputs = inputs)
            }
        }
    }

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

    /**
     * The Power sheet's sleep timer needs the TV's IRCC list ([TvSettingsState.sleepCode]), which
     * only TV settings load: ask for it once when the sheet opens and it isn't known yet.
     */
    fun loadSleepTimer() {
        val settings = tv.value.settings
        if (!settings.loaded && !settings.loading && links.value.rest == Link.ONLINE) loadTvSettings()
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

    /**
     * Screen off, sound on (Iris's top 10, item 4): the TV's power saving `pictureOff`, or `off` to
     * light it again. Sony's help guide says any remote key but volume and mute brings the picture
     * back; unverified on the XR80 and over tutti's own keys, so tutti always offers the way back.
     */
    fun setPictureOff(on: Boolean) = setPowerSaving(pictureOffMode(on))

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
        private const val TAG = "tutti"
        private const val RECENTS_WATCH_MS = 1_500L
        const val MAX_VOLUME = 100
        private const val LINGER_MS = 30_000L
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val RENEW_RETRY_MS = 6 * 60 * 60 * 1000L
        private const val REFUSED_AFTER_RENEW_MS = 60_000L
        private const val READY_TIMEOUT_MS = 8_000L
        private const val POLL_BAR_MS = 20_000L
        private const val REASK_EVERY_POLLS = 3
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

/** Whether the TV's REST pin still applies once the TV is saved at [next]: only at the same address. */
internal fun restPinSurvives(previous: String?, next: String): Boolean = previous != null && previous.trim() == next.trim()

/**
 * The controller scope's last resort: a coroutine that throws is logged (scrubbed) instead of
 * killing the process. Each loop still handles its own failures; this is for the ones nobody
 * foresaw.
 */
internal fun loggingHandler(log: (String) -> Unit): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    val text = runCatching { Redact.text(error.stackTraceToString()) }.getOrDefault(error.javaClass.name)
    runCatching { log("uncaught in the controller scope: $text") }
}

/** The power saving value that turns the picture off ([on]) or back on. `off` also clears a `low`/`high` saving. */
internal fun pictureOffMode(on: Boolean): String = if (on) "pictureOff" else "off"

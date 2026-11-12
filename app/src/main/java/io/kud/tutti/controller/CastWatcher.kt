package io.kud.tutti.controller

import io.kud.tutti.protocol.cast.CastConnection
import io.kud.tutti.protocol.cast.CastConnector
import io.kud.tutti.protocol.cast.CastContext
import io.kud.tutti.protocol.cast.CastEnd
import io.kud.tutti.protocol.cast.CastHold
import io.kud.tutti.protocol.cast.CastLogLines
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastReading
import io.kud.tutti.protocol.cast.CastReport
import io.kud.tutti.protocol.cast.CastSession
import io.kud.tutti.protocol.cast.CastSpeed
import io.kud.tutti.protocol.cast.CastSpeedResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When to try Cast again after a session ended (architecture § 7: never a tight loop).
 * A device that failed auth is left alone for [UNTRUSTED_MS]: it will not have changed its
 * certificate chain in a minute. Anything else (refused, closed, malformed) waits
 * [FIRST_MS], doubling to [MAX_MS]; a session that lasted [HEALTHY_MS] starts the count again.
 */
object CastBackoff {
    const val FIRST_MS = 5_000L
    const val MAX_MS = 5 * 60_000L
    const val UNTRUSTED_MS = 30 * 60_000L
    const val HEALTHY_MS = 60_000L

    /** [failures] counts the ends in a row, this one included (1 for the first). */
    fun delayFor(end: CastEnd, failures: Int): Long = when (end) {
        is CastEnd.Untrusted -> UNTRUSTED_MS
        else -> (FIRST_MS shl (failures - 1).coerceIn(0, 16)).coerceAtMost(MAX_MS)
    }
}

/**
 * Runs one read-only [CastSession] to the TV while [active] holds a host (the remote in front,
 * the TV's host configured and on), and only then. A new host or leaving closes the session at
 * once. After an end it waits per [CastBackoff], and that wait outlives leaving the screen, so
 * flicking between screens never turns into a retry loop. [nowPlaying] goes through [CastHold]:
 * a session's silence (its end, a reconnect, leaving for a moment, an empty status) keeps the last
 * confirmed item for [CastHold.GRACE_MS]; a confirmed end, a new host, or a change [context]
 * proves (standby, another app in front, a tuner or input) clears it at once. [log] gets end
 * classes only, never a title.
 */
class CastWatcher(
    private val connector: CastConnector,
    private val scope: CoroutineScope,
    active: Flow<String?>,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    /** Time to ready of a session, ms: from the connect to its first status read (device auth passed). */
    private val onReady: (Long) -> Unit = {},
    /** The raw numbers of each media status, for the diagnostic; never a title. */
    private val onReading: (CastReading) -> Unit = {},
    /** What the other planes say about the TV ([CastHold.onContext]). */
    context: Flow<CastContext> = emptyFlow(),
    /** Lines for developer mode's event log ([CastLogLines]): states and packages, never viewing data. */
    private val event: (String) -> Unit = {},
    /** Tests replace the session; null runs a real [CastSession], which [setSpeed] can then reach. */
    private val runSession: (suspend (CastConnection, (CastReport) -> Unit) -> CastEnd)? = null,
) {
    /** The session running now, for the one command tutti sends: a picked playback rate. */
    @Volatile private var live: CastSession? = null

    private val refused = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Receiver app ids that refused or ignored a playback rate since tutti started: the Speed
     * chip stays hidden for them (it asked once and the app said no; asking again won't change it).
     */
    val speedRefused: StateFlow<Set<String>> = refused.asStateFlow()

    /** Asks the app in front for [rate], logs how it went (app and package, never a title), and remembers a refusal. */
    suspend fun setSpeed(rate: Double): CastSpeedResult {
        val session = live ?: return CastSpeedResult.NoSession.also { event(CastSpeed.logLine(null, lastForeground, rate, it)) }
        val appId = session.appId
        val result = session.setPlaybackRate(rate)
        event(CastSpeed.logLine(appId, lastForeground, rate, result))
        if (appId != null && result !is CastSpeedResult.Accepted && result !is CastSpeedResult.NoSession) refused.update { it + appId }
        return result
    }

    private val mutable = MutableStateFlow<CastNowPlaying?>(null)
    val nowPlaying: StateFlow<CastNowPlaying?> = mutable.asStateFlow()

    private val lock = Any()
    private val hold = CastHold()
    private var expiry: Job? = null

    private fun fold(change: CastHold.() -> Unit) {
        val shown: CastNowPlaying?
        val expires: Long?
        val lines = mutableListOf<String>()
        synchronized(lock) {
            hold.change()
            shown = hold.at(clock())
            hold.takeClearReason()?.let { lines += CastLogLines.clearedBy(it, lastForeground) }
            if (hold.takeRanOut()) lines += CastLogLines.graceRanOut()
            expires = hold.expiresAtMillis
            expiry?.cancel()
            expiry = expires?.let { at ->
                scope.launch {
                    delay((at - clock()).coerceAtLeast(0))
                    fold { }
                }
            }
        }
        mutable.value = shown
        lines.forEach(event)
    }

    @Volatile private var lastForeground: String? = null

    /** Per host: no attempt before this time, and the ends in a row so far. */
    private val holds = mutableMapOf<String, Pair<Long, Int>>()

    /** Bumped by [networkChanged]: a backoff waiting on it ends, and an end it caused is not a failure. */
    private val network = MutableStateFlow(0)

    /** The socket under the running session, so a lost LAN network can end it now, not on its read timeout. */
    @Volatile private var liveConnection: CastConnection? = null

    /**
     * The LAN network the sockets ride came, went or changed (LanRoute). A session that ends
     * because of it, and the failures on a network that is gone, say nothing about the TV: the
     * backoff starts over and the next try is at once. With [dropped], the running session's
     * socket belongs to the old network and is closed. Never called for a default-network flip.
     */
    fun networkChanged(dropped: Boolean) {
        network.update { it + 1 }
        if (dropped) liveConnection?.let { runCatching { it.close() } }
    }

    init {
        scope.launch {
            var lastHost: String? = null
            active.distinctUntilChanged().collectLatest { host ->
                // Inactive for a moment (the TV's power reading blinking, the screen left) proves
                // nothing; another TV does.
                if (host == null) {
                    fold { onReport(CastReport.Unknown, clock()) }
                } else {
                    if (lastHost != null && host != lastHost) fold { clear() }
                    lastHost = host
                    watch(host)
                }
            }
        }
        scope.launch {
            context.distinctUntilChanged().collect {
                lastForeground = it.foreground
                fold { onContext(it) }
            }
        }
    }

    private suspend fun watch(host: String) {
        while (true) {
            val (notBefore, held) = holds[host] ?: (0L to 0)
            var failures = held
            val wait = notBefore - clock()
            if (wait > 0) {
                val seen = network.value
                // A network change ends the wait: what failed on the old network proves nothing now.
                if (withTimeoutOrNull(wait) { network.first { it != seen } } != null) failures = 0
            }
            val epoch = network.value
            val started = clock()
            event(CastLogLines.connecting())
            val end = try {
                val connection = connector.connect(host)
                liveConnection = connection
                try {
                    var reported = false
                    val emit: (CastReport) -> Unit = {
                        if (!reported) {
                            reported = true
                            onReady(clock() - started)
                            event(CastLogLines.ready(clock() - started))
                        }
                        fold { onReport(it, clock()) }
                    }
                    runSession?.invoke(connection, emit) ?: run {
                        val session = CastSession(connection, emit, onReading = onReading, onEvent = event)
                        live = session
                        try {
                            session.run()
                        } finally {
                            if (live === session) live = null
                        }
                    }
                } finally {
                    if (liveConnection === connection) liveConnection = null
                    connection.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CastEnd.Closed(e.javaClass.simpleName)
            } finally {
                fold { onReport(CastReport.Unknown, clock()) }
            }
            val healthy = end !is CastEnd.Untrusted && clock() - started >= CastBackoff.HEALTHY_MS
            // Ended by a network change (dropped, or every connect timing out on a network that went):
            // not the TV's fault, so no doubling, and one try at once on the network we have now.
            val networkEnded = end !is CastEnd.Untrusted && network.value != epoch
            val count = when {
                networkEnded -> 0
                healthy -> 1
                else -> failures + 1
            }
            val hold = if (networkEnded) 0L else CastBackoff.delayFor(end, count)
            holds[host] = clock() + hold to count
            log("cast ended: $end, next try in ${hold / 1000} s")
            event(CastLogLines.ended(end, hold))
        }
    }

    companion object {
        /** The host to watch, or null: the remote in front, a TV host, the TV on. */
        fun target(inFront: Boolean, host: String?, tvOn: Boolean): String? = host?.takeIf { inFront && tvOn && it.isNotBlank() }

        fun activeFrom(inFront: Flow<Boolean>, host: Flow<String?>, tvOn: Flow<Boolean>): Flow<String?> =
            combine(inFront, host, tvOn, ::target)
    }
}

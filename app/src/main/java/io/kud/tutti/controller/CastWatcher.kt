package io.kud.tutti.controller

import io.kud.tutti.protocol.cast.CastConnection
import io.kud.tutti.protocol.cast.CastConnector
import io.kud.tutti.protocol.cast.CastContext
import io.kud.tutti.protocol.cast.CastEnd
import io.kud.tutti.protocol.cast.CastHold
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastReading
import io.kud.tutti.protocol.cast.CastReport
import io.kud.tutti.protocol.cast.CastSession
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
import kotlinx.coroutines.launch

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
    onReading: (CastReading) -> Unit = {},
    /** What the other planes say about the TV ([CastHold.onContext]). */
    context: Flow<CastContext> = emptyFlow(),
    private val runSession: suspend (CastConnection, (CastReport) -> Unit) -> CastEnd = { connection, emit ->
        CastSession(connection, emit, onReading = onReading).run()
    },
) {
    private val mutable = MutableStateFlow<CastNowPlaying?>(null)
    val nowPlaying: StateFlow<CastNowPlaying?> = mutable.asStateFlow()

    private val lock = Any()
    private val hold = CastHold()
    private var expiry: Job? = null

    private fun fold(change: CastHold.() -> Unit) {
        val shown: CastNowPlaying?
        val expires: Long?
        synchronized(lock) {
            hold.change()
            shown = hold.at(clock())
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
    }

    /** Per host: no attempt before this time, and the ends in a row so far. */
    private val holds = mutableMapOf<String, Pair<Long, Int>>()

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
            context.distinctUntilChanged().collect { fold { onContext(it) } }
        }
    }

    private suspend fun watch(host: String) {
        while (true) {
            val (notBefore, failures) = holds[host] ?: (0L to 0)
            val wait = notBefore - clock()
            if (wait > 0) delay(wait)
            val started = clock()
            val end = try {
                val connection = connector.connect(host)
                try {
                    var reported = false
                    runSession(connection) {
                        if (!reported) {
                            reported = true
                            onReady(clock() - started)
                        }
                        fold { onReport(it, clock()) }
                    }
                } finally {
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
            val count = if (healthy) 1 else failures + 1
            val hold = CastBackoff.delayFor(end, count)
            holds[host] = clock() + hold to count
            log("cast ended: $end, next try in ${hold / 1000} s")
        }
    }

    companion object {
        /** The host to watch, or null: the remote in front, a TV host, the TV on. */
        fun target(inFront: Boolean, host: String?, tvOn: Boolean): String? = host?.takeIf { inFront && tvOn && it.isNotBlank() }

        fun activeFrom(inFront: Flow<Boolean>, host: Flow<String?>, tvOn: Flow<Boolean>): Flow<String?> =
            combine(inFront, host, tvOn, ::target)
    }
}

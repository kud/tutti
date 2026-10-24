package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One persistent socket to the bar. No authentication exists on this port, and the bar
 * only answers when "External control" is enabled in BRAVIA Connect.
 *
 * Reconnection policy belongs to the caller (`:app`), which knows about network changes.
 */
class TheatreClient(
    private val connector: TcpConnector,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMillis: Long = 3_000,
    /** How refreshes pace their `get`s: at most [inFlight] unanswered at once, each given [answerMillis]. */
    private val pacing: RefreshPacing = RefreshPacing(),
    /** Raw exchanges on the socket, for the diagnostic report. */
    val wire: TheatreWireLog = TheatreWireLog(clock = clock),
) {
    private val mutableState = MutableStateFlow(TheatreState())
    val state: StateFlow<TheatreState> = mutableState.asStateFlow()

    private val ids = AtomicInteger(0)
    private val probes = ConcurrentHashMap<Int, CompletableDeferred<TheatreInbound.Result>>()

    @Volatile private var connection: Connection? = null
    private var readJob: Job? = null
    private var tickJob: Job? = null
    private var refreshJob: Job? = null
    private val refreshing = Mutex()

    suspend fun connect(host: String) {
        disconnect()
        val conn = connector.connect(host, TheatreFeature.PORT)
        connection = conn
        wire.note("connected")
        dispatch(TheatreEvent.Connected(true))
        readJob = scope.launch {
            val splitter = JsonStreamSplitter()
            try {
                conn.incoming.collect { chunk ->
                    wire.received(chunk)
                    splitter.feed(chunk).forEach(::onLine)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Socket error: handled as a disconnect below.
                wire.note("read failed: ${e.javaClass.simpleName}")
            } finally {
                // EOF or a read timeout leaves our end open: close it, or the bar keeps a dead client.
                conn.close()
                if (connection === conn) {
                    connection = null
                    dispatch(TheatreEvent.Connected(false))
                }
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                dispatch(TheatreEvent.Tick(clock()))
            }
        }
        // Paced, so it takes seconds: the caller learns it is connected before it ends.
        refreshJob = scope.launch { refreshAll() }
    }

    fun disconnect() {
        val conn = connection
        connection = null
        readJob?.cancel()
        tickJob?.cancel()
        refreshJob?.cancel()
        conn?.close()
        probes.values.forEach { it.cancel() }
        probes.clear()
        if (conn != null) dispatch(TheatreEvent.Connected(false))
    }

    /** Optimistic: the state changes before the request is written. */
    suspend fun set(feature: TheatreFeature, value: String) {
        if (feature.kind is FeatureKind.ReadOnly) return
        val request = TheatreOutbound.SetRequest(nextId(), feature.key, value, numeric = feature.kind is FeatureKind.Level)
        send(request)
    }

    suspend fun setToggle(feature: TheatreFeature, on: Boolean) {
        val value = feature.toggleValue(on) ?: return
        set(feature, value)
    }

    suspend fun setLevel(feature: TheatreFeature, level: Int) {
        val kind = feature.kind as? FeatureKind.Level ?: return
        set(feature, level.coerceIn(kind.min, kind.max).toString())
    }

    suspend fun get(feature: TheatreFeature) {
        send(TheatreOutbound.GetRequest(nextId(), feature.key))
    }

    /**
     * Puts the bar in Bluetooth pairing mode: [BluetoothPairing.steps], each sent optimistically.
     * Unverified on the Bar 9; the caller offers it only once the bar has reported `bluetooth.mode`.
     */
    suspend fun startBluetoothPairing() {
        for (step in BluetoothPairing.steps(state.value.value(TheatreFeature.INPUT))) {
            send(TheatreOutbound.SetRequest(nextId(), step.feature.key, step.value, numeric = false))
            if (step.pauseAfterMillis > 0) delay(step.pauseAfterMillis)
        }
    }

    /**
     * Asks for every known feature, paced; answers fill [TheatreState.confirmed]. Features
     * still silent after the pass are asked once more, one at a time, after a pause.
     */
    suspend fun refreshAll() = refresh(TheatreFeature.entries)

    /** Asks again for what the bar has neither reported nor refused. */
    suspend fun refreshMissing() = refresh(state.value.missing())

    /**
     * Never a burst: the reference client for this port sends one request and waits for its
     * answer before the next. At most [RefreshPacing.inFlight] wait at once, with a small gap
     * between writes. Overlapping refreshes queue rather than double the traffic.
     * Returns the features that never answered.
     */
    suspend fun refresh(features: List<TheatreFeature>): List<TheatreFeature> = refreshing.withLock {
        val silent = pass(features)
        if (silent.isEmpty() || connection == null) return@withLock silent
        delay(pacing.retryAfterMillis)
        val gate = Semaphore(1)
        pass(silent, gate)
    }

    private suspend fun pass(features: List<TheatreFeature>, gate: Semaphore = Semaphore(pacing.inFlight)): List<TheatreFeature> {
        val unanswered = Collections.synchronizedList(mutableListOf<TheatreFeature>())
        coroutineScope {
            for (feature in features) {
                if (connection == null) break
                gate.acquire()
                launch {
                    try {
                        if (ask(feature, pacing.answerMillis) == null) unanswered += feature
                    } finally {
                        gate.release()
                    }
                }
                delay(pacing.gapMillis)
            }
        }
        return features.filter { it in unanswered }
    }

    /** One `get`, awaited: the result, or null on silence. */
    private suspend fun ask(feature: TheatreFeature, timeoutMillis: Long): TheatreInbound.Result? {
        val request = TheatreOutbound.GetRequest(nextId(), feature.key)
        val answer = CompletableDeferred<TheatreInbound.Result>()
        probes[request.id] = answer
        return try {
            send(request)
            withTimeoutOrNull(timeoutMillis) { answer.await() }
        } finally {
            probes.remove(request.id)
        }
    }

    /** Capability probe: true if the bar answers a `get` for [feature] with a value. */
    suspend fun probe(feature: TheatreFeature, timeoutMillis: Long = 1_500): Boolean {
        val result = ask(feature, timeoutMillis)
        return result != null && result.value != null && !result.isAck && !result.isRefusal
    }

    /**
     * One request on the wire at a time: the Bar 9 drops a request sent before it has answered
     * the last (Erwann's wire log, 2026-10-24: of three `get`s in a row only the first answered,
     * and a 360 Spatial `set` "didn't take"). The reference client holds a lock the same way.
     */
    private val oneAtATime = Mutex()

    private suspend fun send(request: TheatreOutbound) = oneAtATime.withLock {
        val conn = connection ?: return@withLock
        dispatch(TheatreEvent.Sent(request, clock(), timeoutMillis))
        val text = TheatreCodec.encode(request)
        wire.sent(text)
        try {
            conn.send(text.encodeToByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            wire.note("write failed: ${e.javaClass.simpleName}")
            dispatch(TheatreEvent.SendFailed(request.id, e.message ?: "send failed"))
            return@withLock
        }
        // Hold the line until this one is answered (or given up on), then let the next go.
        withTimeoutOrNull(pacing.answerMillis) {
            state.first { request.id !in it.outstanding || !it.connected }
        }
    }

    private fun onLine(line: String) {
        val message = TheatreCodec.decode(line)
        if (message is TheatreInbound.Result) {
            val waiting = message.id?.let { probes[it] }
            if (waiting != null) {
                waiting.complete(message)
            } else if (message.id == null && message.feature != null) {
                // No id, but it names its feature: it answers the oldest get still waiting on it.
                state.value.outstanding.entries
                    .filter { !it.value.isSet && it.value.feature == message.feature }
                    .minByOrNull { it.key }
                    ?.let { probes[it.key]?.complete(message) }
            }
        }
        if (message is TheatreInbound.Malformed) wire.note("undecoded (${message.reason})")
        dispatch(TheatreEvent.Received(message))
    }

    private fun dispatch(event: TheatreEvent) {
        mutableState.update { TheatreReducer.reduce(it, event) }
    }

    private fun nextId(): Int = ids.updateAndGet { if (it >= MAX_ID) 1 else it + 1 }

    companion object {
        private const val TICK_MILLIS = 250L
        private const val MAX_ID = 9_999
    }
}

/**
 * Refresh pacing. Defaults: one `get` in flight (the bar drops overlapping requests), 60 ms between writes, 1.5 s for an answer,
 * and one slower second pass for the silent ones after 1.5 s.
 */
data class RefreshPacing(
    val inFlight: Int = 1,
    val gapMillis: Long = 60,
    val answerMillis: Long = 1_500,
    val retryAfterMillis: Long = 1_500,
)

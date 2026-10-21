package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.LineSplitter
import io.kud.tutti.protocol.net.TcpConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
) {
    private val mutableState = MutableStateFlow(TheatreState())
    val state: StateFlow<TheatreState> = mutableState.asStateFlow()

    private val ids = AtomicInteger(0)
    private val probes = ConcurrentHashMap<Int, CompletableDeferred<TheatreInbound.Result>>()

    @Volatile private var connection: Connection? = null
    private var readJob: Job? = null
    private var tickJob: Job? = null

    suspend fun connect(host: String) {
        disconnect()
        val conn = connector.connect(host, TheatreFeature.PORT)
        connection = conn
        dispatch(TheatreEvent.Connected(true))
        readJob = scope.launch {
            val splitter = LineSplitter()
            try {
                conn.incoming.collect { chunk -> splitter.feed(chunk).forEach(::onLine) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Socket error: handled as a disconnect below.
            } finally {
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
        refreshAll()
    }

    fun disconnect() {
        val conn = connection
        connection = null
        readJob?.cancel()
        tickJob?.cancel()
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

    /** Asks for every known feature; answers fill [TheatreState.confirmed]. */
    suspend fun refreshAll() {
        TheatreFeature.entries.forEach { get(it) }
    }

    /** Capability probe: true if the bar answers a `get` for [feature] with a value. */
    suspend fun probe(feature: TheatreFeature, timeoutMillis: Long = 1_500): Boolean {
        val request = TheatreOutbound.GetRequest(nextId(), feature.key)
        val answer = CompletableDeferred<TheatreInbound.Result>()
        probes[request.id] = answer
        return try {
            send(request)
            val result = withTimeoutOrNull(timeoutMillis) { answer.await() }
            result != null && result.value != null && !result.isAck
        } finally {
            probes.remove(request.id)
        }
    }

    private suspend fun send(request: TheatreOutbound) {
        val conn = connection ?: return
        dispatch(TheatreEvent.Sent(request, clock(), timeoutMillis))
        try {
            conn.send(TheatreCodec.encode(request).encodeToByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dispatch(TheatreEvent.SendFailed(request.id, e.message ?: "send failed"))
        }
    }

    private fun onLine(line: String) {
        val message = TheatreCodec.decode(line)
        if (message is TheatreInbound.Result && message.id != null) {
            probes[message.id]?.complete(message)
        }
        dispatch(TheatreEvent.Received(message))
    }

    private fun dispatch(event: TheatreEvent) {
        mutableState.update { TheatreReducer.reduce(it, event) }
    }

    private fun nextId(): Int = ids.updateAndGet { if (it >= MAX_ID) 1 else it + 1 }

    private companion object {
        const val TICK_MILLIS = 250L
        const val MAX_ID = 9_999
    }
}

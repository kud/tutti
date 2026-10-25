package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.atv.AtvRemote
import io.kud.tutti.protocol.atv.ClientDeviceInfo
import io.kud.tutti.protocol.atv.FrameDecoder
import io.kud.tutti.protocol.atv.Framing
import io.kud.tutti.protocol.atv.RemoteSession
import io.kud.tutti.protocol.atv.RemoteState
import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.bravia.BraviaClient
import io.kud.tutti.protocol.bravia.BraviaError
import io.kud.tutti.protocol.bravia.BraviaResult
import io.kud.tutti.protocol.bravia.BraviaService
import io.kud.tutti.protocol.bravia.BraviaTransport
import io.kud.tutti.protocol.bravia.JsonRpc
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.Redact
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import io.kud.tutti.protocol.theatre.BarHttpCodec
import io.kud.tutti.protocol.theatre.BarHttpKey
import io.kud.tutti.protocol.theatre.BarHttpTransport
import io.kud.tutti.protocol.theatre.BarHttpValue
import io.kud.tutti.protocol.theatre.JsonStreamSplitter
import io.kud.tutti.protocol.theatre.TheatreCodec
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreInbound
import io.kud.tutti.protocol.theatre.TheatreOutbound
import io.kud.tutti.protocol.theatre.TheatreWireLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** What the app knows before probing. Hosts and secrets go into the scrub list, never the report. */
data class DiagnosticTargets(
    val tvHost: String?,
    val barHost: String?,
    val psk: String?,
    val remotePin: CertificatePin?,
    val restPinRecorded: Boolean,
)

/** mDNS result, counted only: service names can carry a person's name. */
data class DiscoveryOutcome(val found: Int, val configuredTvSeen: Boolean?)

data class DiagnosticTimeouts(
    val stepMillis: Long = 12_000,
    val sessionMillis: Long = 8_000,
    val settleMillis: Long = 2_500,
    /** How long one bar `get` may wait for its answer; three wait at once. */
    val barAnswerMillis: Long = 1_500,
    /** The whole bar feature pass: about 40 paced `get`s, one at a time, longer than an ordinary step. */
    val barFeaturesMillis: Long = 45_000,
)

/**
 * Walks every plane against the configured devices, one read-only step at a time, and
 * publishes progress in [steps]. Nothing here powers, switches, or changes a level: REST
 * calls are getters, the remote session only answers the TV's own handshake and pings,
 * and the bar only receives `get`s. Cancel the calling coroutine to stop; open sockets
 * are closed on the way out.
 *
 * Its own connections, separate from the controller's live sessions, so a report shows
 * both what a fresh connection sees and (from the app) what the running session holds.
 */
class DiagnosticRunner(
    private val targets: DiagnosticTargets,
    private val bravia: BraviaTransport?,
    private val tls: TlsConnector,
    private val tcp: TcpConnector,
    private val identity: suspend () -> ClientIdentity?,
    private val discover: (suspend () -> DiscoveryOutcome)?,
    private val cloudNote: String,
    private val deviceInfo: ClientDeviceInfo = ClientDeviceInfo(),
    private val timeouts: DiagnosticTimeouts = DiagnosticTimeouts(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Developer mode: keep the scrubbed reply of reads marked [RestRead.raw]. */
    private val developer: Boolean = false,
    /** The bar's HTTP 54545 plane, read-only; null skips its step. */
    private val barHttp: BarHttpTransport? = null,
) {
    private val mutableSteps = MutableStateFlow(DiagnosticSteps.initial())
    val steps: StateFlow<List<StepResult>> = mutableSteps.asStateFlow()

    private val scrub = ConcurrentHashMap.newKeySet<String>().apply {
        listOfNotNull(targets.tvHost, targets.barHost, targets.psk, targets.remotePin?.sha256Hex).forEach(::add)
    }

    /** Everything the report must never show: hosts, PSK, pin, and identifying values seen in replies. */
    val sensitive: Set<String> get() = scrub.toSet()

    suspend fun run() {
        mutableSteps.value = DiagnosticSteps.initial()
        runDiscovery()
        // The bar first: it is quick and needs nothing from the TV, so a slow or stuck TV plane
        // can no longer leave its steps PENDING in a report copied mid-run.
        runBar()
        runBarHttp()
        runRest()
        runRemote()
        skip(DiagnosticSteps.CLOUD, cloudNote)
    }

    // region discovery

    private suspend fun runDiscovery() {
        val discover = discover ?: return skip(DiagnosticSteps.DISCOVERY, "mDNS unavailable")
        step(DiagnosticSteps.DISCOVERY) {
            val outcome = discover()
            fact("found", outcome.found.toString())
            outcome.configuredTvSeen?.let { fact("configured tv seen", yesNo(it)) }
            if (outcome.found == 0) fail("none_found", "no _androidtvremote2._tcp service answered")
        }
    }

    // endregion

    // region BRAVIA REST

    private suspend fun runRest() {
        val transport = bravia
        val restSteps = listOf(DiagnosticSteps.REST_INTERFACE, DiagnosticSteps.REST_AUTH, DiagnosticSteps.REST_METHODS) +
            DiagnosticSteps.REST_READS.map { it.id }
        if (transport == null || targets.tvHost == null) return restSteps.forEach { skip(it, "no TV configured") }

        val reachable = step(DiagnosticSteps.REST_INTERFACE) {
            fact("rest pin recorded", yesNo(targets.restPinRecorded))
            val reply = transport.post(
                BraviaService.SYSTEM.path,
                JsonRpc.request("getInterfaceInformation", 1, emptyList(), "1.0"),
                mapOf("Content-Type" to "application/json; charset=UTF-8"),
            )
            transport.scheme?.let { fact("scheme", it) }
            fact("http", reply.status.toString())
            when (val parsed = JsonRpc.parse(reply.body)) {
                is JsonRpc.Response.Success -> fact("shape", JsonShape.of(parsed.result, scrub))
                is JsonRpc.Response.Error -> fail("rpc_${parsed.code}", parsed.message)
                is JsonRpc.Response.Malformed -> fail("malformed", "${parsed.detail}; body ${reply.body.length} chars: ${reply.body.take(120)}")
            }
            true
        }
        // A TV that refuses the socket refuses every call: don't spend a minute proving it.
        val unreachable = reachable == null && current(DiagnosticSteps.REST_INTERFACE)?.errorClass in UNREACHABLE
        if (unreachable) {
            return restSteps.drop(1).forEach { skip(it, "TV REST unreachable (see rest.interface)") }
        }

        val client = BraviaClient(transport) { targets.psk }
        if (targets.psk.isNullOrEmpty()) {
            skip(DiagnosticSteps.REST_AUTH, "no PSK set in the app")
        } else {
            step(DiagnosticSteps.REST_AUTH) {
                fact("psk", "set")
                val result = expect(client.call(BraviaService.SYSTEM, "getSystemInformation"))
                fact("shape", JsonShape.of(result, scrub))
            }
        }

        step(DiagnosticSteps.REST_METHODS) {
            expect(client.discover())
            val caps = client.capabilities
            fact("discovery", caps.source ?: "none")
            for (service in BraviaService.entries) {
                val methods = caps.methods[service]
                fact(
                    service.id,
                    methods?.sortedBy { it.name }?.joinToString(", ") { "${it.name}@${it.version}" } ?: "no answer",
                )
            }
        }

        for (read in DiagnosticSteps.REST_READS) {
            val service = BraviaService.entries.first { it.id == read.service }
            val caps = client.capabilities
            if (caps.isKnown(service) && !caps.supports(service, read.method)) {
                skip(read.id, "${read.method} not listed by getMethodTypes", "not_listed")
                continue
            }
            step(read.id) {
                val version = caps.version(service, read.method) ?: BraviaClient.DEFAULT_VERSIONS[read.method] ?: "1.0"
                fact("call", "${read.service}.${read.method}@$version")
                val params = if (read.target) listOf(buildJsonObject { put("target", "") }) else emptyList()
                val result = expect(client.call(service, read.method, params))
                fact("shape", JsonShape.of(result, scrub))
                if (developer && read.raw) fact("raw", Redact.text(result.toString(), scrub).take(RAW_MAX))
            }
        }
    }

    private fun Collector.expect(result: BraviaResult<*>): JsonArray = when (result) {
        is BraviaResult.Ok -> when (val value = result.value) {
            is JsonArray -> value
            else -> JsonArray(listOf(JsonPrimitive(value.toString())))
        }
        is BraviaResult.Failure -> when (val error = result.error) {
            BraviaError.Unauthorized -> fail("unauthorized", "PSK refused or missing (HTTP 401/403 or RPC 403)")
            is BraviaError.Rpc -> fail(
                when {
                    error.isIllegalState -> "rpc_${error.code}_standby"
                    error.isUnsupported -> "rpc_${error.code}_unsupported"
                    else -> "rpc_${error.code}"
                },
                error.message,
            )
            is BraviaError.Http -> fail("http_${error.status}", "HTTP ${error.status}")
            is BraviaError.Unsupported -> fail("not_listed", error.method)
            is BraviaError.Malformed -> fail("malformed", error.detail)
            is BraviaError.Transport -> fail("transport", error.detail)
            BraviaError.PinMismatch -> fail("pin_mismatch", "certificate does not match the recorded pin")
        }
    }

    // endregion

    // region Android TV Remote v2

    private suspend fun runRemote() {
        val host = targets.tvHost
        val later = listOf(DiagnosticSteps.REMOTE_PAIRING_PORT, DiagnosticSteps.REMOTE_TLS, DiagnosticSteps.REMOTE_SESSION)
        if (host == null) return (listOf(DiagnosticSteps.REMOTE_IDENTITY) + later).forEach { skip(it, "no TV configured") }

        val client = step(DiagnosticSteps.REMOTE_IDENTITY) {
            val id = identity()
            fact("client identity", if (id == null) "none" else "present")
            id?.let {
                fact("key", "${it.privateKey.algorithm} (${it.privateKey.javaClass.simpleName})")
                fact("certificate valid until", it.certificate.notAfter.toInstant().toString().take(10))
            }
            fact("tv pin recorded", yesNo(targets.remotePin != null))
            if (id == null || targets.remotePin == null) fail("not_paired", "no pairing recorded: pair the TV first")
            id
        }

        step(DiagnosticSteps.REMOTE_PAIRING_PORT) {
            val conn = tcp.connect(host, PAIRING_PORT)
            conn.close()
            fact("port $PAIRING_PORT", "open (closed at once, no pairing started)")
        }

        val pin = targets.remotePin
        if (client == null || pin == null) {
            skip(DiagnosticSteps.REMOTE_TLS, "not paired")
            skip(DiagnosticSteps.REMOTE_SESSION, "not paired")
            return
        }

        var connection: TlsConnection? = null
        try {
            connection = step(DiagnosticSteps.REMOTE_TLS) {
                val conn = tls.connect(host, AtvRemote.PORT, client, pin)
                fact("peer certificate", "matches the recorded pin")
                fact("peer signature", conn.peerCertificate.sigAlgName)
                fact("peer valid until", conn.peerCertificate.notAfter.toInstant().toString().take(10))
                conn
            }
            val conn = connection ?: return skip(DiagnosticSteps.REMOTE_SESSION, "TLS failed (see remote.tls)")
            step(DiagnosticSteps.REMOTE_SESSION) { session(conn) }
        } finally {
            connection?.close()
        }
    }

    /** Answers only configure, set-active and ping; sends no key, no app link, no text. */
    private suspend fun Collector.session(conn: TlsConnection) {
        val session = RemoteSession(deviceInfo)
        val decoder = FrameDecoder()
        var state = RemoteState()
        var readyAt: Long? = null
        val seen = linkedMapOf<String, Int>()
        var undecodable = 0
        var remoteError = false
        withTimeoutOrNull(timeouts.sessionMillis) {
            try {
                conn.incoming.collect { chunk ->
                    for (frame in decoder.feed(chunk)) {
                        val message = try {
                            RemoteMessage.ADAPTER.decode(frame)
                        } catch (e: IOException) {
                            undecodable++
                            continue
                        }
                        kinds(message).forEach { seen[it] = (seen[it] ?: 0) + 1 }
                        message.remote_configure?.device_info?.let { info ->
                            fact("tv model", info.model)
                            fact("tv vendor", info.vendor)
                            fact("tv remote service version", info.app_version)
                        }
                        if (message.remote_error != null) remoteError = true
                        val reduced = session.reduce(state, message)
                        state = reduced.state
                        reduced.replies.forEach { conn.send(Framing.frame(it.encode())) }
                        if (state.ready && readyAt == null) readyAt = clock()
                    }
                    val since = readyAt
                    if (since != null && clock() - since >= timeouts.settleMillis) throw Settled()
                }
            } catch (e: Settled) {
                // Enough heard after the handshake.
            }
        }
        fact("messages", seen.entries.joinToString(", ") { "${it.key}x${it.value}" }.ifEmpty { "none" })
        if (undecodable > 0) fact("undecodable frames", undecodable.toString())
        state.powered?.let { fact("powered", yesNo(it)) }
        state.volume?.let { fact("volume", "${it.level}/${it.max}, muted ${yesNo(it.muted)}") }
        state.currentApp?.let { fact("foreground app", it) }
        when {
            remoteError -> fail("remote_error", "the TV sent remote_error")
            !state.ready -> fail("no_configure", "no remote_configure within ${timeouts.sessionMillis} ms")
        }
    }

    private class Settled : Exception("settled")

    private fun kinds(m: RemoteMessage): List<String> = listOfNotNull(
        m.remote_configure?.let { "configure" },
        m.remote_set_active?.let { "set_active" },
        m.remote_error?.let { "error" },
        m.remote_ping_request?.let { "ping" },
        m.remote_key_inject?.let { "key_inject" },
        m.remote_ime_key_inject?.let { "ime_key_inject" },
        m.remote_ime_batch_edit?.let { "ime_batch_edit" },
        m.remote_ime_show_request?.let { "ime_show_request" },
        m.remote_start?.let { "start" },
        m.remote_set_volume_level?.let { "set_volume_level" },
        m.remote_set_preferred_audio_device?.let { "set_preferred_audio_device" },
    ).ifEmpty { listOf("other") }

    // endregion

    // region soundbar

    /**
     * One `http_get` for the [BarHttpKey] readouts: answered (value, or only "answered" for the
     * AirPlay name), refused or not reported. Nothing else is ever asked on this plane.
     */
    private suspend fun runBarHttp() {
        val transport = barHttp
        if (targets.barHost == null || transport == null) return skip(DiagnosticSteps.BAR_HTTP, "no bar configured")
        step(DiagnosticSteps.BAR_HTTP) {
            fact("port", BarHttpKey.PORT.toString())
            val body = transport.post(BarHttpCodec.request())
            val readouts = BarHttpCodec.parse(body)
                ?: fail("malformed", "not an http_get_result; body ${body.length} chars: ${Redact.text(body, scrub).take(120)}")
            for (key in BarHttpKey.entries) {
                val shown = when (val value = readouts[key]) {
                    is BarHttpValue.Answered -> if (key.key in BarHttpKey.BLANKED) {
                        scrub += value.value
                        "answered"
                    } else {
                        "answered ${value.value}"
                    }
                    BarHttpValue.Refused -> "refused"
                    BarHttpValue.NotReported -> "not reported"
                }
                fact(key.key, shown)
            }
        }
    }

    private suspend fun runBar() {
        val host = targets.barHost
        val later = listOf(DiagnosticSteps.BAR_FEATURES, DiagnosticSteps.BAR_VOICE_ZOOM)
        if (host == null) return (listOf(DiagnosticSteps.BAR_CONNECT) + later).forEach { skip(it, "no bar configured") }

        var connection: Connection? = null
        try {
            connection = step(DiagnosticSteps.BAR_CONNECT) {
                tcp.connect(host, TheatreFeature.PORT).also { fact("port", TheatreFeature.PORT.toString()) }
            }
            val conn = connection ?: return later.forEach { skip(it, "not connected (see bar.connect)") }
            val answers = ConcurrentHashMap<String, String>()
            step(DiagnosticSteps.BAR_FEATURES, timeouts.barFeaturesMillis) { features(conn, answers) }
            step(DiagnosticSteps.BAR_VOICE_ZOOM) {
                val zoom = answers[TheatreFeature.VOICE_ZOOM.key]
                val level = answers[TheatreFeature.VOICE_ZOOM_LEVEL.key]
                fact(TheatreFeature.VOICE_ZOOM.key, zoom ?: "no answer")
                fact(TheatreFeature.VOICE_ZOOM_LEVEL.key, level ?: "no answer")
                val refused = TheatreInbound.REFUSALS.any { zoom.equals(it, ignoreCase = true) }
                if (zoom == null || refused) fail("no_answer", "the bar does not answer audio.voicezoom3: the app hides Voice Zoom")
            }
        } finally {
            connection?.close()
        }
    }

    /**
     * Asks every feature the way the app does (a few at a time, never a burst), then reports
     * each one as answered (with its value), refused or silent, plus the raw exchange.
     * Objects are split by [JsonStreamSplitter], so a bar that sends no newline is still heard.
     */
    private suspend fun Collector.features(conn: Connection, answers: MutableMap<String, String>) {
        val features = TheatreFeature.entries
        val byId = features.mapIndexed { i, f -> (i + 1) to f.key }.toMap()
        val waiting = ConcurrentHashMap<Int, CompletableDeferred<TheatreInbound.Result>>()
        val refused = ConcurrentHashMap.newKeySet<String>()
        val notified = ConcurrentHashMap.newKeySet<String>()
        val odd = mutableListOf<String>()
        val wire = TheatreWireLog(capacity = BAR_WIRE_LINES)
        coroutineScope {
            val reader = launch {
                val splitter = JsonStreamSplitter()
                try {
                    conn.incoming.collect { chunk ->
                        wire.received(chunk)
                        for (text in splitter.feed(chunk)) {
                            when (val message = TheatreCodec.decode(text)) {
                                is TheatreInbound.Result -> {
                                    val key = message.feature ?: message.id?.let(byId::get)
                                    when {
                                        key == null || message.value == null || message.isAck ->
                                            if (odd.size < MAX_SAMPLES) odd += TheatreWireLog.scrub(text.take(160))
                                        message.isRefusal -> refused += key
                                        else -> answers[key] = message.value
                                    }
                                    val id = message.id ?: byId.entries.firstOrNull { it.value == key }?.key
                                    id?.let { waiting[it]?.complete(message) }
                                }
                                is TheatreInbound.Notify -> notified += message.feature
                                is TheatreInbound.Malformed ->
                                    if (odd.size < MAX_SAMPLES) odd += "${message.reason}: ${TheatreWireLog.scrub(text.take(160))}"
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    odd += "read: ${ErrorClass.describe(e)}"
                }
            }
            val gate = Semaphore(BAR_IN_FLIGHT)
            val asks = byId.map { (id, key) ->
                gate.acquire()
                val answer = CompletableDeferred<TheatreInbound.Result>()
                waiting[id] = answer
                val text = TheatreCodec.encode(TheatreOutbound.GetRequest(id, key))
                wire.sent(text)
                conn.send(text.encodeToByteArray())
                launch {
                    try {
                        withTimeoutOrNull(timeouts.barAnswerMillis) { answer.await() }
                    } finally {
                        gate.release()
                    }
                }.also { delay(BAR_GAP_MS) }
            }
            asks.joinAll()
            reader.cancel()
        }
        val silent = byId.values.filter { it !in answers && it !in refused }
        fact("answered", "${answers.size}/${byId.size}, refused ${refused.size}, silent ${silent.size}")
        for (key in byId.values) {
            val answer = answers[key]
            // The user's own name for the bar: answered or not, never its value.
            if (answer != null && key in TheatreFeature.BLANKED) scrub += answer
            val shown = answer?.let { if (key in TheatreFeature.BLANKED) "answered" else "answered $it" }
            fact(key, shown ?: if (key in refused) "refused" else "silent")
        }
        if (notified.isNotEmpty()) fact("notify", notified.joinToString(", "))
        odd.forEachIndexed { i, line -> fact("unexpected ${i + 1}", line) }
        // The raw exchange, so a report shows exactly what this bar sends and how it frames it.
        wire.lines().forEachIndexed { i, line -> fact("wire %02d".format(i + 1), line) }
        if (answers.isEmpty() && refused.isEmpty()) fail("no_answer", "connected, but no get was answered: is External control on in BRAVIA Connect?")
    }

    // endregion

    // region machinery

    /** Facts gathered by one step, kept even when it fails. */
    private class Collector {
        val facts = mutableListOf<Pair<String, String>>()

        fun fact(key: String, value: String) {
            facts += key to value
        }

        fun fail(errorClass: String, message: String): Nothing = throw StepFailure(errorClass, message)
    }

    private class StepFailure(val errorClass: String, override val message: String) : Exception(message)

    private suspend fun <T> step(id: String, timeoutMillis: Long = timeouts.stepMillis, block: suspend Collector.() -> T): T? {
        update(id) { it.copy(status = StepStatus.RUNNING) }
        val start = clock()
        val collector = Collector()
        fun finish(status: StepStatus, errorClass: String? = null, message: String? = null) = update(id) {
            it.copy(status = status, millis = clock() - start, errorClass = errorClass, message = message, facts = collector.facts.toList())
        }
        return try {
            withTimeout(timeoutMillis) { collector.block() }.also { finish(StepStatus.OK) }
        } catch (e: TimeoutCancellationException) {
            finish(StepStatus.FAILED, "timeout", "no result within $timeoutMillis ms")
            null
        } catch (e: CancellationException) {
            finish(StepStatus.SKIPPED, "cancelled", "stopped")
            throw e
        } catch (e: StepFailure) {
            finish(StepStatus.FAILED, e.errorClass, e.message)
            null
        } catch (e: Exception) {
            finish(StepStatus.FAILED, ErrorClass.of(e), ErrorClass.describe(e))
            null
        }
    }

    private fun skip(id: String, reason: String, errorClass: String? = null) =
        update(id) { it.copy(status = StepStatus.SKIPPED, message = reason, errorClass = errorClass) }

    private fun current(id: String): StepResult? = mutableSteps.value.firstOrNull { it.id == id }

    private fun update(id: String, transform: (StepResult) -> StepResult) {
        mutableSteps.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    private companion object {
        const val PAIRING_PORT = 6467
        const val MAX_SAMPLES = 4
        const val BAR_IN_FLIGHT = 1
        const val BAR_GAP_MS = 60L
        const val BAR_WIRE_LINES = 50
        const val RAW_MAX = 2_000
        val UNREACHABLE = setOf("connection_refused", "timeout", "no_route", "unknown_host", "not_lan")
    }

    // endregion
}

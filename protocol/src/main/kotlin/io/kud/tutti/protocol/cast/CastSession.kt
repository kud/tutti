package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.proto.CastMessage
import io.kud.tutti.protocol.net.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.security.cert.X509Certificate

/** A TLS link to a Cast receiver. The peer certificate is trusted only once [CastDeviceAuth] has verified it. */
interface CastConnection : Connection {
    val peerCertificate: X509Certificate
}

/**
 * Opens TLS to a Cast receiver on a private LAN address. The handshake defers the trust
 * decision to [CastDeviceAuth]: the implementation accepts the device's self-signed
 * certificate only to hand it to [CastSession], which sends nothing but the auth challenge
 * until the device has proved it holds a Google-certified key over that very certificate.
 */
interface CastConnector {
    suspend fun connect(host: String, port: Int = PORT): CastConnection

    companion object {
        const val PORT = 8009
    }
}

/** How a session ended. Classes only, never device values: safe to log. */
sealed interface CastEnd {
    data class Untrusted(val reason: CastAuthFailure) : CastEnd
    /** The socket closed or failed; [kind] is an exception class name or "eof". */
    data class Closed(val kind: String) : CastEnd
    data object Malformed : CastEnd
}

/**
 * One Cast session: device auth, then receiver status, then the media status of the app in
 * front, through its transportId. The one media command it sends is a playback rate the user
 * picked ([setPlaybackRate], 2026-11-12); nothing else, and never on its own. Updates go to
 * [onReport]: [CastReport.Playing] with an item, [CastReport.Stopped] when the receiver confirms
 * it is over, and [CastReport.Unknown] for silence (connecting, a closed transport, an empty
 * status, the session's end), which [CastHold] rides out rather than showing nothing at once.
 */
class CastSession(
    private val connection: CastConnection,
    private val onReport: (CastReport) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonce: ByteArray = ByteArray(CastDeviceAuth.NONCE_BYTES).also { SecureRandom().nextBytes(it) },
    private val roots: List<X509Certificate> = CastRoots.all,
    private val heartbeatMillis: Long = HEARTBEAT_MS,
    private val refreshMillis: Long = REFRESH_MS,
    /** The raw numbers of every media status from the app in front, for the diagnostic. */
    private val onReading: (CastReading) -> Unit = {},
    /** Lines for developer mode's event log ([CastLogLines]): states and presence, never viewing data. */
    private val onEvent: (String) -> Unit = {},
) {
    private val decoder = CastFrameDecoder()
    private val reducer = CastStatusReducer()
    private val sendLock = Mutex()
    private val requestId = java.util.concurrent.atomic.AtomicInteger()
    private var verified = false
    @Volatile private var app: CastApp? = null

    /** Set by a media status that said nothing either way: ask again on the next heartbeat, not the next refresh. */
    @Volatile private var askSoon = false
    private var lastReport: CastReport? = null

    /** The last media line logged: an unchanged status every refresh is not news. */
    private var lastMediaLine: String? = null

    /** Whether "asking the receiver again" is already in the log since the last app change. */
    @Volatile private var askLogged = false

    /** The media session the app in front last reported: what a media command has to name. */
    @Volatile private var mediaSessionId: Long? = null

    private class Pending(val requestId: Int, val rate: Double, val answer: CompletableDeferred<CastSpeedResult>)

    @Volatile private var pending: Pending? = null

    /** The receiver app in front, for the speed log and per-app memory. */
    val appId: String? get() = app?.appId

    /**
     * Asks the app in front to play at [rate], naming the media session its last status gave, and
     * waits up to [CastSpeed.ANSWER_MS] for the reply ([CastSpeed.classify]). One request at a time:
     * a new one supersedes an unanswered one, which reads as [CastSpeedResult.NoAnswer].
     */
    suspend fun setPlaybackRate(rate: Double): CastSpeedResult {
        val target = app ?: return CastSpeedResult.NoSession
        val session = mediaSessionId ?: return CastSpeedResult.NoSession
        if (!verified) return CastSpeedResult.NoSession
        val id = requestId.incrementAndGet()
        val ask = Pending(id, rate, CompletableDeferred())
        pending?.answer?.complete(CastSpeedResult.NoAnswer)
        pending = ask
        return try {
            send(text(target.transportId, CastNamespaces.MEDIA, CastSpeed.request(session, id, rate)))
            withTimeoutOrNull(CastSpeed.ANSWER_MS) { ask.answer.await() } ?: CastSpeedResult.NoAnswer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CastSpeedResult.NoAnswer
        } finally {
            if (pending === ask) pending = null
        }
    }

    /** Runs until the connection ends; the caller closes it on cancellation. */
    suspend fun run(): CastEnd = coroutineScope {
        send(CastFraming.binary(SENDER, RECEIVER, CastDeviceAuth.NAMESPACE, CastDeviceAuth.challenge(nonce)))
        val ticker = launch {
            var sinceRefresh = 0L
            while (true) {
                delay(heartbeatMillis)
                if (!verified) continue
                try {
                    send(text(RECEIVER, CastNamespaces.HEARTBEAT, """{"type":"PING"}"""))
                    sinceRefresh += heartbeatMillis
                    if (sinceRefresh >= refreshMillis || askSoon) {
                        sinceRefresh = 0
                        askSoon = false
                        // No app known: a receiver status pushed while we weren't listening, or a
                        // transport that closed under an app still in front, would otherwise leave
                        // the session deaf until it drops.
                        val target = app
                        if (target != null) {
                            mediaStatus(target)
                        } else {
                            if (!askLogged) onEvent(CastLogLines.askReceiver())
                            askLogged = true
                            receiverStatus()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A write on a socket that just died: end the read, which reports how it closed.
                    connection.close()
                    break
                }
            }
        }
        var result: CastEnd? = null
        val end = try {
            connection.incoming.collect { chunk ->
                if (result != null) return@collect
                val messages = try { decoder.feed(chunk) } catch (_: CastFrameException) { result = CastEnd.Malformed; emptyList() }
                for (m in messages) {
                    result = handle(m)
                    if (result != null) break
                }
                if (result != null) connection.close()
            }
            result ?: CastEnd.Closed("eof")
        } catch (e: CancellationException) {
            // Cancelled from outside: propagate, never report it as a closed socket.
            throw e
        } catch (e: Exception) {
            // Closing the socket after a verdict can fail the read: the verdict is the reason.
            result ?: CastEnd.Closed(e.javaClass.simpleName)
        } finally {
            ticker.cancel()
            onReport(CastReport.Unknown)
        }
        end
    }

    private suspend fun handle(m: CastMessage): CastEnd? {
        if (!verified) {
            if (m.namespace != CastDeviceAuth.NAMESPACE) return null
            val reply = m.payload_binary?.toByteArray() ?: return CastEnd.Untrusted(CastAuthFailure.MALFORMED)
            return when (val r = CastDeviceAuth.verify(reply, connection.peerCertificate, nonce, clock(), roots)) {
                is CastAuthResult.Failed -> CastEnd.Untrusted(r.reason)
                CastAuthResult.Verified -> {
                    verified = true
                    send(text(RECEIVER, CastNamespaces.CONNECTION, """{"type":"CONNECT","origin":{}}"""))
                    receiverStatus()
                    null
                }
            }
        }
        val payload = m.payload_utf8?.let(CastStatusParser::parse) ?: return null
        when (m.namespace) {
            CastNamespaces.HEARTBEAT -> if (CastStatusParser.type(payload) == "PING") {
                send(text(m.source_id, CastNamespaces.HEARTBEAT, """{"type":"PONG"}"""))
            }
            CastNamespaces.RECEIVER -> if (CastStatusParser.type(payload) == "RECEIVER_STATUS") onReceiverStatus(payload)
            CastNamespaces.MEDIA -> if (m.source_id == app?.transportId) {
                pending?.let { ask -> CastSpeed.classify(payload, ask.requestId, ask.rate)?.let(ask.answer::complete) }
                if (CastStatusParser.type(payload) != "MEDIA_STATUS") return null
                CastSpeed.mediaSessionId(payload)?.let { mediaSessionId = it }
                val report = reducer.report(payload, clock()).withApp(app?.appId)
                reducer.lastReading?.let(onReading)
                val line = CastLogLines.media(reducer.lastReading, reducer.lastHadTitle, report)
                if (line != lastMediaLine) {
                    lastMediaLine = line
                    onEvent(line)
                }
                // One quick re-ask per gap: a status that keeps saying nothing waits for the refresh.
                if (report == CastReport.Unknown && lastReport != CastReport.Unknown) askSoon = true
                lastReport = report
                onReport(report)
            }
            CastNamespaces.CONNECTION -> if (CastStatusParser.type(payload) == "CLOSE" && m.source_id == app?.transportId) {
                onEvent(CastLogLines.transportClosed(app))
                app = null
                mediaSessionId = null
                lastMediaLine = null
                reducer.reset()
                lastReport = CastReport.Unknown
                onReport(CastReport.Unknown)
                // The receiver pushes a status only when its apps change; an app that closed our
                // virtual connection but stays in front, on the same transport, would never come
                // back. Ask, and [onReceiverStatus] reconnects to it.
                receiverStatus()
            }
        }
        return null
    }

    private suspend fun onReceiverStatus(payload: kotlinx.serialization.json.JsonObject) {
        val next = CastStatusParser.mediaApp(payload)
        val current = app
        if (next?.transportId == current?.transportId) return
        onEvent(CastLogLines.app(current, next))
        app = next
        mediaSessionId = null
        lastMediaLine = null
        askLogged = false
        reducer.reset()
        // Another media app in front ends the item. The same app on a new transport says nothing yet,
        // and so does no media app at all: receivers drop and relist apps around a reload, and a real
        // switch to a non-Cast app shows on the Remote v2's package ([CastHold.onContext]).
        val otherApp = next != null && current != null && current.appId != next.appId
        val report = if (otherApp) CastReport.Stopped else CastReport.Unknown
        lastReport = report
        onReport(report)
        if (next != null) {
            send(text(next.transportId, CastNamespaces.CONNECTION, """{"type":"CONNECT","origin":{}}"""))
            mediaStatus(next)
        }
    }

    private fun CastReport.withApp(appId: String?): CastReport =
        if (this is CastReport.Playing) CastReport.Playing(nowPlaying.copy(appId = appId)) else this

    private suspend fun receiverStatus() {
        send(text(RECEIVER, CastNamespaces.RECEIVER, """{"type":"GET_STATUS","requestId":${requestId.incrementAndGet()}}"""))
    }

    private suspend fun mediaStatus(target: CastApp) {
        send(text(target.transportId, CastNamespaces.MEDIA, """{"type":"GET_STATUS","requestId":${requestId.incrementAndGet()}}"""))
    }

    private fun text(destination: String, namespace: String, json: String) = CastFraming.text(SENDER, destination, namespace, json)

    private suspend fun send(message: CastMessage) = sendLock.withLock { connection.send(CastFraming.encode(message)) }

    companion object {
        const val SENDER = "sender-tutti"
        const val RECEIVER = "receiver-0"
        const val HEARTBEAT_MS = 5_000L
        const val REFRESH_MS = 15_000L
    }
}

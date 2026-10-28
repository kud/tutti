package io.kud.tutti.protocol.cast

import io.kud.tutti.protocol.cast.proto.CastMessage
import io.kud.tutti.protocol.net.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * One read-only Cast session: device auth, then receiver status, then the media status of
 * the app in front, through its transportId. It never sends a media command. Updates go to
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
                        app?.let { mediaStatus(it) }
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
                    send(text(RECEIVER, CastNamespaces.RECEIVER, """{"type":"GET_STATUS","requestId":${requestId.incrementAndGet()}}"""))
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
            CastNamespaces.MEDIA -> if (CastStatusParser.type(payload) == "MEDIA_STATUS" && m.source_id == app?.transportId) {
                val report = reducer.report(payload, clock())
                reducer.lastReading?.let(onReading)
                // One quick re-ask per gap: a status that keeps saying nothing waits for the refresh.
                if (report == CastReport.Unknown && lastReport != CastReport.Unknown) askSoon = true
                lastReport = report
                onReport(report)
            }
            CastNamespaces.CONNECTION -> if (CastStatusParser.type(payload) == "CLOSE" && m.source_id == app?.transportId) {
                app = null
                reducer.reset()
                lastReport = CastReport.Unknown
                onReport(CastReport.Unknown)
            }
        }
        return null
    }

    private suspend fun onReceiverStatus(payload: kotlinx.serialization.json.JsonObject) {
        val next = CastStatusParser.mediaApp(payload)
        val current = app
        if (next?.transportId == current?.transportId) return
        app = next
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

package io.kud.tutti.protocol.atv

import io.kud.tutti.protocol.atv.proto.remote.RemoteMessage
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The remote channel, TLS on port 6466, pinned to the certificate recorded at pairing.
 * The TV pings about every 5 s; the app should treat ~10 s of silence as a dead link
 * (socket read timeout in its [TlsConnector]) and reconnect.
 *
 * A TLS failure right after connecting usually means the TV forgot the pairing:
 * surface "Associer à nouveau" rather than retrying forever.
 */
class AtvRemote(
    private val connector: TlsConnector,
    private val scope: CoroutineScope,
    deviceInfo: ClientDeviceInfo = ClientDeviceInfo(),
) {
    private val session = RemoteSession(deviceInfo)
    private val mutableState = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()

    private val writeLock = Mutex()

    @Volatile private var connection: TlsConnection? = null
    private var readJob: Job? = null

    suspend fun connect(host: String, identity: ClientIdentity, serverPin: CertificatePin) {
        disconnect()
        val conn = connector.connect(host, PORT, identity, serverPin)
        connection = conn
        readJob = scope.launch {
            val decoder = FrameDecoder()
            try {
                conn.incoming.collect { chunk ->
                    for (frame in decoder.feed(chunk)) {
                        val message = try {
                            RemoteMessage.ADAPTER.decode(frame)
                        } catch (e: IOException) {
                            continue
                        }
                        var replies: List<RemoteMessage> = emptyList()
                        mutableState.update { current ->
                            val step = session.reduce(current, message)
                            replies = step.replies
                            step.state
                        }
                        replies.forEach { write(conn, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: "connection lost") }
            } finally {
                if (connection === conn) {
                    connection = null
                    mutableState.update { it.copy(ready = false) }
                }
            }
        }
    }

    fun disconnect() {
        val conn = connection
        connection = null
        readJob?.cancel()
        conn?.close()
        mutableState.update { it.copy(ready = false, ime = null) }
    }

    /** Returns false when not connected or [keyCode] is not a v2 key. */
    suspend fun sendKey(keyCode: Int, direction: KeyDirection = KeyDirection.SHORT): Boolean {
        val message = RemoteMessages.key(keyCode, direction) ?: return false
        return send(message)
    }

    /** Long press: Select held, for the TV's own long-press menus. */
    suspend fun longPress(keyCode: Int): Boolean =
        sendKey(keyCode, KeyDirection.START_LONG) && sendKey(keyCode, KeyDirection.END_LONG)

    /** Replaces the focused field's text. Echoed locally first. */
    suspend fun sendText(text: String): Boolean {
        val current = mutableState.value
        mutableState.update { session.typed(it, text) }
        return send(RemoteMessages.text(text, current.imeCounter, current.fieldCounter))
    }

    suspend fun launch(link: String): Boolean = send(RemoteMessages.appLink(link))

    fun dismissIme() {
        mutableState.update { session.dismissIme(it) }
    }

    private suspend fun send(message: RemoteMessage): Boolean {
        val conn = connection ?: return false
        if (!mutableState.value.ready) return false
        return try {
            write(conn, message)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun write(conn: TlsConnection, message: RemoteMessage) {
        val bytes = Framing.frame(message.encode())
        writeLock.withLock { conn.send(bytes) }
    }

    companion object {
        const val PORT = 6466
    }
}

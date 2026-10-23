package io.kud.tutti.protocol.net

import io.kud.tutti.protocol.atv.Framing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate

/**
 * A connection with the semantics of the app's `SocketConnection`, in virtual time:
 * - the reader runs ahead of the collector (the real one reads on IO through `flowOn`),
 * - a read with nothing to read waits up to [readTimeoutMillis] (soTimeout), then throws,
 * - only [close] releases a waiting read, as only closing a socket does,
 * - the peer may half-close ([peerHalfClose]): the reader sees EOF while our end stays open
 *   until we close it, and a silent peer simply never writes.
 */
open class SocketLikeConnection(var readTimeoutMillis: Long = 20_000) : Connection {
    private val wire = Channel<ByteArray>(Channel.UNLIMITED)
    private val eof = ByteArray(0)

    @Volatile var closed = false
        private set
    val sent = mutableListOf<ByteArray>()

    /** Called on every write, so a scripted peer can answer. */
    var onSend: suspend (ByteArray) -> Unit = {}

    override val incoming: Flow<ByteArray> = flow {
        while (true) {
            val chunk = try {
                withTimeoutOrNull(readTimeoutMillis) { wire.receive() } ?: throw SocketTimeoutException("Read timed out")
            } catch (e: ClosedReceiveChannelException) {
                throw SocketException("Socket closed")
            }
            if (chunk === eof) break
            emit(chunk)
        }
    }.buffer(Channel.BUFFERED)

    override fun setReadTimeout(millis: Int) {
        readTimeoutMillis = if (millis == 0) Long.MAX_VALUE else millis.toLong()
    }

    override suspend fun send(bytes: ByteArray) {
        if (closed) throw SocketException("Socket closed")
        sent += bytes
        onSend(bytes)
    }

    fun peerWrites(bytes: ByteArray) {
        wire.trySend(bytes)
    }

    fun peerWritesFrame(message: ByteArray) = peerWrites(Framing.frame(message))

    /** The peer shuts its output: we read EOF, our socket stays open until [close]. */
    fun peerHalfClose() {
        wire.trySend(eof)
    }

    override fun close() {
        closed = true
        wire.close()
    }
}

open class SocketLikeTlsConnection(
    override val localCertificate: X509Certificate,
    override val peerCertificate: X509Certificate,
    readTimeoutMillis: Long = 20_000,
) : SocketLikeConnection(readTimeoutMillis), TlsConnection

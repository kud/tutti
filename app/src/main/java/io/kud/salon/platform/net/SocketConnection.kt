package io.kud.salon.platform.net

import io.kud.salon.protocol.net.Connection
import io.kud.salon.protocol.net.TlsConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * A blocking socket exposed as a [Connection]. Reads run on the IO dispatcher; a blocked
 * read ends when [close] is called (the caller cancels its collector, then closes).
 */
internal open class SocketConnection(private val socket: Socket) : Connection {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val writeLock = Mutex()

    override val incoming: Flow<ByteArray> = flow {
        val buffer = ByteArray(READ_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) emit(buffer.copyOf(read))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun send(bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                output.write(bytes)
                output.flush()
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val READ_BUFFER = 16 * 1024
    }
}

internal class TlsSocketConnection(
    socket: SSLSocket,
    override val localCertificate: X509Certificate,
) : SocketConnection(socket), TlsConnection {
    override val peerCertificate: X509Certificate = socket.session.peerCertificates[0] as X509Certificate
}

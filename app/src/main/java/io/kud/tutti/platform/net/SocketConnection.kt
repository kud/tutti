package io.kud.tutti.platform.net

import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TlsConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

/**
 * A blocking socket exposed as a [Connection]. Reads run on the IO dispatcher, ahead of the
 * collector. A blocked read ignores coroutine cancellation and only a close ends it, so when the
 * collector stops early (cancelled, `first()`, an exception thrown from collect) the socket is
 * closed under the read: otherwise leaving collect waits out the read timeout (20 s on the TV).
 */
internal open class SocketConnection(private val socket: Socket) : Connection {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val writeLock = Mutex()

    override val incoming: Flow<ByteArray> = channelFlow {
        val stopping = AtomicBoolean(false)
        val reader = launch(Dispatchers.IO) {
            val buffer = ByteArray(READ_BUFFER)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) send(buffer.copyOf(read))
                }
            } catch (e: IOException) {
                // The read we ended by closing below is not a failure to report.
                if (!stopping.get()) throw e
            }
        }
        try {
            reader.join()
        } catch (e: CancellationException) {
            stopping.set(true)
            // The socket's close, not the channel's (the receiver of a bare close() here).
            this@SocketConnection.close()
            throw e
        }
    }

    override fun setReadTimeout(millis: Int) {
        runCatching { socket.soTimeout = millis }
    }

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

    /** Closes from under a read blocked on another thread, without waiting for it. */
    protected fun closeUnder(raw: Socket) {
        runCatching { raw.close() }
        close()
    }

    private companion object {
        const val READ_BUFFER = 16 * 1024
    }
}

internal class TlsSocketConnection(
    socket: SSLSocket,
    private val raw: Socket,
    override val localCertificate: X509Certificate,
) : SocketConnection(socket), TlsConnection {
    override val peerCertificate: X509Certificate = socket.session.peerCertificates[0] as X509Certificate

    // SSLSocket.close() waits for a read blocked on another thread, up to its 20 s timeout: the
    // TV keeps the pairing socket open after the secret ack, so pairing outlived its watchdog.
    // Closing the plain socket underneath ends that read at once.
    override fun close() = closeUnder(raw)

    override fun setReadTimeout(millis: Int) {
        super<SocketConnection>.setReadTimeout(millis)
        runCatching { raw.soTimeout = millis }
    }
}

package io.kud.tutti.platform.net

import io.kud.tutti.protocol.cast.CastConnection
import io.kud.tutti.protocol.cast.CastConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS to the TV's Cast receiver, port 8009, no client certificate (architecture § 7). The
 * handshake makes no trust decision beyond a well-formed, in-date leaf: [PeerRecorder] keeps
 * that leaf for [io.kud.tutti.protocol.cast.CastSession], which sends only its deviceauth
 * challenge and reads nothing else until the device has signed our nonce over this very
 * certificate with a key that chains to Google's Cast roots. The internal constructor is the
 * test seam, as on [AndroidTcpConnector].
 */
@Singleton
class AndroidCastConnector internal constructor(
    private val checkHost: (String) -> Unit,
    private val readTimeoutMs: Int,
) : CastConnector {
    @Inject constructor() : this(::requireLan, CAST_READ_TIMEOUT_MS)

    override suspend fun connect(host: String, port: Int): CastConnection = closingIfCancelled { opened -> withContext(Dispatchers.IO) {
        checkHost(host)
        val recorder = PeerRecorder()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(recorder), SecureRandom())
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.tcpNoDelay = true
            raw.soTimeout = readTimeoutMs
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.startHandshake()
            val peer = recorder.peer ?: throw CertificateException("no peer certificate")
            CastSocketConnection(ssl, raw, peer).also(opened)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    } }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        /** The session pings every 5 s and the TV answers: 20 s of silence is a dead link. */
        const val CAST_READ_TIMEOUT_MS = 20_000
    }
}

/**
 * Records the receiver's leaf certificate and defers trust to Cast device auth. It refuses an
 * empty chain or a leaf outside its dates, so a handshake never completes on nothing; it is not
 * trust-all, because the connection it opens carries nothing but the auth challenge until
 * `CastDeviceAuth.verify` has passed on [peer]. Never used for anything but the Cast socket.
 */
internal class PeerRecorder : X509TrustManager {
    @Volatile var peer: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty chain")
        leaf.checkValidity()
        peer = leaf
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client mode only")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal class CastSocketConnection(
    socket: SSLSocket,
    private val raw: Socket,
    override val peerCertificate: X509Certificate,
) : SocketConnection(socket), CastConnection {
    // As TlsSocketConnection: closing the plain socket ends a read blocked on another thread.
    override fun close() = closeUnder(raw)

    override fun setReadTimeout(millis: Int) {
        super<SocketConnection>.setReadTimeout(millis)
        runCatching { raw.soTimeout = millis }
    }
}

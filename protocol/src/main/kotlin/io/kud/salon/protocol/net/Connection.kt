package io.kud.salon.protocol.net

import kotlinx.coroutines.flow.Flow
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * A connected byte stream. `:protocol` never opens sockets itself: `:app` implements this
 * over `java.net.Socket` / `SSLSocket`, and tests implement it in memory.
 */
interface Connection {
    /** Chunks exactly as read from the socket. Completes on EOF, throws on I/O error. Collect once. */
    val incoming: Flow<ByteArray>

    suspend fun send(bytes: ByteArray)

    fun close()
}

/** Plain TCP, used by the soundbar's port 33336. */
interface TcpConnector {
    suspend fun connect(host: String, port: Int): Connection
}

/** The client certificate and its key. On Android the key is an AndroidKeyStore handle. */
class ClientIdentity(val certificate: X509Certificate, val privateKey: PrivateKey)

interface TlsConnection : Connection {
    val localCertificate: X509Certificate
    val peerCertificate: X509Certificate
}

/**
 * TLS with a client certificate and a trust-on-first-use pin on the server certificate.
 *
 * - [expectedPin] `null`: first contact. The implementation accepts the self-signed peer and
 *   the caller records `CertificatePin.of(peerCertificate)` once the user has confirmed the pairing.
 * - [expectedPin] set: the implementation MUST fail the handshake unless the peer matches.
 *
 * Verification is never simply disabled.
 */
interface TlsConnector {
    suspend fun connect(
        host: String,
        port: Int,
        identity: ClientIdentity,
        expectedPin: CertificatePin?,
    ): TlsConnection
}

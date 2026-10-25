package io.kud.tutti.platform.net

import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/** Refuses anything but a private LAN address: the app never leaves the living room. */
internal fun requireLan(host: String) {
    if (!LanAddress.isPrivate(host)) throw IOException("not a private LAN address")
}

/**
 * Soundbar port 33336. The controller polls every ~20 s, so a long silence means a dead link.
 * The internal constructor is the test seam: the device simulator listens on loopback, which
 * [requireLan] rightly refuses, and a test cannot wait out the real read timeout.
 */
@Singleton
class AndroidTcpConnector internal constructor(
    private val checkHost: (String) -> Unit,
    private val readTimeoutMs: Int,
) : TcpConnector {
    @Inject constructor() : this(::requireLan, BAR_READ_TIMEOUT_MS)

    override suspend fun connect(host: String, port: Int): Connection = closingIfCancelled { opened -> withContext(Dispatchers.IO) {
        checkHost(host)
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = readTimeoutMs
            SocketConnection(socket).also(opened)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    } }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val BAR_READ_TIMEOUT_MS = 75_000
    }
}

/**
 * TLS with the client identity and a TOFU pin on the TV. The TV pings about every 5 s on
 * the remote port, so a 20 s read timeout marks the link dead. The internal constructor is the
 * test seam, as on [AndroidTcpConnector].
 */
@Singleton
class AndroidTlsConnector internal constructor(
    private val checkHost: (String) -> Unit,
    private val readTimeoutMs: Int,
) : TlsConnector {
    @Inject constructor() : this(::requireLan, TV_READ_TIMEOUT_MS)

    override suspend fun connect(
        host: String,
        port: Int,
        identity: ClientIdentity,
        expectedPin: CertificatePin?,
    ): TlsConnection = closingIfCancelled { opened -> withContext(Dispatchers.IO) {
        checkHost(host)
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(IdentityKeyManager(identity)), arrayOf(PinningTrustManager(expectedPin)), SecureRandom())
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.tcpNoDelay = true
            raw.soTimeout = readTimeoutMs
            val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.startHandshake()
            TlsSocketConnection(ssl, raw, identity.certificate).also(opened)
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    } }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val TV_READ_TIMEOUT_MS = 20_000
    }
}

/** Always presents the one identity the TV bound the pairing to. */
internal class IdentityKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)

    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private companion object {
        const val ALIAS = "tutti-client"
    }
}

/**
 * A blocking connect finishes even when its caller was cancelled meanwhile, and `withContext`
 * then throws away the result: the socket stayed open, and the bar kept a client nobody read.
 * Whatever [block] reports through `opened` is closed if the call ends in cancellation.
 */
internal suspend fun <T : Connection> closingIfCancelled(block: suspend (opened: (T) -> Unit) -> T): T {
    var opened: T? = null
    try {
        return block { opened = it }
    } catch (e: kotlinx.coroutines.CancellationException) {
        opened?.let { runCatching { it.close() } }
        throw e
    }
}

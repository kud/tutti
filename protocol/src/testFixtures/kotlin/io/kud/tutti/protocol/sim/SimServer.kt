package io.kud.tutti.protocol.sim

import io.kud.tutti.protocol.atv.ClientCertificates
import io.kud.tutti.protocol.net.CertificatePin
import io.kud.tutti.protocol.net.ClientIdentity
import io.kud.tutti.protocol.net.Connection
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnection
import io.kud.tutti.protocol.net.TlsConnector
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.StandardSocketOptions
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * What a simulated device does wrong. Every field may change while it runs; counts are per
 * connection, in messages the client sent.
 */
class Faults {
    /** Wait this long before every answer. */
    @Volatile var replyDelayMillis: Long = 0

    /** Keep reading, never answer, never ping: a TV that froze with the socket up. */
    @Volatile var silent: Boolean = false

    /** Close the socket (FIN, no goodbye) on receiving this many messages. */
    @Volatile var closeAfter: Int? = null

    /** Shut our output on receiving this many messages: the client reads EOF, we keep reading. */
    @Volatile var halfCloseAfter: Int? = null

    /** Close once the script has nothing more to say. The real TV does not: it keeps the socket open. */
    @Volatile var closeAfterLast: Boolean = false

    fun reset() {
        replyDelayMillis = 0
        silent = false
        closeAfter = null
        halfCloseAfter = null
        closeAfterLast = false
    }
}

/**
 * Fixed test keys, generated once per JVM: RSA 2048 takes long enough that each test making
 * its own would slow the suite. Invented names only.
 */
object SimKeys {
    val tv: ClientIdentity by lazy { ClientCertificates.generate("BRAVIA simulator") }
    val tvAfterReset: ClientIdentity by lazy { ClientCertificates.generate("BRAVIA simulator, reset") }
    val client: ClientIdentity by lazy { ClientCertificates.generate("tutti test client") }
    val otherClient: ClientIdentity by lazy { ClientCertificates.generate("another phone") }
}

/** Server-side TLS for a simulated device presenting [identity]. */
fun serverTls(identity: ClientIdentity, onClientCertificate: (X509Certificate) -> Unit = {}): SSLContext {
    val password = CharArray(0)
    val store = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry("device", identity.privateKey, password, arrayOf(identity.certificate))
    }
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
    // The device side of a test fixture: the TV accepts any client certificate at pairing and
    // decides itself whom it knows. The app's own trust is never this.
    val anyClient = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
            onClientCertificate(chain[0])
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw UnsupportedOperationException("server side only")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, arrayOf(anyClient), SecureRandom()) }
}

/**
 * One device port on 127.0.0.1, served by plain threads so it never shares a dispatcher with
 * the code under test. [sleep] closes the port and every live socket, as standby does;
 * [wake] reopens the same port. Each accepted connection gets a [Link] and its own thread.
 */
abstract class SimServer(private val name: String) : AutoCloseable {
    val faults = Faults()

    @Volatile var port: Int = 0
        private set

    @Volatile var awake: Boolean = false
        private set

    /** Connections accepted since start, across sleeps. */
    val accepted = AtomicInteger()

    protected val links = CopyOnWriteArrayList<Link>()
    private var server: ServerSocket? = null

    /** TLS devices wrap the accepted socket; the raw one stays reachable to drop it abruptly. */
    protected open fun wrap(raw: Socket): Socket = raw

    /** Runs on the connection's own thread; return to end it. Throwing is a dropped link. */
    protected abstract fun serve(link: Link)

    fun start(): SimServer = apply { wake() }

    @Synchronized
    fun wake() {
        if (awake) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Sockets accepted before a sleep may linger (FIN_WAIT) until the client closes its end;
        // SO_REUSEPORT lets the woken port bind beside them, as a rebooted TV's would.
        if (StandardSocketOptions.SO_REUSEPORT in socket.supportedOptions()) socket.setOption(StandardSocketOptions.SO_REUSEPORT, true)
        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
        port = socket.localPort
        server = socket
        awake = true
        thread(isDaemon = true, name = "$name-accept") {
            while (!socket.isClosed) {
                val raw = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                accepted.incrementAndGet()
                thread(isDaemon = true, name = "$name-link") {
                    var link: Link? = null
                    try {
                        raw.tcpNoDelay = true
                        link = Link(wrap(raw), raw).also { links += it }
                        serve(link)
                    } catch (e: Exception) {
                        // A dropped or refused link; the client sees the close.
                    } finally {
                        link?.let { links -= it }
                        runCatching { raw.close() }
                    }
                }
            }
        }
    }

    /** Standby: the port refuses, and every open socket dies without a goodbye. */
    @Synchronized
    fun sleep() {
        awake = false
        runCatching { server?.close() }
        server = null
        dropConnections()
    }

    /** Closes every live socket mid-stream; the port stays open. */
    fun dropConnections() {
        links.forEach { it.drop() }
    }

    /** Shuts our output on every live socket: clients read EOF while we keep reading. */
    fun halfCloseConnections() {
        links.forEach { it.halfClose() }
    }

    val liveConnections: Int get() = links.count { !it.raw.isClosed }

    override fun close() = sleep()

    inner class Link(val socket: Socket, val raw: Socket) {
        val input = socket.getInputStream()
        private val output = socket.getOutputStream()

        /** Messages received on this link. */
        @Volatile var inbound = 0
            private set

        fun write(bytes: ByteArray) = synchronized(this) {
            output.write(bytes)
            output.flush()
        }

        /**
         * Call once per message the client sent, before answering it. False when the faults ate
         * the answer: the link was closed, half-closed, or the device is silent.
         */
        fun received(): Boolean {
            inbound++
            faults.closeAfter?.let { if (inbound >= it) { drop(); return false } }
            faults.halfCloseAfter?.let { if (inbound >= it) { halfClose(); return false } }
            if (faults.silent) return false
            if (faults.replyDelayMillis > 0) Thread.sleep(faults.replyDelayMillis)
            return true
        }

        fun drop() {
            runCatching { raw.close() }
        }

        fun halfClose() {
            runCatching { socket.shutdownOutput() }.onFailure { runCatching { raw.shutdownOutput() } }
        }

        val peerCertificate: X509Certificate?
            get() = (socket as? SSLSocket)?.session?.peerCertificates?.firstOrNull() as? X509Certificate
    }
}

/** A TLS device port: the accepted socket is wrapped in server mode with the device's current identity. */
abstract class TlsSimServer(name: String, identity: ClientIdentity, private val needClientAuth: Boolean) : SimServer(name) {
    @Volatile var identity: ClientIdentity = identity
        private set

    @Volatile private var context: SSLContext = serverTls(identity)

    /** A factory reset or a new TV at the same address: a different certificate from now on. */
    fun changeCertificate(to: ClientIdentity) {
        identity = to
        context = serverTls(to)
    }

    override fun wrap(raw: Socket): Socket {
        val ssl = context.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        ssl.useClientMode = false
        ssl.needClientAuth = needClientAuth
        ssl.startHandshake()
        return ssl
    }
}

/**
 * Sends a real connector to a simulator's port: the clients connect to fixed device ports
 * (6466, 6467, 33336), the simulator listens wherever the OS put it.
 */
class SimTlsConnector(private val real: TlsConnector, private val ports: Map<Int, SimServer>) : TlsConnector {
    override suspend fun connect(host: String, port: Int, identity: ClientIdentity, expectedPin: CertificatePin?): TlsConnection =
        real.connect(host, ports[port]?.port ?: port, identity, expectedPin)
}

class SimTcpConnector(private val real: TcpConnector, private val ports: Map<Int, SimServer>) : TcpConnector {
    override suspend fun connect(host: String, port: Int): Connection = real.connect(host, ports[port]?.port ?: port)
}

/** Polls [condition] for up to [millis]: the simulator runs on its own threads. */
fun eventually(millis: Long = 3_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

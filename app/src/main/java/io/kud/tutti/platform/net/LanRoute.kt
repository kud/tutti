package io.kud.tutti.platform.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.kud.tutti.platform.diagnostic.AppLog
import io.kud.tutti.protocol.diagnostic.EventLog
import io.kud.tutti.protocol.net.LanLink
import io.kud.tutti.protocol.net.NetKind
import io.kud.tutti.protocol.net.NetView
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * The network every LAN socket rides: the Wi-Fi or Ethernet network Android offers for a
 * request on those transports, whatever the default network is ([LanLink] says why). Process-wide,
 * like [AppLog]: transports are built ad hoc all over the controller, and which network carries
 * the living room is a fact about the phone, not about one of them.
 *
 * The request also keeps that network up while it is not the default: an unvalidated Wi-Fi that
 * loses to cellular is otherwise free to linger and go.
 *
 * Nothing here logs a name, an address or a handle: only transports and validation.
 */
object LanRoute {
    /** The bound LAN network, or null: sockets are left unbound and follow the default. */
    @Volatile var network: Network? = null
        private set

    private val link = LanLink()
    @Volatile private var started = false

    /**
     * Starts watching, once per process. [onStep] runs on Android's callback thread with what the
     * caller should do ([LanLink.Step]); its log line has already gone to the event log.
     */
    fun start(context: Context, onStep: (LanLink.Step) -> Unit) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        fun follow(step: LanLink.Step) {
            step.log?.let {
                Log.i(TAG, it)
                AppLog.events.record(EventLog.Kind.CONNECTION, it)
            }
            if (step.drop != null || step.wake) onStep(step)
        }
        val lanCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val view = viewOf(connectivity.getNetworkCapabilities(network)) ?: NetView(setOf(NetKind.WIFI), validated = false)
                synchronized(link) {
                    LanRoute.network = network
                    follow(link.lanAvailable(network.networkHandle, view))
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val view = viewOf(caps) ?: return
                synchronized(link) { follow(link.lanCapabilities(network.networkHandle, view)) }
            }

            override fun onLost(network: Network) {
                synchronized(link) {
                    val step = link.lanLost(network.networkHandle)
                    if (link.bound == null) LanRoute.network = null
                    follow(step)
                }
            }
        }
        // Any Wi-Fi or Ethernet network, with or without internet: a living room on a router
        // with no uplink is still a living room. Transports in one request are alternatives.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val requested = runCatching { connectivity.requestNetwork(request, lanCallback) }
        if (requested.isFailure) {
            // Without CHANGE_NETWORK_STATE a request is refused; listening still binds, it only
            // cannot keep the network up.
            Log.w(TAG, "lan request refused, listening instead", requested.exceptionOrNull())
            runCatching { connectivity.registerNetworkCallback(request, lanCallback) }
                .onFailure { Log.w(TAG, "lan listen refused: sockets stay unbound", it) }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                @Volatile private var current: Network? = null

                override fun onAvailable(network: Network) {
                    current = network
                    val view = viewOf(connectivity.getNetworkCapabilities(network))
                    synchronized(link) { follow(link.defaultChanged(network.networkHandle, view)) }
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val view = viewOf(caps) ?: return
                    synchronized(link) { follow(link.defaultCapabilities(network.networkHandle, view)) }
                }

                override fun onLost(network: Network) {
                    if (current != network) return
                    current = null
                    synchronized(link) { follow(link.defaultChanged(null, null)) }
                }
            })
        }
    }

    /** Binds an unconnected socket to the LAN network; left unbound when there is none or it just went. */
    fun bind(socket: Socket) {
        val target = network ?: return
        runCatching { target.bindSocket(socket) }.onFailure { failed(it) }
    }

    /** As [bind], for the Wake-on-LAN datagram. */
    fun bind(socket: DatagramSocket) {
        val target = network ?: return
        runCatching { target.bindSocket(socket) }.onFailure { failed(it) }
    }

    private fun failed(e: Throwable) {
        val line = "network: binding to the lan failed (${e.javaClass.simpleName}), socket left unbound"
        Log.w(TAG, line)
        AppLog.events.record(EventLog.Kind.CONNECTION, line)
    }

    /** For OkHttp: every socket it opens to the TV or the bar rides the LAN network, read at creation. */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = Socket().also { LanRoute.bind(it) }

        override fun createSocket(host: String, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    private fun viewOf(caps: NetworkCapabilities?): NetView? {
        caps ?: return null
        val kinds = buildSet {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetKind.WIFI)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetKind.ETHERNET)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetKind.CELLULAR)
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetKind.VPN)
            if (isEmpty()) add(NetKind.OTHER)
        }
        return NetView(kinds, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    private const val TAG = "tutti.lan"
}

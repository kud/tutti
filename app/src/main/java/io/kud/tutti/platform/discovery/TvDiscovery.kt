package io.kud.tutti.platform.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.platform.net.LocalNetworkPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class DiscoveredTv(val name: String, val host: String)

/**
 * mDNS discovery of Google TV remote services (`_androidtvremote2._tcp`). A convenience for
 * onboarding only; typing the IP always works. Resolves one service at a time, as NsdManager
 * requires before Android 14.
 */
@Singleton
class TvDiscovery @Inject constructor(@ApplicationContext private val context: Context, private val localNetwork: LocalNetworkPermission) {
    fun discover(): Flow<List<DiscoveredTv>> =
        // Android 17 asks before any LAN traffic, mDNS included: an empty result, not a throw —
        // the onboarding and rediscovery just find nothing while the ask is pending.
        if (localNetwork.missing()) flowOf(emptyList()) else discover(SERVICE_TYPE)

    /**
     * The private addresses answering for [serviceTypes] within [windowMs] per type, one type after
     * the other (NsdManager resolves one service at a time before Android 14). For rediscovery
     * ([io.kud.tutti.protocol.net.Rediscovery]): names are dropped, only hosts come back.
     */
    suspend fun hosts(serviceTypes: List<String>, windowMs: Long): Set<String> {
        // Same gate as [discover]: mDNS is LAN traffic, and Android 17 asks before it.
        if (localNetwork.missing()) return emptySet()
        val hosts = linkedSetOf<String>()
        for (type in serviceTypes) {
            withTimeoutOrNull(windowMs) {
                discover(type).collect { found -> found.mapTo(hosts) { it.host } }
            }
        }
        return hosts
    }

    @Suppress("DEPRECATION")
    private fun discover(serviceType: String): Flow<List<DiscoveredTv>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tutti-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = linkedMapOf<String, DiscoveredTv>()
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = queue.removeFirstOrNull() ?: return
            resolving = true
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(found) { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    synchronized(found) {
                        val host = serviceInfo.host?.hostAddress
                        if (host != null && LanAddress.isPrivate(host)) {
                            found[serviceInfo.serviceName] = DiscoveredTv(serviceInfo.serviceName, host)
                            trySend(found.values.toList())
                        }
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(found) {
                    queue.addLast(serviceInfo)
                    resolveNext()
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(serviceInfo.serviceName) != null) trySend(found.values.toList())
                }
            }
        }
        trySend(emptyList())
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            runCatching { lock.release() }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"

        /**
         * What a BRAVIA Theatre bar may advertise: Chromecast built-in, AirPlay, Spotify Connect.
         * Which of them the Bar 9 actually answers is unverified; port 33336 decides ([io.kud.tutti.protocol.theatre.BarProbe]).
         */
        val BAR_SERVICE_TYPES = listOf("_googlecast._tcp", "_airplay._tcp", "_spotify-connect._tcp")
    }
}

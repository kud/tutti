package io.kud.tutti.platform.net

import android.content.Context
import android.net.ConnectivityManager
import io.kud.tutti.protocol.net.WakeOnLan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Sends the magic packet over UDP, to private LAN addresses only ([WakeOnLan.targets]).
 * Best effort: a TV with "Remote start" off simply does not answer, and nothing here can tell.
 */
class WakeOnLanSender(private val context: Context, private val requireAccess: () -> Unit = {}) {
    /** True when at least one packet left the phone. Gated like a socket: no permission, no packet. */
    suspend fun send(tvHost: String, mac: String): Boolean = withContext(Dispatchers.IO) {
        if (runCatching { requireAccess() }.isFailure) return@withContext false
        val packet = WakeOnLan.magicPacket(mac) ?: return@withContext false
        val (phone, prefix) = phoneIpv4() ?: (null to null)
        val targets = WakeOnLan.targets(tvHost, phone, prefix)
        if (targets.isEmpty()) return@withContext false
        var sent = false
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(BURSTS) { burst ->
                    for (target in targets) {
                        val sentOne = runCatching {
                            socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target), WakeOnLan.PORT))
                        }.isSuccess
                        sent = sent || sentOne
                    }
                    if (burst < BURSTS - 1) delay(BURST_GAP_MS)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No socket at all (no network): report what did go out.
        }
        sent
    }

    /** The phone's own IPv4 address and prefix on the current network, to find the subnet's broadcast. */
    private fun phoneIpv4(): Pair<String, Int>? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val link = connectivity.getLinkProperties(network) ?: return null
        link.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { it.address.hostAddress.orEmpty() to it.prefixLength }
    }.getOrNull()

    private companion object {
        const val BURSTS = 3
        const val BURST_GAP_MS = 100L
    }
}

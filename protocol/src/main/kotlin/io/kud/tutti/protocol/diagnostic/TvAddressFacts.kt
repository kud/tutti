package io.kud.tutti.protocol.diagnostic

import io.kud.tutti.protocol.net.LanAddress

/**
 * What the diagnostic may say about the stored TV address without printing it: its family,
 * whether it is private, whether it shares the bar's /24 (the bar answering proves the phone is
 * on that LAN), whether rediscovery moved it this session, and whether Wake-on-LAN has a MAC to
 * aim at. Enough to tell a stale or wrong address from a TV that is off the network.
 */
object TvAddressFacts {
    fun of(tvHost: String?, barHost: String?, movedThisSession: Boolean, macKnown: Boolean): String {
        val tv = tvHost?.trim() ?: return "none stored"
        val family = when {
            ipv4(tv) != null -> "ipv4"
            tv.contains(':') -> "ipv6"
            else -> "hostname"
        }
        val sameAsBar = sameV4Subnet(tv, barHost)?.let { yesNo(it) } ?: "n/a"
        return "$family, private ${yesNo(LanAddress.isPrivate(tv))}, same /24 as the bar $sameAsBar, " +
            "moved by mDNS this session ${yesNo(movedThisSession)}, mac for wake ${yesNo(macKnown)}"
    }

    /** Whether both are IPv4 in the same /24; null when either is missing or not IPv4. */
    private fun sameV4Subnet(a: String, b: String?): Boolean? {
        val x = ipv4(a) ?: return null
        val y = b?.let { ipv4(it.trim()) } ?: return null
        return x.take(3) == y.take(3)
    }

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            part.takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 } ?: return null
        }
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}

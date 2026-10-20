package io.kud.salon.protocol.net

/**
 * The app talks to the local network only. Cleartext (HTTP) is allowed solely towards
 * addresses this returns true for. Hostnames are refused: resolve first, then check.
 */
object LanAddress {
    fun isPrivate(host: String): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        parseIpv4(h)?.let { return isPrivateIpv4(it) }
        if (h.contains(':')) return isPrivateIpv6(h.lowercase())
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val value = part.toInt()
            if (value > 255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 -> true
        o[0] == 172 && o[1] in 16..31 -> true
        o[0] == 192 && o[1] == 168 -> true
        o[0] == 169 && o[1] == 254 -> true
        else -> false
    }

    private fun isPrivateIpv6(h: String): Boolean {
        val first = h.substringBefore(':').ifEmpty { return false }
        val hextet = first.toIntOrNull(16) ?: return false
        return (hextet and 0xffc0) == 0xfe80 || (hextet and 0xfe00) == 0xfc00
    }
}

package io.kud.tutti.protocol.net

/**
 * Wake-on-LAN, without any socket: MAC parsing, the magic packet and where to send it.
 * A BRAVIA in networked standby ("Remote start" on) powers on from this packet.
 *
 * A MAC is not a secret, but it is a stable device identifier: never log one.
 */
object WakeOnLan {
    /** The discard port, the usual one for magic packets. */
    const val PORT = 9

    private const val LIMITED_BROADCAST = "255.255.255.255"
    private const val MAC_BYTES = 6
    private const val REPEATS = 16

    /**
     * `aa:bb:cc:dd:ee:ff`, `AA-BB-CC-DD-EE-FF` or `aabbccddeeff` to the lower-case colon form.
     * Null for anything else, and for addresses no NIC carries: all zeros, broadcast, multicast.
     */
    fun normaliseMac(raw: String?): String? {
        val text = raw?.trim()?.lowercase() ?: return null
        val parts = if (text.length == MAC_BYTES * 2 && text.all(::isHex)) {
            text.chunked(2)
        } else {
            text.split(':', '-').takeIf { p -> p.size == MAC_BYTES && p.all { it.length == 2 && it.all(::isHex) } }
        } ?: return null
        val bytes = parts.map { it.toInt(16) }
        if (bytes.all { it == 0 } || bytes.all { it == 0xff } || bytes[0] and 1 == 1) return null
        return parts.joinToString(":")
    }

    /** Six 0xFF bytes, then the MAC sixteen times: 102 bytes. Null when [mac] is not one. */
    fun magicPacket(mac: String): ByteArray? {
        val canonical = normaliseMac(mac) ?: return null
        val address = canonical.split(':').map { it.toInt(16).toByte() }
        return ByteArray(MAC_BYTES) { 0xff.toByte() } + ByteArray(MAC_BYTES * REPEATS) { address[it % MAC_BYTES] }
    }

    /**
     * Where to send the packet, private addresses only: the TV itself (its ARP entry often
     * outlives standby), plus the subnet's directed and limited broadcasts when the phone
     * sits on the TV's own IPv4 subnet. Nothing at all for a TV that is not on the LAN.
     */
    fun targets(tvHost: String, phoneAddress: String?, prefixLength: Int?): List<String> {
        if (!LanAddress.isPrivate(tvHost)) return emptyList()
        val host = tvHost.trim().removePrefix("[").removeSuffix("]")
        val tv = ipv4(host) ?: return listOf(host)
        val phone = phoneAddress?.let(::ipv4)
        if (phone == null || prefixLength == null || prefixLength !in 8..30) return listOf(host)
        val mask = -1 shl (32 - prefixLength)
        if (phone and mask != tv and mask) return listOf(host)
        val directed = formatIpv4((tv and mask) or mask.inv())
        return listOf(directed, LIMITED_BROADCAST, host)
            .filter { it == LIMITED_BROADCAST || LanAddress.isPrivate(it) }
            .distinct()
    }

    /**
     * The MAC to wake: the interface that carries [host] (Wi-Fi and Ethernet differ, and only
     * the live one listens), else the first valid interface, else [systemMac].
     */
    fun macFor(host: String, interfaces: List<Pair<String?, String?>>, systemMac: String?): String? {
        val valid = interfaces.mapNotNull { (ip, mac) -> normaliseMac(mac)?.let { ip?.trim() to it } }
        return valid.firstOrNull { it.first == host.trim() }?.second
            ?: valid.firstOrNull()?.second
            ?: normaliseMac(systemMac)
    }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

    private fun ipv4(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val octet = part.toInt()
            if (octet > 255) return null
            value = (value shl 8) or octet
        }
        return value
    }

    private fun formatIpv4(value: Int): String =
        listOf(24, 16, 8, 0).joinToString(".") { ((value ushr it) and 0xff).toString() }
}

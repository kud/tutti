package io.kud.tutti.protocol.net

/**
 * Finding a device again once its address has changed (a DHCP lease handed out elsewhere, a new
 * router). After [AFTER_FAILURES] connection failures in a row at the stored address, the app
 * browses mDNS once, at most every [COOLDOWN_MS], and probes what answers elsewhere on the LAN.
 *
 * The TV moves only on proof: a new address is kept when the device there presents the pinned
 * Remote v2 certificate or the pinned REST public key ([tvConfirmed]), the same identities every
 * session already checks. The soundbar has no identity to prove (port 33336 has no
 * authentication), so a bar answering elsewhere is only ever an offer ([barOffer]) the user takes
 * or leaves; it never moves on its own.
 */
object Rediscovery {
    /** Three failures: the 1, 2 and 4 s backoffs, so a reboot or a blip never sets it off. */
    const val AFTER_FAILURES = 3

    /** One browse per ten minutes at most, however long the device stays away. */
    const val COOLDOWN_MS = 10 * 60_000L

    /**
     * Whether to browse now: [failures] in a row at the stored address, and no browse in the last
     * [COOLDOWN_MS] ([lastAt] and [now] on the same monotonic clock; a clock that went back counts
     * as long ago).
     */
    fun due(failures: Int, lastAt: Long?, now: Long): Boolean =
        failures >= AFTER_FAILURES && (lastAt == null || now - lastAt !in 0 until COOLDOWN_MS)

    /**
     * The addresses worth probing: private ones only, never the stored address nor one already
     * taken by another configured device ([others]), each once, in the order found.
     */
    fun candidates(found: Collection<String>, current: String, others: Collection<String> = emptyList()): List<String> =
        found.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != current && it !in others && LanAddress.isPrivate(it) }
            .distinct()
            .toList()

    /**
     * The TV at a new address is the paired one: it presented the pinned Remote v2 certificate
     * ([remoteSeen], exact match), or the pinned REST public key ([restSeen]). A legacy
     * full-certificate REST pin is not compared here: the Remote v2 pin speaks for those. With no
     * pin at all nothing is proven, and nothing moves.
     */
    fun tvConfirmed(
        remoteSeen: CertificatePin?,
        restSeen: PublicKeyPin?,
        remotePin: CertificatePin?,
        restPin: ServerIdentityPin?,
    ): Boolean =
        (remotePin != null && remoteSeen == remotePin) ||
            (restPin is PublicKeyPin && restSeen == restPin)

    /**
     * A soundbar answering on port 33336 at [host] with its model ([answeredModel]). Offered only
     * when it named one, and, when the model of the configured bar is known ([knownModel]), only
     * when it is the same model. Null: nothing to offer.
     */
    fun barOffer(host: String, answeredModel: String?, knownModel: String?): BarAddressOffer? {
        val model = answeredModel?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (knownModel != null && !model.equals(knownModel.trim(), ignoreCase = true)) return null
        return BarAddressOffer(host, model)
    }
}

/** The soundbar seems to answer at [host] now: shown as an offer, applied only by the user. */
data class BarAddressOffer(val host: String, val model: String)

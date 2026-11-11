package io.kud.tutti.protocol.net

/**
 * When a pinned TV presents another identity: what is recorded, how a second identity is admitted,
 * and when a new identity is admitted on its own ([AutoTrust]).
 *
 * The TV has two TLS planes, each pinned on its own: Remote v2 (port 6466, the whole certificate,
 * recorded at pairing) and BRAVIA REST (HTTPS, the public key, refreshed only at a Remote v2
 * pairing). A mismatch on either used to end at "pair again". Now:
 * - every mismatch is recorded ([PinMismatchEvent]) for the developer-mode diagnostics: when, which
 *   plane, a coarse host label, and short hash labels of the pinned and the presented identity.
 *   Labels are 8 hex characters of a SHA-256 of a public certificate or key: enough to tell two
 *   identities apart, nothing secret;
 * - each plane keeps up to [MAX_PINS] trusted identities, newest first, so a TV that alternates
 *   between two keys is followed without asking once both have been admitted;
 * - a new identity is admitted only by pairing, or automatically while the *other* plane is online
 *   to the same host with its own pin matching ([TrustGate], [AutoTrust]), and that admission is
 *   recorded beside the mismatches. Never without the gate, never trust-all.
 *
 * Until 0.3.1-dev.4 the gate only offered a one-tap "trust the new identity". It went (Erwann,
 * 2026-11-11): under the gate the proof comes from the other plane, and the tap added nothing the
 * user could check. Without the gate there is nothing to tap; pairing is the way back.
 */
enum class PinPlane(val wire: String) {
    REMOTE("remote"),
    REST("rest");

    companion object {
        fun of(wire: String): PinPlane? = entries.firstOrNull { it.wire == wire }
    }
}

/** At most this many trusted identities per plane: the one in use and the one before it. */
const val MAX_PINS = 2

/** A short, non-secret label of a pin's hex digest, for diagnostics only. */
fun pinLabel(sha256Hex: String): String = sha256Hex.take(8)

/**
 * The host, coarsened for a report someone may paste: the last IPv4 octet ("….42"), else "ipv6".
 * [stored] says whether it was the TV's stored address at the time, which is what matters.
 */
fun hostLabel(host: String, stored: Boolean): String {
    val tail = if (host.count { it == '.' } == 3) "…" + host.substring(host.lastIndexOf('.')) else "ipv6"
    return if (stored) "$tail (stored)" else "$tail (other)"
}

/**
 * One mismatch. [pinned] are the labels of the identities tutti trusted, newest first; [seenCert]
 * and [seenKey] label the certificate and the public key the TV presented. [sameKey] tells a
 * reissued certificate (same key) from a new key.
 *
 * With [admittedVia] set, the entry is not a mismatch but the automatic admission of [seenCert] /
 * [seenKey] on [plane], vouched for by [admittedVia], the other plane ([AutoTrust]); [pinned] then
 * labels what was trusted before it. A mismatch encodes exactly as it did in 0.3.1-dev.4 (version
 * 1), so a stored log reads back unchanged; only an admission writes version 2.
 */
data class PinMismatchEvent(
    val atMs: Long,
    val plane: PinPlane,
    val host: String,
    val pinned: List<String>,
    val seenCert: String,
    val seenKey: String,
    val sameKey: Boolean,
    val admittedVia: PinPlane? = null,
) {
    /** One line, safe to paste: no address beyond its last octet, no secret. */
    fun line(): String {
        val head = if (admittedVia == null) plane.wire else "${plane.wire} admitted automatically via ${admittedVia.wire}"
        return "$head host=$host pinned=${pinned.joinToString("/").ifEmpty { "-" }} seen=cert:$seenCert key:$seenKey" +
            (if (sameKey) " same-key" else " new-key") + " at=$atMs"
    }

    fun encode(): String {
        val fields = listOf(atMs, plane.wire, host, pinned.joinToString(","), seenCert, seenKey, if (sameKey) "1" else "0")
        return if (admittedVia == null) {
            (listOf(VERSION_MISMATCH) + fields).joinToString("\t")
        } else {
            (listOf(VERSION_ADMISSION) + fields + admittedVia.wire).joinToString("\t")
        }
    }

    companion object {
        private const val VERSION_MISMATCH = "1"
        private const val VERSION_ADMISSION = "2"

        /** Null for anything [encode] did not write. */
        fun decode(line: String): PinMismatchEvent? {
            val p = line.split('\t')
            val via = when {
                p.size == 8 && p[0] == VERSION_MISMATCH -> null
                p.size == 9 && p[0] == VERSION_ADMISSION -> PinPlane.of(p[8]) ?: return null
                else -> return null
            }
            val plane = PinPlane.of(p[2]) ?: return null
            val at = p[1].toLongOrNull() ?: return null
            return PinMismatchEvent(at, plane, p[3], p[4].split(',').filter { it.isNotEmpty() }, p[5], p[6], p[7] == "1", via)
        }
    }
}

/** The recorded mismatches, newest first, at most [MAX]. Stored as one string, one event per line. */
object PinMismatchLog {
    const val MAX = 12

    fun decode(stored: String?): List<PinMismatchEvent> =
        stored.orEmpty().lineSequence().mapNotNull(PinMismatchEvent::decode).toList()

    fun encode(events: List<PinMismatchEvent>): String = events.take(MAX).joinToString("\n") { it.encode() }

    fun add(stored: String?, event: PinMismatchEvent): String = encode(listOf(event) + decode(stored))

    /** The copyable block for a report: a header, then one line per event. */
    fun report(events: List<PinMismatchEvent>): String =
        if (events.isEmpty()) {
            "pin mismatches: none recorded"
        } else {
            (listOf("pin mismatches (newest first):") + events.map { "- " + it.line() }).joinToString("\n")
        }
}

/** A plane's trusted identities, newest first. */
object TrustedPins {
    /** [admitted] becomes the newest; the previous newest stays as the second; older ones go. */
    fun admit(current: List<String>, admitted: String): List<String> =
        (listOf(admitted) + current.filter { it != admitted }).take(MAX_PINS)

    /**
     * The TV presented [seen], which is already trusted but not the newest: use it first from now
     * on. Null when [seen] is not trusted at all, which only pairing or [TrustGate] may change.
     */
    fun follow(current: List<String>, seen: String): List<String>? =
        if (seen in current) admit(current, seen) else null
}

/** The Remote v2 and REST state the trust gate reads, as the controller knows it. */
data class PlaneState(
    /** The plane's session is up. */
    val online: Boolean,
    /** The host that session talks to. */
    val host: String?,
    /** The session is on a pinned TLS identity it checked (Remote v2 always; REST over pinned HTTPS only). */
    val pinned: Boolean,
)

/**
 * When a new identity may be admitted: the identity changed on [changed]'s plane at [host], and
 * the other plane is, right now, online to that same host over its own pinned and matching TLS
 * identity. That proves the phone is talking to the TV it paired with, so the new
 * identity on the first plane comes from that TV, not from someone in between on that port.
 */
object TrustGate {
    fun allows(changed: PinPlane, host: String, remote: PlaneState, rest: PlaneState): Boolean {
        val other = when (changed) {
            PinPlane.REMOTE -> rest
            PinPlane.REST -> remote
        }
        return other.online && other.pinned && other.host == host
    }
}

/** The plane that vouches for [plane] under [TrustGate]. */
fun PinPlane.other(): PinPlane = when (this) {
    PinPlane.REMOTE -> PinPlane.REST
    PinPlane.REST -> PinPlane.REMOTE
}

/**
 * A new identity seen on [plane] at [host] and not trusted yet. [seenAtMs] is on a monotonic clock
 * and moves to the latest sighting each time the TV presents it again.
 */
data class PendingIdentity(val plane: PinPlane, val host: String, val sha256Hex: String, val seenAtMs: Long)

/**
 * Whether a [PendingIdentity] is admitted on its own. It is, the moment [TrustGate] holds for it,
 * as long as it was last seen no more than [WINDOW_MS] ago and the other plane has no pending
 * change of its own. Nothing else admits it: past the window it is dropped, and pairing is the way
 * back (or the TV presenting it again, which is a fresh sighting).
 *
 * [WINDOW_MS] is two minutes from the latest sighting. The usual case needs none: the other plane
 * is already up when the mismatch is seen, and the identity is admitted at once. The window is for
 * the two planes coming up out of step after a wake or a launch: IP control retries at most every
 * 30 s while it fails, so two minutes is four of its slowest retries, and Remote v2
 * reconnects within seconds of a working TV. It stays short because what the gate proves is that
 * this host is the paired TV *now*; the window is how far "now" may stretch back to the moment the
 * new identity came from that host. An offer from an earlier evening must not ride on tonight's
 * connection.
 */
object AutoTrust {
    const val WINDOW_MS = 2 * 60_000L

    enum class Decision {
        /** The gate holds within the window: admit it now. */
        ADMIT,

        /** Within the window, the gate not (yet) holding: keep it and ask again when a link changes. */
        WAIT,

        /** Past the window, or the other plane changed too: never admitted on its own. Drop it. */
        DROP,
    }

    /**
     * [otherPending] is the other plane's own pending change, if any: when both planes changed,
     * neither vouches for the other, whatever the links say.
     */
    fun decide(
        pending: PendingIdentity,
        nowMs: Long,
        remote: PlaneState,
        rest: PlaneState,
        otherPending: PendingIdentity? = null,
    ): Decision {
        if (nowMs - pending.seenAtMs > WINDOW_MS) return Decision.DROP
        if (otherPending != null && otherPending.plane != pending.plane && nowMs - otherPending.seenAtMs <= WINDOW_MS) return Decision.DROP
        return if (TrustGate.allows(pending.plane, pending.host, remote, rest)) Decision.ADMIT else Decision.WAIT
    }
}

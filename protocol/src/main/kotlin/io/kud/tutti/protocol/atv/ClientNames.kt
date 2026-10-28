package io.kud.tutti.protocol.atv

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import java.security.cert.X509Certificate
import kotlin.random.Random

/**
 * The name a phone pairs under: the `client_name` of the pairing request and the CN of its
 * client certificate, always the same string.
 *
 * Google TV keeps one pairing per client name / certificate subject: a second client presenting
 * the same name replaces the first (observed by Unfolded Circle and open-tv-remote, the latter on
 * a Sony A80J). Every install used to pair as "tutti", so pairing a second phone unpaired the
 * first. A new identity now gets "tutti (<model> <4 hex>)": the model so the TV's pairing prompt
 * says which phone, the suffix so two phones of the same model still differ.
 */
object ClientNames {
    /** What every identity made before per-install names carries. Such pairings keep working. */
    const val LEGACY = "tutti"

    private const val MAX_MODEL = 24
    private val forbidden = Regex("[,=+<>#;\"\\\\()\\p{Cntrl}]")

    fun forInstall(model: String?, random: Random = Random.Default): String {
        val cleaned = model.orEmpty().replace(forbidden, " ").replace(Regex("\\s+"), " ").trim().take(MAX_MODEL).trim()
        val suffix = "%04x".format(random.nextInt(0x10000))
        return "$LEGACY (${cleaned.ifEmpty { "Android" }} $suffix)"
    }

    /** The CN of [certificate], or [LEGACY] when it has none. */
    fun of(certificate: X509Certificate): String {
        val subject = X500Name.getInstance(certificate.subjectX500Principal.encoded)
        val cn = subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value ?: return LEGACY
        return IETFUtils.valueToString(cn).ifEmpty { LEGACY }
    }

    /** True for a name minted per install; false for the shared [LEGACY] one. */
    fun isPerInstall(name: String): Boolean = name != LEGACY && name.startsWith("$LEGACY (")
}

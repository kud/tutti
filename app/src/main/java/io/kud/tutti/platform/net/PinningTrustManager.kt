package io.kud.tutti.platform.net

import io.kud.tutti.protocol.net.CertificatePin
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use for the TV's self-signed certificates.
 *
 * - [expected] set: the handshake fails unless the leaf certificate matches it.
 * - [expected] null: first contact. The leaf is accepted and reported through [onFirstUse];
 *   the caller persists it only once the user has confirmed (pairing code, or a successful
 *   authenticated REST call). Every later connection is pinned.
 *
 * Chain building and validity dates are deliberately not checked: these certificates are
 * self-signed and the pin is the identity. This is never "trust all": a set pin is enforced.
 */
internal class PinningTrustManager(
    @Volatile var expected: CertificatePin?,
    private val onFirstUse: (CertificatePin) -> Unit = {},
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client mode only")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        val pin = expected
        if (pin == null) {
            onFirstUse(CertificatePin.of(leaf))
            return
        }
        if (!pin.matches(leaf)) throw PinMismatchException()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The device presented a different certificate than the one recorded: re-pair, never retry blindly. */
class PinMismatchException : CertificateException("certificate does not match the recorded pin")

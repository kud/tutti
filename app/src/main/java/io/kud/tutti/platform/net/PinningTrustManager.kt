package io.kud.tutti.platform.net

import io.kud.tutti.protocol.net.ServerIdentityPin
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust-on-first-use for the TV's self-signed TLS identity.
 *
 * [expected] may pin the exact certificate (Remote v2) or its public key (BRAVIA REST). With no
 * pin, the leaf is accepted only so the caller can run its own trust ceremony. [onPeerCertificate]
 * observes every tested leaf but never changes trust by itself.
 *
 * Chain building and validity dates are deliberately not checked: these device certificates are
 * self-signed. This is never "trust all": once [expected] is set it is enforced.
 */
internal class PinningTrustManager(
    @Volatile var expected: ServerIdentityPin?,
    private val onPeerCertificate: (X509Certificate) -> Unit = {},
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client mode only")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
        onPeerCertificate(leaf)
        val pin = expected ?: return
        if (!pin.matches(leaf)) throw PinMismatchException()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** The device presented a different pinned identity: pair again, never retry blindly. */
class PinMismatchException : CertificateException("server identity does not match the recorded pin")

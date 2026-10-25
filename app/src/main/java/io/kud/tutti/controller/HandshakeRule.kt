package io.kud.tutti.controller

import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * When the remote link reads "pair again": the TV refused our client certificate
 * ([LIMIT] times in a row), not merely a TLS handshake that failed. Any other outcome
 * (a timeout, a reset, a closed port, a session) clears the count, and a refused
 * certificate is retried every [RETRY_MS] rather than given up on.
 */
object HandshakeRule {
    const val LIMIT = 2
    const val RETRY_MS = 120_000L

    /** The count after one failed connect. */
    fun next(failures: Int, error: Throwable): Int = if (isCertificateAlert(error)) failures + 1 else 0

    fun needsPairing(failures: Int): Boolean = failures >= LIMIT

    /** A TLS alert about a certificate (bad_certificate, certificate_unknown, certificate_required…). */
    fun isCertificateAlert(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { cause ->
            cause is CertificateException ||
                (cause is SSLException && cause.message.orEmpty().contains("certificate", ignoreCase = true))
        }
}

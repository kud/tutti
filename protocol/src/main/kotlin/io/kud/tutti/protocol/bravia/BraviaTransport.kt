package io.kud.tutti.protocol.bravia

data class HttpReply(val status: Int, val body: String)

/**
 * HTTP POST to the TV. `:app` implements it with OkHttp and owns the scheme and trust:
 * HTTPS with a per-host trust-on-first-use pin (EU units may be HTTPS-only), or HTTP only
 * towards a private LAN address (`LanAddress.isPrivate`). Never trust-all.
 *
 * Implementations must not log [headers]: they carry the PSK.
 */
interface BraviaTransport {
    /** @param path e.g. `/sony/system`. Throws `IOException` on network failure. */
    suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply
}

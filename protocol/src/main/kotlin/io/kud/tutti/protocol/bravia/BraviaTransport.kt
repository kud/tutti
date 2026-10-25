package io.kud.tutti.protocol.bravia

/** [setCookies]: every `Set-Cookie` value, which only `actRegister` sends (the IP-control cookie, a secret). */
data class HttpReply(val status: Int, val body: String, val setCookies: List<String> = emptyList()) {
    override fun toString(): String = "HttpReply(status=$status, body=${body.length} chars, setCookies=${setCookies.size})"
}

/**
 * HTTP POST to the TV. `:app` implements it with OkHttp and owns the scheme and trust:
 * HTTPS with a per-host trust-on-first-use pin (EU units may be HTTPS-only), or HTTP only
 * towards a private LAN address (`LanAddress.isPrivate`). Never trust-all.
 *
 * Implementations must not log [headers]: they carry the PSK, the IP-control cookie or the code
 * ([BraviaClient.CREDENTIAL_HEADERS]). A request carrying any of them counts as authenticated.
 */
interface BraviaTransport {
    /** @param path e.g. `/sony/system`. Throws `IOException` on network failure. */
    suspend fun post(path: String, body: String, headers: Map<String, String>): HttpReply

    /** `https` or `http` once a request has gone through, for the diagnostic; null if unknown. */
    val scheme: String? get() = null
}

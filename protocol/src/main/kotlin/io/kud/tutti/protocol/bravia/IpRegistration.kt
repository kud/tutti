package io.kud.tutti.protocol.bravia

import io.kud.tutti.protocol.net.Redact
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * The cookie Sony's "Normal" IP-control authentication hands out once the code shown on the TV
 * has been typed in. A bearer secret: whoever holds [value] controls the TV until it expires, so
 * [toString] never prints it. [clientId] is the name this phone registered under; it is kept so
 * renewal and a new code reuse the same entry in the TV's list of remote devices.
 */
class AuthCookie(
    val clientId: String,
    val value: String,
    /** Epoch ms; null when the TV gave no expiry tutti could read. */
    val expiresAtMs: Long?,
    val obtainedAtMs: Long,
) {
    /** One line for the secure store: version, client id, times, then the value (no tabs in any). */
    fun encode(): String = listOf(VERSION, clientId, obtainedAtMs.toString(), expiresAtMs?.toString().orEmpty(), value).joinToString("\t")

    override fun equals(other: Any?): Boolean =
        other is AuthCookie && other.clientId == clientId && other.value == value && other.expiresAtMs == expiresAtMs && other.obtainedAtMs == obtainedAtMs

    override fun hashCode(): Int = listOf(clientId, value, expiresAtMs, obtainedAtMs).hashCode()

    override fun toString(): String = "AuthCookie(clientId=$clientId, value=${Redact.secret(value)}, expiresAtMs=$expiresAtMs, obtainedAtMs=$obtainedAtMs)"

    companion object {
        private const val VERSION = "1"

        /** Null for anything [encode] did not write. */
        fun decode(stored: String?): AuthCookie? {
            val parts = stored?.split('\t', limit = 5) ?: return null
            if (parts.size != 5 || parts[0] != VERSION) return null
            val obtained = parts[2].toLongOrNull() ?: return null
            val expires = parts[3].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
            if (parts[1].isEmpty() || !IpRegistration.validCookieValue(parts[4])) return null
            return AuthCookie(parts[1], parts[4], expires, obtained)
        }
    }
}

/** What an `actRegister` call came to. Transport and RPC failures stay in [BraviaResult.Failure]. */
sealed interface Registration {
    /** The TV took it and handed out (or kept) a cookie. */
    data class Registered(val cookie: AuthCookie) : Registration

    /** Asked without credentials: the TV answered 401, which is when it shows its 4-digit code. */
    data object CodeShown : Registration

    /** The code (or, on renewal, the cookie) was refused. */
    data object Refused : Registration
}

/**
 * Sony's "Normal" authentication for IP control: the TV shows a 4-digit code, the phone sends it
 * back once, and every later call carries a cookie instead of `X-Auth-PSK`.
 *
 * Sources (2026-10-25):
 * - Sony's REST reference, `accessControl.actRegister` v1.0 (pro-bravia.sony.net, service
 *   accessControl), as summarised in docs/architecture.md: the first call without credentials
 *   answers 401 while the TV shows the code, the second carries `Authorization: Basic
 *   base64(":" + code)` and answers 200 with `Set-Cookie: auth=…`. The page itself could not be
 *   fetched when this was written, so the shape below is the one the two libraries use.
 * - pybravia (github.com/Drafteed/pybravia, `client.py` `register`), which Home Assistant's
 *   braviatv integration uses: the same params with `level: "private"` and
 *   `[{"value": "yes", "function": "WOL"}]`, Basic with an empty user. It starts pairing by
 *   registering with the code "0000" rather than with no credentials; tutti follows Sony and
 *   sends none. It also strips the cookie's `Expires` before parsing because some TVs send a
 *   non-RFC date (its `util.normalize_cookies`), so [parseSetCookie] reads it leniently.
 * - python-bravia-tv (github.com/dcnielsen90/python-bravia-tv, `braviarc.py` `connect`): the same
 *   request, `b64encode(f':{pin}')`.
 *
 * Unverified, on the XR80 or anywhere tutti has looked:
 * - The cookie's lifetime. It is commonly reported as 14 days (`Max-Age=1209600`); tutti reads
 *   whatever the TV sends and assumes [ASSUMED_LIFETIME_MS] when it can read nothing.
 * - Renewal by calling `actRegister` again with only the cookie. Community scripts describe it;
 *   pybravia and Home Assistant instead keep the code and register with it again. tutti keeps no
 *   code, so a refused renewal asks for a new one on the TV ([Registration.Refused]).
 * - Whether the TV shows a code while its screen is off (Sony's error 40005 "Display Is Turned
 *   off" suggests not; the error comes back as [BraviaError.Rpc]).
 */
object IpRegistration {
    const val PATH = "/sony/accessControl"
    const val METHOD = "actRegister"
    const val VERSION = "1.0"
    const val COOKIE_NAME = "auth"
    const val NICKNAME = "tutti"
    const val CLIENT_PREFIX = "tutti:"

    /** Renew this long before the expiry the TV gave. */
    const val RENEW_BEFORE_MS = 3L * DAY_MS

    /** When the TV gave no readable expiry: renew this long after the cookie was obtained. */
    const val ASSUMED_LIFETIME_MS = 7L * DAY_MS

    /** [uuid] comes from the app (`UUID.randomUUID()`), once per TV; reused while a cookie is kept. */
    fun clientId(uuid: String): String = CLIENT_PREFIX + uuid

    fun params(clientId: String, nickname: String = NICKNAME): List<JsonElement> = listOf(
        buildJsonObject {
            put("clientid", clientId)
            put("nickname", nickname)
            put("level", "private")
        },
        buildJsonArray {
            add(buildJsonObject {
                put("value", "yes")
                put("function", "WOL")
            })
        },
    )

    /** The code the TV shows: exactly four digits. */
    fun validCode(code: String): Boolean = code.length == 4 && code.all { it in '0'..'9' }

    /** `Basic base64(":" + code)`: empty user, the code as password. */
    fun basic(code: String): String = "Basic " + Base64.getEncoder().encodeToString(":$code".encodeToByteArray())

    fun cookieHeader(value: String): String = "$COOKIE_NAME=$value"

    /**
     * The `auth` cookie among [setCookies] (each a whole `Set-Cookie` value), else null. `Max-Age`
     * wins over `Expires` (RFC 6265 § 5.3); a value with anything but cookie-octets is refused, so
     * it can never smuggle a header; a cookie already expired is no cookie.
     */
    fun parseSetCookie(setCookies: List<String>, clientId: String, nowMs: Long): AuthCookie? {
        for (line in setCookies) {
            val parts = line.split(';').map { it.trim() }
            val pair = parts.firstOrNull() ?: continue
            if (pair.substringBefore('=').trim() != COOKIE_NAME || '=' !in pair) continue
            val value = pair.substringAfter('=').trim().removeSurrounding("\"")
            if (!validCookieValue(value)) continue
            var maxAge: Long? = null
            var expires: Long? = null
            for (attribute in parts.drop(1)) {
                val name = attribute.substringBefore('=').trim().lowercase(Locale.ROOT)
                val raw = attribute.substringAfter('=', "").trim()
                when (name) {
                    "max-age" -> maxAge = raw.toLongOrNull()
                    "expires" -> expires = parseDate(raw)
                }
            }
            val at = maxAge?.let { nowMs + it.coerceAtLeast(0) * 1000 } ?: expires
            if (at != null && at <= nowMs) return null
            return AuthCookie(clientId, value, at, nowMs)
        }
        return null
    }

    /** Time to renew: near the TV's expiry, or [ASSUMED_LIFETIME_MS] after it was obtained. */
    fun needsRenewal(cookie: AuthCookie, nowMs: Long): Boolean {
        val expires = cookie.expiresAtMs
        return if (expires != null) nowMs >= expires - RENEW_BEFORE_MS else nowMs >= cookie.obtainedAtMs + ASSUMED_LIFETIME_MS
    }

    /** Past the expiry the TV gave. Unknown expiry is never "expired": the TV's 401 says so. */
    fun expired(cookie: AuthCookie, nowMs: Long): Boolean = cookie.expiresAtMs?.let { nowMs >= it } ?: false

    /** RFC 6265 cookie-octets: printable ASCII except space, `"`, `,`, `;` and `\`. */
    fun validCookieValue(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_COOKIE && value.all { it.code in 0x21..0x7e && it != '"' && it != ',' && it != ';' && it != '\\' }

    private fun parseDate(raw: String): Long? {
        for (format in DATE_FORMATS) {
            runCatching { return ZonedDateTime.parse(raw, format).toInstant().toEpochMilli() }
        }
        return null
    }

    private const val MAX_COOKIE = 4096

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, dd-MMM-yyyy HH:mm:ss zzz", Locale.US),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        DateTimeFormatter.ofPattern("EEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
    )
}

private const val DAY_MS = 24L * 60 * 60 * 1000

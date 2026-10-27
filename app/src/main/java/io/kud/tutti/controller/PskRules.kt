package io.kud.tutti.controller

/**
 * What the TV's pre-shared key may be. It travels as an HTTP header, so it must be printable
 * ASCII; spaces are refused too, since a header loses them at either end. Sony asks for 8 to
 * 32 characters (unverified on the XR80's own field, so the bounds are the documented ones).
 */
object PskRules {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 32

    fun valid(value: String): Boolean = value.length in MIN_LENGTH..MAX_LENGTH && value.all { it in '!'..'~' }

    /** How a call that needs the credential went, as far as the PSK is concerned. */
    enum class Auth {
        /** Not asked this poll (TV off, or nothing due). */
        NOT_ASKED,
        ACCEPTED,
        REFUSED,

        /** Failed for another reason (a timeout, an error answer): says nothing about the key. */
        OTHER,
    }

    /**
     * Whether the TV refuses the stored PSK. Discovery and getPowerStatus answer without the key,
     * so a TV can look online while refusing every authenticated call (finding 2, 2026-10-26).
     * Once refused it stays refused until an authenticated call succeeds. It only applies with a
     * PSK and no code cookie: the client sends the cookie when both exist.
     */
    fun refused(wasRefused: Boolean, hasPsk: Boolean, hasCookie: Boolean, auth: Auth): Boolean {
        if (!hasPsk || hasCookie) return false
        return when (auth) {
            Auth.ACCEPTED -> false
            Auth.REFUSED -> true
            Auth.NOT_ASKED, Auth.OTHER -> wasRefused
        }
    }
}

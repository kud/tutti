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
}

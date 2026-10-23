package io.kud.tutti.protocol.atv

/**
 * What the code field accepts: the TV shows [LENGTH] hex characters ([PairingHash.isWellFormed]).
 * Typing or pasting keeps the hex characters only, uppercased, so a pasted "ab 12-cd" becomes
 * "AB12CD" and a letter outside A–F never reaches the hash.
 */
object PairingCode {
    const val LENGTH = 6

    fun normalise(input: String): String = input.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(LENGTH)

    fun isComplete(code: String): Boolean = code.length == LENGTH && PairingHash.isWellFormed(code)
}

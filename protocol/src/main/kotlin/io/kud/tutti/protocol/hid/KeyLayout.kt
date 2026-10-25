package io.kud.tutti.protocol.hid

/** One key press: a usage with the modifiers held for it. Sent as press, then all-up. */
data class KeyStroke(val usage: Int, val modifiers: Int = 0)

/** What typing a text gives: the strokes for what the layout can type, and what it can't. */
data class Typed(val strokes: List<KeyStroke>, val unsupported: Set<Char>)

/**
 * A HID keyboard sends key *positions*, not characters: the TV turns a position into a character
 * through the physical keyboard layout it has chosen for this keyboard. So tutti must send the
 * positions of the layout the TV uses, and [KeyLayout] is that assumption, picked by the user.
 *
 * Usage names are the US positions (0x14 is "Q" on a US board, "A" on an AZERTY one). The French
 * layout is the Linux/Android `fr` map: é è ç à ù on their own keys, the circumflex and diaeresis
 * as dead keys (^ then e gives ê), AltGr for € @ # { } [ ] | \ ~ `. What no French layout can
 * type without a compose key (É È À Ç Ù as capitals, œ, æ, «») comes back in [Typed.unsupported]
 * rather than as something else.
 */
enum class KeyLayout {
    US,
    FR_AZERTY,
    ;

    fun strokes(char: Char): List<KeyStroke>? = when (this) {
        US -> usMap[char]?.let(::listOf)
        FR_AZERTY -> frMap[char]?.let(::listOf) ?: frDead(char)
    }

    fun type(text: String): Typed {
        val out = mutableListOf<KeyStroke>()
        val missing = linkedSetOf<Char>()
        for (char in text) {
            val strokes = strokes(char)
            if (strokes == null) missing += char else out += strokes
        }
        return Typed(out, missing)
    }

    private fun frDead(char: Char): List<KeyStroke>? {
        val circumflex = KeyStroke(0x2F)
        val diaeresis = KeyStroke(0x2F, Modifier.LEFT_SHIFT)
        val (dead, base) = when (char) {
            'â' -> circumflex to 'a'
            'ê' -> circumflex to 'e'
            'î' -> circumflex to 'i'
            'ô' -> circumflex to 'o'
            'û' -> circumflex to 'u'
            'Â' -> circumflex to 'A'
            'Ê' -> circumflex to 'E'
            'Î' -> circumflex to 'I'
            'Ô' -> circumflex to 'O'
            'Û' -> circumflex to 'U'
            'ä' -> diaeresis to 'a'
            'ë' -> diaeresis to 'e'
            'ï' -> diaeresis to 'i'
            'ö' -> diaeresis to 'o'
            'ü' -> diaeresis to 'u'
            'ÿ' -> diaeresis to 'y'
            'Ä' -> diaeresis to 'A'
            'Ë' -> diaeresis to 'E'
            'Ï' -> diaeresis to 'I'
            'Ö' -> diaeresis to 'O'
            'Ü' -> diaeresis to 'U'
            // A dead key then space gives the mark itself.
            '^' -> circumflex to ' '
            '¨' -> diaeresis to ' '
            else -> return null
        }
        val letter = frMap[base] ?: return null
        return listOf(dead, letter)
    }

    private companion object {
        const val SHIFT = Modifier.LEFT_SHIFT
        const val ALTGR = Modifier.RIGHT_ALT

        /** a..z at 0x04..0x1D on a US board. */
        fun letter(c: Char): Int = 0x04 + (c - 'a')

        val usMap: Map<Char, KeyStroke> = buildMap {
            for (c in 'a'..'z') {
                put(c, KeyStroke(letter(c)))
                put(c.uppercaseChar(), KeyStroke(letter(c), SHIFT))
            }
            "1234567890".forEachIndexed { i, c -> put(c, KeyStroke(0x1E + i)) }
            "!@#$%^&*()".forEachIndexed { i, c -> put(c, KeyStroke(0x1E + i, SHIFT)) }
            put(' ', KeyStroke(KeyUsage.SPACE))
            put('\n', KeyStroke(KeyUsage.ENTER))
            put('\t', KeyStroke(KeyUsage.TAB))
            val pairs = listOf(
                0x2D to "-_", 0x2E to "=+", 0x2F to "[{", 0x30 to "]}", 0x31 to "\\|",
                0x33 to ";:", 0x34 to "'\"", 0x35 to "`~", 0x36 to ",<", 0x37 to ".>", 0x38 to "/?",
            )
            for ((usage, chars) in pairs) {
                put(chars[0], KeyStroke(usage))
                put(chars[1], KeyStroke(usage, SHIFT))
            }
        }

        val frMap: Map<Char, KeyStroke> = buildMap {
            // AZERTY letters: A<->Q, Z<->W swap places, M sits where US has ';'.
            val position = mapOf('a' to letter('q'), 'q' to letter('a'), 'z' to letter('w'), 'w' to letter('z'), 'm' to 0x33)
            for (c in 'a'..'z') {
                val usage = position[c] ?: if (c == 'm') 0x33 else letter(c)
                put(c, KeyStroke(usage))
                put(c.uppercaseChar(), KeyStroke(usage, SHIFT))
            }
            // The number row: symbols bare, digits shifted, AltGr for the third level.
            val bare = "&é\"'(-è_çà"
            val altGr = listOf('~', '#', '{', '[', '|', '`', '\\', '^', '@')
            for (i in 0 until 10) {
                val usage = 0x1E + i
                put(bare[i], KeyStroke(usage))
                put("1234567890"[i], KeyStroke(usage, SHIFT))
            }
            // ~ and ` are dead keys on AltGr (2 and 7) and AltGr+9 '^' is too on some ROMs: left
            // unsupported rather than risk a stray accent; '^' goes through the dead circumflex.
            altGr.forEachIndexed { i, c ->
                if (c != '~' && c != '`' && c != '^') put(c, KeyStroke(0x1F + i, ALTGR))
            }
            put(')', KeyStroke(0x2D))
            put('°', KeyStroke(0x2D, SHIFT))
            put(']', KeyStroke(0x2D, ALTGR))
            put('=', KeyStroke(0x2E))
            put('+', KeyStroke(0x2E, SHIFT))
            put('}', KeyStroke(0x2E, ALTGR))
            put('$', KeyStroke(0x30))
            put('£', KeyStroke(0x30, SHIFT))
            put('¤', KeyStroke(0x30, ALTGR))
            put('ù', KeyStroke(0x34))
            put('%', KeyStroke(0x34, SHIFT))
            put('*', KeyStroke(0x31))
            put('µ', KeyStroke(0x31, SHIFT))
            put('²', KeyStroke(0x35))
            put('<', KeyStroke(0x64))
            put('>', KeyStroke(0x64, SHIFT))
            put(',', KeyStroke(0x10))
            put('?', KeyStroke(0x10, SHIFT))
            put(';', KeyStroke(0x36))
            put('.', KeyStroke(0x36, SHIFT))
            put(':', KeyStroke(0x37))
            put('/', KeyStroke(0x37, SHIFT))
            put('!', KeyStroke(0x38))
            put('§', KeyStroke(0x38, SHIFT))
            put('€', KeyStroke(letter('e'), ALTGR))
            put(' ', KeyStroke(KeyUsage.SPACE))
            put('\n', KeyStroke(KeyUsage.ENTER))
            put('\t', KeyStroke(KeyUsage.TAB))
        }
    }
}

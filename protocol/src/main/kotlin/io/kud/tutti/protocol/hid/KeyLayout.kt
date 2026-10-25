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
 *
 * The two Mac layouts are macOS's own: Option (⌥) is the HID Alt modifier and Command (⌘) is GUI.
 * [MAC_FR] is Apple's "French" AZERTY, which is not the PC one: @ # sit left of 1, the number row
 * reads & é " ' ( § è ! ç à, and { } [ ] | \ ~ come from ⌥ and ⌥⇧ rather than AltGr. [MAC_US]
 * reaches accents through macOS's dead keys (⌥e acute, ⌥` grave, ⌥i circumflex, ⌥u diaeresis,
 * ⌥n tilde). Which of 0x35 and 0x64 macOS reads as the key left of 1 depends on the keyboard type
 * its Keyboard Setup Assistant settles on (ISO here); that is unverified on Erwann's Mac.
 */
enum class KeyLayout {
    US,
    FR_AZERTY,
    MAC_FR,
    MAC_US,
    ;

    fun strokes(char: Char): List<KeyStroke>? = when (this) {
        US -> usMap[char]?.let(::listOf)
        FR_AZERTY -> frMap[char]?.let(::listOf) ?: frDead(char)
        MAC_FR -> macFrMap[char]?.let(::listOf) ?: macFrDead(char)
        MAC_US -> (usMap[char] ?: macUsOption[char])?.let(::listOf) ?: macUsDead(char)
    }

    /** True for the layouts a Mac reads (Option is Alt, Command is GUI). */
    val isMac: Boolean get() = this == MAC_FR || this == MAC_US

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

    private fun macFrDead(char: Char): List<KeyStroke>? {
        val tilde = KeyStroke(letter('n'), OPTION)
        if (char == '~') return listOf(tilde, KeyStroke(KeyUsage.SPACE))
        val (dead, base) = when (char) {
            'ñ' -> tilde to 'n'
            'Ñ' -> tilde to 'N'
            'ã' -> tilde to 'a'
            'õ' -> tilde to 'o'
            // The ^ / ¨ dead key and the letters sit where the PC layout has them.
            else -> return frDead(char)
        }
        return listOf(dead, macFrMap[base] ?: return null)
    }

    private fun macUsDead(char: Char): List<KeyStroke>? {
        val decomposed = java.text.Normalizer.normalize(char.toString(), java.text.Normalizer.Form.NFD)
        if (decomposed.length != 2) return null
        val dead = when (decomposed[1]) {
            '\u0301' -> KeyStroke(letter('e'), OPTION)
            '\u0300' -> KeyStroke(0x35, OPTION)
            '\u0302' -> KeyStroke(letter('i'), OPTION)
            '\u0308' -> KeyStroke(letter('u'), OPTION)
            '\u0303' -> KeyStroke(letter('n'), OPTION)
            else -> return null
        }
        val letter = usMap[decomposed[0]] ?: return null
        return listOf(dead, letter)
    }

    private companion object {
        const val SHIFT = Modifier.LEFT_SHIFT
        const val ALTGR = Modifier.RIGHT_ALT
        const val OPTION = Modifier.LEFT_ALT

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
        /** US Mac: what Option gives directly, without a dead key. */
        val macUsOption: Map<Char, KeyStroke> = mapOf(
            'ç' to KeyStroke(letter('c'), OPTION),
            'Ç' to KeyStroke(letter('c'), OPTION or SHIFT),
            'œ' to KeyStroke(letter('q'), OPTION),
            'Œ' to KeyStroke(letter('q'), OPTION or SHIFT),
            'æ' to KeyStroke(0x34, OPTION),
            'Æ' to KeyStroke(0x34, OPTION or SHIFT),
            'ß' to KeyStroke(letter('s'), OPTION),
            '«' to KeyStroke(0x31, OPTION),
            '»' to KeyStroke(0x31, OPTION or SHIFT),
            '€' to KeyStroke(0x1F, OPTION or SHIFT),
            '£' to KeyStroke(0x20, OPTION),
            '°' to KeyStroke(0x25, OPTION or SHIFT),
        )

        /** Apple "French" (AZERTY): letters as on the PC layout, the rest Apple's own. */
        val macFrMap: Map<Char, KeyStroke> = buildMap {
            val position = mapOf('a' to letter('q'), 'q' to letter('a'), 'z' to letter('w'), 'w' to letter('z'), 'm' to 0x33)
            for (c in 'a'..'z') {
                val usage = position[c] ?: letter(c)
                put(c, KeyStroke(usage))
                put(c.uppercaseChar(), KeyStroke(usage, SHIFT))
            }
            val bare = "&é\"'(§è!çà"
            for (i in 0 until 10) {
                put(bare[i], KeyStroke(0x1E + i))
                put("1234567890"[i], KeyStroke(0x1E + i, SHIFT))
            }
            put(')', KeyStroke(0x2D))
            put('°', KeyStroke(0x2D, SHIFT))
            put('-', KeyStroke(0x2E))
            put('_', KeyStroke(0x2E, SHIFT))
            put('$', KeyStroke(0x30))
            put('*', KeyStroke(0x30, SHIFT))
            put('€', KeyStroke(0x30, OPTION))
            put('ù', KeyStroke(0x34))
            put('%', KeyStroke(0x34, SHIFT))
            put('@', KeyStroke(0x35))
            put('#', KeyStroke(0x35, SHIFT))
            put('<', KeyStroke(0x64))
            put('>', KeyStroke(0x64, SHIFT))
            put(',', KeyStroke(0x10))
            put('?', KeyStroke(0x10, SHIFT))
            put(';', KeyStroke(0x36))
            put('.', KeyStroke(0x36, SHIFT))
            put(':', KeyStroke(0x37))
            put('/', KeyStroke(0x37, SHIFT))
            put('=', KeyStroke(0x38))
            put('+', KeyStroke(0x38, SHIFT))
            // Option layer: brackets on the ( and ) keys, the bar on L, the backslash on : /.
            put('{', KeyStroke(0x22, OPTION))
            put('[', KeyStroke(0x22, OPTION or SHIFT))
            put('}', KeyStroke(0x2D, OPTION))
            put(']', KeyStroke(0x2D, OPTION or SHIFT))
            put('|', KeyStroke(letter('l'), OPTION or SHIFT))
            put('\\', KeyStroke(0x37, OPTION or SHIFT))
            put('«', KeyStroke(0x24, OPTION))
            put('»', KeyStroke(0x24, OPTION or SHIFT))
            put('œ', KeyStroke(letter('o'), OPTION))
            put(' ', KeyStroke(KeyUsage.SPACE))
            put('\n', KeyStroke(KeyUsage.ENTER))
            put('\t', KeyStroke(KeyUsage.TAB))
        }
    }
}

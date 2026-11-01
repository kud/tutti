package io.kud.tutti.protocol.atv

/**
 * Text as key presses, for a TV text box that never opened an input session (a WebView, a
 * React Native or custom Compose field): there a batch edit lands nowhere, but a key event
 * reaches whatever has focus, the way Google TV's own fallback and Hubmote's "anything else"
 * path type (YouTube takes them; Netflix only while its letter grid has focus).
 *
 * Codes are Android's US virtual keyboard. Remote v2 key injects carry no meta state, so a
 * capital or shifted symbol is wrapped in a held SHIFT_LEFT (START_LONG … END_LONG); whether
 * the TV folds that hold into the next key's meta state is **unverified**, and at worst the
 * character arrives unshifted. Anything outside printable ASCII (accents, emoji) has no key
 * and is dropped, and the caller names it.
 */
object TextKeys {
    const val SHIFT_LEFT = 59

    data class Stroke(val keyCode: Int, val shift: Boolean = false)

    private const val A = 29
    private const val COMMA = 55
    private const val PERIOD = 56
    private const val GRAVE = 68
    private const val MINUS = 69
    private const val EQUALS = 70
    private const val LEFT_BRACKET = 71
    private const val RIGHT_BRACKET = 72
    private const val BACKSLASH = 73
    private const val SEMICOLON = 74
    private const val APOSTROPHE = 75
    private const val SLASH = 76
    private const val AT = 77
    private const val PLUS = 81
    private const val STAR = 17
    private const val POUND = 18

    private val plain = mapOf(
        ' ' to V2Key.SPACE, ',' to COMMA, '.' to PERIOD, '`' to GRAVE, '-' to MINUS, '=' to EQUALS,
        '[' to LEFT_BRACKET, ']' to RIGHT_BRACKET, '\\' to BACKSLASH, ';' to SEMICOLON,
        '\'' to APOSTROPHE, '/' to SLASH, '@' to AT, '+' to PLUS, '*' to STAR, '#' to POUND,
    )

    private val shifted = mapOf(
        '!' to V2Key.digit(1), '$' to V2Key.digit(4), '%' to V2Key.digit(5), '^' to V2Key.digit(6),
        '&' to V2Key.digit(7), '(' to V2Key.digit(9), ')' to V2Key.digit(0), '_' to MINUS,
        ':' to SEMICOLON, '"' to APOSTROPHE, '<' to COMMA, '>' to PERIOD, '?' to SLASH,
        '{' to LEFT_BRACKET, '}' to RIGHT_BRACKET, '|' to BACKSLASH, '~' to GRAVE,
    )

    /** The key for [char], or null when the TV's keyboard has none. */
    fun stroke(char: Char): Stroke? = when (char) {
        in 'a'..'z' -> Stroke(A + (char - 'a'))
        in 'A'..'Z' -> Stroke(A + (char - 'A'), shift = true)
        in '0'..'9' -> Stroke(V2Key.digit(char - '0'))
        in plain -> Stroke(plain.getValue(char))
        in shifted -> Stroke(shifted.getValue(char), shift = true)
        else -> null
    }

    fun typable(char: Char): Boolean = stroke(char) != null

    /** The key injects for [stroke], shift held around it when needed. */
    fun messages(stroke: Stroke) = buildList {
        if (stroke.shift) add(RemoteMessages.key(SHIFT_LEFT, KeyDirection.START_LONG)!!)
        add(RemoteMessages.key(stroke.keyCode)!!)
        if (stroke.shift) add(RemoteMessages.key(SHIFT_LEFT, KeyDirection.END_LONG)!!)
    }

    /** Length of the longest common prefix, never splitting a surrogate pair. */
    fun commonPrefix(a: String, b: String): Int {
        var i = 0
        val n = minOf(a.length, b.length)
        while (i < n && a[i] == b[i]) i++
        if (i > 0 && i < n && a[i - 1].isHighSurrogate()) i--
        return i
    }
}

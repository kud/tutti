package io.kud.tutti.protocol.net

/** Secrets never reach a log line. Use this for anything that might hold one. */
object Redact {
    fun secret(value: String?): String = if (value.isNullOrEmpty()) "<empty>" else "<redacted:${value.length}>"

    /**
     * Scrubs free text meant to leave the phone (a copied report, a log line): every value in
     * [known] (PSK, hosts, serials seen in a reply…), then anything shaped like an IP address,
     * a MAC address, a UUID, a long hex string (pins, keys, ids) or a long token.
     *
     * A known value is matched whole, case-insensitively, never inside a longer run of letters or
     * digits: button names from a reply ("Red", "Play", "Mute") once turned "configured" into
     * "configu<redacted>" and "PLAYING" into "<redacted>ING". All known values go in one pass, so
     * a marker is never scanned again ("<<redacted>acted>" was "red" matching inside "<redacted>").
     * Values shorter than three characters are ignored: they would shred ordinary words.
     */
    fun text(input: String, known: Collection<String> = emptyList()): String {
        var out = knownPattern(known)?.replace(input, MARKER) ?: input
        out = MAC.replace(out, "<mac>")
        out = UUID.replace(out, "<id>")
        out = IPV6.replace(out) { m ->
            val v = m.value
            if (v.count { it == ':' } >= 2 && v.any { it.isLetterOrDigit() }) "<ip>" else v
        }
        out = IPV4.replace(out, "<ip>")
        out = HEX.replace(out, "<hex>")
        out = TOKEN.replace(out, "<token>")
        return out
    }

    private const val MIN_KNOWN = 3
    private const val MARKER = "<redacted>"

    private fun knownPattern(known: Collection<String>): Regex? {
        val values = known.asSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_KNOWN }
            .distinct()
            // Longest first, so a value that contains a shorter one is taken whole.
            .sortedByDescending { it.length }
            .toList()
        if (values.isEmpty()) return null
        val alternatives = values.joinToString("|") { Regex.escape(it) }
        return Regex("""(?<![\p{L}\p{N}])(?:$alternatives)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    }

    // Android compiles these with ICU, not java.util.regex: a class opening "[:" starts a POSIX
    // property there ("[:alpha:]") and throws at class init, which once crashed pairing. Never open
    // a class with a colon; RedactTest guards it, since the JVM tests can't run ICU.
    private val MAC = Regex("""(?<![0-9A-Fa-f:])[0-9A-Fa-f]{2}(?:[-:][0-9A-Fa-f]{2}){5}(?![0-9A-Fa-f:])""")
    private val UUID = Regex("""\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\b""")
    private val IPV6 = Regex("""(?<![\w:.])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%\w+)?(?![\w:])""")
    private val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")
    private val HEX = Regex("""\b[0-9A-Fa-f]{16,}\b""")
    private val TOKEN = Regex("""[A-Za-z0-9+/_\-]{32,}={0,2}""")

    internal val patterns: List<Regex> get() = listOf(MAC, UUID, IPV6, IPV4, HEX, TOKEN)
}

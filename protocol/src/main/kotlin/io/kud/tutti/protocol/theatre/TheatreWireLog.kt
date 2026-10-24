package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Redact

/**
 * The last [capacity] exchanges on TCP 33336, exactly as they crossed the socket, for the
 * diagnostic report: requests as written, and replies as raw reads (not decoded objects), so
 * the framing the bar uses shows too. Control characters are escaped (`\n`, `\r`, `\0`).
 *
 * The bar has no secrets on this port, but identifiers still stay out: values of
 * [TheatreFeature.NEVER_ASKED] features are blanked, and every entry goes through [Redact.text]
 * (IP, MAC, long hex, tokens) when it is recorded.
 */
class TheatreWireLog(
    private val capacity: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntryChars: Int = 400,
) {
    private val entries = ArrayDeque<String>()
    private var origin: Long? = null

    fun sent(text: String) = record(OUT, text)

    fun received(bytes: ByteArray) = record(IN, bytes.decodeToString())

    fun note(text: String) = record(NOTE, text)

    fun clear() = synchronized(entries) {
        entries.clear()
        origin = null
    }

    /** Oldest first, already scrubbed. */
    fun lines(): List<String> = synchronized(entries) { entries.toList() }

    private fun record(direction: String, text: String) {
        val now = clock()
        val body = scrub(text).let { if (it.length > maxEntryChars) it.take(maxEntryChars) + "…(+${it.length - maxEntryChars})" else it }
        synchronized(entries) {
            val start = origin ?: now.also { origin = it }
            entries.addLast("+${now - start}ms $direction $body")
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    companion object {
        const val OUT = ">"
        const val IN = "<"
        const val NOTE = "."

        private val VALUE = Regex(""""value"\s*:\s*("(?:[^"\\]|\\.)*"|[^,}\s]+)""")

        /** Blanks identifier values, scrubs the rest, and makes control characters visible. */
        fun scrub(text: String): String {
            // A read may cut an object in two, so blank every value in the entry rather than
            // trusting object boundaries: over-redacting a log line costs nothing.
            val blanked = if (TheatreFeature.NEVER_ASKED.any { text.contains(it) }) {
                VALUE.replace(text) { "\"value\":\"<redacted>\"" }
            } else {
                text
            }
            return Redact.text(escape(blanked))
        }

        private fun escape(text: String): String = buildString {
            for (c in text) {
                when {
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c.code < 0x20 || c.code == 0x7f -> append("\\x%02x".format(c.code))
                    else -> append(c)
                }
            }
        }
    }
}

package io.kud.tutti.protocol.theatre

import io.kud.tutti.protocol.net.Redact

/**
 * The last [capacity] exchanges on TCP 33336, exactly as they crossed the socket, for the
 * diagnostic report: requests as written, and replies as raw reads (not decoded objects), so
 * the framing the bar uses shows too. Control characters are escaped (`\n`, `\r`, `\0`).
 *
 * The bar has no secrets on this port, but identifiers still stay out: values of
 * [TheatreFeature.BLANKED] features are blanked, and every entry goes through [Redact.text]
 * (IP, MAC, long hex, tokens) when it is recorded.
 */
class TheatreWireLog(
    private val capacity: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntryChars: Int = 400,
    private val maxPinned: Int = 8,
) {
    private val entries = ArrayDeque<String>()
    private var origin: Long? = null

    /** The story of the last `set` per feature, kept out of the ring: see [pin]. */
    private val pinned = LinkedHashMap<String, String>()

    fun sent(text: String) = record(OUT, text)

    fun received(bytes: ByteArray) = record(IN, bytes.decodeToString())

    fun note(text: String) = record(NOTE, text)

    /**
     * Adds [text] to [feature]'s pinned story (a [fresh] one starts it over): what tutti last set
     * there and what the bar answered, kept whatever the ring rolls past. At most [maxPinned]
     * features, the oldest dropped; each story is capped like an entry.
     */
    fun pin(feature: String, text: String, fresh: Boolean = false) = synchronized(entries) {
        val story = if (fresh) text else pinned[feature]?.let { "$it; $text" } ?: text
        pinned.remove(feature)
        pinned[feature] = story
        while (pinned.size > maxPinned) pinned.remove(pinned.keys.first())
    }

    fun clear() = synchronized(entries) {
        entries.clear()
        pinned.clear()
        origin = null
    }

    /** Oldest first, already scrubbed, then one line per pinned `set` story. */
    fun lines(): List<String> = synchronized(entries) {
        entries.toList() + pinned.map { (feature, story) -> "last set $feature: ${cap(scrub(story))}" }
    }

    private fun record(direction: String, text: String) {
        val now = clock()
        val body = cap(scrub(text))
        synchronized(entries) {
            val start = origin ?: now.also { origin = it }
            entries.addLast("+${now - start}ms $direction $body")
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    private fun cap(text: String): String =
        if (text.length > maxEntryChars) text.take(maxEntryChars) + "…(+${text.length - maxEntryChars})" else text

    companion object {
        const val OUT = ">"
        const val IN = "<"
        const val NOTE = "."

        private val VALUE = Regex(""""value"\s*:\s*("(?:[^"\\]|\\.)*"|[^,}\s]+)""")

        /** Blanks identifier values, scrubs the rest, and makes control characters visible. */
        fun scrub(text: String): String {
            // A read may cut an object in two, so blank every value in the entry rather than
            // trusting object boundaries: over-redacting a log line costs nothing.
            val blanked = if (TheatreFeature.BLANKED.any { text.contains(it) }) {
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

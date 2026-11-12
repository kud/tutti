package io.kud.tutti.protocol.diagnostic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What tutti did lately and what came back, for developer mode: launches and their trace, app
 * links sent (the scheme only, never the link), key holds and releases, each plane's connection
 * changes, errors with their trace, and the last crash. In memory only, and bounded twice: at most
 * [capacity] entries, the oldest dropped first, each cut to [maxEntryChars]. Nothing is written to
 * disk and nothing is sent: the log reaches the clipboard, the share sheet or a GitHub link the
 * person reviews, and only when they ask.
 *
 * Every entry is scrubbed by [LogScrubber] as it is recorded, with [known] (hosts, the PSK, the
 * cookie, device names) read at that moment, so a raw secret never sits in the ring.
 */
class EventLog(
    val capacity: Int = DEFAULT_CAPACITY,
    private val maxEntryChars: Int = MAX_ENTRY_CHARS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val label: String) {
        LAUNCH("launch"),
        LINK("app link"),
        KEY("key"),
        CONNECTION("connection"),
        CAST("cast"),
        PICTURE("picture"),
        ERROR("error"),
    }

    data class Entry(val atMillis: Long, val kind: Kind, val text: String)

    /** The values to scrub by name, asked on every record; set by whoever holds them. */
    @Volatile var known: () -> Collection<String> = { emptyList() }

    private val ring = ArrayDeque<Entry>()
    private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())
    private val mutableCrash = MutableStateFlow<String?>(null)

    /** Oldest first. */
    val entries: StateFlow<List<Entry>> = mutableEntries.asStateFlow()

    /** The last crash's trace, scrubbed; kept apart so a busy log never pushes it out. */
    val lastCrash: StateFlow<String?> = mutableCrash.asStateFlow()

    fun record(kind: Kind, text: String, sensitive: Collection<String> = emptyList()) {
        val entry = Entry(clock(), kind, scrub(text, sensitive))
        synchronized(ring) {
            ring.addLast(entry)
            while (ring.size > capacity) ring.removeFirst()
            mutableEntries.value = ring.toList()
        }
    }

    fun crash(trace: String?) {
        mutableCrash.value = trace?.let { scrub(it, emptyList(), CRASH_CHARS) }
    }

    fun clear() {
        synchronized(ring) {
            ring.clear()
            mutableEntries.value = emptyList()
        }
        mutableCrash.value = null
    }

    /** Plain text, oldest first, one entry per block; the last crash, if any, at the end. */
    fun render(zone: ZoneId = ZoneId.systemDefault()): String = render(entries.value, lastCrash.value, zone)

    private fun scrub(text: String, sensitive: Collection<String>, limit: Int = maxEntryChars): String {
        val known = runCatching { known() }.getOrDefault(emptyList()) + sensitive
        // A scrub that throws drops the text rather than keep it raw.
        return runCatching { LogScrubber.scrub(text.take(limit), known) }.getOrDefault(UNSCRUBBED)
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
        const val MAX_ENTRY_CHARS = 4_000
        private const val CRASH_CHARS = 20_000
        const val UNSCRUBBED = "(not kept: the line could not be scrubbed)"
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun render(entries: List<Entry>, crash: String?, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
            for (entry in entries) append(line(entry, zone)).append('\n')
            if (crash != null) append("last crash:\n").append(crash).append('\n')
        }.trimEnd()

        fun line(entry: Entry, zone: ZoneId = ZoneId.systemDefault()): String =
            "${TIME.format(Instant.ofEpochMilli(entry.atMillis).atZone(zone))} ${entry.kind.label}: ${entry.text}"
    }
}

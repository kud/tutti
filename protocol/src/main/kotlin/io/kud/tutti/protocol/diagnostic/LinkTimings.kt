package io.kud.tutti.protocol.diagnostic

/** The links whose time to ready the diagnostic reports. */
enum class TimedLink(val label: String) {
    REMOTE("tv remote v2"),
    REST("tv ip control"),
    BAR("soundbar tcp"),
    CAST("cast"),
}

/**
 * Time to ready, per link (Maya's top 10, item 8): from the start of a connect attempt to the link
 * being usable (Remote v2 ready after its configure exchange, REST's discovery answered, the bar's
 * socket open, Cast's first status read after device auth). Only the attempt that got there counts;
 * a failed one is replaced by the next [started]. Durations only, never a host: safe in a report.
 */
class LinkTimings {
    data class Stat(val lastMs: Long, val bestMs: Long, val worstMs: Long, val count: Int)

    private val lock = Any()
    private val started = mutableMapOf<TimedLink, Long>()
    private val stats = mutableMapOf<TimedLink, Stat>()

    /** A connect attempt on [link] starts at [at] (a monotonic clock, ms). */
    fun started(link: TimedLink, at: Long) = synchronized(lock) { started[link] = at }

    /** [link] became ready at [at]: records the time since its attempt started, once. */
    fun ready(link: TimedLink, at: Long) = synchronized(lock) {
        val since = started.remove(link) ?: return@synchronized
        record(link, at - since)
    }

    /** A duration measured elsewhere (Cast measures its own session). Negative ones are dropped. */
    fun record(link: TimedLink, millis: Long) = synchronized(lock) {
        if (millis < 0) return@synchronized
        stats[link] = stats[link]?.let { Stat(millis, minOf(it.bestMs, millis), maxOf(it.worstMs, millis), it.count + 1) }
            ?: Stat(millis, millis, millis, 1)
    }

    fun stat(link: TimedLink): Stat? = synchronized(lock) { stats[link] }

    /** One line per link, every link listed: "last 840 ms, best 610 ms, worst 2300 ms, 3 times". */
    fun report(): List<Pair<String, String>> = TimedLink.entries.map { link ->
        "ready in, ${link.label}" to (
            stat(link)?.let { "last ${it.lastMs} ms, best ${it.bestMs} ms, worst ${it.worstMs} ms, ${it.count} ${if (it.count == 1) "time" else "times"}" }
                ?: "not ready this session"
            )
    }
}

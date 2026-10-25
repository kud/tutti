package io.kud.tutti.protocol.apps

import io.kud.tutti.protocol.net.LanAddress
import java.net.URI

/** What became of one app's TV icon, the last time it was looked at. */
enum class IconOutcome(val label: String, val retry: Boolean) {
    CACHED("cached", retry = false),
    BLANK("tv listed no icon", retry = false),
    MALFORMED("not an http(s) url", retry = false),
    INTERNET("internet host, never fetched", retry = false),
    NO_PIN("https before any pin", retry = true),
    REFUSED("connection refused", retry = true),
    NETWORK("network error", retry = true),
    HTTP_STATUS("http status", retry = true),
    TOO_BIG("over the size cap", retry = false),
    NOT_IMAGE("not an image", retry = true),
}

/** One attempt's result: [detail] is a status code or an exception class, never a host or a header. */
data class IconAttempt(val outcome: IconOutcome, val detail: String? = null)

/**
 * Where an app icon can come from, and how often to ask again. Only ever from the TV itself:
 * an icon URL naming another address of the TV (loopback, a second interface, a LAN name) is
 * aimed back at the configured TV host, so the pin and the private-address rule still hold;
 * an internet host is never fetched.
 */
object AppIcons {
    /** How a TV's icon URL relates to the TV, for the report: never the address itself. */
    enum class HostKind { TV, LOOPBACK, OTHER_LAN, LAN_NAME, INTERNET }

    sealed interface Plan {
        /** [shape] describes the URL without its host, for the diagnostic report. */
        val shape: String

        data class Fetch(val url: String, val kind: HostKind, override val shape: String) : Plan
        data class Skip(val outcome: IconOutcome, override val shape: String) : Plan
    }

    fun plan(iconUrl: String?, tvHost: String): Plan {
        val raw = iconUrl?.trim().orEmpty()
        if (raw.isEmpty()) return Plan.Skip(IconOutcome.BLANK, "none")
        val uri = runCatching { URI(raw) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]")
        if (uri == null || scheme !in setOf("http", "https") || host.isNullOrEmpty()) {
            return Plan.Skip(IconOutcome.MALFORMED, "${scheme ?: "?"} url")
        }
        val kind = kindOf(host, bare(tvHost))
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val shape = "$scheme$port ${pathShape(path)} host=${kind.name.lowercase().replace('_', '-')}"
        if (kind == HostKind.INTERNET) return Plan.Skip(IconOutcome.INTERNET, shape)
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        val tv = bare(tvHost).let { if (it.contains(':')) "[$it]" else it }
        return Plan.Fetch("$scheme://$tv$port$path$query", kind, shape)
    }

    /** The first two path segments, then an ellipsis: enough to tell `/DIAL/icon/…` from another layout. */
    private fun pathShape(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        val head = segments.take(2).joinToString("/", prefix = "/")
        return if (segments.size > 2) "$head/…" else head
    }

    private fun bare(host: String) = host.trim().removePrefix("[").removeSuffix("]").lowercase()

    private fun kindOf(host: String, tvHost: String): HostKind {
        val h = host.lowercase()
        return when {
            h == tvHost -> HostKind.TV
            h == "localhost" || h.startsWith("127.") || h == "::1" || h == "0:0:0:0:0:0:0:1" -> HostKind.LOOPBACK
            LanAddress.isPrivate(h) -> HostKind.OTHER_LAN
            looksLikeIp(h) -> HostKind.INTERNET
            !h.contains('.') || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") -> HostKind.LAN_NAME
            else -> HostKind.INTERNET
        }
    }

    private fun looksLikeIp(h: String) = h.contains(':') || h.split('.').let { p -> p.size == 4 && p.all { s -> s.isNotEmpty() && s.all(Char::isDigit) } }

    /** Whether [bytes] start like an image Android's BitmapFactory decodes (PNG, JPEG, GIF, WebP, BMP, ICO). */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0x89 && ascii(1, "PNG") -> true
            at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> true
            ascii(0, "GIF8") -> true
            ascii(0, "RIFF") && ascii(8, "WEBP") -> true
            ascii(0, "BM") && bytes.size > 14 -> true
            at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0 -> true
            else -> false
        }
    }

    /** Wait before asking again after the [attempts]th failure in a row: 30 s, 2 min, 10 min, 30 min, then hourly. */
    fun retryDelayMs(attempts: Int): Long = RETRY_DELAYS_MS[(attempts - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)]

    private val RETRY_DELAYS_MS = longArrayOf(30_000, 120_000, 600_000, 1_800_000, 3_600_000)
}

/**
 * What tutti knows about each app's icon this process: the URL it last tried, how it went and
 * when to try again. Keyed by package. A URL the TV changes starts over; an outcome that cannot
 * change by waiting (blank, internet host, too big) is not retried until the URL does.
 */
data class IconBook(val entries: Map<String, Entry> = emptyMap()) {
    data class Entry(val url: String?, val shape: String, val last: IconAttempt, val failures: Int, val nextAtMs: Long)

    fun due(packageName: String, url: String?, nowMs: Long): Boolean {
        val entry = entries[packageName] ?: return true
        if (entry.url != url?.trim()?.ifEmpty { null }) return true
        if (entry.last.outcome == IconOutcome.CACHED) return false
        return entry.last.outcome.retry && nowMs >= entry.nextAtMs
    }

    fun record(packageName: String, url: String?, shape: String, attempt: IconAttempt, nowMs: Long): IconBook {
        val key = url?.trim()?.ifEmpty { null }
        val previous = entries[packageName]?.takeIf { it.url == key }
        val failures = if (attempt.outcome == IconOutcome.CACHED) 0 else (previous?.failures ?: 0) + 1
        val next = if (attempt.outcome.retry) nowMs + AppIcons.retryDelayMs(failures) else Long.MAX_VALUE
        return copy(entries = entries + (packageName to Entry(key, shape, attempt, failures, next)))
    }

    /**
     * One line per outcome with its count, then one per app not cached; no URL host ever appears.
     * [shown] names what the tile wears instead (phone, mark, letter), appended when known.
     */
    fun report(shown: (String) -> String? = { null }): List<String> {
        if (entries.isEmpty()) return listOf("nothing tried this session")
        val counts = entries.values.groupingBy { it.last.outcome }.eachCount()
        val summary = IconOutcome.entries.filter { it in counts }.joinToString { "${it.label} ${counts[it]}" }
        val missing = entries.toSortedMap().filterValues { it.last.outcome != IconOutcome.CACHED }.map { (pkg, e) ->
            val detail = e.last.detail?.let { " ($it)" }.orEmpty()
            val instead = shown(pkg)?.let { "; shown: $it" }.orEmpty()
            "$pkg: ${e.last.outcome.label}$detail, ${e.shape}, tries ${e.failures}$instead"
        }
        return listOf(summary) + missing
    }
}

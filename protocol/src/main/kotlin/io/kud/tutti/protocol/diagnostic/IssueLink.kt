package io.kud.tutti.protocol.diagnostic

import java.net.URLEncoder

/**
 * "Report on GitHub" without a key: an `issues/new` link with the title and body filled in, which
 * the browser opens and the person reviews and submits with their own account. tutti sends
 * nothing; building the link is all it does.
 *
 * The body is the context (app version and flavour, Android, the TV's model family), the failure's
 * detail, then the newest lines of the event log that fit. Links much past 8,000 characters fail
 * on GitHub's side, so the link stays under [MAX_LENGTH]: the log loses its oldest lines first,
 * with [CUT] in their place, and only then is the detail halved, with [DETAIL_CUT]. Copy and Share
 * carry the whole text. Everything goes through [LogScrubber] once more on the way out.
 */
object IssueLink {
    const val NEW_ISSUE = "https://github.com/kud/tutti/issues/new"
    const val MAX_LENGTH = 7_500
    const val CUT = "[older lines cut to fit the link: copy the log in tutti for all of it]"
    const val DETAIL_CUT = "[cut to fit the link: Copy details in tutti for all of it]"
    private const val MAX_TITLE = 120
    private const val MIN_DETAIL = 200

    fun build(
        title: String,
        facts: List<Pair<String, String>>,
        detail: String?,
        log: String?,
        maxLength: Int = MAX_LENGTH,
    ): String {
        val cleanTitle = LogScrubber.scrub(title.lineSequence().firstOrNull().orEmpty().trim()).take(MAX_TITLE)
            .ifEmpty { "Problem report" }
        val cleanFacts = facts.map { (key, value) -> key to LogScrubber.scrub(value) }
        val logLines = log?.takeIf { it.isNotBlank() }?.let { LogScrubber.scrub(it.trim()).lines() }.orEmpty()

        fun url(detailText: String?, kept: Int): String {
            val tail = logLines.takeLast(kept)
            val shown = if (kept < logLines.size) listOf(CUT) + tail else tail
            return NEW_ISSUE + "?title=" + encode(cleanTitle) + "&body=" + encode(body(cleanTitle, cleanFacts, detailText, shown))
        }

        // No log at all and still too long: halve the detail until it fits, or drop it.
        var detailText = detail?.takeIf { it.isNotBlank() }?.let { LogScrubber.scrub(it.trim()) }
        while (detailText != null && url(detailText, 0).length > maxLength) {
            val text = detailText.removeSuffix("\n$DETAIL_CUT")
            detailText = if (text.length < MIN_DETAIL) null else text.take(text.length / 2).trimEnd() + "\n" + DETAIL_CUT
        }
        // Then the most log lines that fit, newest kept.
        var low = 0
        var high = logLines.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (url(detailText, mid).length <= maxLength) low = mid else high = mid - 1
        }
        return url(detailText, low)
    }

    internal fun body(title: String, facts: List<Pair<String, String>>, detail: String?, log: List<String>): String = buildString {
        append("<!-- Filled in by tutti in developer mode. Nothing has been sent: read it, edit it, then submit. -->\n\n")
        append("### What happened\n\n").append(title).append("\n\n")
        if (facts.isNotEmpty()) {
            append("### Context\n\n")
            facts.forEach { (key, value) -> append("- ").append(key).append(": ").append(value).append('\n') }
            append('\n')
        }
        if (detail != null) append("### Details\n\n```\n").append(fence(detail)).append("\n```\n\n")
        if (log.isNotEmpty()) append("### Recent events\n\n```\n").append(fence(log.joinToString("\n"))).append("\n```\n")
    }.trimEnd()

    /** Three backticks inside would close the block early. */
    private fun fence(text: String) = text.replace("```", "'''")

    /** Form encoding, with spaces as %20 rather than +. */
    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20")

    /**
     * The TV's model family without its screen size: "K-65XR80" reads "K-xxXR80", "KD-55X85J"
     * "KD-xxX85J". Nothing that tells one set from another; null when unknown or odd.
     */
    fun modelFamily(model: String?): String? {
        val clean = model?.trim()?.takeIf { it.isNotEmpty() && it.length <= 32 } ?: return null
        return SIZE.replace(clean) { it.groupValues[1] + "xx" }.takeIf { FAMILY.matches(it) }
    }

    private val SIZE = Regex("""^([A-Za-z]{1,4}-)\d{2,3}""")
    private val FAMILY = Regex("""[A-Za-z0-9 _.\-]+""")
}

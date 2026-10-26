package io.kud.tutti.ui.common

/**
 * When a short list of choices shows as segments instead of a menu (Iris, 2026-10-26, after
 * Erwann's « Pour des options comme ça on peut mettre un segment nan ? »): two to [MAX_OPTIONS]
 * options, and every label fits its segment in at most [MAX_LINES] lines without a word broken,
 * measured in the language, width and font size on screen. Anything else keeps the menu, so a
 * long French label or a large font falls back rather than ellipsising.
 */
object SegmentFit {
    const val MAX_OPTIONS = 4
    const val MAX_LINES = 2

    /** One label as measured at its segment's text width. */
    data class Label(val longestWordFits: Boolean, val lines: Int)

    /** The lines every segment reserves (so they share one height), or null for the menu. */
    fun lines(labels: List<Label>): Int? {
        if (labels.size !in 2..MAX_OPTIONS) return null
        if (labels.any { !it.longestWordFits || it.lines !in 1..MAX_LINES }) return null
        return labels.maxOf { it.lines }
    }
}

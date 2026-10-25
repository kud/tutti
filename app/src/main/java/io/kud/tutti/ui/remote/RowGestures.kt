package io.kud.tutti.ui.remote

import io.kud.tutti.R
import io.kud.tutti.platform.data.SecondaryButton

/**
 * What a round key's long press does while he hasn't chosen one of his own (Iris's gesture
 * audit, 2026-10-25, item 4): Back goes back to the previous app, Home opens the Apps sheet, recent
 * apps first. Play has no default yet: its first long press still opens the picker. An assigned action
 * always wins, and the default says itself once with "Change" beside it, never imposed in silence.
 */
internal enum class RowDefault(val label: Int) {
    PREVIOUS_APP(R.string.row_long_previous_app),
    RECENT_APPS(R.string.row_long_recent_apps),
}

internal fun rowDefault(button: SecondaryButton): RowDefault? = when (button) {
    SecondaryButton.BACK -> RowDefault.PREVIOUS_APP
    SecondaryButton.HOME -> RowDefault.RECENT_APPS
    SecondaryButton.PLAY -> null
}

/** Long-press Keyboard (audit item 8): what pasting the phone's clipboard into the TV's field does. */
internal sealed interface Paste {
    /** Nothing on the clipboard (or only blanks): nothing is sent, and the remote says so. */
    data object Empty : Paste

    /** The TV has no text field open: nowhere to paste, and the remote says so. */
    data object NoField : Paste

    /** Replace the TV field's text with [text]: what was there, a space, then the clipboard. */
    data class Send(val text: String) : Paste
}

/**
 * The clipboard is appended after a space, as the input view's own Paste chip does, so a link
 * lands after a typed word rather than glued to it. The clipboard is checked first: an empty one
 * says "empty" whatever the TV shows. Pure; the text is never logged.
 */
internal fun paste(clip: String?, field: String?): Paste {
    val addition = clip?.takeIf { it.isNotBlank() } ?: return Paste.Empty
    val current = field ?: return Paste.NoField
    return Paste.Send(if (current.isEmpty() || current.endsWith(' ')) current + addition else "$current $addition")
}

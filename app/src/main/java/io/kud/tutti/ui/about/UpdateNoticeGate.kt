package io.kud.tutti.ui.about

/**
 * Whether the update notice may speak now (Iris, 2026-10-30, « Fixe tout ce que tu penses »): only
 * on the plain remote, with no sheet, menu or editor over it and no other message showing. Otherwise
 * it is skipped, never queued and never replacing another message: the ⋮'s dot and its row already
 * carry the build, so nothing is lost by keeping quiet.
 */
internal fun updateNoticeAllowed(
    onRemote: Boolean,
    editing: Boolean,
    covers: Int,
    messageShowing: Boolean,
): Boolean = onRemote && !editing && covers == 0 && !messageShowing

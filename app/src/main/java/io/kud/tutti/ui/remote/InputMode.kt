package io.kud.tutti.ui.remote

/** Where the input view came from, which picks its entrance: the key morphs, a TV field rises. */
enum class InputOrigin { KEY, TV }

/** What the field shows. Each has its own outline, mark and words; never colour alone. */
enum class FieldPhase { LIVE, WAITING, CLOSED, UNREACHABLE }

/** What Android Back does in the input view: the phone keyboard goes first, then the view. */
enum class BackAction { HIDE_KEYBOARD, LEAVE }

/**
 * The full-screen input view's state, a state of the remote rather than a screen of its own.
 * Pure: [InputRules] moves it, the clock comes in as `now`, so the 700 ms hand-over is testable.
 */
data class InputMode(
    val open: Boolean = false,
    val origin: InputOrigin = InputOrigin.TV,
    /** The TV has a text field open. */
    val hasField: Boolean = false,
    /** The TV's show-request count for the field we know, to tell a new field from the same one. */
    val shows: Int = -1,
    /** The field he walked away from with ← or Back: it waits behind the Keyboard key, it doesn't reopen the view. */
    val left: Int? = null,
    /** When the TV closed the field the view was showing; the remote comes back [InputRules.GRACE_MS] later. */
    val closedAt: Long? = null,
    /** The TV closed it within [InputRules.SENT_WINDOW_MS] of Enter: the search went through. */
    val sent: Boolean = false,
    val enterAt: Long? = null,
    /** Bumped each time a new TV field takes over the open view, for its haptic and cross-fade. */
    val handOvers: Int = 0,
) {
    /** The remote's Keyboard key wears its "on" look: a TV field is waiting behind it. */
    val fieldWaiting: Boolean get() = !open && hasField
}

object InputRules {
    /** How long the "closed" frame holds before the remote comes back, and a new field can take over. */
    const val GRACE_MS = 700L

    /** A close this soon after Enter means the TV ran it: `confirm`. */
    const val SENT_WINDOW_MS = 2_000L

    /** The TV's side: [hasField] from `ime != null`, [shows] its show-request count. */
    fun tv(mode: InputMode, hasField: Boolean, shows: Int, now: Long): InputMode {
        if (!hasField) {
            if (!mode.hasField) return mode
            if (!mode.open) return mode.copy(hasField = false, left = null)
            val sent = mode.enterAt?.let { now - it in 0..SENT_WINDOW_MS } == true
            return mode.copy(hasField = false, closedAt = now, sent = sent)
        }
        if (mode.hasField && shows == mode.shows) return mode
        return when {
            // A new field while open, or within the grace after the last one closed: it takes over in place.
            mode.open && (mode.hasField || mode.closedAt != null) -> mode.copy(
                hasField = true,
                shows = shows,
                closedAt = null,
                sent = false,
                enterAt = null,
                handOvers = mode.handOvers + 1,
            )
            // Waiting for a field: it goes live.
            mode.open -> mode.copy(hasField = true, shows = shows)
            // The field he left: the Keyboard key says it's there; no nagging.
            mode.left == shows -> mode.copy(hasField = true, shows = shows)
            else -> InputMode(open = true, origin = InputOrigin.TV, hasField = true, shows = shows, handOvers = mode.handOvers)
        }
    }

    /** The remote's Keyboard key: open, live if the TV has a field, waiting if not. */
    fun keyboardKey(mode: InputMode): InputMode =
        mode.copy(open = true, origin = InputOrigin.KEY, left = null, closedAt = null, sent = false, enterAt = null)

    /** ← or Back with the phone keyboard down. The TV's field stays open there. */
    fun leave(mode: InputMode): InputMode =
        mode.copy(open = false, left = if (mode.hasField) mode.shows else null, closedAt = null, sent = false, enterAt = null)

    fun enter(mode: InputMode, now: Long): InputMode = mode.copy(enterAt = now)

    /** Called when the grace may have run out. */
    fun tick(mode: InputMode, now: Long): InputMode {
        val closedAt = mode.closedAt ?: return mode
        if (now - closedAt < GRACE_MS) return mode
        return mode.copy(open = false, closedAt = null, sent = false, enterAt = null, left = null)
    }

    /**
     * The keyboard comes to him (Iris's benchmark, idea 6): a new TV field ([shows] past
     * [seenShows]) while tutti is in front but off the remote, where the view would open by itself,
     * is offered once as a quiet message with "Type". The same field is never offered twice.
     */
    fun offerAway(seenShows: Int, hasField: Boolean, shows: Int, onRemote: Boolean): Boolean =
        hasField && shows != seenShows && !onRemote

    fun back(imeVisible: Boolean): BackAction = if (imeVisible) BackAction.HIDE_KEYBOARD else BackAction.LEAVE

    fun phase(mode: InputMode, reachable: Boolean): FieldPhase = when {
        !reachable -> FieldPhase.UNREACHABLE
        mode.closedAt != null -> FieldPhase.CLOSED
        mode.hasField -> FieldPhase.LIVE
        else -> FieldPhase.WAITING
    }
}

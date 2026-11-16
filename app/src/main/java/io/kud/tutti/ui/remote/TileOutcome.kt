package io.kud.tutti.ui.remote

/** How a Son tile's request ended, once the bar stops treating it as pending. */
enum class TileSettle {
    NONE,
    CONFIRMED,
    REFUSED,

    /** The value refused a moment ago arrived after all (a late ACK, a notify, the re-ask): clear the warning. */
    LATE,
}

/**
 * Watches one tile's pending value, as plain logic. The tile flips at once (the bar's state is
 * optimistic) and the reducer drops the pending value on an ACK, a matching notify, a refusal or a
 * timeout. Only the shown value tells them apart: the one asked for means it took, anything else
 * means it rolled back. A disconnect drops every pending value too, but that is not the tile's
 * failure: its reason ("Bar 9 unreachable") says so instead.
 */
class TileOutcome {
    private var asked: String? = null

    /** The value last settled as refused, until it shows or a new request replaces it. */
    private var missed: String? = null

    /** Feed every new frame: [pending] the bar's pending value for this tile, [shown] what it shows. */
    fun observe(pending: String?, shown: String?, connected: Boolean): TileSettle {
        val before = asked
        asked = pending
        if (pending != null) missed = null
        return when {
            pending == null && before == null && missed != null && shown == missed -> TileSettle.LATE.also { missed = null }
            pending != null || before == null -> TileSettle.NONE
            !connected -> TileSettle.NONE
            shown == before -> TileSettle.CONFIRMED
            else -> TileSettle.REFUSED.also { missed = before }
        }
    }
}

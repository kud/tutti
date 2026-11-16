package io.kud.tutti.protocol.theatre

/**
 * Pure reducer. Reconciliation rules:
 * - `set` makes the value pending; an ACK or a matching notify confirms it, whichever comes first.
 * - A notify with a different value updates [TheatreState.confirmed] but keeps a newer pending intent.
 * - A `set` result that echoes the asked value counts as an ACK: it is not a refusal.
 * - A non-ACK result, a send failure, a timeout or a disconnect drops the pending value (rollback).
 * - A `set` that timed out stays [TheatreState.overdue] for [LATE_SET_MILLIS]: a late ACK (or echo)
 *   then confirms the value the bar did take.
 */
object TheatreReducer {
    /** How long a timed-out `set` still waits for a late answer. */
    const val LATE_SET_MILLIS = 15_000L

    fun reduce(state: TheatreState, event: TheatreEvent): TheatreState = when (event) {
        is TheatreEvent.Connected ->
            if (event.connected) {
                state.copy(connected = true, lastError = null, silent = emptySet())
            } else {
                state.copy(connected = false, pending = emptyMap(), outstanding = emptyMap(), overdue = emptyMap())
            }
        is TheatreEvent.Sent -> sent(state, event)
        is TheatreEvent.SendFailed -> {
            val request = state.outstanding[event.requestId]
            val pending = if (request != null && state.pending[request.feature]?.requestId == event.requestId) {
                state.pending - request.feature
            } else {
                state.pending
            }
            state.copy(pending = pending, outstanding = state.outstanding - event.requestId, lastError = event.reason)
        }
        is TheatreEvent.Received -> received(state, event.message)
        is TheatreEvent.Tick -> tick(state, event.now)
    }

    private fun sent(state: TheatreState, event: TheatreEvent.Sent): TheatreState {
        val deadline = event.now + event.timeoutMillis
        return when (val request = event.request) {
            is TheatreOutbound.GetRequest -> state.copy(
                outstanding = state.outstanding + (request.id to Outstanding(request.feature, isSet = false, value = null, deadline = deadline)),
            )
            is TheatreOutbound.SetRequest -> state.copy(
                outstanding = state.outstanding + (request.id to Outstanding(request.feature, isSet = true, value = request.value, deadline = deadline)),
                pending = state.pending + (request.feature to Pending(request.id, request.value, deadline)),
            )
        }
    }

    private fun received(state: TheatreState, message: TheatreInbound): TheatreState = when (message) {
        is TheatreInbound.Malformed -> state
        is TheatreInbound.Notify -> {
            val pending = state.pending[message.feature]
            state.copy(
                confirmed = state.confirmed + (message.feature to message.value),
                pending = if (pending?.value == message.value) state.pending - message.feature else state.pending,
                refused = state.refused - message.feature,
                silent = state.silent - message.feature,
                refusedValues = state.refusedValues.without(message.feature, message.value),
            )
        }
        is TheatreInbound.Result -> result(state, message)
    }

    private fun result(state: TheatreState, message: TheatreInbound.Result): TheatreState {
        val id = message.id
        val request = if (id != null) state.outstanding[id] else null
        if (request == null && id != null) state.overdue[id]?.let { return late(state, id, it, message) }
        if (request == null || id == null) {
            // Unknown id (late answer after a timeout, or another client): trust a self-describing result only.
            val feature = message.feature ?: return state
            val value = message.value ?: return state
            if (message.isAck || message.isRefusal) return state
            return state.copy(
                confirmed = state.confirmed + (feature to value),
                silent = state.silent - feature,
                refusedValues = state.refusedValues.without(feature, value),
            )
        }
        val outstanding = state.outstanding - id
        if (!request.isSet) {
            val value = message.value
            return when {
                message.isRefusal -> state.copy(
                    outstanding = outstanding,
                    confirmed = state.confirmed - request.feature,
                    refused = state.refused + request.feature,
                    silent = state.silent - request.feature,
                )
                value == null || message.isAck -> state.copy(outstanding = outstanding)
                else -> state.copy(
                    outstanding = outstanding,
                    confirmed = state.confirmed + (request.feature to value),
                    refused = state.refused - request.feature,
                    silent = state.silent - request.feature,
                    // The bar holds it: whatever "didn't take" said before, it is not refused now.
                    refusedValues = state.refusedValues.without(request.feature, value),
                )
            }
        }
        val pendingIsThis = state.pending[request.feature]?.requestId == id
        return if (tookSet(message, request)) {
            val confirmedValue = request.value ?: return state.copy(outstanding = outstanding)
            state.copy(
                outstanding = outstanding,
                confirmed = state.confirmed + (request.feature to confirmedValue),
                pending = if (pendingIsThis) state.pending - request.feature else state.pending,
                refusedValues = state.refusedValues.without(request.feature, confirmedValue),
            )
        } else {
            state.copy(
                outstanding = outstanding,
                pending = if (pendingIsThis) state.pending - request.feature else state.pending,
                lastError = "${request.feature}: ${message.value ?: "refused"}",
                refusedValues = state.refusedValues.with(request.feature, request.value),
            )
        }
    }

    /** An ACK, or the asked value echoed back: either way the bar took it. */
    private fun tookSet(message: TheatreInbound.Result, request: Outstanding): Boolean =
        message.isAck || (!message.isRefusal && message.value != null && message.value == request.value)

    /**
     * The answer to a `set` already rolled back at its timeout. Taken: the value is confirmed and
     * cleared from the refused ones, while a newer pending intent for the feature stays in front.
     * Anything else: the rollback stands.
     */
    private fun late(state: TheatreState, id: Int, request: Outstanding, message: TheatreInbound.Result): TheatreState {
        val overdue = state.overdue - id
        val value = request.value
        if (value == null || !tookSet(message, request)) return state.copy(overdue = overdue)
        return state.copy(
            overdue = overdue,
            confirmed = state.confirmed + (request.feature to value),
            refusedValues = state.refusedValues.without(request.feature, value),
            silent = state.silent - request.feature,
        )
    }

    private fun Map<String, Set<String>>.with(feature: String, value: String?): Map<String, Set<String>> =
        if (value == null) this else this + (feature to (this[feature].orEmpty() + value))

    private fun Map<String, Set<String>>.without(feature: String, value: String?): Map<String, Set<String>> {
        val left = this[feature].orEmpty() - (value ?: return this)
        return if (left.isEmpty()) this - feature else this + (feature to left)
    }

    private fun tick(state: TheatreState, now: Long): TheatreState {
        val expired = state.outstanding.filterValues { it.deadline <= now }
        val stale = state.overdue.filterValues { it.deadline <= now }.keys
        if (expired.isEmpty()) return if (stale.isEmpty()) state else state.copy(overdue = state.overdue - stale)
        val expiredPending = state.pending.filterValues { it.requestId in expired.keys }
        val unanswered = expired.values.filter { !it.isSet && it.feature !in state.confirmed }.map { it.feature }
        val unansweredSets = expired.values.filter { it.isSet && it.value != null }
        return state.copy(
            refusedValues = unansweredSets.fold(state.refusedValues) { map, request -> map.with(request.feature, request.value) },
            outstanding = state.outstanding - expired.keys,
            overdue = state.overdue - stale + expired
                .filterValues { it.isSet }
                .mapValues { it.value.copy(deadline = now + LATE_SET_MILLIS) },
            silent = state.silent + unanswered,
            pending = state.pending - expiredPending.keys,
            lastError = if (expiredPending.isEmpty()) state.lastError else "timeout: ${expiredPending.keys.joinToString()}",
        )
    }
}

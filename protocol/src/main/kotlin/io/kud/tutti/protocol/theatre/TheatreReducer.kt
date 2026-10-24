package io.kud.tutti.protocol.theatre

/**
 * Pure reducer. Reconciliation rules:
 * - `set` makes the value pending; an ACK or a matching notify confirms it, whichever comes first.
 * - A notify with a different value updates [TheatreState.confirmed] but keeps a newer pending intent.
 * - A non-ACK result, a send failure, a timeout or a disconnect drops the pending value (rollback).
 */
object TheatreReducer {
    fun reduce(state: TheatreState, event: TheatreEvent): TheatreState = when (event) {
        is TheatreEvent.Connected ->
            if (event.connected) {
                state.copy(connected = true, lastError = null, silent = emptySet())
            } else {
                state.copy(connected = false, pending = emptyMap(), outstanding = emptyMap())
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
            )
        }
        is TheatreInbound.Result -> result(state, message)
    }

    private fun result(state: TheatreState, message: TheatreInbound.Result): TheatreState {
        val id = message.id
        val request = if (id != null) state.outstanding[id] else null
        if (request == null || id == null) {
            // Unknown id (late answer after a timeout, or another client): trust a self-describing result only.
            val feature = message.feature ?: return state
            val value = message.value ?: return state
            if (message.isAck || message.isRefusal) return state
            return state.copy(confirmed = state.confirmed + (feature to value), silent = state.silent - feature)
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
                )
            }
        }
        val pendingIsThis = state.pending[request.feature]?.requestId == id
        return if (message.isAck) {
            val confirmedValue = request.value ?: return state.copy(outstanding = outstanding)
            state.copy(
                outstanding = outstanding,
                confirmed = state.confirmed + (request.feature to confirmedValue),
                pending = if (pendingIsThis) state.pending - request.feature else state.pending,
            )
        } else {
            state.copy(
                outstanding = outstanding,
                pending = if (pendingIsThis) state.pending - request.feature else state.pending,
                lastError = "${request.feature}: ${message.value ?: "refused"}",
            )
        }
    }

    private fun tick(state: TheatreState, now: Long): TheatreState {
        val expired = state.outstanding.filterValues { it.deadline <= now }
        if (expired.isEmpty()) return state
        val expiredPending = state.pending.filterValues { it.requestId in expired.keys }
        val unanswered = expired.values.filter { !it.isSet && it.feature !in state.confirmed }.map { it.feature }
        return state.copy(
            outstanding = state.outstanding - expired.keys,
            silent = state.silent + unanswered,
            pending = state.pending - expiredPending.keys,
            lastError = if (expiredPending.isEmpty()) state.lastError else "timeout: ${expiredPending.keys.joinToString()}",
        )
    }
}

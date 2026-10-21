package io.kud.tutti.protocol.theatre

/** An optimistic value shown before the bar confirms it. */
data class Pending(val requestId: Int, val value: String, val deadline: Long)

data class Outstanding(val feature: String, val isSet: Boolean, val value: String?, val deadline: Long)

/**
 * Soundbar state as the UI sees it: [confirmed] is what the bar last reported,
 * [pending] what the user just asked for. [value] prefers the pending one, so the UI
 * moves within a frame and rolls back if the bar refuses or stays silent.
 */
data class TheatreState(
    val connected: Boolean = false,
    val confirmed: Map<String, String> = emptyMap(),
    val pending: Map<String, Pending> = emptyMap(),
    val outstanding: Map<Int, Outstanding> = emptyMap(),
    val lastError: String? = null,
) {
    fun value(key: String): String? = pending[key]?.value ?: confirmed[key]

    fun value(feature: TheatreFeature): String? = value(feature.key)

    fun isPending(feature: TheatreFeature): Boolean = pending.containsKey(feature.key)

    fun isOn(feature: TheatreFeature): Boolean? {
        val kind = feature.kind as? FeatureKind.Toggle ?: return null
        return when (value(feature)) {
            kind.on -> true
            kind.off -> false
            else -> null
        }
    }

    fun level(feature: TheatreFeature): Int? = value(feature)?.toIntOrNull()
}

sealed interface TheatreEvent {
    data class Connected(val connected: Boolean) : TheatreEvent

    /** Dispatch BEFORE writing to the socket, so the UI never waits on the network. */
    data class Sent(val request: TheatreOutbound, val now: Long, val timeoutMillis: Long) : TheatreEvent

    data class SendFailed(val requestId: Int, val reason: String) : TheatreEvent

    data class Received(val message: TheatreInbound) : TheatreEvent

    data class Tick(val now: Long) : TheatreEvent
}

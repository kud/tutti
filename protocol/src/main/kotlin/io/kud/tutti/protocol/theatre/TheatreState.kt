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
    /** Features whose `get` the bar refused (`ERR`/`NAK`): unsupported on this model or firmware. */
    val refused: Set<String> = emptySet(),
    /** Features whose last `get` this session met silence (no answer before the timeout). */
    val silent: Set<String> = emptySet(),
    /**
     * Values the bar refused (`ERR`/`NAK`) or never answered when tutti tried to set them, per
     * feature, kept for the session (Erwann, 2026-10-24: "afficher une erreur quand l'option ne
     * marche pas"). A value the bar later acknowledges or reports is cleared.
     */
    val refusedValues: Map<String, Set<String>> = emptyMap(),
) {
    fun refusedValues(feature: TheatreFeature): Set<String> = refusedValues[feature.key].orEmpty()

    /** Everyday features the bar has neither reported nor refused: what a refresh should ask again. Details are not re-asked. */
    fun missing(): List<TheatreFeature> =
        TheatreFeature.everyday.filter { it.key !in confirmed && it.key !in refused }

    /**
     * True once the bar has reported a value for [feature], by a `get` answer or a notify.
     * The probe gate for settings whose support varies by model: unanswered stays hidden.
     */
    fun supports(feature: TheatreFeature): Boolean = confirmed.containsKey(feature.key)

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

    /**
     * [isOn], but only while the bar is connected. [confirmed] outlives a drop on purpose (it is
     * the probe gate, and what a reconnect re-asks against), so a remembered value is not a
     * current one: anything that says what the bar is doing now (mute, power) reads this.
     */
    fun liveOn(feature: TheatreFeature): Boolean? = if (connected) isOn(feature) else null

    /** [level], but only while the bar is connected (see [liveOn]). */
    fun liveLevel(feature: TheatreFeature): Int? = if (connected) level(feature) else null

    /**
     * What a toggle press asks for: the opposite of the reported value, on when the bar is
     * connected but has not reported it (a pin for Night is pressed to turn Night on, and a
     * refusal rolls the optimistic value back). Null while the bar is not connected: there is
     * nothing to send to, so the press is refused rather than guessed.
     */
    fun toggleTarget(feature: TheatreFeature): Boolean? {
        if (!connected || feature.kind !is FeatureKind.Toggle) return null
        return isOn(feature) != true
    }
}

sealed interface TheatreEvent {
    data class Connected(val connected: Boolean) : TheatreEvent

    /** Dispatch BEFORE writing to the socket, so the UI never waits on the network. */
    data class Sent(val request: TheatreOutbound, val now: Long, val timeoutMillis: Long) : TheatreEvent

    data class SendFailed(val requestId: Int, val reason: String) : TheatreEvent

    data class Received(val message: TheatreInbound) : TheatreEvent

    data class Tick(val now: Long) : TheatreEvent
}

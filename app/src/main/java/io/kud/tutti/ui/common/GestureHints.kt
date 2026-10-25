package io.kud.tutti.ui.common

import io.kud.tutti.platform.data.HintMemory

object GestureHints {
    /**
     * True when hint [id] may show [today]: it names a registered gesture, has never shown, and
     * no other hint showed today (never two the same day).
     */
    fun mayShow(memory: HintMemory, id: String, today: Long, known: (String) -> Boolean = { Gestures.byId(it) != null }): Boolean =
        known(id) && id !in memory.shown && memory.lastDay != today

    /** The memory once hint [id] showed [today]. */
    fun shown(memory: HintMemory, id: String, today: Long): HintMemory =
        memory.copy(shown = memory.shown + id, lastDay = today)
}

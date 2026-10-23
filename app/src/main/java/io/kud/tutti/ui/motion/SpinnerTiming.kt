package io.kud.tutti.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The loading rules, as plain maths: no spinner before [Motion.SHORT] (a quick action never
 * flickers), and once shown it stays at least [Motion.LONG].
 */
object SpinnerTiming {
    const val SHOW_AFTER_MS = Motion.SHORT.toLong()
    const val MIN_VISIBLE_MS = Motion.LONG.toLong()

    /** Whether the spinner shows [elapsedMs] after the wait began, while still waiting. */
    fun visibleAt(elapsedMs: Long): Boolean = elapsedMs >= SHOW_AFTER_MS

    /**
     * The wait ended [finishedAtMs] after it began: how much longer the waiting look must hold
     * before the outcome shows. Zero when the spinner never appeared.
     */
    fun holdAfter(finishedAtMs: Long): Long =
        if (!visibleAt(finishedAtMs)) 0 else (SHOW_AFTER_MS + MIN_VISIBLE_MS - finishedAtMs).coerceAtLeast(0)
}

/**
 * Follows [waiting] through [SpinnerTiming]: [Waiting.held] stays true until the minimum spinner
 * time is over, [Waiting.spinner] turns on only after the grace delay.
 */
class Waiting internal constructor() {
    var held by mutableStateOf(false)
        internal set
    var spinner by mutableStateOf(false)
        internal set
}

@Composable
fun rememberWaiting(waiting: Boolean, clock: () -> Long = System::currentTimeMillis): Waiting {
    val state = remember { Waiting() }
    var since by remember { mutableLongStateOf(0L) }
    LaunchedEffect(waiting) {
        if (waiting) {
            if (!state.held) {
                since = clock()
                state.held = true
                state.spinner = false
            }
            val left = SpinnerTiming.SHOW_AFTER_MS - (clock() - since)
            if (left > 0) delay(left)
            state.spinner = true
        } else if (state.held) {
            delay(SpinnerTiming.holdAfter(clock() - since))
            state.held = false
            state.spinner = false
        }
    }
    return state
}

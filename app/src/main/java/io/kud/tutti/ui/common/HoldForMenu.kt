package io.kud.tutti.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A long press on a control that keeps its own tap (a segment, a tile, a Material button):
 * discoverability rules 1 and 5 in one modifier. The ring sweeps [shape]'s edge from
 * [RING_DELAY_MS] to the long-press timeout, like the round keys'; at the timeout [onLongPress]
 * runs and the rest of the gesture is swallowed, so the child's own click never fires on release.
 * A finger that moves past the touch slop is a scroll, never a long press. TalkBack gets the same
 * thing as a [CustomAccessibilityAction] named [label] (what it does, never "long press").
 *
 * [enabled] false (the device's link is down, design.md): the press still answers, by [onRefused]
 * (a bump), and the ring never starts. Watches the Initial pass and consumes nothing until it fires.
 */
@Composable
fun Modifier.holdForMenu(
    label: String,
    shape: Shape,
    enabled: Boolean = true,
    onRefused: () -> Unit = {},
    onLongPress: () -> Unit,
): Modifier {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val reduced = LocalReducedMotion.current
    val color = MaterialTheme.colorScheme.onSurface
    val fire by rememberUpdatedState(onLongPress)
    val refuse by rememberUpdatedState(onRefused)
    val on by rememberUpdatedState(enabled)
    return this
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(label) {
                    if (on) fire() else refuse()
                    true
                },
            )
        }
        .longPressRing({ progress.value }, shape, color)
        .pointerInput(timeout, reduced) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var sweep: Job? = null
                if (on) {
                    sweep = scope.launch {
                        progress.snapTo(0f)
                        delay(RING_DELAY_MS)
                        val rest = (timeout - RING_DELAY_MS).coerceAtLeast(1L)
                        if (reduced) {
                            delay(rest)
                            progress.snapTo(1f)
                        } else {
                            progress.animateTo(1f, tween(rest.toInt(), easing = LinearEasing))
                        }
                    }
                }
                var ended = false
                withTimeoutOrNull(timeout) {
                    while (!ended) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || change.isConsumed || (change.position - down.position).getDistance() > slop) ended = true
                    }
                }
                sweep?.cancel()
                if (!ended) {
                    if (on) fire() else refuse()
                    // Swallow the rest: the release must not also tap the control.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
                scope.launch { progress.animateTo(0f, tween(Motion.FADE_OUT)) }
            }
        }
}

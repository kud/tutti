package io.kud.tutti.ui.common

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a short row or column whose items all sit in one parent. Each item reports
 * its centre; a drag moves the item under the finger, and on release it lands on the item whose
 * centre is nearest to where it was dropped. Nothing moves until the finger lifts, so a slip
 * changes nothing. Dragging is the quick path; [reorderActions] gives TalkBack the same moves.
 */
class ReorderState internal constructor() {
    internal val centres = mutableStateMapOf<Int, Float>()
    var dragging by mutableIntStateOf(-1)
        internal set
    internal var offset by mutableFloatStateOf(0f)

    internal fun target(from: Int): Int {
        val here = (centres[from] ?: return from) + offset
        return centres.minByOrNull { (_, centre) -> kotlin.math.abs(centre - here) }?.key ?: from
    }
}

@Composable
fun rememberReorderState(): ReorderState = remember { ReorderState() }

/**
 * Makes item [index] draggable along one axis. [afterLongPress] waits for a hold first, for lists
 * inside something that scrolls the same way.
 */
@Composable
fun Modifier.reorderable(
    state: ReorderState,
    index: Int,
    vertical: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    afterLongPress: Boolean = false,
): Modifier {
    val move by rememberUpdatedState(onMove)
    val currentIndex by rememberUpdatedState(index)
    val lifted = state.dragging == index
    return this
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInParent()
            state.centres[index] = if (vertical) bounds.center.y else bounds.center.x
        }
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            if (lifted) {
                if (vertical) translationY = state.offset else translationX = state.offset
                scaleX = 1.06f
                scaleY = 1.06f
            }
        }
        .pointerInput(state, afterLongPress, vertical) {
            val start: (androidx.compose.ui.geometry.Offset) -> Unit = {
                state.dragging = currentIndex
                state.offset = 0f
            }
            val end: () -> Unit = {
                val from = state.dragging
                if (from >= 0) {
                    val to = state.target(from)
                    if (to != from) move(from, to)
                }
                state.dragging = -1
                state.offset = 0f
            }
            val cancel: () -> Unit = {
                state.dragging = -1
                state.offset = 0f
            }
            if (afterLongPress) {
                detectDragGesturesAfterLongPress(onDragStart = start, onDragEnd = end, onDragCancel = cancel) { change, amount ->
                    change.consume()
                    state.offset += if (vertical) amount.y else amount.x
                }
            } else if (vertical) {
                detectVerticalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = cancel) { change, amount ->
                    change.consume()
                    state.offset += amount
                }
            } else {
                // One axis only, so a vertical swipe over the item still scrolls the page.
                detectHorizontalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = cancel) { change, amount ->
                    change.consume()
                    state.offset += amount
                }
            }
        }
}

/** "Move left / right" (or up / down) as TalkBack actions, the accessible twin of the drag. */
fun Modifier.reorderActions(
    index: Int,
    count: Int,
    earlier: String,
    later: String,
    onMove: (from: Int, to: Int) -> Unit,
): Modifier = semantics {
    customActions = buildList {
        if (index > 0) add(CustomAccessibilityAction(earlier) { onMove(index, index - 1); true })
        if (index < count - 1) add(CustomAccessibilityAction(later) { onMove(index, index + 1); true })
    }
}

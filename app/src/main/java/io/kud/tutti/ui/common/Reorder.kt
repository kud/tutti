package io.kud.tutti.ui.common

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a short row or column whose items all sit in one parent. Each item reports
 * its centre; a drag moves the item under the finger, and on release it lands on the item whose
 * centre is nearest to where it was dropped. Nothing moves until the finger lifts, so a slip
 * changes nothing. Dragging is the quick path; [reorderActions] gives TalkBack the same moves.
 *
 * The lift (Iris's interaction spec): the item rises to 1.06 with a shadow and the slot it left
 * shows as a dashed outline; a `tick` for every slot it crosses, `select` when a drop reorders.
 * Reduced motion drops the scale only: the item still follows the finger, since that is the
 * manipulation itself, not decoration.
 */
class ReorderState internal constructor() {
    internal val centres = mutableStateMapOf<Int, Float>()
    var dragging by mutableIntStateOf(-1)
        internal set
    internal var offset by mutableFloatStateOf(0f)

    /** The slot the lifted item would land on now; a change is a slot crossed. */
    internal var over = -1

    internal fun target(from: Int): Int {
        val here = (centres[from] ?: return from) + offset
        return centres.minByOrNull { (_, centre) -> kotlin.math.abs(centre - here) }?.key ?: from
    }
}

@Composable
fun rememberReorderState(): ReorderState = remember { ReorderState() }

/**
 * Makes item [index] draggable along one axis. [afterLongPress] waits for a hold first, for lists
 * inside something that scrolls the same way. [shape] is the item's own, for the shadow and the
 * dashed slot it leaves behind.
 */
@Composable
fun Modifier.reorderable(
    state: ReorderState,
    index: Int,
    vertical: Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    afterLongPress: Boolean = false,
    shape: Shape = RoundedCornerShape(16.dp),
): Modifier {
    val move by rememberUpdatedState(onMove)
    val currentIndex by rememberUpdatedState(index)
    val feedback by rememberUpdatedState(rememberFeedback())
    val reduced = LocalReducedMotion.current
    val slot = MaterialTheme.colorScheme.outline
    val lifted = state.dragging == index
    return this
        .onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInParent()
            state.centres[index] = if (vertical) bounds.center.y else bounds.center.x
        }
        .zIndex(if (lifted) 1f else 0f)
        // Drawn before the lift's translation, so it stays where the item was.
        .drawBehind {
            if (lifted) {
                val width = 1.5.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                drawPath(shape.createOutline(size, layoutDirection, this).asPath(), slot, style = Stroke(width, pathEffect = dash))
            }
        }
        .graphicsLayer {
            if (lifted) {
                if (vertical) translationY = state.offset else translationX = state.offset
                if (!reduced) {
                    scaleX = LIFT_SCALE
                    scaleY = LIFT_SCALE
                }
                this.shape = shape
                shadowElevation = 8.dp.toPx()
            }
        }
        .pointerInput(state, afterLongPress, vertical) {
            val start: (androidx.compose.ui.geometry.Offset) -> Unit = {
                state.dragging = currentIndex
                state.offset = 0f
                state.over = currentIndex
            }
            val follow: (Float) -> Unit = { amount ->
                state.offset += amount
                val over = state.target(state.dragging)
                if (over != state.over) {
                    state.over = over
                    feedback.tick()
                }
            }
            val end: () -> Unit = {
                val from = state.dragging
                if (from >= 0) {
                    val to = state.target(from)
                    if (to != from) {
                        feedback.select()
                        move(from, to)
                    }
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
                    follow(if (vertical) amount.y else amount.x)
                }
            } else if (vertical) {
                detectVerticalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = cancel) { change, amount ->
                    change.consume()
                    follow(amount)
                }
            } else {
                // One axis only, so a vertical swipe over the item still scrolls the page.
                detectHorizontalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = cancel) { change, amount ->
                    change.consume()
                    follow(amount)
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

private const val LIFT_SCALE = 1.06f

package io.kud.tutti.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.motion.snappyOrFade
import kotlin.math.abs

/**
 * Caps a sheet's content so the whole sheet, drag handle included, stops below the status bar.
 * The sheet itself may span the window (edge to edge), so its content is told it has [topPx] less
 * room than offered; anything taller scrolls inside the sheet instead of pushing its top off screen.
 */
fun Modifier.belowStatusBar(topPx: Int): Modifier = layout { measurable, constraints ->
    val maxHeight = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight - topPx).coerceAtLeast(constraints.minHeight)
    } else {
        constraints.maxHeight
    }
    val placeable = measurable.measure(constraints.copy(maxHeight = maxHeight))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * Every modal sheet in tutti: opens fully expanded, grows up to just under the status bar and no
 * further, and leaves scrolling to its content (a `verticalScroll` or a lazy list), whose nested
 * scroll still hands a downward drag at the top back to the sheet. It carries its own
 * [MessageHost], since a message from the app root would draw under the sheet's window.
 *
 * The bars are read here, in the activity's window, and the sheet's own insets are switched off so
 * the two never add up: left to its defaults, the Apps sheet put its handle under the clock on
 * Erwann's phone (2026-10-23).
 *
 * The handle answers a drag (Iris's interaction spec): it widens 32 → 48 dp and goes to full ink
 * while a finger has moved the sheet. There is no top-stop bump: the sheet opens already at its
 * highest, so no drag can reach it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuttiSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val grip = remember { SheetGrip() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { SheetHandle(sheetState, grip) },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Box(Modifier.belowStatusBar(statusTop).grip(grip, sheetState)) {
            Column(
                Modifier
                    .padding(bottom = navigationBottom)
                    .consumeWindowInsets(PaddingValues(bottom = navigationBottom)),
                content = content,
            )
            // The sheet is a window above the app root's host: its messages show here, over it.
            MessageHost(Modifier.matchParentSize().padding(bottom = navigationBottom), insetNavigationBar = false)
        }
    }
}

/**
 * Where the sheet sat when a finger landed on it, or null with no finger down. Watched on the
 * initial pass and never consumed, so the sheet's own drag and the content's scroll are untouched.
 */
@Stable
private class SheetGrip {
    var downAt by mutableStateOf<Float?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
private fun Modifier.grip(grip: SheetGrip, sheetState: SheetState): Modifier = pointerInput(grip, sheetState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        grip.downAt = sheetState.offsetIfOpen()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.any { it.pressed })
        grip.downAt = null
    }
}

/** The handle: wider and in full ink while a finger has moved the sheet off where it was. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetHandle(sheetState: SheetState, grip: SheetGrip) {
    val slop = with(LocalDensity.current) { 1.dp.toPx() }
    val dragged by remember(sheetState, grip, slop) {
        derivedStateOf {
            val from = grip.downAt ?: return@derivedStateOf false
            val now = sheetState.offsetIfOpen() ?: return@derivedStateOf false
            abs(now - from) > slop
        }
    }
    val width by animateDpAsState(if (dragged) 48.dp else 32.dp, snappyOrFade(), label = "handle")
    val color by animateColorAsState(
        if (dragged) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        snappyOrFade(),
        label = "handleInk",
    )
    BottomSheetDefaults.DragHandle(modifier = Modifier.grip(grip, sheetState), width = width, color = color)
}

@OptIn(ExperimentalMaterial3Api::class)
private fun SheetState.offsetIfOpen(): Float? =
    if (currentValue == SheetValue.Expanded) runCatching { requireOffset() }.getOrNull() else null

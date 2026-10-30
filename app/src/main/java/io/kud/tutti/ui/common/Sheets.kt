package io.kud.tutti.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.snappyOrFade
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * further, and leaves scrolling to its content (a `verticalScroll` or a lazy list) under a
 * [PinnedHeader], which keeps every drag below the header for the body. It carries its own
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
    CoversRemote()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // The remote goes clearly behind, not half-lit: Material's 32 % dim left Power's "on" fill a
        // muted teal that read as disabled (Erwann's dev screenshot, 2026-10-30). Android's own window
        // dim (60 %) puts everything above the sheet in shadow alike, so nothing there reads as a state.
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = SHEET_SCRIM_ALPHA),
        dragHandle = { SheetHandle(sheetState, grip) },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        // The keyboard pads only the part of the sheet it actually covers, measured: the sheet may
        // already end above it, and padding by the whole inset then counted it twice, which crushed
        // the picker to its title and an empty search (Erwann, 2026-10-28, after dev.37). Read inside
        // the sheet's own window, whose inset also holds the navigation bar.
        val ime = WindowInsets.ime.getBottom(density)
        val windowHeight = LocalView.current.rootView.height
        var sheetBottom by remember { mutableIntStateOf(0) }
        val covered = if (ime > 0 && sheetBottom > 0) (sheetBottom - (windowHeight - ime)).coerceAtLeast(0) else 0
        val bottom = if (ime > 0) with(density) { covered.toDp() } else navigationBottom
        Box(
            Modifier
                .belowStatusBar(statusTop)
                .grip(grip, sheetState)
                .onGloballyPositioned { sheetBottom = (it.positionInWindow().y + it.size.height).roundToInt() },
        ) {
            Column(
                Modifier
                    .padding(bottom = bottom)
                    .consumeWindowInsets(PaddingValues(bottom = bottom)),
                content = content,
            )
            // The sheet is a window above the app root's host: its messages show here, over it.
            MessageHost(Modifier.matchParentSize().padding(bottom = bottom), insetNavigationBar = false)
        }
    }
}

/**
 * A sheet's pinned header (design.md, Sheets: "the title, and a search where there is one, stays
 * put; only the body scrolls"). [header] is drawn above [body], which takes the room left and
 * scrolls on its own; once it has scrolled ([scrolled]), a hairline shows under the header.
 *
 * Only the top moves the sheet (Erwann, 2026-10-25: a drag between the tiles slid the whole Apps
 * sheet when he meant to scroll). Below the header nothing reaches the sheet's own drag: the body
 * keeps a pull-down at its top to itself ([KeepPullDown]), and a vertical drag that no scrollable
 * took, in a gap or under a short body, is swallowed ([holdBelow]). The handle and the header
 * still drag it; the scrim, back and the handle's TalkBack actions still close it. Upward drags
 * are untouched, so the Son sheet's peek still opens from anywhere on it.
 *
 * A slot inside the sheet's content rather than a parameter of [TuttiSheet]: the header's state
 * (the picker's search, its chips) belongs to the content, and the Son sheet, which is not a
 * modal sheet, wears the same header.
 */
@Composable
fun PinnedHeader(
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    var headerBottom by remember { mutableIntStateOf(0) }
    Column(modifier.holdBelow { headerBottom }) {
        Column(Modifier.onSizeChanged { headerBottom = it.height }) {
            header()
            SheetHeaderRule(scrolled)
        }
        Column(Modifier.weight(1f, fill = false).nestedScroll(KeepPullDown), content = body)
    }
}

/**
 * Keeps what the body's scroll leaves of a downward drag or fling (the pull-down at its top) from
 * reaching the sheet, which would slide it shut. Upward leftovers pass, as before.
 */
private object KeepPullDown : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (available.y > 0f) Velocity(0f, available.y) else Velocity.Zero
}

/**
 * Below [top] (px from this node's top), takes any vertical drag that no child took once it passes
 * touch slop, so the sheet's own drag, which waits on the same slop further out, never starts there.
 * Taps and horizontal drags are left alone; a drag starting above [top] is the header's, the sheet's.
 */
private fun Modifier.holdBelow(top: () -> Int): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.y < top()) return@awaitEachGesture
        val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
            ?: return@awaitEachGesture
        verticalDrag(drag.id) { it.consume() }
    }
}

/**
 * The hairline under a pinned header, in `outlineVariant`, shown only once the body has scrolled.
 * It fades by opacity, which is also its reduced-motion form.
 */
@Composable
fun SheetHeaderRule(scrolled: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (scrolled) 1f else 0f, Motion.fade(), label = "headerRule")
    HorizontalDivider(
        modifier.graphicsLayer { this.alpha = alpha },
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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

/** A modal sheet's scrim: Android's own window dim (`backgroundDimAmount`), never Material's 32 %. */
internal const val SHEET_SCRIM_ALPHA = 0.6f

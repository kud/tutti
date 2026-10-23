package io.kud.tutti.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity

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
 * scroll still hands a downward drag at the top back to the sheet.
 *
 * The bars are read here, in the activity's window, and the sheet's own insets are switched off so
 * the two never add up: left to its defaults, the Apps sheet put its handle under the clock on
 * Erwann's phone (2026-10-23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuttiSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .belowStatusBar(statusTop)
                .padding(bottom = navigationBottom)
                .consumeWindowInsets(PaddingValues(bottom = navigationBottom)),
            content = content,
        )
    }
}

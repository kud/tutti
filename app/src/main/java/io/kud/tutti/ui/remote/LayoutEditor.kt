package io.kud.tutti.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.PadSize
import io.kud.tutti.platform.data.RemoteLayout
import io.kud.tutti.platform.data.RowButton
import io.kud.tutti.ui.common.TonalRoundButton
import io.kud.tutti.ui.common.reorderActions
import io.kud.tutti.ui.common.reorderable
import io.kud.tutti.ui.common.rememberReorderState
import io.kud.tutti.ui.common.slotLabel

/**
 * The remote in edit mode: the same screen from top to bottom, with what he may arrange in a
 * dashed frame and what never moves under a lock. Every change is saved as it is made, so
 * "Done" only leaves; "Reset" asks first. Pins open their own editor on tap.
 */
@Composable
fun LayoutEditor(
    state: TuttiUiState,
    onLayout: ((RemoteLayout) -> RemoteLayout) -> Unit,
    onReset: () -> Unit,
    onMovePin: (from: Int, to: Int) -> Unit,
    onEditPin: (index: Int) -> Unit,
    onAddPage: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.prefs.layout
    val colors = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }
    val fixed = stringResource(R.string.layout_fixed)
    val volumeFixed = stringResource(R.string.layout_volume) + " · " + fixed

    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.layout_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onDone) { Text(stringResource(R.string.layout_done)) }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // Anchors: the chip and Power, shown as they are, under a lock.
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SystemChip(state, onClick = {}, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Locked(stringResource(R.string.action_power) + " · " + fixed, size = 52.dp)
            }

            Spacer(Modifier.height(8.dp))
            ScenesFrame(state, layout.scenes, onToggle = { onLayout { it.copy(scenes = !it.scenes) } })

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(220.dp)) {
                PadFrame(layout.padSize, onSize = { size -> onLayout { it.copy(padSize = size) } }, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .width(52.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(26.dp))
                        .background(colors.surfaceContainerHigh)
                        .semantics { contentDescription = volumeFixed },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = colors.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
            RowFrame(layout, onLayout)

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.layout_row_add, RemoteLayout.MAX_ROW),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(Modifier.height(6.dp))
            val full = layout.shownRow.size >= RemoteLayout.MAX_ROW
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                layout.addable.forEach { button ->
                    AssistChip(
                        onClick = { onLayout { it.withAdded(button) } },
                        enabled = !full,
                        label = { Text(rowButtonLabel(button)) },
                        leadingIcon = {
                            Icon(painterResource(rowButtonIcon(button)), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                        },
                    )
                }
            }
            if (full) {
                Text(
                    stringResource(R.string.layout_row_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            PinsFrame(state, onMovePin, onEditPin, onAddPage)

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.layout_pins_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmReset = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.layout_reset), color = colors.error, fontWeight = FontWeight.Medium)
            }
            Text(
                stringResource(R.string.layout_anchors),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.layout_reset_title)) },
            text = { Text(stringResource(R.string.layout_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text(stringResource(R.string.layout_reset_confirm), color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** What he may change wears this: a dashed outline in the accent. */
@Composable
private fun Modifier.dashedFrame(): Modifier {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    return drawBehind {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(20.dp.toPx()),
            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
        )
    }
}

@Composable
private fun Locked(description: String, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScenesFrame(state: TuttiUiState, shown: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val names = state.scenes.joinToString(" · ") { it.scene.name }
    val hidden = stringResource(R.string.layout_scenes_hidden)
    Row(
        Modifier.fillMaxWidth().dashedFrame().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.layout_scenes),
            style = MaterialTheme.typography.titleMedium,
            color = if (shown) colors.onSurface else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (shown) names else hidden,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconToggleButton(
            checked = shown,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics { stateDescription = if (shown) names.ifEmpty { "" } else hidden },
        ) {
            Icon(
                painterResource(if (shown) R.drawable.ic_visibility else R.drawable.ic_visibility_off),
                contentDescription = stringResource(if (shown) R.string.layout_scenes_hide else R.string.layout_scenes_show),
                tint = if (shown) colors.primary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PadFrame(size: PadSize, onSize: (PadSize) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // The pad drawn at its chosen share of the frame, so the three steps read as sizes.
    val share = when (size) {
        PadSize.COMPACT -> 0.78f
        PadSize.NORMAL -> 0.9f
        PadSize.LARGE -> 1f
    }
    Box(modifier.dashedFrame().padding(8.dp), contentAlignment = Alignment.BottomCenter) {
        // The pad and, under it, its mode switch: both move and grow together, as on the remote.
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (share < 1f) Spacer(Modifier.weight(1f - share))
            Box(
                Modifier
                    .fillMaxWidth(share)
                    .weight(share)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surfaceContainer),
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .size(width = 62.dp, height = 22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.surfaceContainerLow)
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(11.dp))
                    .padding(3.dp),
            ) {
                Box(Modifier.fillMaxHeight().width(27.dp).clip(RoundedCornerShape(8.dp)).background(colors.primaryContainer))
            }
        }
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.layout_pad), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow {
                PadSize.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = entry == size,
                        onClick = { onSize(entry) },
                        shape = SegmentedButtonDefaults.itemShape(index, PadSize.entries.size),
                        icon = {},
                    ) {
                        Text(
                            stringResource(
                                when (entry) {
                                    PadSize.COMPACT -> R.string.layout_pad_compact
                                    PadSize.NORMAL -> R.string.layout_pad_normal
                                    PadSize.LARGE -> R.string.layout_pad_large
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.layout_pad_size), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun RowFrame(layout: RemoteLayout, onLayout: ((RemoteLayout) -> RemoteLayout) -> Unit) {
    val row = layout.shownRow
    val reorder = rememberReorderState()
    val left = stringResource(R.string.layout_move_left)
    val right = stringResource(R.string.layout_move_right)
    val move: (Int, Int) -> Unit = { from, to -> onLayout { it.withMoved(from, to) } }
    Box(Modifier.fillMaxWidth().dashedFrame().padding(horizontal = 8.dp, vertical = 14.dp)) {
        if (row.isEmpty()) {
            Text(
                stringResource(R.string.layout_row_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(vertical = 16.dp),
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = rowArrangement(row.size)) {
                row.forEachIndexed { index, button ->
                    val label = rowButtonLabel(button)
                    Box(
                        Modifier
                            .reorderable(reorder, index, vertical = false, onMove = move, shape = CircleShape)
                            .reorderActions(index, row.size, left, right, move),
                    ) {
                        // Shown, not live: a tap here never reaches the TV.
                        TonalRoundButton(icon = rowButtonIcon(button), contentDescription = label, onClick = {}, size = 48.dp)
                        RemoveBadge(
                            description = stringResource(R.string.layout_row_remove, label),
                            onClick = { onLayout { it.withRemoved(button) } },
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoveBadge(description: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    // A 24dp mark inside a 32dp touch target.
    IconButton(onClick = onClick, modifier = modifier.size(32.dp)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(colors.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = description, tint = colors.onErrorContainer, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PinsFrame(
    state: TuttiUiState,
    onMovePin: (from: Int, to: Int) -> Unit,
    onEditPin: (index: Int) -> Unit,
    onAddPage: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pages = state.dock.pages
    var page by remember { mutableIntStateOf(0) }
    if (page > pages.lastIndex) page = pages.lastIndex
    val reorder = rememberReorderState()
    val left = stringResource(R.string.layout_move_left)
    val right = stringResource(R.string.layout_move_right)
    val base = page * DockConfig.PAGE_SIZE
    // Offsets within the page, turned into flat indices for the dock.
    val move: (Int, Int) -> Unit = { from, to -> onMovePin(base + from, base + to) }

    Column(Modifier.fillMaxWidth().dashedFrame().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.layout_pins, page + 1, pages.size),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (pages.size > 1) {
                IconButton(onClick = { page-- }, enabled = page > 0) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = stringResource(R.string.layout_move_left))
                }
                IconButton(onClick = { page++ }, enabled = page < pages.lastIndex) {
                    Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = stringResource(R.string.layout_move_right))
                }
            }
            if (pages.size < DockConfig.MAX_PAGES) {
                TextButton(onClick = onAddPage) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.layout_pins_add_page))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pages[page].forEachIndexed { offset, slot ->
                val label = slotLabel(slot, state) ?: stringResource(R.string.dock_add)
                Column(
                    Modifier
                        .weight(1f)
                        .reorderable(reorder, offset, vertical = false, onMove = move, shape = RoundedCornerShape(20.dp))
                        .reorderActions(offset, DockConfig.PAGE_SIZE, left, right, move),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surfaceContainerHigh)
                            .clickable { onEditPin(base + offset) }
                            .semantics { contentDescription = label }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PinFace(slot.primary, slot.icon, slot.letters, state, colors.onSurfaceVariant, iconSize = 20.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        painterResource(R.drawable.ic_drag),
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

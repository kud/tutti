package io.kud.tutti.ui.remote

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import io.kud.tutti.ui.common.rememberHaptics
import kotlin.math.roundToInt
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
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
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.DockLabels
import io.kud.tutti.ui.common.PinNameOnly
import io.kud.tutti.ui.common.pinDress
import io.kud.tutti.platform.data.Hand
import io.kud.tutti.platform.data.RemoteLayout
import io.kud.tutti.platform.data.RowButton
import io.kud.tutti.ui.common.TonalRoundButton
import io.kud.tutti.ui.common.reorderActions
import io.kud.tutti.ui.common.reorderable
import io.kud.tutti.ui.common.rememberReorderState
import io.kud.tutti.ui.common.slotLabel

/**
 * The remote in edit mode: the same screen from top to bottom, with what he may arrange in a
 * dashed frame, what never moves drawn as itself but dimmed and locked ([Fixed]), and the
 * options below a rule. Every change is saved as it is made, so
 * "Done" only leaves; "Reset" asks first. Pins open their own editor on tap.
 */
@Composable
fun LayoutEditor(
    state: TuttiUiState,
    onLayout: ((RemoteLayout) -> RemoteLayout) -> Unit,
    onReset: () -> Unit,
    onSwapPin: (from: Int, to: Int) -> Unit,
    onRemovePin: (index: Int) -> Unit,
    onPutBackPin: (index: Int, slot: DockSlot) -> Unit,
    onEditPin: (index: Int) -> Unit,
    onKind: (index: Int, kind: PinKind) -> Unit,
    onRestoreSpare: (index: Int) -> Unit,
    onDropSpare: (index: Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = state.prefs.layout
    val colors = MaterialTheme.colorScheme
    var confirmReset by remember { mutableStateOf(false) }
    val volumeName = stringResource(R.string.layout_volume)

    // The dock's frame draws above its neighbours while a pin is lifted, so the pin shows wherever it is dragged.
    var lifting by remember { mutableStateOf(false) }
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
            // Anchors: the chip and Power drawn as themselves, dimmed and untouchable (Erwann, 2026-10-30).
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Fixed(stringResource(R.string.layout_status), Modifier.weight(1f)) {
                    SystemChip(state, onClick = {}, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(POWER_GAP_DP.dp))
                Fixed(stringResource(R.string.action_power)) {
                    TonalRoundButton(
                        icon = R.drawable.ic_power,
                        contentDescription = stringResource(R.string.action_power),
                        onClick = {},
                        active = state.powered == true,
                        size = 52.dp,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            ScenesFrame(state, layout.scenes, onToggle = { onLayout { it.copy(scenes = !it.scenes) } })

            Spacer(Modifier.height(8.dp))
            // The pad and the rail as they stand for the chosen hand: the pad under the thumb.
            val padOnRight = layout.hand.padOnRight
            Row(Modifier.fillMaxWidth().height(220.dp).semantics { isTraversalGroup = true }) {
                val pad = @Composable {
                    PadFrame(
                        hand = layout.hand,
                        onHand = { hand -> onLayout { it.copy(hand = hand) } },
                        modifier = Modifier.weight(1f).fillMaxHeight().semantics { traversalIndex = 0f },
                    )
                }
                val rail = @Composable {
                    Fixed(volumeName, Modifier.fillMaxHeight().semantics { traversalIndex = 1f }) {
                        VolumeRail(
                            volume = state.volume,
                            cap = state.volumeCap,
                            muted = state.muted,
                            enabled = state.bar.connected,
                            onVolume = {},
                            onStep = {},
                            onMute = {},
                            guardLine = state.volumeGuardLine,
                            landmarks = state.volumeLandmarks,
                        )
                    }
                }
                if (padOnRight) {
                    rail()
                    Spacer(Modifier.width(12.dp))
                    pad()
                } else {
                    pad()
                    Spacer(Modifier.width(12.dp))
                    rail()
                }
            }

            Spacer(Modifier.height(12.dp))
            PinsFrame(
                state = state,
                onSwap = onSwapPin,
                onRemove = onRemovePin,
                onPutBack = onPutBackPin,
                onEdit = onEditPin,
                onKind = onKind,
                onLifting = { lifting = it },
                modifier = Modifier.zIndex(if (lifting) 2f else 0f),
            )
            if (state.dock.spare.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SpareFrame(state, onRestoreSpare, onDropSpare)
            }


            // Options, not layout (Erwann, 2026-10-30): plain rows under a rule, never a dashed frame.
            Spacer(Modifier.height(20.dp))
            OptionsSection(
                labels = layout.dockLabels,
                onLabels = { labels -> onLayout { it.copy(dockLabels = labels) } },
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

/**
 * A piece of the layout he can move or change in place wears this: a dashed outline in the accent.
 * Options that only set how things behave or dress (the hand, what pins show) never do: they are
 * plain rows in [OptionsSection] (Erwann, 2026-10-30: « c'est pas un layout donc il devrait pas
 * avoir de zone pointillée »).
 */
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

/**
 * What never moves, drawn as itself (Erwann, 2026-10-30: « on devrait montrer plutôt l'élément, mais
 * disabled à changer »): the real control at 45 % opacity, no dashed frame, a small lock on its top
 * corner so it isn't said by fading alone, and a cover that takes every touch. TalkBack hears one
 * node: the name, then "Can't be changed".
 */
@Composable
private fun Fixed(name: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fixedState = stringResource(R.string.layout_cannot_change)
    Box(
        modifier.clearAndSetSemantics {
            contentDescription = name
            stateDescription = fixedState
        },
    ) {
        Box(Modifier.alpha(0.45f)) { content() }
        // Over the control, so no tap, long press or drag reaches it.
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } })
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 4.dp, y = (-4).dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(12.dp))
        }
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
private fun PadFrame(hand: Hand, onHand: (Hand) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val switchOnLeft = hand.padOnRight
    val haptics = rememberHaptics()
    // The pad is a piece he moves (Erwann, 2026-10-30): dragged across the rail it trades sides with it.
    val threshold = with(LocalDensity.current) { SWAP_DRAG.toPx() }
    val reach = with(LocalDensity.current) { (RAIL_WIDTH + 12.dp).toPx() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val currentHand by rememberUpdatedState(hand)
    val drag = rememberDraggableState { delta ->
        val toward = if (currentHand.padOnRight) -1f else 1f
        // Free towards the rail, a little give the other way.
        dragX = (dragX + delta).let { if (toward > 0) it.coerceIn(-12f, reach) else it.coerceIn(-reach, 12f) }
        val past = padSideAfterDrag(currentHand, dragX, threshold) != currentHand
        if (past != armed) {
            armed = past
            haptics.tick()
        }
    }
    val moveLabel = stringResource(if (hand.padOnRight) R.string.layout_pad_move_left else R.string.layout_pad_move_right)
    Box(
        modifier
            .zIndex(1f)
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .draggable(
                drag,
                Orientation.Horizontal,
                onDragStopped = {
                    val next = padSideAfterDrag(currentHand, dragX, threshold)
                    dragX = 0f
                    armed = false
                    if (next != currentHand) {
                        haptics.confirm()
                        onHand(next)
                    }
                },
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(moveLabel) { onHand(currentHand.swapped); true }) }
            .dashedFrame()
            .padding(8.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The pad with its mode switch on the corner facing the rail, as on the remote (PadModeSwitch).
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surfaceContainer),
            ) {
                Icon(
                    painterResource(R.drawable.ic_pad_touchpad_outline),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier
                        .align(if (switchOnLeft) Alignment.TopStart else Alignment.TopEnd)
                        .padding(12.dp)
                        .size(18.dp),
                )
            }
        }
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.layout_pad), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            // Says the pad moves in words and a glyph, beside the size it can also take.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_drag), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.layout_pad_swap), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * What the dock's pins wear (Erwann, 2026-10-29): Icons, Both or Names, one segmented choice under
 * the dock it changes, so the pins above redress as he picks. Both is the default; the tile's size
 * never changes, only what sits in it ([PinDress]).
 */
@Composable
private fun DockLabelsRow(labels: DockLabels, onLabels: (DockLabels) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            stringResource(R.string.layout_dock_labels),
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // Three words, never glyphs alone: the choice is between words and glyphs.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DockLabels.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = entry == labels,
                    onClick = { onLabels(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, DockLabels.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (entry) {
                                DockLabels.ICON -> R.string.layout_dock_labels_icon
                                DockLabels.BOTH -> R.string.layout_dock_labels_both
                                DockLabels.NAME -> R.string.layout_dock_labels_name
                            },
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The options under the layout: a rule, a quiet heading, then plain settings rows with no frame,
 * so nothing here reads as a piece he could move. Pins show first, nearest the dock it redresses.
 */
@Composable
private fun OptionsSection(labels: DockLabels, onLabels: (DockLabels) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
        HorizontalDivider(Modifier.padding(horizontal = 4.dp), color = colors.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.layout_options),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Spacer(Modifier.height(4.dp))
        DockLabelsRow(labels, onLabels)
    }
}

/** How far the pad travels towards the rail before letting go swaps them: most of the rail's width. */
private val SWAP_DRAG = 48.dp

/**
 * The "−" that lets a pin go in one tap (Erwann, 2026-10-30: « J'aimais bien aussi les moins qu'on
 * avait avant »): a 22 dp mark, the minus glyph saying it rather than the colour, inside a touch
 * target of [target] (48 dp on the dock's places).
 */
@Composable
internal fun RemoveBadge(description: String, onClick: () -> Unit, modifier: Modifier, target: Dp = 32.dp) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = modifier.size(target)) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(colors.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = description, tint = colors.onErrorContainer, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Pins that didn't fit the twelve when the row joined the dock (2026-10-29), kept rather than lost:
 * a tap puts one back in the first free place, the badge lets it go.
 */
@Composable
private fun SpareFrame(state: TuttiUiState, onRestore: (Int) -> Unit, onDrop: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val full = state.dock.full
    Column(Modifier.fillMaxWidth().dashedFrame().padding(12.dp)) {
        Text(
            stringResource(R.string.layout_spare_title),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(if (full) R.string.layout_spare_full else R.string.layout_spare_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.dock.spare.forEachIndexed { index, slot ->
                val label = slotLabel(slot, state) ?: stringResource(R.string.dock_reassign)
                Box {
                    AssistChip(
                        onClick = { onRestore(index) },
                        enabled = !full,
                        label = { Text(label) },
                        leadingIcon = {
                            PinFace(slot.primary ?: slot.secondary, slot.icon, slot.letters, state, colors.onSurfaceVariant, iconSize = AssistChipDefaults.IconSize)
                        },
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                    )
                    RemoveBadge(
                        description = stringResource(R.string.layout_spare_drop, label),
                        onClick = { onDrop(index) },
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
                    )
                }
            }
        }
    }
}

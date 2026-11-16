package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.isSet
import io.kud.tutti.protocol.atv.KeyGroup
import io.kud.tutti.protocol.atv.NamedKey
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.PinNameOnly
import io.kud.tutti.ui.common.pinDress
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.common.slotLabel
import kotlin.math.roundToInt

/**
 * The kinds of thing a pin can be, listed under the dock in edit mode (Erwann, 2026-10-30: « partons
 * sur la liste de types », « à la Firefox avec Customize interface, sauf qu'on drag and drop des
 * types »). A kind never leaves the list and can be placed any number of times: dropped on a place,
 * it asks which one (the action picker for that kind alone). [STEPS] opens the pin's own editor.
 */
enum class PinKind(@StringRes val label: Int, @DrawableRes val icon: Int) {
    KEYS(R.string.layout_kind_keys, R.drawable.ic_dpad),
    APPS(R.string.layout_kind_apps, R.drawable.ic_apps),
    CHANNELS(R.string.layout_kind_channels, R.drawable.ic_dialpad),
    INPUTS(R.string.layout_kind_inputs, R.drawable.ic_input),
    SCENES(R.string.layout_kind_scenes, R.drawable.ic_scene),
    SOUND(R.string.layout_kind_sound, R.drawable.ic_surround_sound),
    TV(R.string.layout_kind_tv, R.drawable.ic_tv),
    STEPS(R.string.layout_kind_steps, R.drawable.ic_timer),
}

/** The keys the Channel kind offers: channel up, down and last, the guide, recordings and the digits. */
val NamedKey.isChannel: Boolean
    get() = group == KeyGroup.DIGITS || "CHANNEL" in name || name == "GUIDE" || name == "DVR"

/** What a finger holds in the editor: a pin lifted from place [from], or a [kind] from the list. */
private data class Lift(val from: Int? = null, val kind: PinKind? = null)

/**
 * The dock in edit mode with the list of kinds under it, on one page, as Firefox's toolbar over its
 * palette. Long press lifts (a plain swipe still scrolls the page); a pin dropped on another place
 * moves there, trading with what sits in it; dropped off the dock (on the list, or anywhere) it
 * leaves with an Undo; its "−" badge does the same in one tap. A kind dropped on a place asks which
 * one. Every drag has a no-drag path: tap a kind then a place, tap an empty place to choose a kind,
 * and TalkBack's "Place in…", "Move to…" and "Remove".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PinsFrame(
    state: TuttiUiState,
    onSwap: (from: Int, to: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onPutBack: (index: Int, slot: DockSlot) -> Unit,
    onEdit: (index: Int) -> Unit,
    onKind: (index: Int, kind: PinKind) -> Unit,
    onLifting: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    above: @Composable ColumnScope.() -> Unit = {},
    between: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val messages = LocalMessages.current
    val undo = stringResource(R.string.pin_undo)
    val pins = state.dock.pages.single()

    var lift by remember { mutableStateOf<Lift?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var hover by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<PinKind?>(null) }
    var moveFrom by remember { mutableStateOf<Int?>(null) }
    var placeKind by remember { mutableStateOf<PinKind?>(null) }
    var chooseFor by remember { mutableStateOf<Int?>(null) }
    var ghost by remember { mutableStateOf(Size.Zero) }
    val frame = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dock = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val places = remember { arrayOfNulls<LayoutCoordinates>(DockConfig.PAGE_SIZE) }

    fun box(c: LayoutCoordinates?) = frame[0]?.takeIf { it.isAttached }?.let { f -> c?.takeIf { it.isAttached }?.let { f.localBoundingBoxOf(it, false) } }
    fun placeUnder(p: Offset): Int? = places.indices.firstOrNull { box(places[it])?.contains(p) == true }
    fun onDock(p: Offset): Boolean = box(dock[0])?.contains(p) == true

    fun remove(index: Int, text: String) {
        val slot = pins[index]
        onRemove(index)
        messages?.show(text, actionLabel = undo, onAction = { onPutBack(index, slot) })
    }

    fun begin(l: Lift, at: Offset, size: Size) {
        haptics.longPress()
        selected = null
        lift = l
        pointer = at
        hover = l.from
        ghost = size
        onLifting(true)
    }

    fun drag(at: Offset) {
        pointer = at
        val now = placeUnder(at)
        if (now != hover) {
            hover = now
            if (now != null) haptics.tick()
        }
    }

    fun end(cancelled: Boolean, removedText: (Int) -> String) {
        val l = lift
        val over = hover
        lift = null
        hover = null
        onLifting(false)
        if (l == null || cancelled) return
        val from = l.from
        val kind = l.kind
        when {
            from != null -> when (val drop = dockDrop(from, over, onDock(pointer))) {
                is DockDrop.Move -> {
                    haptics.confirm()
                    onSwap(drop.from, drop.to)
                }
                is DockDrop.Remove -> {
                    haptics.confirm()
                    remove(drop.from, removedText(drop.from))
                }
                DockDrop.Stay -> if (over == null) haptics.bump()
            }
            kind != null && over != null -> {
                haptics.confirm()
                onKind(over, kind)
            }
            else -> haptics.bump()
        }
    }

    val labels = pins.map { slotLabel(it, state) }
    val removedTexts = labels.map { stringResource(R.string.layout_pin_removed, it ?: "") }
    val removedText: (Int) -> String = { removedTexts[it] }

    Box(modifier.fillMaxWidth().onGloballyPositioned { frame[0] = it }) {
        Column(Modifier.fillMaxWidth()) {
            // The screen: the remote as it will look, from the chip down to the dock, on its own
            // surface; the tools (kinds, hints, options) stay on the page around it (Erwann, 2026-10-30).
            StageHeading()
            Column(Modifier.fillMaxWidth().remoteStage(colors)) {
                above()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { dock[0] = it }
                        .dashedOutline(colors.onSurfaceVariant.copy(alpha = 0.7f), 20.dp, dashed = true, width = 1.5.dp)
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
                ) {
                    Text(
                        stringResource(R.string.layout_dock, DockConfig.PAGE_SIZE),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    pins.chunked(DockConfig.ROW).forEachIndexed { row, cells ->
                        if (row > 0) Spacer(Modifier.height(14.dp))
                        // Later rows draw above earlier ones, and each place above its right neighbour,
                        // so a badge reaching past its tile's corner takes the touch there.
                        Row(Modifier.zIndex(row.toFloat()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            cells.forEachIndexed { column, slot ->
                                val index = row * DockConfig.ROW + column
                                Place(
                                    index = index,
                                    slot = slot,
                                    label = labels[index],
                                    state = state,
                                    lifted = lift?.from == index,
                                    lit = lift != null || selected != null,
                                    hovered = lift != null && hover == index && lift?.from != index,
                                    badge = lift == null,
                                    frame = { frame[0] },
                                    onPositioned = { places[index] = it },
                                    onTap = {
                                        val kind = selected
                                        when {
                                            kind != null -> {
                                                selected = null
                                                haptics.confirm()
                                                onKind(index, kind)
                                            }
                                            slot.isSet -> onEdit(index)
                                            else -> chooseFor = index
                                        }
                                    },
                                    onLift = { at, size -> begin(Lift(from = index), at, size) },
                                    onDrag = ::drag,
                                    onEnd = { end(it, removedText) },
                                    onMoveTo = { moveFrom = index },
                                    onRemove = {
                                        haptics.confirm()
                                        remove(index, removedText(index))
                                    },
                                    modifier = Modifier.weight(1f).zIndex((DockConfig.ROW - column).toFloat()),
                                )
                            }
                        }
                    }
                }
            }
            between()

            // The list of kinds: never a dashed frame, since it is not a piece of the layout; a pin
            // dropped here leaves the dock, as one dropped on Firefox's palette does.
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.layout_kinds_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 16.dp).semantics { heading() },
            )
            Text(
                stringResource(if (selected != null) R.string.layout_kind_selected else R.string.layout_kinds_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PinKind.entries.forEach { kind ->
                    KindChip(
                        kind = kind,
                        selected = selected == kind,
                        frame = { frame[0] },
                        onTap = { selected = if (selected == kind) null else kind },
                        onLift = { at, size -> begin(Lift(kind = kind), at, size) },
                        onDrag = ::drag,
                        onEnd = { end(it, removedText) },
                        onPlaceIn = { placeKind = kind },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.layout_pins_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // What the finger holds, under it: the pin or the kind, and "Let go to remove" off the dock.
        val held = lift
        if (held != null) {
            val density = LocalDensity.current
            val offDock = held.from != null && hover == null && !onDock(pointer)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = colors.surfaceContainerHighest,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .zIndex(10f)
                    .offset { IntOffset((pointer.x - ghost.width / 2).roundToInt(), (pointer.y - ghost.height / 2).roundToInt()) }
                    .width(with(density) { ghost.width.toDp() }.coerceAtLeast(56.dp))
                    .heightIn(min = with(density) { ghost.height.toDp() }),
            ) {
                Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (held.kind != null) {
                        Icon(painterResource(held.kind.icon), contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
                        Text(stringResource(held.kind.label), style = MaterialTheme.typography.labelSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        val from = held.from ?: 0
                        val slot = pins[from]
                        PinFace(slot.primary, slot.icon, slot.letters, state, colors.onSurface, iconSize = 20.dp)
                        Text(labels[from] ?: "", style = MaterialTheme.typography.labelSmall, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (offDock) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_remove), contentDescription = null, tint = colors.error, modifier = Modifier.size(12.dp))
                                Text(stringResource(R.string.layout_drop_remove), style = MaterialTheme.typography.labelSmall, color = colors.error, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    moveFrom?.let { from ->
        PlaceChooser(
            title = stringResource(R.string.layout_move_title, labels[from] ?: ""),
            labels = labels,
            except = from,
            onPick = { to ->
                moveFrom = null
                haptics.confirm()
                onSwap(from, to)
            },
            onDismiss = { moveFrom = null },
        )
    }
    placeKind?.let { kind ->
        PlaceChooser(
            title = stringResource(R.string.layout_kind_place_title, stringResource(kind.label)),
            labels = labels,
            except = null,
            onPick = { to ->
                placeKind = null
                onKind(to, kind)
            },
            onDismiss = { placeKind = null },
        )
    }
    chooseFor?.let { index ->
        KindChooser(
            title = stringResource(R.string.layout_kind_choose_title, index + 1),
            onPick = { kind ->
                chooseFor = null
                onKind(index, kind)
            },
            onDismiss = { chooseFor = null },
        )
    }
}

/**
 * Long press lifts, then the finger carries it; positions are reported in [frame]'s coordinates,
 * read from the layout at each move, so they stay right even if what was pressed moves meanwhile.
 */
@Composable
private fun Modifier.lifter(
    key: Any,
    frame: () -> LayoutCoordinates?,
    onLift: (Offset, Size) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: (cancelled: Boolean) -> Unit,
): Modifier {
    val self = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val lift by rememberUpdatedState(onLift)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    return this
        .onGloballyPositioned { self[0] = it }
        .pointerInput(key) {
            fun toFrame(p: Offset): Offset {
                val f = frame()
                val c = self[0]
                return if (f != null && c != null && f.isAttached && c.isAttached) f.localPositionOf(c, p) else p
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { at ->
                    val s = self[0]?.size
                    lift(toFrame(at), if (s != null) Size(s.width.toFloat(), s.height.toFloat()) else Size.Zero)
                },
                onDragEnd = { end(false) },
                onDragCancel = { end(true) },
                onDrag = { change, _ ->
                    change.consume()
                    drag(toFrame(change.position))
                },
            )
        }
}

/** A rounded outline, dashed or solid, drawn inside the bounds. */
private fun Modifier.dashedOutline(color: Color, radius: Dp, dashed: Boolean, width: Dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null),
    )
}

/**
 * One of the twelve places. Filled, it is the pin dressed as the dock dresses it, with its "−"
 * badge; empty, a quiet dashed outline. While something is lifted or a kind is chosen every place
 * lights (a dashed outline in the accent); the one under the finger turns solid and thick, so the
 * target is told by the line's shape, never its colour alone.
 */
@Composable
private fun Place(
    index: Int,
    slot: DockSlot,
    label: String?,
    state: TuttiUiState,
    lifted: Boolean,
    lit: Boolean,
    hovered: Boolean,
    badge: Boolean,
    frame: () -> LayoutCoordinates?,
    onPositioned: (LayoutCoordinates) -> Unit,
    onTap: () -> Unit,
    onLift: (Offset, Size) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: (Boolean) -> Unit,
    onMoveTo: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val filled = slot.isSet
    val name = label ?: ""
    val placeName = stringResource(R.string.layout_place, index + 1)
    val emptyName = stringResource(R.string.layout_place_empty, index + 1)
    val moveLabel = stringResource(R.string.layout_place_move)
    val removeLabel = stringResource(R.string.layout_place_remove)
    val tapLabel = stringResource(if (filled) R.string.layout_place_edit else R.string.layout_place_choose)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onGloballyPositioned(onPositioned)
                .alpha(if (lifted) 0.35f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .then(if (filled) Modifier.background(colors.surfaceContainerHigh) else Modifier)
                .then(
                    when {
                        hovered -> Modifier.dashedOutline(colors.onSurface, 16.dp, dashed = false, width = 3.dp)
                        lit -> Modifier.dashedOutline(colors.onSurfaceVariant, 16.dp, dashed = true, width = 2.dp)
                        !filled -> Modifier.dashedOutline(colors.outlineVariant, 16.dp, dashed = true, width = 1.5.dp)
                        else -> Modifier
                    },
                )
                .clickable(onClickLabel = tapLabel, onClick = onTap)
                .then(if (filled) Modifier.lifter(index to slot, frame, onLift, onDrag, onEnd) else Modifier)
                .semantics {
                    contentDescription = if (filled) name else emptyName
                    if (filled) {
                        stateDescription = placeName
                        customActions = listOf(
                            CustomAccessibilityAction(moveLabel) { onMoveTo(); true },
                            CustomAccessibilityAction(removeLabel) { onRemove(); true },
                        )
                    }
                }
                .padding(horizontal = 2.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (filled) {
                // Dressed as the dock is, so "Pins show" below shows here as it is picked.
                val dress = pinDress(state.prefs.layout.dockLabels, name)
                if (dress.glyph) {
                    PinFace(slot.primary, slot.icon, slot.letters, state, colors.onSurfaceVariant, iconSize = dress.glyphSize, faceSize = dress.faceSize)
                }
                if (dress.glyph && dress.name) Spacer(Modifier.height(4.dp))
                if (!dress.glyph) {
                    PinNameOnly(name, dress.nameLines, colors.onSurfaceVariant, description = null)
                } else if (dress.name) {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        if (filled && badge) {
            // Centred 10 dp inside the tile's corner: the mark sits on it, the 48 dp target reaches past.
            RemoveBadge(
                description = stringResource(R.string.layout_place_remove_named, name),
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-14).dp),
                target = 48.dp,
            )
        }
    }
}

/** A kind in the list: tap chooses it (then tap a place), long press lifts a copy; it never leaves. */
@Composable
private fun KindChip(
    kind: PinKind,
    selected: Boolean,
    frame: () -> LayoutCoordinates?,
    onTap: () -> Unit,
    onLift: (Offset, Size) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: (Boolean) -> Unit,
    onPlaceIn: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(kind.label)
    val placeIn = stringResource(R.string.layout_kind_place_in)
    val chosen = stringResource(R.string.layout_kind_chosen)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHighest)
            .then(if (selected) Modifier.dashedOutline(colors.onSurface, 12.dp, dashed = false, width = 2.dp) else Modifier)
            .clickable(onClick = onTap)
            .lifter(kind, frame, onLift, onDrag, onEnd)
            .semantics {
                contentDescription = name
                if (selected) stateDescription = chosen
                customActions = listOf(CustomAccessibilityAction(placeIn) { onPlaceIn(); true })
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Chosen shows a tick, not just a fill.
        Icon(
            painterResource(if (selected) R.drawable.ic_check else kind.icon),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
        Spacer(Modifier.width(6.dp))
        Icon(painterResource(R.drawable.ic_drag), contentDescription = null, tint = colors.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
    }
}

/** The twelve places as a list, for "Move to…" and "Place in…": the no-drag path to any place. */
@Composable
private fun PlaceChooser(title: String, labels: List<String?>, except: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val empty = stringResource(R.string.layout_place_free)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                labels.forEachIndexed { index, label ->
                    if (index == except) return@forEachIndexed
                    TextButton(onClick = { onPick(index) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.layout_place_row, index + 1, label ?: empty),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** What goes in an empty place that was tapped: the kinds, then the picker for the one chosen. */
@Composable
private fun KindChooser(title: String, onPick: (PinKind) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PinKind.entries.forEach { kind ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(kind) }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(kind.icon), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(kind.label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

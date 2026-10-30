package io.kud.tutti.ui.remote

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import io.kud.tutti.ui.motion.refusalShake
import io.kud.tutti.ui.motion.rememberRefusalShake
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kud.tutti.R
import io.kud.tutti.ui.common.longPressRing
import io.kud.tutti.ui.common.rememberLongPressProgress
import io.kud.tutti.ui.common.TuttiMenu
import io.kud.tutti.ui.common.DOWN_STROKE
import io.kud.tutti.ui.common.LocalChipNudge
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockLabels
import io.kud.tutti.ui.common.LocalDockLabels
import io.kud.tutti.ui.common.PIN_FACE
import io.kud.tutti.ui.common.PIN_ICON_LARGE
import io.kud.tutti.ui.common.PinDress
import io.kud.tutti.ui.common.PinNameOnly
import io.kud.tutti.ui.common.pinDress
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.RowButton
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.rememberWaiting
import io.kud.tutti.ui.motion.snappyOrFade
import io.kud.tutti.ui.common.PressSource
import io.kud.tutti.ui.common.pressScale
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPressSource
import io.kud.tutti.ui.common.rememberTuttiIndication
import io.kud.tutti.ui.common.actionIcon
import io.kud.tutti.ui.common.actionLabel
import io.kud.tutti.ui.common.pinGlyphRes
import io.kud.tutti.ui.common.slotLabel

/**
 * The dock: one grid of two rows of six, twelve places, no pages (Erwann, 2026-10-29: « un dock de
 * 2 lignes au final et basta »). The remote's own keys, the round row until then, are pins here
 * ([DockAction.Remote], drawn by [remoteKey] so they behave exactly as the row's keys did), and so
 * are Apps and Keys; he may move or drop any of them, and the ⋮ offers whatever sheet no pin
 * opens any more. Every pin wears one tone (Erwann, 2026-10-28: « tous la même couleur »), the
 * quiet container, keys included; an app keeps its own icon on it. Only filled pins show (Erwann, 2026-10-25: no "+ Add" slots; the editor is in the top
 * bar's ⋮): each row lays its pins in the columns he chose in the editor, at the one width six across give them (an empty place before a pin stays empty, after the last one it is dropped), rows
 * line up in columns, and the grid is centred, sized by the widest row and the most rows on any
 * page, so a swipe never moves it. A row with no pin is left out, so a dock of six or fewer stays
 * one row high. An empty dock shows one quiet "Customise the dock". From two pages the grid swipes
 * sideways and small dots show where he is. A pin runs on tap and opens its editor on long press;
 * a pin with its own long press offers that first, the editor below it, like a launcher's
 * shortcuts. A pin whose tap toggles a bar setting wears that setting's state ([PinToggle]): a
 * check badge and the accent-filled, squarer tile when on (Power's and Mute's on), an empty ring
 * when off, a dashed ring when tutti does not know, and TalkBack hears it as a switch.
 */
@Composable
fun Dock(
    state: TuttiUiState,
    onRun: (index: Int, slot: DockSlot) -> Unit,
    onEdit: (index: Int) -> Unit,
    onCustomise: () -> Unit,
    modifier: Modifier = Modifier,
    onRunSecondary: (index: Int, action: DockAction) -> Unit = { _, _ -> },
    initialPage: Int = 0,
    /** Draws one of the remote's own keys as a pin, at the width given: the remote screen's live key. */
    remoteKey: @Composable (button: RowButton, modifier: Modifier) -> Unit = { _, _ -> },
) {
    val pages = shownPins(state.dock)
    val span = dockSpan(pages)
    val rows = dockRows(pages)
    val pager = rememberPagerState(initialPage = initialPage) { pages.size }
    // His dress for every pin, the remote's own keys included (PinDress).
    CompositionLocalProvider(LocalDockLabels provides state.prefs.layout.dockLabels) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Six equal tiles and five 6dp gaps: the width the doors and four pins shared before.
        // Fewer pins keep that width and the grid centres, rather than stretching (Iris, 2026-10-25).
        val tile = (maxWidth - GAP * (DockConfig.ROW - 1)) / DockConfig.ROW
        val pinsWidth = tile * span + GAP * (span - 1).coerceAtLeast(0)
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            if (pages.isEmpty()) {
                CustomiseEntry(onCustomise, Modifier.width(tile * 4 + GAP * 3))
                return@Column
            }
            val pageDescription = stringResource(R.string.dock_page_desc, pager.currentPage + 1, pages.size)
            HorizontalPager(
                state = pager,
                userScrollEnabled = pages.size > 1,
                pageSpacing = GAP,
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .width(pinsWidth)
                    .then(if (pages.size > 1) Modifier.semantics { stateDescription = pageDescription } else Modifier),
            ) { page ->
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    pages[page].forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            row.forEach { place ->
                                // An empty place before the row's last pin keeps its column.
                                val index = place ?: run {
                                    Spacer(Modifier.width(tile))
                                    return@forEach
                                }
                                val slot = state.dock.slot(index)
                                val key = slot.primary as? DockAction.Remote
                                if (key != null) {
                                    remoteKey(key.button, Modifier.width(tile))
                                    return@forEach
                                }
                                PinTile(
                                    slot = slot,
                                    state = state,
                                    // A slot with only a long-press action has no tap: it opens its editor.
                                    onClick = { if (slot.primary != null) onRun(index, slot) else onEdit(index) },
                                    onEdit = { onEdit(index) },
                                    onSecondary = { action -> onRunSecondary(index, action) },
                                    modifier = Modifier.width(tile),
                                )
                            }
                        }
                    }
                    // A page with fewer rows than the tallest keeps its height, so nothing below jumps.
                    repeat(rows - pages[page].size) { Spacer(Modifier.height(TILE_MIN)) }
                }
            }
            if (pages.size > 1) {
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .width(pinsWidth),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(pages.size) { page ->
                        val current = page == pager.currentPage
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (current) 8.dp else 6.dp)
                                .align(Alignment.CenterVertically)
                                .clip(CircleShape)
                                .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * The dock as shown: for each page holding at least one pin, its rows that hold one, each as its
 * places' slot indices across pages up to its last pin, null for an empty place before it. A pin
 * stands in the column he put it in the editor (Erwann, 2026-10-30: « Je l'ai mis central
 * volontairement »); only empty places after a row's last pin, empty rows and empty pages are left
 * out. The editor and "Pin to dock" still list every place.
 */
internal fun shownPins(dock: DockConfig): List<List<List<Int?>>> =
    dock.pages.mapIndexedNotNull { page, slots ->
        slots.chunked(DockConfig.ROW).mapIndexedNotNull { row, cells ->
            val last = cells.indexOfLast { it.primary != null || it.secondary != null }
            if (last < 0) return@mapIndexedNotNull null
            (0..last).map { col ->
                val cell = cells[col]
                if (cell.primary != null || cell.secondary != null) page * DockConfig.PAGE_SIZE + row * DockConfig.ROW + col else null
            }
        }.takeIf { it.isNotEmpty() }
    }

/** How many pin widths the grid reserves: the widest row on any page, so a swipe never moves it. */
internal fun dockSpan(pages: List<List<List<Int?>>>): Int = pages.flatten().maxOfOrNull { it.size } ?: 0

/** How many rows the grid reserves: the most any page shows, so a swipe never changes its height. */
internal fun dockRows(pages: List<List<List<Int?>>>): Int = pages.maxOfOrNull { it.size } ?: 0

/** An empty dock's one way in: quiet text where the pins would be, never a tile that looks like one. */
@Composable
private fun CustomiseEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .height(64.dp)
            .clip(TileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.dock_customise),
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinTile(
    slot: DockSlot,
    state: TuttiUiState,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onSecondary: (DockAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = slotLabel(slot, state) ?: slot.secondary?.let { actionLabel(it, state) } ?: stringResource(R.string.dock_reassign)
    val edit = stringResource(R.string.dock_reassign)
    val secondary = slot.secondary
    var menu by remember { mutableStateOf(false) }
    // The click plays on down; the command waits for release, since a long press means another.
    val feedback = rememberFeedback()
    // The remote's one rule (controlLink): a pin goes down with the device it talks to, and only that
    // one. Power keys, scenes and macros wake or mix devices, so they never do.
    val owner = dockOwner(slot.primary)
    val link = state.controlLink(owner)
    val reason = controlLinkReason(link, owner, state)
    val isDown by rememberUpdatedState(link.down)
    val nudge = LocalChipNudge.current
    val press = rememberPressSource { if (!isDown) feedback.click() }
    val toggle = pinToggle(slot.primary, state.bar, state.devices.bar != null)
    val on = (toggle as? PinToggle.Known)?.on == true && !link.down
    val scheme = MaterialTheme.colorScheme
    // On is the remote's one "on" dress, as on Power and Mute: the accent fill, squarer corners and a
    // check. Never the doors' tonal fill, which only says "a door", so fill alone never means on.
    val background by animateColorAsState(
        when {
            link.down -> Color.Transparent
            on -> scheme.primary
            else -> scheme.surfaceContainerHigh
        },
        snappyOrFade(),
        label = "pin",
    )
    val content = if (on) scheme.onPrimary else scheme.onSurfaceVariant
    val downEdge = scheme.outline
    val reduced = LocalReducedMotion.current
    // On also squares the tile a little, as the Sound sheet's tiles do: shape, not only colour.
    val corner by animateDpAsState(if (on) 14.dp else 20.dp, if (reduced) snap() else Motion.snappy(), label = "pinCorner")
    val shape = RoundedCornerShape(corner)
    val secondaryToggle = pinToggle(secondary, state.bar, state.devices.bar != null) as? PinToggle.Known
    // A refusal, as on the Sound tile: the badge has already sprung back (the rollback); the pin
    // plays the shared refusal shake with its reject (RefusalShake).
    val feature = (slot.primary as? DockAction.ToggleBar)?.featureKey?.let(TheatreFeature::fromKey)
    val outcome = remember { TileOutcome() }
    var refusals by remember { mutableIntStateOf(0) }
    val shake = rememberRefusalShake()
    val pendingValue = feature?.let { state.bar.pending[it.key]?.value }
    val shownValue = feature?.let { state.bar.value(it) }
    LaunchedEffect(feature, pendingValue, shownValue, state.bar.connected) {
        if (feature != null && outcome.observe(pendingValue, shownValue, state.bar.connected) == TileSettle.REFUSED) refusals++
    }
    LaunchedEffect(refusals) {
        if (refusals == 0) return@LaunchedEffect
        shake.refuse()
    }
    // The long press opens the pin's menu or editor: the same ring as the segments and tiles
    // (discoverability rule 1). Editing is local, so it sweeps even while the device is down.
    val ring = rememberLongPressProgress(press.interaction, enabled = true)
    val dress = pinDress(LocalDockLabels.current, label)
    Box(
        modifier
            .refusalShake(shake)
            .longPressRing({ ring.value }, shape, scheme.onSurface),
    ) {
        Tile(
            background = background,
            content = content,
            label = label,
            dress = dress,
            press = press,
            shape = shape,
            stateText = reason ?: toggleStateText(toggle, state),
            modifier = Modifier
                .drawBehind {
                    if (link.down) {
                        val half = DOWN_STROKE.toPx() / 2f
                        inset(half) { drawDownRing(shape.createOutline(size, layoutDirection, this), downEdge) }
                    }
                }
                .combinedClickable(
                interactionSource = press.interaction,
                indication = rememberTuttiIndication(shape, layerColor = content),
                role = if (toggle != null) Role.Switch else null,
                onLongClickLabel = if (secondary != null) actionLabel(secondary, state) else edit,
                onLongClick = {
                    feedback.longPress()
                    if (secondary != null) menu = true else onEdit()
                },
                // Down, a tap is the Sound tile's: a bump, the chip nudged to say why, nothing sent.
                // The long press still opens the menu and the editor.
                onClick = {
                    if (link.down) {
                        feedback.bump()
                        nudge.poke()
                    } else {
                        onClick()
                    }
                },
            ),
        ) { tint ->
            Box(contentAlignment = Alignment.Center) {
                PinFace(slot.primary ?: slot.secondary, slot.icon, slot.letters, state, tint, iconSize = dress.glyphSize, faceSize = dress.faceSize)
                if (toggle != null) {
                    PinStateBadge(toggle, tint, background, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp))
                }
            }
        }
        // Names alone: the toggle's mark has no glyph to sit on, so it takes the tile's corner. The
        // mark, never the fill alone, still says on, off or not known.
        if (toggle != null && !dress.glyph) {
            PinStateBadge(toggle, content, background, Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp))
        }
        if (secondary != null) {
            TuttiMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(actionLabel(secondary, state), fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(painterResource(actionIcon(secondary, state)), contentDescription = null) },
                    // A long-press toggle says where it stands now; unknown says nothing rather than "Off".
                    trailingIcon = secondaryToggle?.let { known ->
                        { Text(stringResource(if (known.on) R.string.toggle_state_on else R.string.toggle_state_off), style = MaterialTheme.typography.labelMedium) }
                    },
                    onClick = {
                        menu = false
                        onSecondary(secondary)
                    },
                )
                DropdownMenuItem(
                    text = { Text(edit) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_edit), contentDescription = null) },
                    onClick = {
                        menu = false
                        onEdit()
                    },
                )
            }
        }
    }
}

/**
 * What a pin shows above its name: his letters, else his icon, else the app's own icon for an
 * app, else the action's icon. Shared by the dock and the editor's preview so they never differ.
 */
@Composable
internal fun PinFace(
    action: DockAction?,
    icon: String?,
    letters: String?,
    state: TuttiUiState,
    tint: Color,
    iconSize: Dp,
    /** An app's own face, which reads a size larger than a glyph (24 dp beside a name). */
    faceSize: Dp = PIN_FACE,
) {
    val shown = letters?.trim()?.takeIf { it.isNotEmpty() }
    when {
        // Same height as an icon, so a lettered pin's name sits level with its neighbours'.
        shown != null -> Box(Modifier.height(iconSize), contentAlignment = Alignment.Center) {
            Text(
                shown,
                style = (if (iconSize >= PIN_ICON_LARGE) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    .copy(lineHeight = iconSize.value.sp),
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
            )
        }
        icon == null && action is DockAction.LaunchApp ->
            AppIcon(action.label, state.appIcons[action.packageName], size = faceSize, corner = faceSize / 4, packageName = action.packageName)
        else -> Icon(
            painter = painterResource(icon?.let(::pinGlyphRes) ?: action?.let { actionIcon(it, state) } ?: R.drawable.ic_add),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun Tile(
    background: Color,
    content: Color,
    label: String,
    press: PressSource,
    modifier: Modifier,
    dress: PinDress = pinDress(DockLabels.BOTH, label),
    outer: Modifier = Modifier.fillMaxWidth(),
    shape: Shape = TileShape,
    stateText: String? = null,
    icon: @Composable (Color) -> Unit,
) {
    Column(
        // At least 64 dp, taller when large text needs it: the label is never clipped mid-line.
        modifier = outer
            .heightIn(min = TILE_MIN)
            .pressScale(press.state)
            .clip(shape)
            .background(background, shape)
            .then(modifier)
            .semantics {
                contentDescription = label
                if (stateText != null) stateDescription = stateText
            }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The dock's dress (PinDress): the tile's own node carries the name for TalkBack in every one.
        when {
            !dress.name -> icon(content)
            !dress.glyph -> PinNameOnly(label, dress.nameLines, content, description = null)
            else -> {
                icon(content)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What TalkBack says a toggle pin is: on, off, or why tutti does not know. */
@Composable
private fun toggleStateText(toggle: PinToggle?, state: TuttiUiState): String? = when (toggle) {
    null -> null
    is PinToggle.Known -> stringResource(if (toggle.on) R.string.toggle_state_on else R.string.toggle_state_off)
    PinToggle.Unknown -> stringResource(R.string.sound_reason_unreported)
    PinToggle.Unreachable -> stringResource(R.string.system_unreachable, state.devices.bar?.name.orEmpty())
    PinToggle.NoBar -> stringResource(R.string.sound_reason_no_bar)
}

/**
 * The toggle's mark on a pin's icon, the Sound tile's [TileMark] at badge size: a filled disc with
 * a check (on), an empty ring (off), a dashed ring in the outline tone (unknown, bar unreachable or
 * absent). A ring of the tile's own colour cuts it out of the icon. Fill changes by opacity only,
 * so reduced motion needs no other form; a slow confirmation turns a thin arc round it (static
 * under reduced motion).
 */
@Composable
private fun PinStateBadge(toggle: PinToggle, color: Color, cutout: Color, modifier: Modifier = Modifier) {
    val known = toggle as? PinToggle.Known
    val fill by animateFloatAsState(if (known?.on == true) 1f else 0f, snappyOrFade(), label = "pinFill")
    val dashed = MaterialTheme.colorScheme.outline
    val waiting = rememberWaiting(known?.pending == true)
    Box(
        modifier
            .size(16.dp)
            .clearAndSetSemantics {}
            .drawBehind {
                val width = 1.5.dp.toPx()
                val mark = 6.dp.toPx()
                drawCircle(cutout, mark + 2.dp.toPx())
                if (known == null) {
                    drawCircle(dashed, mark - width / 2f, style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
                } else {
                    drawCircle(color, mark - width / 2f, style = Stroke(width))
                    drawCircle(color, mark, alpha = fill.coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (known != null) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = cutout.copy(alpha = fill.coerceIn(0f, 1f)),
                modifier = Modifier.size(9.dp),
            )
        }
        if (waiting.spinner) {
            val arc = Modifier.requiredSize(18.dp)
            if (LocalReducedMotion.current) {
                CircularProgressIndicator(progress = { 0.75f }, modifier = arc, color = color, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
            } else {
                CircularProgressIndicator(modifier = arc, color = color, strokeWidth = 1.5.dp, trackColor = Color.Transparent)
            }
        }
    }
}

private val GAP = 6.dp
private val TILE_MIN = 64.dp
private val TileShape = RoundedCornerShape(20.dp)

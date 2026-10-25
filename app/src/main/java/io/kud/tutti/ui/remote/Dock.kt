package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import io.kud.tutti.ui.common.TuttiMenu
import io.kud.tutti.ui.common.DOWN_STROKE
import io.kud.tutti.ui.common.LocalChipNudge
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
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
 * The bottom row: two fixed doors, Apps and Keys (the rest of the remote), then his pins, four to
 * a page. The doors share a tone so they read as one family, apart from the pins. With one page
 * the row is exactly the old one; from two, the pins swipe sideways under the still doors and
 * small dots show where he is. A pin runs on tap and opens its editor on long press; a pin with
 * its own long press offers that first, the editor below it, like a launcher's shortcuts. An
 * empty pin asks on tap. A pin whose tap toggles a bar setting wears that setting's state
 * ([PinToggle]): a check badge and the accent-filled, squarer tile when on (Power's and Mute's on), an empty ring when off, a
 * dashed ring when tutti does not know, and TalkBack hears it as a switch.
 */
@Composable
fun Dock(
    state: TuttiUiState,
    onApps: () -> Unit,
    onKeys: () -> Unit,
    onRun: (index: Int, slot: DockSlot) -> Unit,
    onAssign: (index: Int) -> Unit,
    onEdit: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
    onRunSecondary: (index: Int, action: DockAction) -> Unit = { _, _ -> },
    initialPage: Int = 0,
) {
    val pages = state.dock.pages
    val pager = rememberPagerState(initialPage = initialPage) { pages.size }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Six equal tiles: five 6dp gaps plus the 6dp step between the doors and the pins.
        val tile = (maxWidth - GAP * 6) / 6
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                DoorTile(R.drawable.ic_apps, stringResource(R.string.dock_apps), onApps, Modifier.width(tile))
                DoorTile(R.drawable.ic_dialpad, stringResource(R.string.dock_keys), onKeys, Modifier.width(tile))
                Spacer(Modifier.width(0.dp))
                val pageDescription = stringResource(R.string.dock_page_desc, pager.currentPage + 1, pages.size)
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = pages.size > 1,
                    pageSpacing = GAP,
                    modifier = Modifier
                        .width(tile * 4 + GAP * 3)
                        .then(if (pages.size > 1) Modifier.semantics { stateDescription = pageDescription } else Modifier),
                ) { page ->
                    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        pages[page].forEachIndexed { offset, slot ->
                            val index = page * DockConfig.PAGE_SIZE + offset
                            PinTile(
                                slot = slot,
                                state = state,
                                onClick = { if (slot.primary != null) onRun(index, slot) else onAssign(index) },
                                onEdit = { onEdit(index) },
                                onSecondary = { action -> onRunSecondary(index, action) },
                                modifier = Modifier.width(tile),
                            )
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Row(
                    Modifier
                        .padding(start = tile * 2 + GAP * 3, top = 8.dp)
                        .width(tile * 4 + GAP * 3),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DoorTile(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val feedback = rememberFeedback()
    val press = rememberPressSource { feedback.click() }
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Tile(
        background = MaterialTheme.colorScheme.secondaryContainer,
        content = content,
        label = label,
        press = press,
        modifier = Modifier.combinedClickable(
            interactionSource = press.interaction,
            indication = rememberTuttiIndication(TileShape, layerColor = content),
            onClick = onClick,
        ),
        outer = modifier,
    ) { tint ->
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
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
    val label = slotLabel(slot, state) ?: stringResource(R.string.dock_add)
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
    // shakes 4 dp and plays reject. Reduced motion keeps the haptic, not the shake.
    val feature = (slot.primary as? DockAction.ToggleBar)?.featureKey?.let(TheatreFeature::fromKey)
    val outcome = remember { TileOutcome() }
    var refusals by remember { mutableIntStateOf(0) }
    val shake = remember { Animatable(0f) }
    val pendingValue = feature?.let { state.bar.pending[it.key]?.value }
    val shownValue = feature?.let { state.bar.value(it) }
    LaunchedEffect(feature, pendingValue, shownValue, state.bar.connected) {
        if (feature != null && outcome.observe(pendingValue, shownValue, state.bar.connected) == TileSettle.REFUSED) refusals++
    }
    LaunchedEffect(refusals) {
        if (refusals == 0) return@LaunchedEffect
        feedback.reject()
        if (!reduced) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = Motion.MEDIUM
                    4f at 40
                    -4f at 100
                    3f at 160
                    -2f at 210
                },
            )
        }
    }
    Box(modifier.graphicsLayer { translationX = shake.value.dp.toPx() }) {
        Tile(
            background = background,
            content = content,
            label = label,
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
                PinFace(slot.primary, slot.icon, slot.letters, state, tint, iconSize = 20.dp)
                if (toggle != null) {
                    PinStateBadge(toggle, tint, background, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp))
                }
            }
        }
        if (secondary != null) {
            TuttiMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(actionLabel(secondary, state), fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(painterResource(actionIcon(secondary, state)), contentDescription = null) },
                    // A long-press toggle says where it stands now; unknown says nothing rather than "Off".
                    trailingIcon = secondaryToggle?.let { known ->
                        { Text(stringResource(if (known.on) R.string.state_on else R.string.state_off), style = MaterialTheme.typography.labelMedium) }
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
) {
    val shown = letters?.trim()?.takeIf { it.isNotEmpty() }
    when {
        // Same height as an icon, so a lettered pin's name sits level with its neighbours'.
        shown != null -> Box(Modifier.height(iconSize), contentAlignment = Alignment.Center) {
            Text(
                shown,
                style = MaterialTheme.typography.titleMedium.copy(lineHeight = iconSize.value.sp),
                fontWeight = FontWeight.SemiBold,
                color = tint,
                maxLines = 1,
            )
        }
        icon == null && action is DockAction.LaunchApp ->
            AppIcon(action.label, state.appIcons[action.packageName], size = 24.dp, corner = 6.dp, packageName = action.packageName)
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
    outer: Modifier = Modifier.fillMaxWidth(),
    shape: Shape = TileShape,
    stateText: String? = null,
    icon: @Composable (Color) -> Unit,
) {
    Column(
        // At least 64 dp, taller when large text needs it: the label is never clipped mid-line.
        modifier = outer
            .heightIn(min = 64.dp)
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

/** What TalkBack says a toggle pin is: on, off, or why tutti does not know. */
@Composable
private fun toggleStateText(toggle: PinToggle?, state: TuttiUiState): String? = when (toggle) {
    null -> null
    is PinToggle.Known -> stringResource(if (toggle.on) R.string.state_on else R.string.state_off)
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
private val TileShape = RoundedCornerShape(20.dp)

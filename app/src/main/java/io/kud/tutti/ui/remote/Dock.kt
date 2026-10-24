package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.ui.common.AppIcon
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
 * empty pin asks on tap.
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
    val press = rememberPressSource { feedback.click() }
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier) {
        Tile(
            background = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = content,
            label = label,
            press = press,
            modifier = Modifier.combinedClickable(
                interactionSource = press.interaction,
                indication = rememberTuttiIndication(TileShape, layerColor = content),
                onLongClickLabel = if (secondary != null) actionLabel(secondary, state) else edit,
                onLongClick = {
                    feedback.longPress()
                    if (secondary != null) menu = true else onEdit()
                },
                onClick = onClick,
            ),
        ) { tint ->
            PinFace(slot.primary, slot.icon, slot.letters, state, tint, iconSize = 20.dp)
        }
        if (secondary != null) {
            TuttiMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(actionLabel(secondary, state), fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(painterResource(actionIcon(secondary, state)), contentDescription = null) },
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
    icon: @Composable (Color) -> Unit,
) {
    Column(
        modifier = outer
            .height(64.dp)
            .pressScale(press.state)
            .clip(TileShape)
            .background(background)
            .then(modifier)
            .semantics { contentDescription = label }
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

private val GAP = 6.dp
private val TileShape = RoundedCornerShape(20.dp)

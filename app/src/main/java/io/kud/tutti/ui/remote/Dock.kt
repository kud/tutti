package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.actionIcon
import io.kud.tutti.ui.common.actionLabel

/**
 * The bottom row: two fixed doors, Apps and Keys (the rest of the remote), then his four pins.
 * The doors share a tone so they read as one family, apart from the pins. A pin runs on tap
 * and reassigns on long press; an empty one asks on tap.
 */
@Composable
fun Dock(
    state: TuttiUiState,
    onApps: () -> Unit,
    onKeys: () -> Unit,
    onRun: (index: Int, slot: DockSlot) -> Unit,
    onAssign: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DoorTile(R.drawable.ic_apps, stringResource(R.string.dock_apps), onApps, Modifier.weight(1f))
        DoorTile(R.drawable.ic_dialpad, stringResource(R.string.dock_keys), onKeys, Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        for (index in 0 until DockConfig.SIZE) {
            val slot = state.dock.slots.getOrNull(index) ?: DockSlot()
            PinTile(
                slot = slot,
                state = state,
                onClick = { if (slot.primary != null) onRun(index, slot) else onAssign(index) },
                onLongClick = { onAssign(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DoorTile(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Tile(
        background = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        label = label,
        modifier = modifier.combinedClickable(onClick = onClick),
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
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = slot.primary
    val label = if (action != null) actionLabel(action, state) else stringResource(R.string.dock_add)
    val assign = stringResource(R.string.dock_reassign)
    Tile(
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        label = label,
        modifier = modifier.combinedClickable(onLongClickLabel = assign, onLongClick = onLongClick, onClick = onClick),
    ) { tint ->
        if (action is DockAction.LaunchApp) {
            AppIcon(action.label, state.appIcons[action.packageName], size = 24.dp, corner = 6.dp)
        } else {
            Icon(
                painter = painterResource(if (action != null) actionIcon(action, state) else R.drawable.ic_add),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Tile(
    background: Color,
    content: Color,
    label: String,
    modifier: Modifier,
    icon: @Composable (Color) -> Unit,
) {
    Column(
        modifier = Modifier
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
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

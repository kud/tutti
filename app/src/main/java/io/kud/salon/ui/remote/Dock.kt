package io.kud.salon.ui.remote

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.salon.R
import io.kud.salon.controller.SalonUiState
import io.kud.salon.platform.data.DockConfig
import io.kud.salon.platform.data.DockSlot
import io.kud.salon.ui.common.actionIcon
import io.kud.salon.ui.common.actionLabel

/** Four shortcut slots. Tap runs; long press reassigns; an empty slot asks on tap. */
@Composable
fun Dock(
    state: SalonUiState,
    onRun: (index: Int, slot: DockSlot) -> Unit,
    onAssign: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (index in 0 until DockConfig.SIZE) {
            val slot = state.dock.slots.getOrNull(index) ?: DockSlot()
            DockTile(
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
private fun DockTile(
    slot: DockSlot,
    state: SalonUiState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = slot.primary
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(if (action != null) actionIcon(action, state) else R.drawable.ic_add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (action != null) actionLabel(action, state) else stringResource(R.string.dock_add),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

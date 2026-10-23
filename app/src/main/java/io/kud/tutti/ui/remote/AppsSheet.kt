package io.kud.tutti.ui.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.actionLabel

/** Every app tutti knows, full height over the remote. Back or a tap outside closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSheet(
    state: TuttiUiState,
    onLaunch: (KnownApp) -> Unit,
    onAllApps: () -> Unit,
    onPin: (KnownApp, slot: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        AppsSheetContent(state, onLaunch, onAllApps, onPin, Modifier.fillMaxHeight())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsSheetContent(
    state: TuttiUiState,
    onLaunch: (KnownApp) -> Unit,
    onAllApps: () -> Unit,
    onPin: (KnownApp, slot: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pinning by remember { mutableStateOf<KnownApp?>(null) }
    val pinLabel = stringResource(R.string.apps_pin_action)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Column {
                Text(
                    stringResource(R.string.apps_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.apps_pin_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!state.ipControl) {
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.apps_no_ip_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.size(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onAllApps)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_tv),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.apps_open_tv_screen),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        items(state.apps, key = { it.packageName }) { app ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClickLabel = app.name,
                        onLongClickLabel = pinLabel,
                        onLongClick = { pinning = app },
                        onClick = { onLaunch(app) },
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(
                    name = app.name,
                    iconPath = state.appIcons[app.packageName],
                    size = 88.dp,
                    corner = 20.dp,
                    modifier = Modifier.aspectRatio(1f),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    app.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    pinning?.let { app ->
        PinDialog(
            app = app,
            state = state,
            onPick = { slot ->
                onPin(app, slot)
                pinning = null
            },
            onDismiss = { pinning = null },
        )
    }
}

/** Which dock slot takes the app; each row says what it replaces. */
@Composable
private fun PinDialog(app: KnownApp, state: TuttiUiState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_pin_title, app.name)) },
        text = {
            Column {
                for (index in 0 until DockConfig.SIZE) {
                    val current = state.dock.slots.getOrNull(index)?.primary
                    val what = current?.let { actionLabel(it, state) } ?: stringResource(R.string.apps_slot_empty)
                    Text(
                        stringResource(R.string.apps_slot, index + 1, what),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(index) }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

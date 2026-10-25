package io.kud.tutti.ui.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.kud.tutti.protocol.apps.LaunchPhase
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.LocalDeveloperMode
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.ProblemNote
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiSheet
import io.kud.tutti.ui.common.actionLabel
import io.kud.tutti.ui.common.pressScale
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPressSource
import io.kud.tutti.ui.common.rememberTuttiIndication
import io.kud.tutti.ui.motion.rememberWaiting

/** Every app tutti knows, full height over the remote. Back or a tap outside closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSheet(
    state: TuttiUiState,
    onLaunch: (KnownApp) -> Long?,
    onLaunched: () -> Unit,
    onAllApps: () -> Unit,
    onPin: (KnownApp, slot: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        AppsSheetContent(state, onLaunch, onLaunched, onAllApps, onPin, Modifier.fillMaxHeight())
    }
}

/**
 * Tapping a tile launches optimistically (Erwann, 2026-10-25: "faut un click optimiste"): the
 * controller shows the app on the now-showing card at once and the sheet closes the moment the
 * launch goes out, without waiting for the TV to confirm; the card rolls back, with a quiet
 * message, if it didn't open. Tapped in a blip of the TV's link, the launch is held: the tile
 * keeps its ring and says it is waiting for the TV, and the sheet closes when it goes out. Every
 * tap reaches the controller, which never launches the same app twice and lets a newer app win.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsSheetContent(
    state: TuttiUiState,
    onLaunch: (KnownApp) -> Long?,
    onLaunched: () -> Unit,
    onAllApps: () -> Unit,
    onPin: (KnownApp, slot: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pinning by remember { mutableStateOf<KnownApp?>(null) }
    // The launch this sheet asked for (its id): the sheet closes once it has gone out.
    var asked by remember { mutableStateOf<Long?>(null) }
    val inFlight = state.launches.pending
    // Only a failure from while the sheet is open marks a tile; one from before is old news.
    val before = remember { state.launches.failure }
    val failure = state.launches.failure?.takeIf { it !== before }
    val feedback = rememberFeedback()
    val developer = LocalDeveloperMode.current
    val sent = state.launches.lastSentId
    LaunchedEffect(asked, sent) {
        val id = asked ?: return@LaunchedEffect
        if (sent >= id) {
            asked = null
            feedback.confirm()
            onLaunched()
        }
    }
    val pinLabel = stringResource(R.string.apps_pin_action)
    val grid = rememberLazyGridState()
    // Title and subtitle are the pinned header: only the grid scrolls (design.md, Sheets).
    PinnedHeader(
        scrolled = grid.canScrollBackward,
        modifier = modifier.fillMaxWidth(),
        header = {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
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
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            // Down to the sheet's foot, so a drag under the last row scrolls too (design.md, Sheets).
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    if (!state.ipControl) {
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
                        Spacer(Modifier.size(12.dp))
                    }
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
            val failedApp = failure?.let { f -> state.visibleApps.firstOrNull { it.packageName == f.packageName } }
            if (failedApp != null && developer) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "launch-failure") {
                    ProblemNote(stringResource(R.string.apps_launch_failed_note, failedApp.name), failure?.detail)
                }
            }
            items(state.visibleApps, key = { it.packageName }) { app ->
                AppTile(
                    app = app,
                    iconPath = state.appIcons[app.packageName],
                    pending = inFlight?.packageName == app.packageName,
                    held = inFlight?.packageName == app.packageName && inFlight.phase == LaunchPhase.QUEUED,
                    failed = failure?.packageName == app.packageName,
                    pinLabel = pinLabel,
                    onLongClick = { pinning = app },
                    onClick = {
                        // Refused (the TV down): the message says why and the sheet stays.
                        onLaunch(app)?.let { asked = it }
                    },
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

/**
 * One app: answers on down (scale and click), shows a ring round the icon while the TV is asked
 * (after the grace delay), and a cross badge plus "Didn't open" when it failed. Never colour alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: KnownApp,
    iconPath: String?,
    pending: Boolean,
    held: Boolean,
    failed: Boolean,
    pinLabel: String,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val feedback = rememberFeedback()
    val press = rememberPressSource(PressStyle.TILE, onDown = feedback::click)
    val waiting = rememberWaiting(pending)
    val scheme = MaterialTheme.colorScheme
    val pendingLabel = stringResource(if (held) R.string.apps_launch_waiting else R.string.apps_launch_pending)
    val failedLabel = stringResource(R.string.apps_launch_failed)
    Column(
        Modifier
            .pressScale(press.state)
            .clip(TILE_SHAPE)
            .combinedClickable(
                interactionSource = press.interaction,
                indication = rememberTuttiIndication(TILE_SHAPE),
                onClickLabel = app.name,
                onLongClickLabel = pinLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                when {
                    waiting.held -> stateDescription = pendingLabel
                    failed -> stateDescription = failedLabel
                }
            }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            AppIcon(
                name = app.name,
                iconPath = iconPath,
                packageName = app.packageName,
                size = 88.dp,
                corner = 20.dp,
                modifier = Modifier.aspectRatio(1f),
            )
            if (waiting.spinner) {
                // Reduced motion: a still three-quarter ring says "working" without turning.
                if (LocalReducedMotion.current) {
                    CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.size(40.dp), color = scheme.onSurface, strokeWidth = 3.dp, trackColor = scheme.surface)
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = scheme.onSurface, strokeWidth = 3.dp, trackColor = scheme.surface)
                }
            }
            if (failed && !waiting.held) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(28.dp)
                        .background(scheme.errorContainer, CircleShape)
                        .border(2.dp, scheme.surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = scheme.onErrorContainer, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (failed && !waiting.held) {
            Text(
                failedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        } else if (held) {
            // Held through a blip of the TV's link: say what it waits for, not only a ring.
            Text(
                pendingLabel,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val TILE_SHAPE = RoundedCornerShape(16.dp)

/** Which dock slot takes the app; each row says what it replaces. */
@Composable
private fun PinDialog(app: KnownApp, state: TuttiUiState, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_pin_title, app.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val pages = state.dock.pages
                for (index in 0 until pages.size * DockConfig.PAGE_SIZE) {
                    val page = index / DockConfig.PAGE_SIZE
                    val offset = index % DockConfig.PAGE_SIZE
                    // Every page's pins, each page under its own heading once there are two.
                    if (pages.size > 1 && offset == 0) {
                        Text(
                            stringResource(R.string.apps_pin_page, page + 1),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp, top = if (page > 0) 8.dp else 0.dp, bottom = 2.dp),
                        )
                    }
                    val current = state.dock.slot(index).primary
                    val what = current?.let { actionLabel(it, state) } ?: stringResource(R.string.apps_slot_empty)
                    Text(
                        stringResource(R.string.apps_slot, offset + 1, what),
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

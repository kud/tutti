package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiSheet
import io.kud.tutti.ui.common.drawDownRing
import io.kud.tutti.ui.common.keyIcon
import io.kud.tutti.ui.common.keyLabel
import io.kud.tutti.ui.common.rememberHaptics

/**
 * The now-showing card's header, worn by both of its sheets: the same icon and words as the card,
 * under a small label saying what the sheet is ("Playback", "App actions").
 */
@Composable
private fun NowShowingSheetHeader(
    label: String,
    showing: CardShowing,
    tvName: String,
    appIcons: Map<String, String>,
    cast: io.kud.tutti.protocol.cast.CastNowPlaying? = null,
) {
    // With a Cast report the header names what plays, then the artist or channel (else the app), with no bar.
    val words = cardWords(showing, tvName).let { words ->
        if (cast == null) words else CardWords(cast.title, cast.secondary ?: words.title)
    }
    // The sheet has the room, so the title shows whole, with no tap to open it (Erwann, 2026-10-26).
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardIcon(showing, 48.dp, appIcons)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(
                words.title,
                style = MaterialTheme.typography.titleLarge,
            )
            words.detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The card's tap (Erwann, 2026-10-25): the TV's media keys under the thumb. Nothing here claims a
 * state: only Cast reports a title, and only for some apps, so play/pause is one toggle glyph,
 * the header names the Cast title when there is one, and there is no progress bar. With the TV's link down every key wears the down
 * dress, can't be pressed, and the reason shows above them; the sheet stays open and comes back
 * to life with the link.
 */
@Composable
internal fun PlaybackSheet(
    showing: CardShowing,
    tvName: String,
    appIcons: Map<String, String>,
    downReason: String?,
    onKey: (Int) -> Unit,
    onDismiss: () -> Unit,
    barToggles: (@Composable () -> Unit)? = null,
    barDownReason: String? = null,
    cast: io.kud.tutti.protocol.cast.CastNowPlaying? = null,
    onScrub: ((Int) -> Unit)? = null,
    appShortcuts: (@Composable () -> Unit)? = null,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = { NowShowingSheetHeader(stringResource(R.string.playback_label), showing, tvName, appIcons, cast?.takeIf { castFits(showing) }) },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                downReason?.let { ReasonLine(it) }
                // How far rewind and fast forward go is the app's choice: TalkBack hears it on
                // those two keys, where the quiet footnote used to say it (Iris, 2026-10-27).
                val appDecides = stringResource(R.string.playback_note)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (code in PLAYBACK_TRANSPORT) {
                        val main = code == V2Key.MEDIA_PLAY_PAUSE
                        val skip = code == V2Key.MEDIA_REWIND || code == V2Key.MEDIA_FAST_FORWARD
                        PlaybackKey(
                            code,
                            size = if (main) 72.dp else 56.dp,
                            shape = CircleShape,
                            main = main,
                            downReason = downReason,
                            onKey = onKey,
                            hint = appDecides.takeIf { skip },
                        )
                    }
                }
                // The room kept for the per-app row (Erwann, 2026-10-27: commands for the app on
                // the TV, such as YouTube's), under the transport keys and above the chips. None yet.
                appShortcuts?.invoke()
                // Captions and Audio track open a picker on the TV; the bar's Voice and Night switch.
                // One row of compact chips, two when it doesn't fit (PlaybackChips.kt).
                PlaybackChipRow(Modifier.fillMaxWidth()) {
                    for (code in PLAYBACK_TRACKS) KeyChip(code, downReason, onKey)
                    barToggles?.invoke()
                }
                if (barToggles != null && barDownReason != null) ReasonLine(barDownReason)
                onScrub?.let { ScrubStrip(downReason, appDecides, it) }
            }
        }
    }
}

/** Why the controls under it can't be pressed, in words beside a quiet glyph. */
@Composable
private fun ReasonLine(reason: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The scrub strip under the chips (benchmark idea 2): a sideways drag moves back
 * or forward, one key per [SCRUB_STEP_DP] of travel ([ScrubTrack], [scrubKey]) and one select
 * haptic per key, so the thumb counts the steps. It names no duration and shows no target
 * position: the app decides how far a key goes and no source tells tutti where playback is.
 * Ticks along the bottom edge follow the finger and settle back on the lift. TalkBack hears it as
 * one control with "Rewind" and "Fast forward" actions, and [appDecides] as its state. Down: the
 * dashed ring, no drag, the reason as its state.
 */
@Composable
private fun ScrubStrip(downReason: String?, appDecides: String, onKey: (Int) -> Unit) {
    val haptics = rememberHaptics()
    val send by rememberUpdatedState(onKey)
    val stepPx = with(LocalDensity.current) { SCRUB_STEP_DP.dp.toPx() }
    val track = remember(stepPx) { ScrubTrack(stepPx) }
    var offset by remember { mutableFloatStateOf(0f) }
    val down = downReason != null
    val shape = RoundedCornerShape(16.dp)
    val ring = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (down) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val tick = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val label = stringResource(R.string.scrub_label)
    val back = keyLabel(V2Key.MEDIA_REWIND)
    val forward = keyLabel(V2Key.MEDIA_FAST_FORWARD)
    fun step(delta: Int) {
        haptics.select()
        send(scrubKey(delta))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .then(
                if (down) {
                    Modifier.drawBehind { drawDownRing(shape.createOutline(size, layoutDirection, this), ring) }
                } else {
                    Modifier.background(fill, shape)
                },
            )
            .drawBehind {
                if (down) return@drawBehind
                val gap = SCRUB_TICK_GAP_DP.dp.toPx()
                val height = 6.dp.toPx()
                val shift = ((offset % gap) + gap) % gap
                var x = shift
                while (x < size.width) {
                    drawLine(tick, Offset(x, size.height - height - 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    x += gap
                }
            }
            .then(
                if (down) {
                    Modifier
                } else {
                    Modifier.pointerInput(track) {
                        detectHorizontalDragGestures(
                            onDragStart = { track.reset() },
                            onDragEnd = { offset = 0f },
                            onDragCancel = { offset = 0f },
                        ) { change, amount ->
                            change.consume()
                            offset += amount
                            track.move(amount).forEach(::step)
                        }
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = downReason ?: appDecides
                if (!down) {
                    customActions = listOf(
                        CustomAccessibilityAction(back) { step(-1); true },
                        CustomAccessibilityAction(forward) { step(1); true },
                    )
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(keyIcon(V2Key.MEDIA_REWIND)), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(painterResource(keyIcon(V2Key.MEDIA_FAST_FORWARD)), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/** Spacing of the strip's ticks: closer than a step, so a step crosses a few of them. */
private const val SCRUB_TICK_GAP_DP = 12f

/**
 * One media key: a glyph (and its name when [wide]), 56 dp or more. Up: a tonal fill (the
 * primary container for play/pause, the one key the thumb looks for). Down: no fill and the
 * dashed ring, the glyph in onSurfaceVariant, not clickable, TalkBack hearing why.
 */
@Composable
private fun PlaybackKey(
    code: Int,
    size: Dp,
    shape: Shape,
    main: Boolean,
    downReason: String?,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    hint: String? = null,
) {
    val label = keyLabel(code)
    val down = downReason != null
    val fill = if (main) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = when {
        down -> MaterialTheme.colorScheme.onSurfaceVariant
        main -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val ring = MaterialTheme.colorScheme.outline
    val base = (if (wide) modifier.heightIn(min = size) else modifier.size(size))
        .clip(shape)
        .then(
            if (down) {
                Modifier.drawBehind { drawDownRing(shape.createOutline(this.size, layoutDirection, this), ring) }
            } else {
                Modifier.background(fill, shape)
            },
        )
        .clickable(enabled = !down, role = Role.Button, onClickLabel = label) { onKey(code) }
        .semantics(mergeDescendants = true) {
            contentDescription = if (hint == null) label else "$label. $hint"
            downReason?.let { stateDescription = it }
        }
    Row(base.padding(horizontal = if (wide) 16.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(painterResource(keyIcon(code)), contentDescription = null, tint = ink, modifier = Modifier.size(if (main) 32.dp else 24.dp))
        if (wide) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The card's long press on an app (Erwann, 2026-10-25: « aller sur home de l'app ou kill l'app »),
 * offering only what tutti can do ([appActions]): pin or unpin, and close the TV's apps, which
 * Sony's IP control does for every app at once, so the row says so and a dialog asks first.
 */
@Composable
internal fun AppActionsSheet(
    showing: CardShowing,
    app: NowShowing.App,
    tvName: String,
    appIcons: Map<String, String>,
    actions: List<AppAction>,
    pinnedAt: Int?,
    closeDown: Boolean,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onCloseApps: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = { NowShowingSheetHeader(stringResource(R.string.app_actions_label), showing, tvName, appIcons) },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (action in actions) {
                    when (action) {
                        AppAction.PIN -> ActionRow(R.drawable.ic_add, stringResource(R.string.app_actions_pin), stringResource(R.string.app_actions_pin_detail), onClick = onPin)
                        AppAction.UNPIN -> ActionRow(
                            R.drawable.ic_remove,
                            stringResource(R.string.app_actions_unpin),
                            stringResource(R.string.app_actions_unpin_detail, (pinnedAt ?: 0) + 1),
                            onClick = onUnpin,
                        )
                        AppAction.CLOSE_APPS -> ActionRow(
                            R.drawable.ic_close,
                            stringResource(R.string.app_actions_close),
                            stringResource(if (closeDown) R.string.app_actions_close_down else R.string.app_actions_close_detail),
                            enabled = !closeDown,
                            onClick = { confirming = true },
                        )
                    }
                }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.app_actions_close_confirm_title)) },
            text = { Text(stringResource(R.string.app_actions_close_confirm_text, app.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onCloseApps()
                }) { Text(stringResource(R.string.app_actions_close_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A row like the Power sheet's: the glyph on its tile, the action and what it does. Disabled: dashed tile, no fill. */
@Composable
private fun ActionRow(@DrawableRes icon: Int, name: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val ring = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .then(if (enabled) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape) else Modifier.drawBehind { drawDownRing(shape.createOutline(size, layoutDirection, this), ring) })
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = name, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(if (enabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

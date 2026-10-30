package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.kud.tutti.protocol.cast.CastPlayerState
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
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
        CastLead(showing, 48.dp, appIcons, cast)
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
 * state Cast didn't report: play/pause is one toggle glyph, the header names the Cast title when
 * there is one, and a timeline shows only for a trusted Cast time; otherwise the jog wheel. With
 * the TV's link down every key wears the down dress, can't be pressed, and the reason shows above
 * them; the sheet stays open and comes back to life with the link.
 */
@Composable
internal fun PlaybackSheet(
    showing: CardShowing,
    tvName: String,
    appIcons: Map<String, String>,
    downReason: String?,
    onKey: (Int) -> Unit,
    onDismiss: () -> Unit,
    cast: io.kud.tutti.protocol.cast.CastNowPlaying? = null,
    onScrub: ((Int) -> Unit)? = null,
    appShortcuts: (@Composable () -> Unit)? = null,
) {
    val shownCast = cast?.takeIf { castFits(showing) }
    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = { NowShowingSheetHeader(stringResource(R.string.playback_label), showing, tvName, appIcons, shownCast) },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                // Three groups now (keys, chips, the strip), so they breathe at 16 dp, not 12.
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                downReason?.let { ReasonLine(it) }
                // How far rewind and fast forward go is the app's choice: TalkBack hears it on
                // those two keys and as the jog wheel's state (Iris, 2026-10-27).
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
                // the TV, such as YouTube's), under the transport keys and above the chips.
                appShortcuts?.invoke()
                // Captions and Audio track open a picker on the TV: one row of two compact chips.
                // No sound settings here (Erwann, 2026-10-28: « Pourquoi on a encore des settings
                // de son ici ? »): Voice and Night live in the Sound sheet.
                PlaybackChipRow(Modifier.fillMaxWidth()) {
                    for (code in PLAYBACK_TRACKS) KeyChip(code, downReason, onKey)
                }
                // A timeline when Cast gives a trusted time, else the jog wheel (Erwann, 2026-10-28).
                // Fade through on a switch, never every second: the key is the kind, not the time.
                onScrub?.let { send ->
                    val now by produceState(System.currentTimeMillis(), shownCast) {
                        while (true) {
                            value = System.currentTimeMillis()
                            if (shownCast?.state != CastPlayerState.PLAYING) break
                            delay(1_000L - value % 1_000L)
                        }
                    }
                    AnimatedContent(
                        targetState = playbackStrip(shownCast, now),
                        contentKey = { it is PlaybackStrip.Timeline },
                        transitionSpec = {
                            fadeIn(tween(Motion.MEDIUM, delayMillis = Motion.FADE_OUT)) togetherWith fadeOut(tween(Motion.FADE_OUT))
                        },
                        label = "playback-strip",
                        modifier = Modifier.fillMaxWidth(),
                    ) { strip ->
                        when (strip) {
                            is PlaybackStrip.Timeline -> Timeline(strip, shownCast?.state)
                            PlaybackStrip.JogWheel -> JogWheel(downReason, appDecides, send)
                        }
                    }
                }
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
 * Where playback is, when Cast says so and the reading is trusted (`playbackStrip`): elapsed at
 * the start, the total at the end, a 4 dp track filled to the position and a playhead mark on it,
 * so right is always later. Display only, with no thumb to grab: no seek reaches the TV (Cast stays
 * read-only, architecture.md § 7), and skipping stays on the transport row's ⏪ ⏩. Paused and
 * loading say so in words between the times. It steps once a second with no animated fill, so
 * reduced motion needs nothing extra. TalkBack hears "Playback position" and the minutes as its
 * state, which changes once a minute, never every tick.
 */
@Composable
private fun Timeline(strip: PlaybackStrip.Timeline, state: CastPlayerState?) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceContainerHighest
    val fill = scheme.secondary
    val head = scheme.onSurface
    val label = stringResource(R.string.timeline_label)
    val spoken = stringResource(R.string.timeline_spoken, (strip.elapsedSec / 60.0).roundToLong(), (strip.totalSec / 60.0).roundToLong())
    val word = when (state) {
        CastPlayerState.PAUSED -> R.string.cast_paused
        CastPlayerState.BUFFERING -> R.string.cast_loading
        else -> null
    }?.let { stringResource(it) }
    val timeStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = STRIP_HEIGHT)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = listOfNotNull(spoken, word).joinToString(", ")
            },
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(16.dp)
                .drawBehind {
                    val y = size.height / 2
                    val stroke = 4.dp.toPx()
                    val x = size.width * strip.progress
                    drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
                    if (x > 0f) drawLine(fill, Offset(0f, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
                    // The playhead: a short upright bar, not a round thumb, so it marks a place
                    // without inviting a drag. Its shape says where, whatever the colours.
                    val w = 3.dp.toPx()
                    val hx = x.coerceIn(w / 2, size.width - w / 2)
                    drawLine(head, Offset(hx, 0f), Offset(hx, size.height), strokeWidth = w, cap = StrokeCap.Round)
                },
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(clockOf(strip.elapsedSec), style = timeStyle, color = scheme.onSurface)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (state == CastPlayerState.PAUSED) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                word?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1) }
            }
            Text(clockOf(strip.totalSec), style = timeStyle, color = scheme.onSurfaceVariant)
        }
    }
}

/**
 * The jog wheel, when no trusted time is known (Erwann, 2026-10-28: « Si on drag left ou right
 * c'est comme une roulette donc ça devrait faire l'opposé »): a wheel seen edge-on, its ticks
 * crowding and fading towards both ends like a cylinder's. The surface follows the finger and
 * stays where it is left, as a wheel does (no spring back, so nothing moves on its own and reduced
 * motion needs nothing). Pushing it left goes forward, right goes back ([JogTrack]): one rewind or
 * fast-forward key per [SCRUB_STEP_DP] of travel and one select haptic per key, so the thumb
 * counts the steps. At rest it reads "Spin to skip"; while turning, the name of the key it sends,
 * so the direction is said in words. It names no duration and no target: the app on the TV
 * decides how far a key goes. TalkBack hears "Jog wheel" with "Rewind" and "Fast forward"
 * actions and [appDecides] as its state. Down: the dashed ring, no drag, the reason as its state.
 */
@Composable
private fun JogWheel(downReason: String?, appDecides: String, onKey: (Int) -> Unit) {
    val haptics = rememberHaptics()
    val send by rememberUpdatedState(onKey)
    val stepPx = with(LocalDensity.current) { SCRUB_STEP_DP.dp.toPx() }
    val track = remember(stepPx) { JogTrack(stepPx) }
    var offset by remember { mutableFloatStateOf(0f) }
    var turning by remember { mutableStateOf<Int?>(null) }
    val down = downReason != null
    val shape = RoundedCornerShape(16.dp)
    val ring = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = if (down) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val name = stringResource(R.string.jog_name)
    val rest = stringResource(R.string.jog_label)
    val back = keyLabel(V2Key.MEDIA_REWIND)
    val forward = keyLabel(V2Key.MEDIA_FAST_FORWARD)
    fun step(delta: Int) {
        haptics.select()
        turning = delta
        send(scrubKey(delta))
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = STRIP_HEIGHT)
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
                // A cylinder of radius half the width: a tick at angle a sits at sin(a) from the
                // middle and is as tall and as strong as cos(a), so the middle reads flat and the
                // ends turn away. The surface under the finger moves one px per px dragged.
                val radius = size.width / 2
                val gap = SCRUB_TICK_GAP_DP.dp.toPx() / radius
                val turn = offset / radius
                val tallest = size.height * 0.36f
                val stroke = 1.5.dp.toPx()
                val shift = ((turn % gap) + gap) % gap
                var a = -PI / 2 + shift - gap * ceil((PI / 2) / gap)
                while (a < PI / 2) {
                    val depth = cos(a).toFloat()
                    if (a > -PI / 2 && depth > 0.05f) {
                        val x = radius + radius * sin(a).toFloat()
                        val h = tallest * (0.35f + 0.65f * depth)
                        drawLine(
                            tick.copy(alpha = 0.2f + 0.5f * depth),
                            Offset(x, size.height - 6.dp.toPx() - h),
                            Offset(x, size.height - 6.dp.toPx()),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                    a += gap
                }
            }
            .then(
                if (down) {
                    Modifier
                } else {
                    Modifier.pointerInput(track) {
                        detectHorizontalDragGestures(
                            onDragStart = { track.reset() },
                            onDragEnd = { turning = null },
                            onDragCancel = { turning = null },
                        ) { change, amount ->
                            change.consume()
                            offset += amount
                            track.move(amount).forEach(::step)
                        }
                    }
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = name
                stateDescription = downReason ?: appDecides
                if (!down) {
                    customActions = listOf(
                        CustomAccessibilityAction(back) { step(-1); turning = null; true },
                        CustomAccessibilityAction(forward) { step(1); turning = null; true },
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            turning?.let { delta ->
                Icon(painterResource(keyIcon(scrubKey(delta))), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                when (turning) {
                    null -> rest
                    1 -> forward
                    else -> back
                },
                style = MaterialTheme.typography.labelLarge,
                color = ink,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Spacing of the wheel's ticks at its middle: closer than a step, so a step crosses a few. */
private const val SCRUB_TICK_GAP_DP = 12f

/** The timeline and the wheel stand the same height, so the sheet doesn't jump between them. */
private val STRIP_HEIGHT = 56.dp

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
    // The main key wears OK's dress (secondaryContainer), the pad's own centre: bright primary means "on" only, and
    // play/pause claims no state (Iris, 2026-10-30).
    val fill = if (main) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = when {
        down -> MaterialTheme.colorScheme.onSurfaceVariant
        main -> MaterialTheme.colorScheme.onSecondaryContainer
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
    onHide: () -> Unit,
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
                        AppAction.HIDE -> ActionRow(
                            R.drawable.ic_visibility_off,
                            stringResource(R.string.app_actions_hide),
                            stringResource(R.string.app_actions_hide_detail),
                            onClick = onHide,
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

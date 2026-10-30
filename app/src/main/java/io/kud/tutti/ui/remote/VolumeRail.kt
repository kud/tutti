package io.kud.tutti.ui.remote

import io.kud.tutti.ui.motion.snappyOrFade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.common.tabular
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Vertical rail on the right edge: the soundbar's absolute volume (0–100), the second loudest
 * element. Dragging anywhere moves the level relatively (a stray tap never jumps the volume);
 * a tick per step, and the controller's bump at 0, 100 or the scene's cap.
 *
 * Three states, told apart by shape as well as colour: connected (the level's number under + and
 * the fill, always shown; Erwann, 2026-10-24: "je veux que le niveau de volume reste affiché"),
 * muted (the fill greys, is hatched, the speaker takes its slash and its disc inverts), and not
 * connected (no number and no fill, a hollow track, the keys off and the speaker crossed).
 *
 * Three keys, one family (Erwann, 2026-10-24: "un bouton + et -, possiblement en haut et en bas";
 * "pas ouf la transparence de l'icône"): + at the top, the readout under it, − just above the
 * speaker, and the speaker (mute) at the foot where it always was. Each is a 48 dp target on a
 * solid disc in the surface colour with a hairline ring, its icon at full strength in onSurface, so
 * it reads the same over the fill and over the empty track, whatever the level. A press of + or −
 * steps the bar by one at once (a tick inside the range, the controller's bump and the rail's
 * stretch at a wall), and a hold repeats on [KeyRepeat]'s rhythm. A tap on the speaker toggles
 * mute. Keys only take a tap: the rail's drag claims past touch slop, so a drag that starts on a
 * key still moves the level and cancels the tap or the repeat. Each key is its own accessibility
 * node ("Volume up", "Volume down", "Mute"); while the bar is unreachable they lose their disc and
 * say disabled, + and − dimmed and the speaker crossed, so off is a shape too.
 *
 * While dragged the rail widens 64 → 72 dp over its neighbours' gap, so the layout never moves.
 * Reaching 0 or the cap stretches it 3 % against the wall and springs back (the controller plays
 * the bump); the cap is a dashed line that thickens while the level presses on it.
 */
@Composable
fun VolumeRail(
    volume: Int?,
    cap: Int?,
    muted: Boolean,
    enabled: Boolean,
    onVolume: (Int) -> Unit,
    onStep: (Int) -> Unit,
    onMute: () -> Unit,
    modifier: Modifier = Modifier,
    /** The high-volume guard's line while it can stop a change ([TuttiUiState.volumeGuardLine]): the rail's soft wall. */
    guardLine: Int? = null,
    /** A tap on the level's number opens the volume sheet (exact level, saved levels, cap for tonight). */
    onOpenLevel: (() -> Unit)? = null,
    /** Levels felt as a detent rather than a tick ([TuttiUiState.volumeLandmarks]). */
    landmarks: List<Int> = emptyList(),
) {
    val haptics = rememberHaptics()
    val currentGuard by rememberUpdatedState(guardLine)
    val currentLandmarks by rememberUpdatedState(landmarks)
    var dragLevel by remember { mutableStateOf<Int?>(null) }
    val currentVolume by rememberUpdatedState(volume)
    val currentCap by rememberUpdatedState(cap)
    val setVolume by rememberUpdatedState(onVolume)
    val toggleMute by rememberUpdatedState(onMute)
    val stepBy by rememberUpdatedState(onStep)
    val connected = enabled
    val scope = rememberCoroutineScope()
    val reduced by rememberUpdatedState(LocalReducedMotion.current)
    val stretch = remember { Animatable(1f) }
    var stretchOrigin by remember { mutableFloatStateOf(1f) }
    var onCap by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState(
        if (dragLevel != null) DRAG_WIDTH else RAIL_WIDTH,
        if (LocalReducedMotion.current) snap() else Motion.snappy(),
        label = "railWidth",
    )
    val shown = if (connected) dragLevel ?: volume else null
    val fraction by animateFloatAsState((shown ?: 0) / TuttiController.MAX_VOLUME.toFloat(), snappyOrFade(), label = "volume")
    val scheme = MaterialTheme.colorScheme
    // The amount is a gauge of its own between the readout and −, never under a key: from the foot
    // of the rail it once rose round Mute as a bright crescent that read as "mute on" (Iris,
    // 2026-10-30). Not the accent either: bright primary means "on" and nothing else, and a gauge is
    // an amount (design.md, Visual › State). secondary is light on the dark track, dark in light.
    val fill = if (muted) scheme.outline else scheme.secondary
    val track = scheme.surfaceContainerHighest
    val capColor = scheme.tertiary
    val hatch = scheme.surface.copy(alpha = HATCH_ALPHA)
    val label = stringResource(R.string.volume_label)
    val openLabel = stringResource(R.string.volume_open_sheet)
    val openLevel by rememberUpdatedState(onOpenLevel)
    val state = when {
        !connected -> stringResource(R.string.volume_state_disconnected)
        shown == null -> stringResource(R.string.volume_state_unknown)
        muted -> stringResource(R.string.volume_state_muted, shown)
        else -> shown.toString()
    }

    // A step key: the controller steps (and bumps at a wall); here, the tick inside the range or the
    // stretch against the wall, as a drag gets.
    val step = step@{ delta: Int ->
        stepBy(delta)
        // An unknown level: the controller asks the bar for it; nothing to tick yet.
        val now = currentVolume ?: return@step
        val ceiling = wallFor(currentCap, currentGuard, now)
        val next = VolumeSteps.step(now, delta, ceiling)
        val feel = VolumeSteps.feel(now, next, ceiling, currentLandmarks)
        if (feel == StepFeel.DETENT) {
            haptics.detent()
        } else if (feel == StepFeel.TICK) {
            haptics.tick()
        } else if (!reduced) {
            stretchOrigin = if (next <= 0) 0f else 1f
            scope.launch {
                stretch.animateTo(STRETCH, Motion.pressIn())
                stretch.animateTo(1f, Motion.snappy())
            }
        }
    }

    // The slot keeps its rest width; the rail widens over the gap around it while dragged.
    Box(modifier.width(RAIL_WIDTH).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .requiredWidth(railWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    scaleY = stretch.value
                    transformOrigin = TransformOrigin(0.5f, stretchOrigin)
                }
                .clip(RoundedCornerShape(32.dp))
                .background(if (connected) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
                .then(
                    if (connected) Modifier
                    else Modifier.border(1.5.dp, scheme.outlineVariant, RoundedCornerShape(32.dp)),
                )
                // A slider to TalkBack: the level as its value (the readout itself is silent), and
                // the volume keys or a swipe set it while the bar is reachable.
                .semantics {
                    contentDescription = label
                    stateDescription = state
                    if (connected && shown != null) {
                        progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..(currentCap ?: TuttiController.MAX_VOLUME).toFloat())
                        setProgress { target ->
                            setVolume(target.roundToInt())
                            true
                        }
                        if (onOpenLevel != null) {
                            customActions = listOf(CustomAccessibilityAction(openLabel) { openLevel?.invoke(); true })
                        }
                    } else {
                        disabled()
                    }
                }
                .drawWithContent {
                    val gauge = gaugeBounds(size.height, GAUGE_TOP.toPx(), GAUGE_BOTTOM.toPx(), GAUGE_WIDTH.toPx())
                    if (connected && gauge != null) {
                        val w = GAUGE_WIDTH.toPx()
                        val x = (size.width - w) / 2f
                        val radius = CornerRadius(w / 2f)
                        drawRoundRect(track, Offset(x, gauge.start), Size(w, gauge.endInclusive - gauge.start), radius)
                        val f = fraction.coerceIn(0f, 1f)
                        if (f > 0f) {
                            // At least a round dot, so level 1 still shows.
                            val h = maxOf(f * (gauge.endInclusive - gauge.start), w)
                            val bar = Path().apply {
                                addRoundRect(RoundRect(x, gauge.endInclusive - h, x + w, gauge.endInclusive, radius))
                            }
                            drawPath(bar, fill)
                            // Muted is a pattern, not a hue: diagonal hatching over the greyed fill.
                            if (muted) {
                                clipPath(bar) {
                                    val gap = HATCH_GAP.toPx()
                                    var y = gauge.endInclusive - h - w
                                    while (y < gauge.endInclusive) {
                                        drawLine(hatch, Offset(x, y + w), Offset(x + w, y), strokeWidth = 2.dp.toPx())
                                        y += gap
                                    }
                                }
                            }
                        }
                    }
                    drawContent()
                    val c = currentCap
                    if (c != null && connected && gauge != null) {
                        val y = gauge.endInclusive - (gauge.endInclusive - gauge.start) * (c / TuttiController.MAX_VOLUME.toFloat())
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                        val width = (if (onCap) 3.dp else 2.dp).toPx()
                        drawLine(capColor, Offset(10.dp.toPx(), y), Offset(size.width - 10.dp.toPx(), y), strokeWidth = width, pathEffect = dash)
                    }
                }
                .pointerInput(connected, haptics) {
                    if (!connected) return@pointerInput
                    var start = 0
                    var travelled = 0f
                    var asked = 0
                    detectVerticalDragGestures(
                        onDragStart = {
                            start = currentVolume ?: 0
                            travelled = 0f
                            dragLevel = start
                            asked = start
                        },
                        onDragEnd = {
                            dragLevel = null
                            onCap = false
                        },
                        onDragCancel = {
                            dragLevel = null
                            onCap = false
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            travelled -= dy
                            val ceiling = currentCap ?: TuttiController.MAX_VOLUME
                            val wanted = VolumeSteps.level(start, travelled / size.height, TuttiController.MAX_VOLUME * SENSITIVITY, ceiling)
                            // The guard's line is a wall the finger can't drag through: the rail stops on
                            // it and the controller asks (it hears every level the finger asks for).
                            val wall = wallFor(ceiling, currentGuard, start)
                            val level = minOf(wanted, wall)
                            if (wanted != asked && wanted > wall) {
                                asked = wanted
                                setVolume(wanted)
                            }
                            if (level != dragLevel) {
                                val from = dragLevel ?: start
                                dragLevel = level
                                onCap = currentCap != null && level == ceiling
                                val feel = VolumeSteps.feel(from, level, wall, currentLandmarks)
                                if (feel == StepFeel.DETENT) {
                                    haptics.detent()
                                } else if (feel == StepFeel.TICK) {
                                    haptics.tick()
                                } else if (!reduced) {
                                    // Against a wall: stretch away from it and spring back.
                                    stretchOrigin = if (level == 0) 0f else 1f
                                    scope.launch {
                                        stretch.animateTo(STRETCH, Motion.pressIn())
                                        stretch.animateTo(1f, Motion.snappy())
                                    }
                                }
                                if (wanted <= wall) {
                                    asked = level
                                    setVolume(level)
                                }
                            }
                        },
                    )
                },
        ) {
            // Always a number while connected (the controller keeps the last level until the bar
            // echoes a new one); an ellipsis while its level is not reported yet; nothing while the
            // bar is unreachable (the crossed speaker and the hollow track say so), never a stale level,
            // and never a dash that would read as the − key under it.
            if (connected) {
                val opens = onOpenLevel != null && shown != null
                // The number is a door (Iris's gesture audit): a tap opens the volume sheet. It sits
                // in a key of its own, the rail keys' dress (surface, hairline ring) drawn as a
                // rounded square, so it reads as pressable by its shape. The small up chevron under
                // it went (Iris, 2026-10-30): beside + and − it read as "louder", not "opens".
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = KEY_INSET + KEY_TARGET)
                        .size(KEY_TARGET)
                        .then(
                            if (onOpenLevel != null) {
                                Modifier
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = openLabel
                                        stateDescription = state
                                    }
                                    .pointerInput(Unit) { detectTapGestures { openLevel?.invoke() } }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = KEY_TARGET, height = READOUT_HEIGHT)
                            .then(
                                if (opens) {
                                    Modifier
                                        .background(scheme.surface, READOUT_SHAPE)
                                        .border(1.dp, scheme.outlineVariant, READOUT_SHAPE)
                                } else {
                                    Modifier
                                },
                            )
                            .clearAndSetSemantics { },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = shown?.toString() ?: NO_LEVEL,
                            // Tabular figures: the number keeps its width while a drag runs through it.
                            style = MaterialTheme.typography.titleLarge.tabular(),
                            fontWeight = FontWeight.SemiBold,
                            color = if (shown == null) scheme.onSurfaceVariant else scheme.onSurface,
                        )
                    }
                }
            }
            RailKey(
                icon = R.drawable.ic_add,
                label = stringResource(R.string.volume_up),
                enabled = connected,
                onPress = { step(+1) },
                repeats = true,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = KEY_INSET),
            )
            RailKey(
                icon = R.drawable.ic_remove,
                label = stringResource(R.string.volume_down),
                enabled = connected,
                onPress = { step(-1) },
                repeats = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = MINUS_BOTTOM),
            )
            // One speaker, always at the foot, so it never moves: the mute key while connected (the
            // slash and an inverted disc when muted), the crossed speaker, untappable, when the bar is gone.
            RailKey(
                icon = when {
                    !connected -> R.drawable.ic_no_sound
                    muted -> R.drawable.ic_volume_off
                    else -> R.drawable.ic_volume_up
                },
                label = stringResource(if (muted) R.string.volume_unmute else R.string.volume_mute),
                state = if (muted) stringResource(R.string.volume_muted) else null,
                enabled = connected,
                selected = muted,
                dimWhenOff = false,
                onPress = {
                    haptics.click()
                    toggleMute()
                },
                repeats = false,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = FOOT_INSET + FOOT_ICON / 2 - FOOT_TARGET / 2),
            )
        }
    }
}

/**
 * A key on the rail: a 48 dp target, its icon at full strength on a solid disc (the surface colour,
 * a hairline ring) while [enabled], so it reads over the fill and the empty track alike. [selected]
 * (mute on) inverts the disc. Off: no disc, the node disabled and, if [dimWhenOff], the icon dimmed.
 * [repeats]: a press acts at once and a hold repeats ([KeyRepeat]) until the finger lifts or the
 * rail's drag claims it; otherwise it acts on a tap.
 */
@Composable
private fun RailKey(
    icon: Int,
    label: String,
    enabled: Boolean,
    onPress: () -> Unit,
    repeats: Boolean,
    modifier: Modifier = Modifier,
    state: String? = null,
    selected: Boolean = false,
    dimWhenOff: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val act by rememberUpdatedState(onPress)
    val disc = if (selected) scheme.onSurface else scheme.surface
    val ink = when {
        !enabled -> scheme.onSurfaceVariant
        selected -> scheme.surface
        else -> scheme.onSurface
    }
    Box(
        modifier
            .size(KEY_TARGET)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (state != null) stateDescription = state
                if (!enabled) disabled()
            }
            // Its own detector: the rail's drag sees the down too, and claims it only past slop,
            // which cancels this press; a key never moves the level by itself.
            .pointerInput(enabled, repeats) {
                if (!enabled) return@pointerInput
                if (repeats) {
                    detectTapGestures(
                        onPress = {
                            act()
                            coroutineScope {
                                val repeat = launch { KeyRepeat.run { act() } }
                                tryAwaitRelease()
                                repeat.cancel()
                            }
                        },
                    )
                } else {
                    detectTapGestures { act() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) {
            Box(
                Modifier
                    .size(KEY_DISC)
                    .background(disc, CircleShape)
                    .border(1.dp, scheme.outlineVariant, CircleShape),
            )
        }
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(FOOT_ICON).alpha(if (enabled || !dimWhenOff) 1f else OFF_ALPHA),
        )
    }
}

internal val RAIL_WIDTH = 64.dp
private val DRAG_WIDTH = 72.dp
private val HATCH_GAP = 8.dp
private const val HATCH_ALPHA = 0.45f

/** How far the rail stretches against 0 or the cap: 3 %, then it springs back. */
private const val STRETCH = 1.03f

private val FOOT_INSET = 18.dp
private val FOOT_ICON = 20.dp
private val FOOT_TARGET = 48.dp
/** Every key's solid disc: 36 dp, clear of the 64 dp rail's edges, so it reads as a key on it. */
private val KEY_DISC = 36.dp

/** + sits at the top as the speaker sits at the foot: its glyph 18 dp in, like the speaker's. */
private val KEY_INSET = 4.dp
private val KEY_TARGET = 48.dp

/** − stands on the speaker's target: the two 48 dp targets meet, neither overlaps. */
private val MINUS_BOTTOM = FOOT_INSET + FOOT_ICON / 2 + FOOT_TARGET / 2

/** The readout's key: a rounded square inside its 48 dp target, centred under +. */
private val READOUT_HEIGHT = 40.dp
private val READOUT_SHAPE = RoundedCornerShape(14.dp)

/**
 * The gauge: a 12 dp pill centred on the rail, from 8 dp under the readout's key to 8 dp over −'s
 * target, so no key ever sits on the amount and the speaker never wears it.
 */
private val GAUGE_WIDTH = 12.dp
private val GAUGE_TOP = KEY_INSET + KEY_TARGET * 2 + 8.dp
private val GAUGE_BOTTOM = MINUS_BOTTOM + KEY_TARGET + 8.dp

/** The number while connected and the level is not reported yet: waiting, not zero. */
private const val NO_LEVEL = "…"

/** A step key while the bar is unreachable: dimmed, on top of losing its disc. */
private const val OFF_ALPHA = 0.38f


/**
 * Where a step or a drag stops: the ceiling, or the high-volume guard's line while the level is
 * at or under it (the controller then asks before going on).
 */
internal fun wallFor(ceiling: Int?, guard: Int?, from: Int): Int {
    val top = ceiling ?: TuttiController.MAX_VOLUME
    return if (guard != null && from <= guard && guard < top) guard else top
}

/**
 * The gauge's top and foot in a rail [height] px tall, or null when the rail is too short to hold
 * one taller than it is wide (a squeezed layout): then the number alone says the level.
 */
internal fun gaugeBounds(height: Float, top: Float, bottom: Float, width: Float): ClosedFloatingPointRange<Float>? {
    val foot = height - bottom
    return if (foot - top > width) top..foot else null
}

/** One full rail height covers 60 steps: precise without being slow. */
private const val SENSITIVITY = 0.6f

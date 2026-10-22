package io.kud.tutti.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.rememberHaptics
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/**
 * The pad: one large quiet surface, no grid lines. Touchpad (swipe steps) or D-pad (its ring
 * only), switchable in one move. Tap = OK; long press = held Select for the TV.
 * Two gestures only, in touchpad mode: two-finger tap = play/pause, two-finger vertical drag = bar volume.
 */
@Composable
fun Pad(
    mode: PadMode,
    onModeChange: (PadMode) -> Unit,
    onKey: (Int) -> Unit,
    onLongSelect: () -> Unit,
    onPlayPause: () -> Unit,
    onVolumeStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val glowAlpha = remember { Animatable(0f) }
    var glow by remember { mutableStateOf(Offset.Unspecified) }
    val key by rememberUpdatedState(onKey)
    val longSelect by rememberUpdatedState(onLongSelect)
    val playPause by rememberUpdatedState(onPlayPause)
    val volumeStep by rememberUpdatedState(onVolumeStep)
    val glowColor = MaterialTheme.colorScheme.primary
    val ringColor = MaterialTheme.colorScheme.outlineVariant
    val centreColor = MaterialTheme.colorScheme.secondaryContainer

    fun flash(at: Offset) {
        glow = at
        scope.launch {
            glowAlpha.snapTo(0.28f)
            glowAlpha.animateTo(0f, tween(durationMillis = 260))
        }
    }

    val padLabel = stringResource(R.string.pad_label)
    val gestures = if (mode == PadMode.TOUCHPAD) {
        Modifier.pointerInput(haptics) {
            touchpadGestures(
                onStep = { code ->
                    haptics.tick()
                    key(code)
                },
                onTap = { at ->
                    haptics.select()
                    flash(at)
                    key(V2Key.DPAD_CENTER)
                },
                onLong = {
                    haptics.longPress()
                    longSelect()
                },
                onTwoFingerTap = {
                    haptics.select()
                    playPause()
                },
                onTwoFingerStep = { delta ->
                    haptics.tick()
                    volumeStep(delta)
                },
                onMove = { at -> glow = at },
            )
        }
    } else {
        Modifier.pointerInput(haptics) {
            detectTapGestures(
                onTap = { at ->
                    val code = dpadZone(at, size.width.toFloat(), size.height.toFloat())
                    if (code == V2Key.DPAD_CENTER) haptics.select() else haptics.tick()
                    flash(at)
                    key(code)
                },
                onLongPress = { at ->
                    if (dpadZone(at, size.width.toFloat(), size.height.toFloat()) == V2Key.DPAD_CENTER) {
                        haptics.longPress()
                        longSelect()
                    }
                },
            )
        }
    }

    // The switch is the surface's sibling, not its child: touchpadGestures reads every touch,
    // consumed or not, so a tap on a child button also reached the TV as OK.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = padLabel }
                .drawBehind {
                    if (glow.isSpecified && glowAlpha.value > 0f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(glowColor.copy(alpha = glowAlpha.value), glowColor.copy(alpha = 0f)),
                                center = glow,
                                radius = 120.dp.toPx(),
                            ),
                            radius = 120.dp.toPx(),
                            center = glow,
                        )
                    }
                }
                .then(gestures),
        ) {
            if (mode == PadMode.DPAD) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = min(size.width, size.height) / 2f
                    val stroke = radius * 0.30f
                    drawCircle(ringColor, radius = radius * 0.80f - stroke / 2f, style = Stroke(width = stroke))
                    drawCircle(centreColor, radius = radius * 0.30f)
                }
            }
        }
        IconButton(
            onClick = {
                haptics.click()
                onModeChange(if (mode == PadMode.TOUCHPAD) PadMode.DPAD else PadMode.TOUCHPAD)
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            Icon(
                painter = painterResource(if (mode == PadMode.TOUCHPAD) R.drawable.ic_dpad else R.drawable.ic_touchpad),
                contentDescription = stringResource(
                    if (mode == PadMode.TOUCHPAD) R.string.pad_switch_dpad else R.string.pad_switch_touchpad,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dpadZone(at: Offset, width: Float, height: Float): Int {
    val dx = at.x - width / 2f
    val dy = at.y - height / 2f
    val radius = min(width, height) / 2f
    return when {
        Offset(dx, dy).getDistance() < radius * 0.34f -> V2Key.DPAD_CENTER
        abs(dx) > abs(dy) -> if (dx > 0) V2Key.DPAD_RIGHT else V2Key.DPAD_LEFT
        else -> if (dy > 0) V2Key.DPAD_DOWN else V2Key.DPAD_UP
    }
}

/**
 * One gesture at a time: a swipe emits a D-pad step every [STEP_DP] along its dominant axis;
 * a still finger held past the long-press timeout is a long Select; two fingers become
 * play/pause (tap) or bar volume (vertical drag, one step per [VOLUME_STEP_DP]).
 */
private suspend fun PointerInputScope.touchpadGestures(
    onStep: (Int) -> Unit,
    onTap: (Offset) -> Unit,
    onLong: () -> Unit,
    onTwoFingerTap: () -> Unit,
    onTwoFingerStep: (Int) -> Unit,
    onMove: (Offset) -> Unit,
) {
    val stepPx = STEP_DP.dp.toPx()
    val volumeStepPx = VOLUME_STEP_DP.dp.toPx()
    val slop = viewConfiguration.touchSlop
    val longPressMillis = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var pointers = 1
        var moved = false
        var longFired = false
        var travel = Offset.Zero
        var accumulated = Offset.Zero
        var volumeAccumulated = 0f
        var volumeTravel = 0f
        var lastUptime = down.uptimeMillis
        while (true) {
            val event: PointerEvent? = if (!moved && !longFired && pointers == 1) {
                val remaining = (longPressMillis - (lastUptime - down.uptimeMillis)).coerceAtLeast(1L)
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                longFired = true
                onLong()
                continue
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            lastUptime = event.changes.first().uptimeMillis
            if (pressed.size > pointers) pointers = pressed.size
            if (pointers >= 2) {
                val dy = pressed.map { it.positionChange().y }.average().toFloat()
                volumeAccumulated += dy
                volumeTravel += abs(dy)
                if (volumeTravel > slop) moved = true
                while (volumeAccumulated <= -volumeStepPx) {
                    onTwoFingerStep(1)
                    volumeAccumulated += volumeStepPx
                }
                while (volumeAccumulated >= volumeStepPx) {
                    onTwoFingerStep(-1)
                    volumeAccumulated -= volumeStepPx
                }
            } else if (!longFired) {
                val change = pressed.first()
                val delta = change.positionChange()
                onMove(change.position)
                travel += delta
                accumulated += delta
                if (!moved && travel.getDistance() > slop) moved = true
                if (moved) {
                    if (abs(accumulated.x) >= stepPx && abs(accumulated.x) >= abs(accumulated.y)) {
                        val right = accumulated.x > 0
                        onStep(if (right) V2Key.DPAD_RIGHT else V2Key.DPAD_LEFT)
                        accumulated = Offset(accumulated.x - if (right) stepPx else -stepPx, 0f)
                    } else if (abs(accumulated.y) >= stepPx && abs(accumulated.y) > abs(accumulated.x)) {
                        val downwards = accumulated.y > 0
                        onStep(if (downwards) V2Key.DPAD_DOWN else V2Key.DPAD_UP)
                        accumulated = Offset(0f, accumulated.y - if (downwards) stepPx else -stepPx)
                    }
                }
            }
            event.changes.forEach { it.consume() }
        }
        if (!moved && !longFired) {
            if (pointers >= 2) onTwoFingerTap() else onTap(down.position)
        }
    }
}

private const val STEP_DP = 44
private const val VOLUME_STEP_DP = 14

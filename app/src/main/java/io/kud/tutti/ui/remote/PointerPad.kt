package io.kud.tutti.ui.remote

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.protocol.hid.AirPointer
import io.kud.tutti.protocol.hid.MouseButton
import io.kud.tutti.protocol.hid.MouseStep
import io.kud.tutti.protocol.hid.TouchpadMotion
import io.kud.tutti.ui.common.rememberHaptics

/** The scroll strip's width and the click buttons' height: 48 dp targets (Iris, 2026-10-25). */
val SCROLL_STRIP = 48.dp
val CLICK_ROW = 48.dp

/** Whether the phone has a gyroscope at all; the gyro mode is never hidden for want of one, only disabled. */
@Composable
fun rememberHasGyroscope(): Boolean {
    val context = LocalContext.current
    return remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null }
}

/**
 * The pad for a Bluetooth target (the computer, or the TV over Bluetooth), in its pointing modes:
 * [PadMode.TOUCHPAD] is a trackpad (slide to move, tap to click, two fingers to scroll), and
 * [PadMode.GYRO] the phone as an air mouse (tap clicks, holding freezes the cursor while you
 * re-aim). Around it: a scroll strip on the [stripOnLeft] side, separate from the surface so a drag
 * reaching the edge never scrolls, and Left, Middle and Right buttons under it. The D-pad mode stays
 * the ordinary [Pad], its keys sent as arrows.
 *
 * Down ([down]): the surface stays, dashed and dimmed, and a touch bumps and sends nothing; TalkBack
 * hears [downReason]. The corner glyph cycles the modes ([onNextMode]); TalkBack gets one action
 * per mode, the gyro one saying its [gyroReason] when it can't run.
 */
@Composable
fun PointerPad(
    mode: PadMode,
    onMove: (List<MouseStep>) -> Unit,
    onClick: (Int) -> Unit,
    onNextMode: () -> Unit,
    onPickMode: (PadMode) -> Unit,
    modifier: Modifier = Modifier,
    stripOnLeft: Boolean = false,
    down: Boolean = false,
    downReason: String? = null,
    /** Why gyro can't run, or null when it can. */
    gyroReason: String? = null,
    corner: Dp = 28.dp,
) {
    val haptics = rememberHaptics()
    val move by rememberUpdatedState(onMove)
    val click by rememberUpdatedState(onClick)
    val motion = remember { TouchpadMotion() }
    val shape = RoundedCornerShape(corner)
    val outline = MaterialTheme.colorScheme.outline
    val gyro = mode == PadMode.GYRO

    val surfaceLabel = stringResource(if (gyro) R.string.pointer_gyro_label else R.string.pointer_pad_label)
    val hint = stringResource(if (gyro) R.string.pointer_gyro_hint else R.string.pointer_pad_hint)
    val modeLabel = stringResource(R.string.pad_mode_label)
    val names = mapOf(
        PadMode.TOUCHPAD to stringResource(R.string.pad_mode_touchpad),
        PadMode.DPAD to stringResource(R.string.pad_mode_dpad),
        PadMode.GYRO to stringResource(R.string.pad_mode_gyro),
    )
    val switchTo = mapOf(
        PadMode.TOUCHPAD to stringResource(R.string.pad_switch_touchpad),
        PadMode.DPAD to stringResource(R.string.pad_switch_dpad),
        PadMode.GYRO to (gyroReason?.let { stringResource(R.string.pad_switch_gyro_blocked, it) } ?: stringResource(R.string.pad_switch_gyro)),
    )
    val leftClick = stringResource(R.string.click_left_said)
    var frozen by remember { mutableStateOf(false) }

    if (gyro && !down) GyroSensor(frozen = frozen, onMove = { move(it) })

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stripOnLeft) ScrollStrip(down, onScroll = { move(it) })
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .liveOrDashed(!down, outline, corner)
                    .clearAndSetSemantics {
                        contentDescription = surfaceLabel
                        stateDescription = downReason ?: if (frozen) "" else hint
                        if (!down) {
                            onClick(label = leftClick) {
                                click(MouseButton.PRIMARY)
                                true
                            }
                        }
                        customActions = padModes(bluetooth = true).filter { it != mode }.map { target ->
                            CustomAccessibilityAction(switchTo.getValue(target)) {
                                onPickMode(target)
                                true
                            }
                        }
                    }
                    .pointerInput(down, gyro) {
                        when {
                            down -> detectTapGestures(onPress = { haptics.bump() })
                            gyro -> awaitEachGesture {
                                val first = awaitFirstDown()
                                var held = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    if (!held && event.changes.first().uptimeMillis - first.uptimeMillis > viewConfiguration.longPressTimeoutMillis) {
                                        held = true
                                        frozen = true
                                        haptics.tick()
                                    }
                                }
                                if (held) {
                                    frozen = false
                                    haptics.tick()
                                } else {
                                    haptics.click()
                                    click(MouseButton.PRIMARY)
                                }
                            }
                            else -> awaitEachGesture {
                                val firstDown = awaitFirstDown()
                                var last = firstDown.uptimeMillis
                                var travel = 0f
                                var moved = false
                                var fingers = 1
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.count { it.pressed }
                                    if (pressed == 0) break
                                    fingers = maxOf(fingers, pressed)
                                    val pan: Offset = event.calculatePan()
                                    val now = event.changes.first().uptimeMillis
                                    travel += pan.getDistance()
                                    if (travel > viewConfiguration.touchSlop) moved = true
                                    if (moved) {
                                        if (pressed >= 2) move(motion.scroll(pan.y.toDouble()))
                                        else move(motion.move(pan.x.toDouble(), pan.y.toDouble(), (now - last).toDouble()))
                                    }
                                    last = now
                                    event.changes.forEach { it.consume() }
                                }
                                if (!moved) {
                                    haptics.click()
                                    click(if (fingers >= 2) MouseButton.SECONDARY else MouseButton.PRIMARY)
                                }
                            }
                        }
                    },
            ) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painterResource(if (gyro) R.drawable.ic_gyro else R.drawable.ic_pad_touchpad_outline),
                        null,
                        Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (down) 0.5f else 1f),
                    )
                    Text(
                        when {
                            down -> downReason.orEmpty()
                            gyro && frozen -> stringResource(R.string.pointer_gyro_frozen)
                            else -> hint
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The corner glyph: the mode a tap moves to, as on the TV's pad. One meaning per spot.
                val next = nextPadMode(mode, bluetooth = true)
                Box(
                    Modifier
                        .align(if (stripOnLeft) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft)
                        .padding(4.dp)
                        .size(48.dp)
                        .clearAndSetSemantics {
                            role = Role.Button
                            contentDescription = modeLabel
                            stateDescription = names.getValue(mode)
                            onClick(label = switchTo.getValue(next)) {
                                onNextMode()
                                true
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures { onNextMode() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(
                            when (next) {
                                PadMode.TOUCHPAD -> R.drawable.ic_pad_touchpad_outline
                                PadMode.DPAD -> R.drawable.ic_pad_dpad_outline
                                PadMode.GYRO -> R.drawable.ic_gyro
                            },
                        ),
                        null,
                        Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!stripOnLeft) ScrollStrip(down, onScroll = { move(it) })
        }
        ClickRow(down = down, onClick = { button ->
            haptics.click()
            click(button)
        })
    }
}

/**
 * The scroll strip (Iris, 2026-10-25): 48 dp wide beside the pad, with a visible track and a ↕ glyph,
 * so a scroll is aimed at, never stumbled into. Drag to scroll, content follows the finger. TalkBack:
 * "Scroll", with Scroll up and Scroll down as actions.
 */
@Composable
fun ScrollStrip(down: Boolean, onScroll: (List<MouseStep>) -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberHaptics()
    val scroll by rememberUpdatedState(onScroll)
    val motion = remember { TouchpadMotion() }
    val label = stringResource(R.string.pad_scroll)
    val up = stringResource(R.string.pad_scroll_up)
    val downward = stringResource(R.string.pad_scroll_down)
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .width(SCROLL_STRIP)
            .fillMaxHeight()
            .liveOrDashed(!down, outline, 24.dp)
            .clearAndSetSemantics {
                contentDescription = label
                if (!down) {
                    customActions = listOf(
                        CustomAccessibilityAction(up) {
                            scroll(listOf(MouseStep(0, 0, 1)))
                            true
                        },
                        CustomAccessibilityAction(downward) {
                            scroll(listOf(MouseStep(0, 0, -1)))
                            true
                        },
                    )
                }
            }
            .pointerInput(down) {
                if (down) {
                    detectTapGestures(onPress = { haptics.bump() })
                    return@pointerInput
                }
                awaitEachGesture {
                    awaitFirstDown()
                    motion.reset()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val steps = motion.scroll(event.calculatePan().y.toDouble())
                        if (steps.isNotEmpty()) {
                            haptics.tick()
                            scroll(steps)
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The track: a 4 dp groove the whole height, the ↕ glyph on it.
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .padding(vertical = 16.dp)
                .drawBehind {
                    drawRoundRect(color = outline.copy(alpha = if (down) 0.3f else 0.6f), cornerRadius = CornerRadius(2.dp.toPx()))
                },
        )
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Icon(
                painterResource(R.drawable.ic_scroll_vertical),
                null,
                Modifier.padding(4.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (down) 0.5f else 1f),
            )
        }
    }
}

/** Left, Middle and Right (Iris, 2026-10-25): three equal buttons, each 48 dp tall with its name in words. */
@Composable
fun ClickRow(down: Boolean, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(CLICK_ROW), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClickButton(R.string.click_left, R.string.click_left_said, down, Modifier.weight(1f)) { onClick(MouseButton.PRIMARY) }
        ClickButton(R.string.click_middle, R.string.click_middle_said, down, Modifier.weight(1f)) { onClick(MouseButton.MIDDLE) }
        ClickButton(R.string.click_right, R.string.click_right_said, down, Modifier.weight(1f)) { onClick(MouseButton.SECONDARY) }
    }
}

@Composable
private fun ClickButton(name: Int, said: Int, down: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    val label = stringResource(said)
    Surface(
        onClick = { if (down) haptics.bump() else onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (down) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (down) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = CLICK_ROW)
            .liveOrDashed(!down, MaterialTheme.colorScheme.outline, 16.dp, solid = false)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick {
                    if (down) haptics.bump() else onClick()
                    true
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                stringResource(name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (down) 0.5f else 1f),
            )
        }
    }
}

/** The gyroscope runs only while gyro is in front and the link is up; [frozen] holds the cursor. */
@Composable
private fun GyroSensor(frozen: Boolean, onMove: (List<MouseStep>) -> Unit) {
    val context = LocalContext.current
    val move by rememberUpdatedState(onMove)
    val pointer = remember { AirPointer() }
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) } ?: return
    if (frozen) pointer.hold() else if (pointer.held) pointer.release()
    DisposableEffect(sensor) {
        var lastNs = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val dt = if (lastNs == 0L) 0.0 else (event.timestamp - lastNs) / 1e9
                lastNs = event.timestamp
                val steps = pointer.sample(event.values[0].toDouble(), event.values[2].toDouble(), dt)
                if (steps.isNotEmpty()) move(steps)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors?.unregisterListener(listener) }
    }
}

/** Live: a hairline edge. Down: no fill, a dashed edge, the remote's "dropped link" dress. */
private fun Modifier.liveOrDashed(live: Boolean, color: Color, corner: Dp, solid: Boolean = true): Modifier =
    if (live) {
        if (solid) border(BorderStroke(1.dp, color), RoundedCornerShape(corner)) else this
    } else {
        drawBehind {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(corner.toPx()),
                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
            )
        }
    }

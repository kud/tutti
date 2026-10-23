package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.kud.tutti.R
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The pad's mode switch (K1, Iris with Erwann): a strip under the pad, never on it, with a cell
 * per mode and a thumb under the current one. A sideways swipe moves the thumb and switches; a
 * tap only nudges the thumb toward the other side and springs back, so a thumb landing here on
 * its way to the pad never changes what the pad does. The first tap ever explains the swipe.
 *
 * The current mode is told by shape, never colour: the thumb sits under it and its icon is the
 * solid one, the other the outline. To TalkBack, Switch Access and a keyboard the strip is one
 * switch whose click toggles, with an action for each mode.
 */
@Composable
fun PadModeSwitch(
    mode: PadMode,
    onModeChange: (PadMode) -> Unit,
    hintSeen: Boolean,
    onHintSeen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val travelPx = with(density) { (CELL_WIDTH + CELL_GAP).toPx() }
    val nudge = with(density) { NUDGE.toPx() } / travelPx

    // 0 = D-pad (left), 1 = touchpad (right). The icons read it too, so the solid icon is always
    // the one under the thumb, even mid-swipe.
    val thumb = remember { Animatable(mode.position) }
    var dragging by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(mode)
    val change by rememberUpdatedState(onModeChange)
    val seen by rememberUpdatedState(hintSeen)
    val markSeen by rememberUpdatedState(onHintSeen)

    suspend fun settle(to: Float) {
        if (reduced) thumb.snapTo(to) else thumb.animateTo(to, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium))
    }

    LaunchedEffect(mode) {
        if (!dragging) settle(mode.position)
    }
    LaunchedEffect(hint) {
        if (hint) {
            delay(HINT_MILLIS)
            hint = false
        }
    }

    fun pick(target: PadMode) {
        if (target == current) return
        haptics.select()
        change(target)
    }

    fun tapped() {
        haptics.tick()
        if (!reduced) {
            val base = current.position
            val toward = if (base < 0.5f) nudge else -nudge
            scope.launch {
                thumb.animateTo(base + toward, tween(durationMillis = 70))
                thumb.animateTo(base, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
            }
        }
        if (!seen) {
            hint = true
            markSeen()
        }
    }

    val label = stringResource(R.string.pad_mode_label)
    val dpadName = stringResource(R.string.pad_mode_dpad)
    val touchpadName = stringResource(R.string.pad_mode_touchpad)
    val toDpad = stringResource(R.string.pad_switch_dpad)
    val toTouchpad = stringResource(R.string.pad_switch_touchpad)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val trackShape = RoundedCornerShape(TRACK_HEIGHT / 2)

    Box(
        modifier
            .size(TRACK_WIDTH, TRACK_HEIGHT)
            .clearAndSetSemantics {
                role = Role.Switch
                contentDescription = label
                stateDescription = if (mode == PadMode.DPAD) dpadName else touchpadName
                onClick(label = if (mode == PadMode.DPAD) toTouchpad else toDpad) {
                    pick(mode.other)
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(toDpad) {
                        pick(PadMode.DPAD)
                        true
                    },
                    CustomAccessibilityAction(toTouchpad) {
                        pick(PadMode.TOUCHPAD)
                        true
                    },
                )
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> pick(current.other)
                    Key.DirectionLeft -> pick(PadMode.DPAD)
                    Key.DirectionRight -> pick(PadMode.TOUCHPAD)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(interactionSource = interaction)
            .background(scheme.surfaceContainerLow, trackShape)
            .border(if (focused) 2.dp else 1.3.dp, if (focused) scheme.primary else scheme.outlineVariant, trackShape)
            // Exactly the drawn track: nothing reaches below its edge, toward the row under it.
            .pointerInput(haptics, reduced, travelPx) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val start = thumb.value
                    val velocity = VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, down.position)
                    var dx = 0f
                    var dy = 0f
                    var swiped = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!finger.pressed) {
                            finger.consume()
                            break
                        }
                        val delta = finger.positionChange()
                        dx += delta.x
                        dy += delta.y
                        velocity.addPosition(finger.uptimeMillis, finger.position)
                        if (!swiped && abs(dx) > slop && abs(dx) >= abs(dy)) {
                            swiped = true
                            dragging = true
                        }
                        if (swiped) {
                            val at = (start + dx / travelPx).coerceIn(0f, 1f)
                            scope.launch { thumb.snapTo(at) }
                        }
                        finger.consume()
                    }
                    if (swiped) {
                        val flung = velocity.calculateVelocity().x
                        val flingPx = FLING.toPx()
                        val endsAt = (start + dx / travelPx).coerceIn(0f, 1f)
                        val target = when {
                            flung > flingPx -> PadMode.TOUCHPAD
                            flung < -flingPx -> PadMode.DPAD
                            endsAt >= 0.5f -> PadMode.TOUCHPAD
                            else -> PadMode.DPAD
                        }
                        dragging = false
                        if (target != current) {
                            pick(target)
                            // He has found the swipe on his own: the hint has nothing left to teach.
                            if (!seen) markSeen()
                        }
                        scope.launch { settle(target.position) }
                    } else if (abs(dy) <= slop) {
                        tapped()
                    }
                }
            },
    ) {
        val at = thumb.value
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((TRACK_INSET.toPx() + at * travelPx).roundToInt(), 0) }
                .size(CELL_WIDTH, CELL_HEIGHT)
                .background(scheme.primaryContainer, RoundedCornerShape(CELL_HEIGHT / 2)),
        )
        val onTouchpad = at >= 0.5f
        Cell(
            icon = if (onTouchpad) R.drawable.ic_pad_dpad_outline else R.drawable.ic_pad_dpad_filled,
            selected = !onTouchpad,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = TRACK_INSET),
        )
        Cell(
            icon = if (onTouchpad) R.drawable.ic_pad_touchpad_filled else R.drawable.ic_pad_touchpad_outline,
            selected = onTouchpad,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = TRACK_INSET),
        )

        if (hint) {
            val gap = with(density) { HINT_GAP.roundToPx() }
            Popup(popupPositionProvider = remember(gap) { Above(gap) }, properties = PopupProperties(focusable = false)) {
                Text(
                    text = stringResource(R.string.pad_mode_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.inverseOnSurface,
                    modifier = Modifier
                        .background(scheme.inverseSurface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Cell(@DrawableRes icon: Int, selected: Boolean, modifier: Modifier) {
    Box(modifier.size(CELL_WIDTH, CELL_HEIGHT), contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Centred above the strip, kept inside the window; the pad's edge, not its middle. */
private class Above(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

private val PadMode.position: Float get() = if (this == PadMode.TOUCHPAD) 1f else 0f

private val PadMode.other: PadMode get() = if (this == PadMode.TOUCHPAD) PadMode.DPAD else PadMode.TOUCHPAD

private val CELL_WIDTH = 56.dp
private val CELL_HEIGHT = 34.dp
private val CELL_GAP = 4.dp
private val TRACK_INSET = 4.dp
private val TRACK_WIDTH = TRACK_INSET * 2 + CELL_WIDTH * 2 + CELL_GAP

/** The strip's drawn height, which is also its whole touch target. */
internal val PAD_SWITCH_HEIGHT = 44.dp
private val TRACK_HEIGHT = PAD_SWITCH_HEIGHT

/** How far a tap leans the thumb toward the other side before it springs back. */
private val NUDGE = 6.dp

/** A swipe released faster than this switches toward its direction, wherever the thumb is. */
private val FLING = 400.dp

private val HINT_GAP = 8.dp
private const val HINT_MILLIS = 2_500L

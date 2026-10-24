package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics
import kotlin.math.roundToInt

/**
 * The pad's mode switch (K1, Iris with Erwann): a track at the top of the pad's area, under the
 * scene pills and centred on the pad's axis, never on the pad (Iris, 2026-10-24: it is rarely
 * used, so it leaves the thumb's half). A cell per mode and a thumb under the current one. A tap on
 * the other cell switches; a tap on the current one does nothing. The track is drawn
 * [TRACK_HEIGHT] tall and answers over [PAD_SWITCH_HEIGHT]: nothing tappable sits round it.
 *
 * The current mode is told by shape, never colour: the thumb sits under it and its icon is the
 * solid one, the other the outline. To TalkBack, Switch Access and a keyboard the strip is one
 * switch whose click toggles, with an action for each mode.
 */
@Composable
fun PadModeSwitch(
    mode: PadMode,
    onModeChange: (PadMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val travelPx = with(density) { (CELL_WIDTH + CELL_GAP).toPx() }

    // 0 = D-pad (left), 1 = touchpad (right). The icons read it too, so the solid icon is always
    // the one under the thumb, even mid-slide.
    val thumb = remember { Animatable(mode.position) }
    val current by rememberUpdatedState(mode)
    val change by rememberUpdatedState(onModeChange)

    LaunchedEffect(mode) {
        if (reduced) thumb.snapTo(mode.position) else thumb.animateTo(mode.position, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium))
    }

    fun pick(target: PadMode) {
        if (target == current) return
        haptics.select()
        change(target)
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
            .size(TRACK_WIDTH, PAD_SWITCH_HEIGHT)
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
            // The whole 48 dp target answers; the track is drawn inside it.
            .pointerInput(Unit) {
                detectTapGestures { at -> pick(if (at.x < size.width / 2f) PadMode.DPAD else PadMode.TOUCHPAD) }
            }
            .padding(vertical = (PAD_SWITCH_HEIGHT - TRACK_HEIGHT) / 2)
            .background(scheme.surfaceContainerLow, trackShape)
            .border(if (focused) 2.dp else 1.3.dp, if (focused) scheme.primary else scheme.outlineVariant, trackShape),
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

private val PadMode.position: Float get() = if (this == PadMode.TOUCHPAD) 1f else 0f

private val PadMode.other: PadMode get() = if (this == PadMode.TOUCHPAD) PadMode.DPAD else PadMode.TOUCHPAD

private val CELL_WIDTH = 52.dp
private val CELL_HEIGHT = 28.dp
private val CELL_GAP = 4.dp
private val TRACK_INSET = 4.dp
private val TRACK_WIDTH = TRACK_INSET * 2 + CELL_WIDTH * 2 + CELL_GAP

/** The switch's touch target, taller than the track it draws. */
internal val PAD_SWITCH_HEIGHT = 48.dp
private val TRACK_HEIGHT = 36.dp

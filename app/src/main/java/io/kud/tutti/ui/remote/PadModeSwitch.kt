package io.kud.tutti.ui.remote

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.platform.data.PadMode

/**
 * The pad's mode switch (K1, Erwann, 2026-10-24): one quiet glyph on the pad's surface, in its
 * top corner facing the rail (top-left when [atLeft], for the right hand; else top-right), that
 * doesn't break the pad's shape. It shows the mode a tap switches TO, in
 * its outline form (the touchpad's in D-pad mode, the D-pad's in touchpad mode): the pad already
 * says the current mode loudly. A bare 24 dp glyph in the on-surface-variant tone, 16 dp in from
 * the top and side edges, with no chip, border or label (Erwann's reference build); [pressed]
 * tints and tightens it.
 *
 * It draws and speaks; it never reads a touch. The pad's own gesture code hit-tests the corner
 * ([inPadSwitchCorner]) and calls [onSwitch] for a tap there ([padTouchEnd]), so a swipe that
 * starts in the corner stays a swipe and nothing reaches the TV twice. To TalkBack, Switch
 * Access and a keyboard it is one button: the state is the current mode, the click switches,
 * with an action for each mode.
 */
@Composable
fun PadModeSwitch(
    mode: PadMode,
    pressed: Boolean,
    onSwitch: (PadMode) -> Unit,
    modifier: Modifier = Modifier,
    atLeft: Boolean = false,
) {
    val label = stringResource(R.string.pad_mode_label)
    val dpadName = stringResource(R.string.pad_mode_dpad)
    val touchpadName = stringResource(R.string.pad_mode_touchpad)
    val toDpad = stringResource(R.string.pad_switch_dpad)
    val toTouchpad = stringResource(R.string.pad_switch_touchpad)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme

    fun pick(target: PadMode) {
        if (target != mode) onSwitch(target)
    }

    // The corner square; the 48 dp target sits in its inner bottom corner, so the glyph lands 16 dp in.
    Box(modifier.size(PAD_SWITCH_ZONE.dp)) {
        Box(
            Modifier
                .align(if (atLeft) AbsoluteAlignment.BottomRight else AbsoluteAlignment.BottomLeft)
                .size(TARGET)
                .clearAndSetSemantics {
                    role = Role.Button
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
                        Key.Enter, Key.NumPadEnter, Key.Spacebar -> pick(mode.other)
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable(interactionSource = interaction)
                // A keyboard's focus is the only time anything but the glyph is drawn.
                .border(2.dp, scheme.primary.copy(alpha = if (focused) 1f else 0f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(if (mode == PadMode.DPAD) R.drawable.ic_pad_touchpad_outline else R.drawable.ic_pad_dpad_outline),
                contentDescription = null,
                tint = if (pressed) scheme.onSurface else scheme.onSurfaceVariant,
                // Pressed: a brief tint and the glyph tightening under the finger, nothing else.
                modifier = Modifier.size(GLYPH).graphicsLayer {
                    val scale = if (pressed) PRESSED_SCALE else 1f
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
    }
}

/** What a finished one-finger or two-finger touch on the touchpad means. */
internal enum class PadTouchEnd { NOTHING, TAP, TWO_FINGER_TAP, SWITCH }

/**
 * The touchpad's verdict when the last finger lifts. A touch that moved past slop or became a long
 * press was already acted on; a still two-finger touch is play/pause; a still one-finger touch is
 * OK, unless it went down inside the switch's corner, where it switches mode instead and never
 * reaches the TV (a long hold there does nothing). Only the down position counts, so a swipe that
 * starts in the corner, or crosses it, tracks as normal.
 */
internal fun padTouchEnd(moved: Boolean, longFired: Boolean, pointers: Int, startedInCorner: Boolean): PadTouchEnd = when {
    moved || longFired -> PadTouchEnd.NOTHING
    pointers >= 2 -> PadTouchEnd.TWO_FINGER_TAP
    startedInCorner -> PadTouchEnd.SWITCH
    else -> PadTouchEnd.TAP
}

internal val PadMode.other: PadMode get() = if (this == PadMode.DPAD) PadMode.TOUCHPAD else PadMode.DPAD

private val GLYPH = 24.dp
private val TARGET = 48.dp
private const val PRESSED_SCALE = 0.9f

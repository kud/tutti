package io.kud.tutti.ui.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.ui.common.motion
import io.kud.tutti.ui.common.rememberHaptics
import kotlin.math.roundToInt

/**
 * Vertical rail on the right edge: the soundbar's absolute volume (0–100), the second loudest
 * element. Dragging anywhere moves the level relatively (a stray tap never jumps the volume);
 * a tick per step, and the controller's bump at 0, 100 or the scene's cap.
 *
 * Three states, told apart by shape as well as colour: connected (level on top, a faint speaker
 * at the foot), muted (the fill greys and the foot speaker takes its slash), and not connected
 * (no level and no fill, a hollow track, and the foot speaker crossed at full strength).
 */
@Composable
fun VolumeRail(
    volume: Int?,
    cap: Int?,
    muted: Boolean,
    enabled: Boolean,
    onVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    var dragLevel by remember { mutableStateOf<Int?>(null) }
    var railHeight by remember { mutableIntStateOf(0) }
    val currentVolume by rememberUpdatedState(volume)
    val currentCap by rememberUpdatedState(cap)
    val setVolume by rememberUpdatedState(onVolume)
    val connected = enabled
    val shown = if (connected) dragLevel ?: volume else null
    val fraction by animateFloatAsState((shown ?: 0) / TuttiController.MAX_VOLUME.toFloat(), motion(), label = "volume")
    val scheme = MaterialTheme.colorScheme
    val fill = if (muted) scheme.outline else scheme.primary
    val onFill = if (muted) scheme.surface else scheme.onPrimary
    val capColor = scheme.tertiary
    val label = stringResource(R.string.volume_label)
    val state = when {
        !connected -> stringResource(R.string.volume_state_disconnected)
        shown == null -> ""
        muted -> stringResource(R.string.volume_state_muted, shown)
        else -> shown.toString()
    }
    // The foot speaker sits on the fill once the fill has climbed past its top edge.
    val footTop = with(LocalDensity.current) { (FOOT_INSET + FOOT_ICON).toPx() }
    val footOnFill = railHeight > 0 && fraction * railHeight >= footTop

    Box(
        modifier = modifier
            .width(64.dp)
            .fillMaxHeight()
            .onSizeChanged { railHeight = it.height }
            .clip(RoundedCornerShape(32.dp))
            .background(if (connected) scheme.surfaceContainerHigh else scheme.surfaceContainerLow)
            .then(
                if (connected) Modifier
                else Modifier.border(1.5.dp, scheme.outlineVariant, RoundedCornerShape(32.dp)),
            )
            .semantics {
                contentDescription = label
                stateDescription = state
            }
            .drawWithContent {
                drawContent()
                val c = currentCap
                if (c != null && connected) {
                    val y = size.height * (1f - c / TuttiController.MAX_VOLUME.toFloat())
                    drawLine(capColor, Offset(10.dp.toPx(), y), Offset(size.width - 10.dp.toPx(), y), strokeWidth = 2.dp.toPx())
                }
            }
            .pointerInput(connected, haptics) {
                if (!connected) return@pointerInput
                var start = 0
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        start = currentVolume ?: 0
                        travelled = 0f
                        dragLevel = start
                    },
                    onDragEnd = { dragLevel = null },
                    onDragCancel = { dragLevel = null },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        travelled -= dy
                        val ceiling = currentCap ?: TuttiController.MAX_VOLUME
                        val level = (start + travelled / size.height * TuttiController.MAX_VOLUME * SENSITIVITY)
                            .roundToInt()
                            .coerceIn(0, ceiling)
                        if (level != dragLevel) {
                            dragLevel = level
                            if (level in 1 until ceiling) haptics.tick()
                            setVolume(level)
                        }
                    },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(0f, 1f))
                .background(fill),
        )
        if (shown != null) {
            Text(
                text = shown.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (fraction > 0.9f) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
        }
        // One speaker, always at the foot, so it never moves: a quiet hint while connected, the
        // muted slash when muted, and the crossed speaker at full strength when the bar is gone.
        Icon(
            painter = painterResource(
                when {
                    !connected -> R.drawable.ic_no_sound
                    muted -> R.drawable.ic_volume_off
                    else -> R.drawable.ic_volume_up
                },
            ),
            contentDescription = null,
            tint = when {
                !connected -> scheme.onSurfaceVariant
                footOnFill -> onFill.copy(alpha = if (muted) 0.7f else 0.4f)
                else -> scheme.onSurfaceVariant.copy(alpha = if (muted) 0.7f else 0.4f)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FOOT_INSET)
                .size(FOOT_ICON),
        )
    }
}

private val FOOT_INSET = 18.dp
private val FOOT_ICON = 20.dp

/** One full rail height covers 60 steps: precise without being slow. */
private const val SENSITIVITY = 0.6f

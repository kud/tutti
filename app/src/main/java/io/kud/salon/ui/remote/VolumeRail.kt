package io.kud.salon.ui.remote

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.kud.salon.R
import io.kud.salon.controller.SalonController
import io.kud.salon.ui.common.motion
import io.kud.salon.ui.common.rememberHaptics
import kotlin.math.roundToInt

/**
 * Vertical rail on the right edge: the soundbar's absolute volume (0–100), the second loudest
 * element. Dragging anywhere moves the level relatively (a stray tap never jumps the volume);
 * a tick per step, and the controller's bump at 0, 100 or the scene's cap.
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
    val currentVolume by rememberUpdatedState(volume)
    val currentCap by rememberUpdatedState(cap)
    val setVolume by rememberUpdatedState(onVolume)
    val shown = dragLevel ?: volume
    val fraction by animateFloatAsState((shown ?: 0) / SalonController.MAX_VOLUME.toFloat(), motion(), label = "volume")
    val scheme = MaterialTheme.colorScheme
    val fill = if (muted) scheme.outline else scheme.primary
    val capColor = scheme.tertiary
    val label = stringResource(R.string.volume_label)

    Box(
        modifier = modifier
            .width(64.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(32.dp))
            .background(scheme.surfaceContainerHigh)
            .semantics {
                contentDescription = label
                stateDescription = shown?.toString() ?: "—"
            }
            .drawWithContent {
                drawContent()
                val c = currentCap
                if (c != null) {
                    val y = size.height * (1f - c / SalonController.MAX_VOLUME.toFloat())
                    drawLine(capColor, Offset(10.dp.toPx(), y), Offset(size.width - 10.dp.toPx(), y), strokeWidth = 2.dp.toPx())
                }
            }
            .pointerInput(enabled, haptics) {
                if (!enabled) return@pointerInput
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
                        val ceiling = currentCap ?: SalonController.MAX_VOLUME
                        val level = (start + travelled / size.height * SalonController.MAX_VOLUME * SENSITIVITY)
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
        Text(
            text = shown?.toString() ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (fraction > 0.9f) scheme.onPrimary else scheme.onSurface,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
        )
    }
}

/** One full rail height covers 60 steps: precise without being slow. */
private const val SENSITIVITY = 0.6f

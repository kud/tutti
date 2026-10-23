package io.kud.tutti.ui.remote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import io.kud.tutti.ui.common.LocalChipNudge
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.snappyOrFade
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState

private val Online = Color(0xFF4CD18A)
private val Pending = Color(0xFFF2B84B)

private fun Link.isDown() = this == Link.UNREACHABLE || this == Link.NEEDS_PAIRING

/**
 * "Salon · BRAVIA 8 + Bar 9" with one status dot. Splits into two lines only when exactly
 * one device drops ("Bar 9 injoignable").
 */
@Composable
fun SystemChip(state: TuttiUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tv = state.devices.tv
    val bar = state.devices.bar
    val tvLink = if (tv != null) state.links.tv else Link.ABSENT
    val barLink = if (bar != null) state.links.bar else Link.ABSENT
    val configured = listOfNotNull(tv?.let { it.name to tvLink }, bar?.let { it.name to barLink })
    val down = configured.filter { it.second.isDown() }
    val dotState = when {
        configured.isEmpty() -> DotState.NONE
        down.size == configured.size -> DotState.DOWN
        down.isNotEmpty() -> DotState.PARTIAL
        // Waking the TV: the open ring until it answers, then the full dot (motion spec).
        configured.any { it.second == Link.CONNECTING } || (state.waking && tv != null) -> DotState.CONNECTING
        else -> DotState.ONLINE
    }
    val dotTarget = when (dotState) {
        DotState.NONE -> MaterialTheme.colorScheme.outline
        DotState.DOWN -> MaterialTheme.colorScheme.error
        DotState.PARTIAL, DotState.CONNECTING -> Pending
        DotState.ONLINE -> Online
    }
    val nudge = LocalChipNudge.current
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(nudge.pokes) {
        if (nudge.pokes == 0) return@LaunchedEffect
        pulse.animateTo(NUDGE_LAYER, tween(Motion.FADE_OUT))
        pulse.animateTo(0f, tween(Motion.LONG))
    }
    val pulseColor = MaterialTheme.colorScheme.onSurface
    val dot by animateColorAsState(dotTarget, snappyOrFade(), label = "dot")
    val room = stringResource(R.string.system_room)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.drawWithContent {
            drawContent()
            if (pulse.value > 0f) drawRoundRect(pulseColor.copy(alpha = pulse.value), cornerRadius = CornerRadius(24.dp.toPx()))
        },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Ring to dot (and back) is a short fade between shapes; the colour follows on its own.
            Crossfade(dotState, Modifier.size(12.dp), animationSpec = tween(Motion.SHORT), label = "dotShape") { shape ->
                StatusDot(shape, dot, Modifier.size(12.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                if (configured.isEmpty()) {
                    Text(stringResource(R.string.system_no_device), style = MaterialTheme.typography.titleSmall)
                } else if (down.size == 1 && configured.size == 2) {
                    val healthy = configured.first { !it.second.isDown() }
                    val (name, link) = down.first()
                    Text("$room · ${healthy.first}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = if (link == Link.NEEDS_PAIRING) {
                            stringResource(R.string.system_needs_pairing, name)
                        } else {
                            stringResource(R.string.system_unreachable, name)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                } else {
                    Text(
                        text = "$room · " + configured.joinToString(" + ") { it.first },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dotState == DotState.DOWN) {
                        Text(
                            stringResource(R.string.system_nothing_reachable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    } else {
                        // The app on screen, when the catalogue knows it (daily-state: current app).
                        foregroundAppName(state)?.let { app ->
                            Text(
                                stringResource(R.string.system_current_app, app),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class DotState { NONE, ONLINE, CONNECTING, PARTIAL, DOWN }

/**
 * Online is a filled dot, connecting a dashed ring that turns, one device down a half-filled dot
 * (with the second line), all down a slashed ring (with "Nothing reachable"). The turn stays under
 * reduced motion: it is a status indicator, not decoration.
 */
@Composable
private fun StatusDot(state: DotState, color: Color, modifier: Modifier = Modifier) {
    val turn = if (state == DotState.CONNECTING) {
        rememberInfiniteTransition(label = "dot").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
            label = "turn",
        ).value
    } else {
        0f
    }
    Canvas(modifier) {
        val width = 1.5.dp.toPx()
        val radius = size.minDimension / 2f - width / 2f
        val ring = Stroke(width)
        when (state) {
            DotState.ONLINE -> drawCircle(color)
            DotState.NONE -> drawCircle(color, radius, style = ring)
            DotState.CONNECTING -> rotate(turn) {
                drawCircle(color, radius, style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(radius * 0.9f, radius * 0.7f))))
            }
            DotState.PARTIAL -> {
                drawCircle(color, radius, style = ring)
                drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset(center.x - radius, center.y - radius), size = Size(radius * 2f, radius * 2f))
            }
            DotState.DOWN -> {
                drawCircle(color, radius, style = ring)
                val arm = radius * 0.7f
                drawLine(color, center + Offset(-arm, arm), center + Offset(arm, -arm), width, StrokeCap.Round)
            }
        }
    }
}

private const val NUDGE_LAYER = 0.16f

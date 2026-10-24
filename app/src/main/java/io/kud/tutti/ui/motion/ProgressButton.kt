package io.kud.tutti.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.rememberPress
import io.kud.tutti.ui.common.rememberTuttiIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics

/** The four states of an action button. */
enum class ActionPhase { IDLE, PENDING, SUCCESS, FAILURE }

/**
 * The one action button (Pair, Add, Save), per the motion spec's loading rules:
 * - the press answers in the frame: 96 % scale and a click haptic, before any network;
 * - pending is disabled but not greyed; its spinner waits [Motion.SHORT] and then stays at least
 *   [Motion.LONG] ([SpinnerTiming]); the label names the real step ([pendingLabel]); with a
 *   known [fraction] it fills left to right instead of turning;
 * - success folds into a circle and draws its tick ([Motion.MEDIUM]) with a confirm haptic, the
 *   word beside it ([successLabel]);
 * - failure shakes 3 × 6 dp with a reject haptic, wears a cross and "Try again", and says why
 *   under it ([failureMessage]).
 * No state rests on colour: ring and word, tick and word, cross and sentence. With reduced
 * motion there is no scale, shake, spring or spinner: only [Motion.SHORT] fades, and the haptics.
 *
 * It wraps its content: give it no fillMaxWidth, or the circle has nowhere to fold.
 * Outcomes play only on a change, unless [announceInitial] (e.g. a dialog reopened on a failure).
 */
@Composable
fun ProgressButton(
    phase: ActionPhase,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pendingLabel: String = label,
    successLabel: String? = null,
    failureMessage: String? = null,
    fraction: Float? = null,
    announceInitial: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    val waiting = rememberWaiting(phase == ActionPhase.PENDING)
    // An outcome waits behind the spinner's minimum time, so a shown spinner never flashes.
    val shown = if (waiting.held) ActionPhase.PENDING else phase

    val shake = remember { Animatable(0f) }
    val tick = remember { Animatable(if (shown == ActionPhase.SUCCESS) 1f else 0f) }
    val announced = remember { booleanArrayOf(announceInitial) }
    LaunchedEffect(shown) {
        val announce = announced[0]
        announced[0] = true
        when (shown) {
            ActionPhase.SUCCESS -> {
                if (announce) haptics.confirm()
                if (announce && !reduced) {
                    tick.snapTo(0f)
                    tick.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.Emphasized))
                } else {
                    tick.snapTo(1f)
                }
            }
            ActionPhase.FAILURE -> {
                if (announce) haptics.reject()
                if (announce && !reduced) {
                    shake.snapTo(0f)
                    shake.animateTo(
                        0f,
                        keyframes {
                            durationMillis = Motion.MEDIUM
                            SHAKE_DP at 42
                            -SHAKE_DP at 83
                            SHAKE_DP at 125
                            -SHAKE_DP at 167
                            SHAKE_DP at 208
                        },
                    )
                }
            }
            else -> tick.snapTo(0f)
        }
    }

    val interaction = remember { MutableInteractionSource() }
    // The click lands on down with the press-in, like every key; reduced motion keeps it still.
    val press = rememberPress(interaction, PressStyle.SURFACE, onDown = haptics::click)
    val indication = rememberTuttiIndication(CircleShape, layerColor = MaterialTheme.colorScheme.onSecondaryContainer)

    val colors = MaterialTheme.colorScheme
    val idleDisabled = !enabled && shown == ActionPhase.IDLE
    val container by animateColorAsState(
        when {
            idleDisabled -> colors.onSurface.copy(alpha = 0.12f)
            else -> colors.secondaryContainer
        },
        Motion.fade(),
        label = "container",
    )
    val content by animateColorAsState(
        when {
            idleDisabled -> colors.onSurface.copy(alpha = 0.38f)
            else -> colors.onSecondaryContainer
        },
        Motion.fade(),
        label = "content",
    )
    val fill by animateFloatAsState(fraction ?: 0f, snappyOrFade(), label = "fill")
    val filling = shown == ActionPhase.PENDING && fraction != null
    val success = successLabel ?: label

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .offset { IntOffset(shake.value.dp.roundToPx(), 0) }
                    .graphicsLayer {
                        val scale = 1f - (1f - PRESSED_SCALE) * press.fraction
                        scaleX = scale
                        scaleY = scale
                    }
                    .animateContentSize(snappyOrFade())
                    .height(HEIGHT)
                    .defaultMinSize(minWidth = HEIGHT)
                    // Before the clip, so the shared focus ring can sit 3 dp outside the circle.
                    .clickable(
                        interactionSource = interaction,
                        indication = indication,
                        enabled = enabled && shown != ActionPhase.PENDING,
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        if (shown == ActionPhase.SUCCESS) contentDescription = success
                    }
                    .clip(CircleShape)
                    .background(container)
                    .drawBehind {
                        if (filling) drawRect(content.copy(alpha = 0.18f), size = Size(size.width * fill, size.height))
                    }
                    .then(if (shown == ActionPhase.SUCCESS) Modifier.width(HEIGHT) else Modifier.padding(horizontal = 24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(shown, animationSpec = Motion.fade(), label = "phase") { now ->
                    when (now) {
                        ActionPhase.IDLE -> Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                        ActionPhase.PENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                            if (waiting.spinner && fraction == null && !reduced) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = content, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(pendingLabel, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                        }
                        ActionPhase.SUCCESS -> DrawnTick(progress = { tick.value }, color = content)
                        ActionPhase.FAILURE -> Row(verticalAlignment = Alignment.CenterVertically) {
                            // An offer to try again, dressed as an action: the failure is told by the
                            // shake, the haptic and the warning line below, never by a red X button.
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = shown == ActionPhase.SUCCESS && successLabel != null,
                enter = fadeIn(Motion.fade()),
                exit = fadeOut(Motion.exit()),
            ) {
                Text(
                    success,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = shown == ActionPhase.FAILURE && failureMessage != null,
            enter = fadeIn(Motion.fade()),
            exit = fadeOut(Motion.exit()),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(failureMessage.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
    }
}

/** A tick traced from its short arm to the tip as [progress] runs 0 to 1. */
@Composable
private fun DrawnTick(progress: () -> Float, color: Color) {
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(Modifier.size(24.dp)) {
        val tick = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.52f)
            lineTo(size.width * 0.42f, size.height * 0.72f)
            lineTo(size.width * 0.8f, size.height * 0.3f)
        }
        measure.setPath(tick, false)
        segment.reset()
        measure.getSegment(0f, measure.length * progress().coerceIn(0f, 1f), segment, true)
        drawPath(segment, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private val HEIGHT = 48.dp
private const val PRESSED_SCALE = 0.96f
private const val SHAKE_DP = 6f

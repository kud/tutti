package io.kud.tutti.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * How a family answers a press (Iris's interaction spec). Keys tighten to 0.92, OK to 0.94,
 * tiles and cards to 0.97; rows and surfaces don't scale (a full-width row that shrinks looks
 * cheap, and the pad and rail draw their own answer).
 */
enum class PressStyle(val scale: Float) {
    KEY(0.92f),
    OK(0.94f),
    TILE(0.97f),
    ROW(1f),
    SURFACE(1f),
}

/** A press's progress: [fraction] 0 at rest, 1 fully pressed in; [scale] for the family. */
@Stable
class PressState internal constructor(private val anim: Animatable<Float, AnimationVector1D>, private val style: PressStyle) {
    val fraction: Float get() = anim.value
    val scale: Float get() = 1f - (1f - style.scale) * anim.value
}

/**
 * The shape answers first: [onDown] (the haptic, the click) fires on [PressInteraction.Press],
 * in the frame the finger lands, never in `onClick`. The press-in is critically damped; the
 * release is [Motion.snappy] and waits for the press-in to land, so even a quick tap shows.
 * With reduced motion nothing travels: [PressState.fraction] stays 0 and only the layer (drawn
 * by [TuttiIndication]) answers; the haptic stays.
 */
@Composable
fun rememberPress(interaction: InteractionSource, style: PressStyle, onDown: (() -> Unit)? = null): PressState {
    val anim = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    val down by rememberUpdatedState(onDown)
    LaunchedEffect(interaction, reduced) {
        if (reduced) anim.snapTo(0f)
        var pressIn: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    down?.invoke()
                    if (!reduced) pressIn = launch { anim.animateTo(1f, Motion.pressIn()) }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> if (!reduced) {
                    val landing = pressIn
                    launch {
                        landing?.join()
                        anim.animateTo(0f, Motion.snappy())
                    }
                }
            }
        }
    }
    return remember(anim, style) { PressState(anim, style) }
}

/**
 * Long-press progress, 0..1: nothing for [RING_DELAY_MS], then a linear sweep to the system
 * long-press timeout, so the ring tells the time honestly. Reduced motion: no sweep, it appears
 * whole at the timeout. Back to 0 on release.
 */
@Composable
fun rememberLongPressProgress(interaction: InteractionSource, enabled: Boolean): State<Float> {
    val progress = remember { Animatable(0f) }
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
    val reduced = LocalReducedMotion.current
    LaunchedEffect(interaction, enabled, timeout, reduced) {
        progress.snapTo(0f)
        if (!enabled) return@LaunchedEffect
        var sweep: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> {
                    sweep?.cancel()
                    sweep = launch {
                        progress.snapTo(0f)
                        delay(RING_DELAY_MS)
                        val rest = (timeout - RING_DELAY_MS).coerceAtLeast(1L)
                        if (reduced) {
                            delay(rest)
                            progress.snapTo(1f)
                        } else {
                            progress.animateTo(1f, tween(rest.toInt(), easing = LinearEasing))
                        }
                    }
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    sweep?.cancel()
                    sweep = launch { progress.animateTo(0f, tween(Motion.FADE_OUT)) }
                }
            }
        }
    }
    return progress.asState()
}

/** Draws the long-press ring 3 dp outside [shape], following it, swept to [progress]. */
fun Modifier.longPressRing(progress: () -> Float, shape: Shape, color: Color): Modifier = drawWithContent {
    drawContent()
    val p = progress()
    if (p <= 0f) return@drawWithContent
    val width = 3.dp.toPx()
    val offset = 3.dp.toPx() + width / 2f
    val ringSize = Size(size.width + offset * 2f, size.height + offset * 2f)
    val path = shape.createOutline(ringSize, layoutDirection, this).asPath()
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    measure.getSegment(0f, measure.length * p.coerceAtMost(1f), segment, true)
    translate(-offset, -offset) {
        drawPath(segment, color, style = Stroke(width = width, cap = StrokeCap.Round))
    }
}

internal fun Outline.asPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

/**
 * A rounded shape whose corner, in percent of the shorter side, is read at draw time, so a key
 * can morph 50 → 30 % without recomposing or re-creating its indication every frame.
 */
@Stable
class MorphCornerShape(private val percent: () -> Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = min(size.width, size.height) * percent().coerceIn(0f, 50f) / 100f
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
}

/** The ring waits this long, so a plain tap never shows it. */
const val RING_DELAY_MS = 150L

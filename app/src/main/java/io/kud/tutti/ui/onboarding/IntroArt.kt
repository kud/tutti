package io.kud.tutti.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.intro.MarkColours
import io.kud.tutti.ui.intro.markColours
import io.kud.tutti.ui.motion.Motion
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Milliseconds since this first composed, frame by frame; stays 0 while not [running]. */
@Composable
internal fun rememberClock(running: Boolean = true): State<Long> = produceState(0L, running) {
    if (!running) return@produceState
    val start = withFrameMillis { it }
    while (true) withFrameMillis { value = it - start }
}

/**
 * The waves alive at [ms], each as 0..1 of its [life]: one is born every [every] ms, at most
 * [limit] in all. Born at 0, so nothing is already mid-flight on the first frame.
 */
internal fun wavePhases(ms: Long, every: Long = WAVE_EVERY_MS, life: Long = WAVE_LIFE_MS, limit: Int = Int.MAX_VALUE): List<Float> {
    if (ms < 0) return emptyList()
    val newest = min((ms / every).toInt(), limit - 1)
    val oldest = maxOf(0, newest - (life / every).toInt())
    return (oldest..newest).mapNotNull { n ->
        val age = ms - n * every
        if (age in 0 until life) age.toFloat() / life else null
    }
}

private fun DrawScope.drawWaves(center: Offset, from: Float, to: Float, phases: List<Float>, color: Color) {
    for (t in phases) {
        drawCircle(color.copy(alpha = WAVE_ALPHA * (1f - t)), from + (to - from) * t, center, style = Stroke(1.5.dp.toPx()))
    }
}

/** The brand mark at [u] px per unit of the launcher's 108-unit viewport, centred on [center]. */
private fun DrawScope.drawMark(center: Offset, u: Float, colours: MarkColours, okFill: Float, check: Float, tick: Path, measure: PathMeasure) {
    val o = Offset(center.x - 54f * u, center.y - 54f * u)
    drawRoundRect(colours.body, o + Offset(36f * u, 28f * u), Size(36f * u, 52f * u), CornerRadius(14f * u))
    val ok = o + Offset(54f * u, 48f * u)
    drawCircle(lerp(colours.button, colours.buttonWarm, okFill), 9f * u, ok)
    drawLine(colours.line, o + Offset(48f * u, 68f * u), o + Offset(60f * u, 68f * u), strokeWidth = 4f * u, cap = StrokeCap.Round)
    if (check > 0f) {
        val path = Path().apply {
            moveTo(ok.x - 4.5f * u, ok.y + 0.2f * u)
            lineTo(ok.x - 1.2f * u, ok.y + 3.4f * u)
            lineTo(ok.x + 4.6f * u, ok.y - 3.2f * u)
        }
        measure.setPath(path, false)
        tick.reset()
        measure.getSegment(0f, measure.length * check.coerceIn(0f, 1f), tick, true)
        // Ink on amber: the tick reads by lightness, never by hue.
        drawPath(tick, colours.button, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * The mark with waves leaving its OK button ([waves], from [wavePhases]); [okFill] warms the OK
 * to amber and [check] draws a tick on it (Ready). The OK sits [markShare] of the width across.
 */
@Composable
internal fun IntroMark(waves: List<Float>, modifier: Modifier = Modifier, okFill: Float = 0f, check: Float = 0f, markShare: Float = 0.2f, showMark: Boolean = true) {
    val colours = markColours()
    val tick = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier) {
        val u = size.minDimension * markShare / 36f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawWaves(Offset(c.x, c.y - 6f * u), 12f * u, size.minDimension / 2f, waves, colours.body)
        if (showMark) drawMark(c, u, colours, okFill, check, tick, measure)
    }
}

/**
 * The search radar: rings, a sweep turning once every [SWEEP_MS], the waves still leaving, and a
 * dot per TV in [found] (by address), popping in where [radarAngle] puts it.
 */
@Composable
internal fun Radar(found: List<String>, modifier: Modifier = Modifier) {
    val colours = markColours()
    val clock by rememberClock()
    val seen = remember { mutableStateMapOf<String, Long>() }
    val tick = remember { Path() }
    val measure = remember { PathMeasure() }
    val ms = clock
    for (host in found) if (host !in seen) seen[host] = ms
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val ink = colours.body
        for (k in 1..3) drawCircle(ink.copy(alpha = 0.16f), r * k / 3f, c, style = Stroke(1.dp.toPx()))
        val angle = (ms % SWEEP_MS) / SWEEP_MS.toFloat() * 360f
        drawArc(ink.copy(alpha = 0.14f), angle - 50f, 50f, useCenter = true, topLeft = c - Offset(r, r), size = Size(2 * r, 2 * r))
        drawWaves(c, r * 0.18f, r, wavePhases(ms), ink)
        for (host in found) {
            val t = ((ms - (seen[host] ?: ms)) / Motion.LONG.toFloat()).coerceIn(0f, 1f)
            val a = Math.toRadians(radarAngle(host).toDouble())
            val at = c + Offset((cos(a) * r * 0.72f).toFloat(), (sin(a) * r * 0.72f).toFloat())
            drawCircle(ink, 5.dp.toPx() * easeOutBack(t), at)
        }
        drawMark(c, r * 0.32f / 36f, colours, 0f, 0f, tick, measure)
    }
}

private fun easeOutBack(t: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

/** A tick traced from its short arm to the tip as [progress] runs 0 to 1. */
@Composable
internal fun DrawnCheck(progress: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val segment = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier.size(size)) {
        val path = Path().apply {
            moveTo(this@Canvas.size.width * 0.18f, this@Canvas.size.height * 0.54f)
            lineTo(this@Canvas.size.width * 0.42f, this@Canvas.size.height * 0.76f)
            lineTo(this@Canvas.size.width * 0.84f, this@Canvas.size.height * 0.28f)
        }
        measure.setPath(path, false)
        segment.reset()
        measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), segment, true)
        drawPath(segment, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * "Step 2 of 4" and four segments with their names. A segment fills over [Motion.LONG] when its
 * step is reached (half for the current one, whole once done), then its tick draws. State reads
 * by number, fill and tick, never by colour: done ✓ name, current "2. Name" in bold, later muted.
 */
@Composable
internal fun StepBar(current: Int, labels: List<String>, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val stepOf = stringResource(R.string.intro_step_of, current + 1, labels.size)
    Column(modifier.semantics { contentDescription = "$stepOf · ${labels[current]}" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stepOf, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, name ->
                val fill by animateFloatAsState(
                    when {
                        i < current -> 1f
                        i == current -> 0.5f
                        else -> 0f
                    },
                    if (reduced) Motion.fade() else tween(Motion.LONG, easing = Motion.Emphasized),
                    label = "segment",
                )
                val check by animateFloatAsState(
                    if (i < current) 1f else 0f,
                    if (reduced) Motion.fade() else tween(Motion.MEDIUM, delayMillis = Motion.LONG, easing = Motion.Emphasized),
                    label = "check",
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(colors.onBackground.copy(alpha = 0.18f), cornerRadius = radius)
                        if (fill > 0f) drawRoundRect(colors.onBackground, size = Size(size.width * fill, size.height), cornerRadius = radius)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (i < current) {
                            DrawnCheck(check, colors.onBackground, size = 12.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            if (i < current) name else "${i + 1}. $name",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                            color = if (i > current) colors.onSurfaceVariant else colors.onBackground,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Rises 16 dp into place while fading in, [index] × 60 ms after it first composes (Welcome,
 * Ready). Reduced motion: a [Motion.SHORT] fade, no rise, no stagger.
 */
@Composable
internal fun Rise(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) {
            shown.animateTo(1f, Motion.fade())
        } else {
            kotlinx.coroutines.delay(index * STAGGER_MS)
            shown.animateTo(1f, tween(Motion.LONG, easing = Motion.Emphasized))
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = shown.value
            if (!reduced) translationY = (1f - shown.value) * RISE.toPx()
        },
    ) { content() }
}

/**
 * One line of a checklist, read by glyph and word, never colour: a drawn tick (done, [Motion.MEDIUM]),
 * a turning ring in bold (active; a still open ring with reduced motion), a cross and the
 * [failure] sentence (failed), or a dashed circle in a muted word (waiting).
 */
@Composable
internal fun CheckLine(state: CheckState, text: String, failure: String? = null) {
    val reduced = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val tick by animateFloatAsState(
        if (state == CheckState.DONE) 1f else 0f,
        if (reduced) Motion.fade() else tween(Motion.MEDIUM, easing = Motion.Emphasized),
        label = "tick",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    CheckState.DONE -> {
                        Canvas(Modifier.size(20.dp)) { drawCircle(colors.onSurface) }
                        DrawnCheck(tick, colors.surface, size = 14.dp)
                    }
                    CheckState.ACTIVE -> if (reduced) {
                        Canvas(Modifier.size(16.dp)) {
                            drawArc(colors.onSurface, -90f, 270f, useCenter = false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        }
                    } else {
                        CircularProgressIndicator(Modifier.size(16.dp), color = colors.onSurface, strokeWidth = 2.dp)
                    }
                    CheckState.FAILED -> Icon(
                        painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(18.dp).border(1.5.dp, colors.error, RoundedCornerShape(50)).padding(2.dp),
                    )
                    CheckState.WAITING -> Canvas(Modifier.size(18.dp)) {
                        drawCircle(
                            colors.onSurfaceVariant,
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (state == CheckState.ACTIVE) FontWeight.Bold else FontWeight.Normal,
                color = if (state == CheckState.WAITING) colors.onSurfaceVariant else colors.onSurface,
            )
        }
        if (state == CheckState.FAILED && failure != null) {
            Text(failure, style = MaterialTheme.typography.bodySmall, color = colors.onSurface, modifier = Modifier.padding(start = 34.dp))
        }
    }
}

/**
 * The TV in the code step's illustration: a frame showing where the code appears. It pulses once
 * as the step arrives ("look there"); reduced motion leaves it still.
 */
@Composable
internal fun TvIllustration(modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val colors = MaterialTheme.colorScheme
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        kotlinx.coroutines.delay(Motion.LONG.toLong())
        pulse.animateTo(PULSE_SCALE, tween(Motion.SHORT, easing = Motion.Emphasized))
        pulse.animateTo(1f, Motion.snappy())
    }
    Column(
        modifier.graphicsLayer {
            scaleX = pulse.value
            scaleY = pulse.value
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier
                .width(200.dp)
                .border(2.dp, colors.onBackground, RoundedCornerShape(10.dp))
                .padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.intro_code_on_tv), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text("• • • • • •", style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
        }
        Canvas(Modifier.width(40.dp).height(3.dp)) { drawRoundRect(colors.onBackground, cornerRadius = CornerRadius(size.height)) }
    }
}

internal const val WAVE_EVERY_MS = 1_200L
internal const val WAVE_LIFE_MS = 3_600L
internal const val SWEEP_MS = 2_400L
private const val WAVE_ALPHA = 0.45f
private const val STAGGER_MS = 60L
private const val PULSE_SCALE = 1.06f
private val RISE = 16.dp


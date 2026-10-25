package io.kud.tutti.ui.relief

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.common.FeedbackEvent
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the eye is, for every slab on the remote. Null in [LocalRelief] means the relief is off,
 * and every key draws exactly as it always has. The eye is read at draw time only, so a tilt
 * redraws the keys and never recomposes them.
 */
@Stable
class ReliefScene {
    var eyeX by mutableFloatStateOf(HeadTracker.REST_EYE_X)
    var eyeY by mutableFloatStateOf(HeadTracker.REST_EYE_Y)

    fun rest() {
        eyeX = HeadTracker.REST_EYE_X
        eyeY = HeadTracker.REST_EYE_Y
    }
}

val LocalRelief = staticCompositionLocalOf<ReliefScene?> { null }

/** A slab's corner radius in px, for its drawn size; read every frame, so it may morph. */
fun interface ReliefCorner {
    fun radius(width: Float, height: Float, density: Density): Float

    companion object {
        val Circle = ReliefCorner { w, h, _ -> min(w, h) / 2f }
        fun of(corner: Dp) = ReliefCorner { _, _, density -> with(density) { corner.toPx() } }
    }
}

/** How deep each family stands, and the comfort limits (Maya, 2026-10-25: deep, for pop-out). */
object ReliefDepth {
    val KEY = 9.dp
    val OK = 10.dp
    val DISC = 8.dp
    val TILE = 7.dp
    val RAIL_KEY = 6.dp
    /** The slab that reaches [MAX_OFFSET] at a hard tilt; shallower ones travel in proportion. */
    val REFERENCE = 10.dp
    /** The top face never travels further than this from where the key really is. */
    val MAX_OFFSET = 14.dp
}

/**
 * The physical press under "3D relief" (Erwann, dev.79: "un vrai effet bouton"): down the full
 * depth to a thin lip in [DOWN_MS], no overshoot; back up on a spring with one small bounce,
 * settled in about 250 ms. Reduced motion: instant both ways. 0 rest, 1 down, briefly below 0 on
 * the bounce.
 */
object ReliefPress {
    const val DOWN_MS = 70
    /** Damping 0.5 overshoots about 16 % once; stiffness 1000 settles within about 250 ms. */
    const val UP_DAMPING = 0.5f
    const val UP_STIFFNESS = 1000f

    suspend fun down(sink: Animatable<Float, AnimationVector1D>, reduced: Boolean) {
        if (reduced) sink.snapTo(1f) else sink.animateTo(1f, tween(DOWN_MS, easing = FastOutLinearInEasing))
    }

    suspend fun up(sink: Animatable<Float, AnimationVector1D>, reduced: Boolean) {
        if (reduced) sink.snapTo(0f) else sink.animateTo(0f, spring(dampingRatio = UP_DAMPING, stiffness = UP_STIFFNESS))
    }
}

/**
 * A slab's press for [interaction] while the relief is [enabled], else null. A held press (and a
 * long press) stays down until the finger lifts; a release waits for the slab to land, so a quick
 * tap still shows the whole travel, then plays a lighter release haptic (tutti's vibration
 * setting applies: [FeedbackEvent.KEY_UP] is not essential). The down haptic stays the caller's.
 */
@Composable
fun rememberReliefPress(interaction: InteractionSource, enabled: Boolean): Animatable<Float, AnimationVector1D>? {
    if (!enabled) return null
    val sink = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    val haptics = rememberHaptics()
    LaunchedEffect(interaction, reduced) {
        sink.snapTo(0f)
        var landing: Job? = null
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> landing = launch { ReliefPress.down(sink, reduced) }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    if (event is PressInteraction.Release) haptics.perform(FeedbackEvent.KEY_UP)
                    val landed = landing
                    launch {
                        landed?.join()
                        ReliefPress.up(sink, reduced)
                    }
                }
            }
        }
    }
    return sink
}

/**
 * Draws this node as a slab [depth] high, when [scene] is set: its shadows, the walls the eye can
 * see, then the node's own drawing moved (and scaled a touch) onto the top face, then the top
 * face's gleam and, when pressed, its inner shade. Only the drawing moves: layout and hit testing
 * never see it, so the tap target stays exactly where it was. [press] is the slab's sink
 * ([rememberReliefPress]). [slab] draws a centred square of that size instead of the whole node (a
 * key inside a larger touch target). With [scene] null this returns the modifier untouched.
 */
fun Modifier.relief(
    scene: ReliefScene?,
    depth: Dp,
    side: () -> Color,
    corner: ReliefCorner = ReliefCorner.Circle,
    press: () -> Float = { 0f },
    slab: Dp = Dp.Unspecified,
): Modifier = if (scene == null) {
    this
} else {
    this.drawWithCache {
        val slabber = Slabber()
        onDrawWithContent {
            val w = if (slab == Dp.Unspecified) size.width else min(slab.toPx(), size.width)
            val h = if (slab == Dp.Unspecified) size.height else min(slab.toPx(), size.height)
            slabber.polygon.setRoundRect((size.width - w) / 2f, (size.height - h) / 2f, w, h, corner.radius(w, h, this))
            val top = slabber.drawUnder(this, scene, depth.toPx(), side(), sink = press())
            val k = slabber.scale
            withTransform({
                translate(top.x, top.y)
                scale(k, k, Offset(size.width / 2f, size.height / 2f))
            }) { this@onDrawWithContent.drawContent() }
            slabber.drawOver(this)
        }
    }
}

/**
 * One slab's reusable drawing state: its outline and paths, allocated once and rewritten in place,
 * so a frame allocates nothing. Also used directly by surfaces that draw several slabs on one
 * canvas (the D-pad's disc and OK). Call [drawUnder], draw the top face at [top] and [scale]
 * (per quarter with [quarterTop] and [quarterScale] when [quarterSink] is used), then [drawOver].
 */
class Slabber(segmentsPerCorner: Int = 8) {
    val polygon = ReliefPolygon(segmentsPerCorner)
    /** Extra sink per quarter (0 right, 1 down, 2 left, 3 up): the D-pad presses one quarter in. */
    val quarterSink = FloatArray(4)
    private val qTopX = FloatArray(4)
    private val qTopY = FloatArray(4)
    private val qScale = FloatArray(4)
    private val qSink = FloatArray(4)
    private val path = Path()
    private val quad = Path()
    private val darkBand = Path()
    private val lipBand = Path()

    var top = Offset.Zero
        private set
    var scale = 1f
        private set
    private var drawn = false
    private var dark = false
    private var maxOffsetPx = 0f
    private var innerPx = 0f
    private var gleamPx = 0f

    fun quarterTop(q: Int): Offset = Offset(qTopX[q], qTopY[q])
    fun quarterScale(q: Int): Float = qScale[q]
    /** True when one quarter stands at another height than the rest. */
    fun split(): Boolean = abs(quarterSink[0]) + abs(quarterSink[1]) + abs(quarterSink[2]) + abs(quarterSink[3]) > 1e-3f

    /**
     * Shadows and walls for [polygon], [restDepthPx] deep pressed in by [sink]; returns the top
     * face's offset. [hole] draws the walls of a hole instead (normals pointing in, no shadow),
     * for the inside edge of a ring.
     */
    fun drawUnder(scope: DrawScope, scene: ReliefScene, restDepthPx: Float, side: Color, sink: Float = 0f, hole: Boolean = false): Offset = with(scope) {
        val ref = ReliefDepth.REFERENCE.toPx()
        maxOffsetPx = ReliefDepth.MAX_OFFSET.toPx()
        innerPx = INNER_SHADE.toPx()
        gleamPx = GLEAM.toPx()
        dark = side.luminance() <= Relief.DARK_LUMINANCE
        val depth = Relief.pressedDepth(restDepthPx, sink)
        top = Relief.topOffset(scene.eyeX, scene.eyeY, depth, ref, maxOffsetPx)
        scale = Relief.closeness(depth, ref)
        for (q in 0..3) {
            qSink[q] = if (quarterSink[q] > 0f) maxOf(sink, quarterSink[q]) else sink
            val d = Relief.pressedDepth(restDepthPx, qSink[q])
            val t = Relief.topOffset(scene.eyeX, scene.eyeY, d, ref, maxOffsetPx)
            qTopX[q] = t.x
            qTopY[q] = t.y
            qScale[q] = Relief.closeness(d, ref)
        }
        drawn = restDepthPx > 0f && !hole
        if (restDepthPx <= 0f) return top
        val p = polygon
        val n = p.count
        if (!hole) {
            // The cast shadow: soft, larger than the key, sliding against the top; it collapses
            // as the key goes down. Four stacked rings stand in for a blur without a mask filter.
            val down = sink.coerceIn(0f, 1f)
            val cast = Relief.castShadow(depth, top)
            val spread = Relief.shadowSpread(restDepthPx)
            val castAlpha = CAST_ALPHA * (1f - down) / CAST_LAYERS
            if (castAlpha > 0f) {
                for (layer in 1..CAST_LAYERS) {
                    outline(path, spread * layer / CAST_LAYERS, cast.x, cast.y)
                    drawPath(path, Color.Black.copy(alpha = castAlpha))
                }
            }
            // The contact shadow: tight at the base, there even when pressed flat.
            val contact = CONTACT_REACH.toPx()
            outline(path, CONTACT_GROW.toPx(), -Relief.LIGHT_X * contact, -Relief.LIGHT_Y * contact)
            drawPath(path, Color.Black.copy(alpha = CONTACT_ALPHA))
        }
        // All visible walls in their darkest shade first, as one path, so the per-edge quads that
        // follow never leave an antialiased seam onto the background between them.
        val sign = if (hole) -1f else 1f
        path.reset()
        darkBand.reset()
        lipBand.reset()
        var any = false
        for (i in 0 until n) {
            if (!visible(i, sign)) continue
            val j = if (i + 1 == n) 0 else i + 1
            band(path, i, j, 0f, 1f)
            band(darkBand, i, j, 0f, BASE_BAND)
            band(lipBand, i, j, LIP_BAND, 1f)
            any = true
        }
        if (!any) return top
        drawPath(path, wall(side, 0f, 0f))
        for (i in 0 until n) {
            if (!visible(i, sign)) continue
            quad.reset()
            band(quad, i, if (i + 1 == n) 0 else i + 1, 0f, 1f)
            drawPath(quad, wall(side, p.nx[i] * sign, p.ny[i] * sign))
        }
        // A gradient in three steps, without a per-frame brush: darker at the base, lit at the lip.
        drawPath(darkBand, Color.Black.copy(alpha = BASE_SHADE))
        drawPath(lipBand, Color.White.copy(alpha = if (dark) LIP_LIGHT_DARK else LIP_LIGHT))
        top
    }

    /** The top face's gleam along its lit edge, dimmed when pressed, and the pressed inner shade. */
    fun drawOver(scope: DrawScope) = with(scope) {
        if (!drawn) return
        val p = polygon
        val n = p.count
        val gleam = Relief.highlightDirection(top, maxOffsetPx)
        val gleamAlpha = if (dark) GLEAM_DARK else GLEAM_LIGHT
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val q = p.quarter(i)
            val k = qScale[q]
            val x0 = topX(i, q, k)
            val y0 = topY(i, q, k)
            val x1 = topX(j, q, k)
            val y1 = topY(j, q, k)
            val s = qSink[q].coerceIn(0f, 1f)
            if (s > 0.01f) {
                // Inset by half the stroke, so the shade sits inside the sunk top face.
                val ix = -p.nx[i] * innerPx / 2f * k
                val iy = -p.ny[i] * innerPx / 2f * k
                drawLine(Color.Black.copy(alpha = INNER_ALPHA * s), Offset(x0 + ix, y0 + iy), Offset(x1 + ix, y1 + iy), strokeWidth = innerPx)
            }
            val g = Relief.highlight(p.nx[i], p.ny[i], gleam) * gleamAlpha * (1f - GLEAM_PRESSED_DIM * s)
            if (g > 0.01f) {
                val ix = -p.nx[i] * gleamPx / 2f
                val iy = -p.ny[i] * gleamPx / 2f
                drawLine(Color.White.copy(alpha = g), Offset(x0 + ix, y0 + iy), Offset(x1 + ix, y1 + iy), strokeWidth = gleamPx, cap = StrokeCap.Round)
            }
        }
    }

    private fun topX(i: Int, q: Int, k: Float) = polygon.centreX + qTopX[q] + (polygon.xs[i] - polygon.centreX) * k
    private fun topY(i: Int, q: Int, k: Float) = polygon.centreY + qTopY[q] + (polygon.ys[i] - polygon.centreY) * k

    private fun visible(i: Int, sign: Float): Boolean {
        val p = polygon
        val j = if (i + 1 == p.count) 0 else i + 1
        val q = p.quarter(i)
        val k = qScale[q] - 1f
        val mx = (p.xs[i] + p.xs[j]) / 2f - p.centreX
        val my = (p.ys[i] + p.ys[j]) / 2f - p.centreY
        return Relief.wallVisible(p.nx[i] * sign, p.ny[i] * sign, Offset(qTopX[q] + mx * k, qTopY[q] + my * k))
    }

    /** The wall quad of edge i..j between heights [from] and [to] (0 base, 1 top). */
    private fun band(path: Path, i: Int, j: Int, from: Float, to: Float) {
        val p = polygon
        val q = p.quarter(i)
        val k = qScale[q]
        val ax = topX(i, q, k) - p.xs[i]
        val ay = topY(i, q, k) - p.ys[i]
        val bx = topX(j, q, k) - p.xs[j]
        val by = topY(j, q, k) - p.ys[j]
        path.moveTo(p.xs[i] + ax * from, p.ys[i] + ay * from)
        path.lineTo(p.xs[j] + bx * from, p.ys[j] + by * from)
        path.lineTo(p.xs[j] + bx * to, p.ys[j] + by * to)
        path.lineTo(p.xs[i] + ax * to, p.ys[i] + ay * to)
        path.close()
    }

    /** The outline grown by [grow] along each corner's normal and moved by ([dx], [dy]). */
    private fun outline(path: Path, grow: Float, dx: Float, dy: Float) {
        val p = polygon
        val n = p.count
        path.reset()
        for (i in 0 until n) {
            val prev = if (i == 0) n - 1 else i - 1
            var vx = p.nx[prev] + p.nx[i]
            var vy = p.ny[prev] + p.ny[i]
            val length = sqrt(vx * vx + vy * vy)
            if (length > 0f) {
                vx /= length
                vy /= length
            }
            val x = p.xs[i] + vx * grow + dx
            val y = p.ys[i] + vy * grow + dy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** The key's colour on a wall with outward normal ([nx], [ny]): light and shade only, never hue. */
    private fun wall(side: Color, nx: Float, ny: Float): Color {
        val k = Relief.wallFactor(if (nx == 0f && ny == 0f) Relief.AMBIENT else Relief.wallShade(nx, ny))
        val lift = if (nx == 0f && ny == 0f) Relief.wallLift(0f, 0f, dark) else Relief.wallLift(nx, ny, dark)
        return Color(
            side.red * k * (1f - lift) + lift,
            side.green * k * (1f - lift) + lift,
            side.blue * k * (1f - lift) + lift,
            side.alpha,
        )
    }

    private companion object {
        const val CAST_ALPHA = 0.30f
        const val CAST_LAYERS = 4
        const val CONTACT_ALPHA = 0.28f
        val CONTACT_GROW = 0.75.dp
        val CONTACT_REACH = 1.dp
        const val BASE_BAND = 0.45f
        const val BASE_SHADE = 0.18f
        const val LIP_BAND = 0.78f
        const val LIP_LIGHT = 0.12f
        const val LIP_LIGHT_DARK = 0.20f
        val INNER_SHADE = 4.dp
        const val INNER_ALPHA = 0.22f
        val GLEAM = 1.5.dp
        const val GLEAM_LIGHT = 0.55f
        const val GLEAM_DARK = 0.45f
        const val GLEAM_PRESSED_DIM = 0.7f
    }
}

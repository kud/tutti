package io.kud.tutti.ui.relief

import androidx.compose.runtime.Stable
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

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

/** How deep each family stands, and the comfort limits. */
object ReliefDepth {
    val KEY = 3.dp
    val OK = 4.dp
    val DISC = 3.dp
    val TILE = 2.5.dp
    val RAIL_KEY = 2.5.dp
    /** The top face never travels further than this from where the key really is. */
    val MAX_OFFSET = 4.dp
    /** A phone held at about 30 cm. */
    val VIEW_DISTANCE = 1900.dp
}

/**
 * Draws this node as a slab [depth] high, when [scene] is set: its contact shadow, the walls the
 * eye can see (one quad per outline edge, shaded by the fixed light), then the node's own drawing
 * moved onto the top face. Only the drawing moves: layout and hit testing never see the offset,
 * so the tap target stays exactly where it was. [press] (0..1) pushes the slab down to flat.
 * [slab] draws a centred square of that size instead of the whole node (a key inside a larger
 * touch target). With [scene] null this returns the modifier untouched.
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
            val depthPx = depth.toPx() * (1f - press().coerceIn(0f, 1f))
            val top = slabber.drawSlab(this, scene, depthPx, side())
            translate(top.x, top.y) { this@onDrawWithContent.drawContent() }
        }
    }
}

/**
 * One slab's reusable drawing state: its outline and two paths, allocated once and rewritten in
 * place, so a frame allocates nothing. Also used directly by surfaces that draw several slabs on
 * one canvas (the D-pad's disc and OK).
 */
class Slabber(segmentsPerCorner: Int = 8) {
    val polygon = ReliefPolygon(segmentsPerCorner)
    private val hull = Path()
    private val quad = Path()

    /**
     * Shadow and walls for [polygon] at [depthPx]; returns the top face's offset. [hole] draws the
     * walls of a hole instead (normals pointing in), for the inside edge of a ring.
     */
    fun drawSlab(scope: DrawScope, scene: ReliefScene, depthPx: Float, side: Color, hole: Boolean = false): Offset = with(scope) {
        val top = Relief.topOffset(
            scene.eyeX,
            scene.eyeY,
            depthPx,
            ReliefDepth.VIEW_DISTANCE.toPx(),
            ReliefDepth.MAX_OFFSET.toPx(),
        )
        if (depthPx <= 0f) return top
        val p = polygon
        val n = p.count
        if (!hole) {
            val shadow = Relief.shadowOffset(depthPx)
            hull.reset()
            for (i in 0 until n) {
                val x = p.xs[i] + shadow.x
                val y = p.ys[i] + shadow.y
                if (i == 0) hull.moveTo(x, y) else hull.lineTo(x, y)
            }
            hull.close()
            drawPath(hull, Color.Black.copy(alpha = SHADOW_ALPHA * min(1f, depthPx / ReliefDepth.KEY.toPx())))
        }
        // All visible walls in their darkest shade first, as one path, so the per-edge quads that
        // follow never leave an antialiased seam onto the background between them.
        val sign = if (hole) -1f else 1f
        hull.reset()
        var any = false
        for (i in 0 until n) {
            if (!Relief.wallVisible(p.nx[i] * sign, p.ny[i] * sign, top)) continue
            addQuad(hull, i, if (i + 1 == n) 0 else i + 1, top)
            any = true
        }
        if (!any) return top
        drawPath(hull, shade(side, Relief.AMBIENT))
        for (i in 0 until n) {
            val nx = p.nx[i] * sign
            val ny = p.ny[i] * sign
            if (!Relief.wallVisible(nx, ny, top)) continue
            quad.reset()
            addQuad(quad, i, if (i + 1 == n) 0 else i + 1, top)
            drawPath(quad, shade(side, Relief.wallShade(nx, ny)))
        }
        top
    }

    private fun addQuad(path: Path, i: Int, j: Int, top: Offset) {
        val p = polygon
        path.moveTo(p.xs[i], p.ys[i])
        path.lineTo(p.xs[j], p.ys[j])
        path.lineTo(p.xs[j] + top.x, p.ys[j] + top.y)
        path.lineTo(p.xs[i] + top.x, p.ys[i] + top.y)
        path.close()
    }

    private companion object {
        const val SHADOW_ALPHA = 0.22f
        /** The darkest wall keeps this much of the key's own colour. */
        const val WALL_FLOOR = 0.35f

        /** The key's colour under the wall's light, relative to the top face: light and shade only. */
        fun shade(side: Color, brightness: Float): Color {
            val k = WALL_FLOOR + (1f - WALL_FLOOR) * (brightness / Relief.TOP_SHADE).coerceIn(0f, 1f)
            return Color(side.red * k, side.green * k, side.blue * k, side.alpha)
        }
    }
}

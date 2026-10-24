package io.kud.tutti.ui.intro

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreenViewProvider
import io.kud.tutti.R
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.remote.DPAD_CHEVRON_STROKE
import io.kud.tutti.ui.remote.DPAD_CLOCKWISE
import io.kud.tutti.ui.remote.dpadChevronArm
import io.kud.tutti.ui.remote.dpadEdgeRadius
import io.kud.tutti.ui.remote.dpadOkRadius
import io.kud.tutti.ui.remote.dpadRingInner
import io.kud.tutti.ui.remote.drawChevron
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The launch intro, in three beats (Iris's splash notes). The system splash shows the launcher
 * mark still (res/drawable/splash_icon.xml): it is only the bridge, since the system plays
 * animated icons in its own process. Once the app has drawn its first frame, this overlay draws
 * the very same mark at the very same place, removes the system splash under it, and plays on the
 * app's own frame clock. Then the remote, already composed underneath and never held back.
 *
 * On a cold start it first opens on PR #11's splash composition ([SplashOpening]): the wordmark
 * arrives under the mark and, on a dark splash, a soft halo behind it, while the intro decides;
 * both leave as the choreography starts.
 *
 * "The OK becomes the pad" (Iris, 2026-10-23), richer (variant A of her second pass, plus B's
 * shimmering ring; Erwann, 2026-10-24): a sheen, the OK warms and slides onto the live D-pad's OK
 * while a ring runs out and becomes the disc; a click and a bloom on landing, the chevrons pop, a
 * last sheen signs the OK, then the backdrop opens from the pad outwards. The light is the theme's,
 * soft and low (Erwann, 2026-10-24: no bling: no white core, no fixed amber). 800 ms on a cold
 * start, 300 ms on a warm one, nothing on a hot resume. The first launch ever
 * hands the mark to the welcome screen instead. In touchpad mode the OK lands on the touchpad
 * instead, its ring becoming the pad's rounded rectangle ([OkBecomesTouchpad]). Reduced motion,
 * or no pad drawn yet, is a 200 ms fade. A tap skips any of them.
 *
 * Each choreography is data (Track keys) and a draw; nothing else knows about the timings.
 */

/** One keyframe: at [ms], the value is [value], reached from the previous key along [easing]. */
data class Key(val ms: Float, val value: Float, val easing: Easing = LinearEasing)

/** A property over time. Before the first key it holds the first value, after the last the last. */
class Track(vararg keys: Key) {
    private val keys = keys.sortedBy { it.ms }

    fun at(ms: Float): Float {
        if (ms <= keys.first().ms) return keys.first().value
        for (i in 1 until keys.size) {
            val to = keys[i]
            if (ms <= to.ms) {
                val from = keys[i - 1]
                val span = to.ms - from.ms
                val u = if (span <= 0f) 1f else (ms - from.ms) / span
                return from.value + (to.value - from.value) * to.easing.transform(u)
            }
        }
        return keys.last().value
    }

    val endMs: Float get() = keys.last().ms
}

/**
 * A spring as an easing over a [durationMs] segment, so a keyframe can travel like
 * [Motion.snappy] (damping 0.8, stiffness 600, unit mass): it overshoots a hair and settles.
 * Underdamped only ([damping] < 1). Exactly 1 at the end of the segment.
 */
class SpringEasing(private val durationMs: Float, private val damping: Float = 0.8f, stiffness: Float = 600f) : Easing {
    private val natural = sqrt(stiffness)
    private val damped = natural * sqrt(1f - damping * damping)

    override fun transform(fraction: Float): Float {
        if (fraction >= 1f) return 1f
        val t = fraction * durationMs / 1000f
        val decay = exp(-damping * natural * t)
        return 1f - decay * (cos(damped * t) + damping * natural / damped * sin(damped * t))
    }
}

private val Breath = CubicBezierEasing(0.37f, 0f, 0.63f, 1f) // sine in-out: a breath, no snap

/** Where the live screens put what the intro lands on, in window pixels. Null when not on screen. */
@Stable
class IntroAnchors {
    /** The D-pad face's box (Pad, D-pad mode only). */
    var pad by mutableStateOf<Rect?>(null)

    /** The touchpad surface (Pad, touchpad mode only): its box and corner radius. */
    var touchpad by mutableStateOf<TouchpadAnchor?>(null)

    /** The welcome screen's mark: the box of its 108-unit viewport, drawn by [LaunchMark]. */
    var welcome by mutableStateOf<Rect?>(null)
}

/** The touchpad's rounded rectangle: [bounds] and [cornerRadius] in pixels, as Pad clips it. */
data class TouchpadAnchor(val bounds: Rect, val cornerRadius: Float)

val LocalIntroAnchors = staticCompositionLocalOf { IntroAnchors() }

/** The live D-pad's geometry, in the overlay's coordinates, as [io.kud.tutti.ui.remote.DpadFace] draws it. */
data class PadTarget(
    val centre: Offset,
    val okRadius: Float,
    val edgeRadius: Float,
    /** The ring's inner edge: the OK plus its moat. */
    val innerRadius: Float = okRadius,
    /** A resting chevron's arm. */
    val chevronArm: Float = 0f,
) {
    val ringMid: Float get() = (innerRadius + edgeRadius) / 2f
}

/** Everything a choreography draws from at one frame. */
class IntroScene(
    val colours: MarkColours,
    /** The launcher mark's 108-unit viewport, where the system splash drew it. */
    val icon: Rect,
    val pad: PadTarget?,
    val welcome: Rect?,
    /** The D-pad's OK, disc and chevron colours, so the landing is seamless. */
    val padOk: Color,
    val padDisc: Color,
    val padChevron: Color = padDisc,
    /** The app theme's light for the halo, the rings, the sheen on the OK and the bloom. */
    val theme: IntroTheme = IntroTheme(padOk, padOk, padOk, dark = false),
    /** The live touchpad, in the overlay's coordinates, when the pad is in touchpad mode. */
    val touchpad: TouchpadAnchor? = null,
    /** The opening's wordmark and halo at this frame, set by the overlay before each draw. */
    val opening: OpeningFrame = OpeningFrame(),
)

/**
 * The intro's light, from the app theme (MaterialTheme.colorScheme), so it follows dynamic colour
 * and light/dark: [accent] is the touchpad's own glow (primary), [dim] the same at lower contrast
 * against the pad's surface, [sheen] a soft lift that reads on the OK in this theme. Never white,
 * never a fixed amber (Erwann, 2026-10-24: "plus subtil, ou avec les couleurs du thème").
 */
data class IntroTheme(val accent: Color, val dim: Color, val sheen: Color, val dark: Boolean)

/**
 * A choreography: everything the intro draws, as a function of time. At 0 ms it must draw exactly
 * what the system splash shows. [landingMs] is when the click (haptic, and the key sound if it is
 * on) lands, if any.
 */
interface IntroChoreography {
    val durationMs: Float
    val landingMs: Float? get() = null

    /** How the opening's wordmark and halo leave once this starts: 1 → 0 over its first beat. */
    val openingExit: Track get() = OpeningExit

    fun DrawScope.draw(ms: Float, scene: IntroScene)
}

/** Where the mark is at one instant, in the launcher mark's 108-unit viewport. */
data class MarkPose(
    val markScale: Float,
    val markAlpha: Float,
    val okWarmth: Float,
    val okScale: Float,
    val backdropAlpha: Float,
)

/** The pose the system splash shows: the starting picture of every choreography. */
val RestingPose = MarkPose(markScale = 1f, markAlpha = 1f, okWarmth = 0f, okScale = 1f, backdropAlpha = 1f)

/**
 * What both landings share: the OK warms, swells and slides onto the pad while a ring runs out and
 * becomes its surface, a click and a bloom on landing, then the backdrop opens from the pad outwards.
 * Same tracks, same budget (800 ms cold, 300 ms warm); [OkBecomesPad] lands on the D-pad's OK and
 * disc, [OkBecomesTouchpad] on the touchpad's rounded rectangle.
 */
internal abstract class OkLanding(warm: Boolean) : IntroChoreography {
    val warmth = if (warm) Track(Key(0f, 0f)) else Track(Key(0f, 0f), Key(160f, 1f, Motion.Emphasized))
    val swell = if (warm) Track(Key(0f, 1f)) else Track(Key(0f, 1f), Key(160f, 1.18f, Motion.Emphasized))
    val halo = if (warm) Track(Key(0f, 0f)) else Track(Key(0f, 0f), Key(120f, 1f, Motion.Emphasized), Key(160f, 1f), Key(320f, 0f, Motion.Exit))
    val glint = if (warm) null else Track(Key(0f, 0f), Key(240f, 1f, Breath))
    val body = if (warm) Track(Key(0f, 1f), Key(100f, 0f, Motion.Exit)) else Track(Key(0f, 1f), Key(160f, 1f), Key(300f, 0f, Motion.Exit))
    val travel = if (warm) Track(Key(0f, 0f), Key(220f, 1f, SpringEasing(220f))) else Track(Key(0f, 0f), Key(160f, 0f), Key(470f, 1f, SpringEasing(310f)))
    val ring = if (warm) Track(Key(0f, 0f), Key(40f, 0f), Key(220f, 1f, Motion.Emphasized)) else Track(Key(0f, 0f), Key(220f, 0f), Key(470f, 1f, Motion.Emphasized))
    val ringFade = if (warm) Track(Key(0f, 1f), Key(220f, 1f), Key(300f, 0f, Motion.Exit)) else Track(Key(0f, 1f), Key(470f, 1f), Key(620f, 0f, Motion.Exit))
    val echo = if (warm) null else Track(Key(0f, 0f), Key(290f, 0f), Key(530f, 1f, Motion.Emphasized))
    val bloom = if (warm) {
        Track(Key(0f, 0f), Key(220f, 0f), Key(250f, 0.5f, Motion.Emphasized), Key(300f, 0f, Motion.Exit))
    } else {
        Track(Key(0f, 0f), Key(470f, 0f), Key(520f, 1f, Motion.Emphasized), Key(740f, 0f, Motion.Exit))
    }
    val reveal = if (warm) Track(Key(0f, 0f), Key(180f, 0f), Key(300f, 1f, Motion.Emphasized)) else Track(Key(0f, 0f), Key(500f, 0f), Key(800f, 1f, Motion.Emphasized))

    override val durationMs: Float = if (warm) 300f else 800f
    override val landingMs: Float? = if (warm) null else 470f

    private var paints: IntroPaints? = null

    protected fun DrawScope.paintsFor(okRadius: Float, bloomRadius: Float, scene: IntroScene): IntroPaints =
        paints?.takeIf { it.fits(okRadius, bloomRadius, scene.colours, scene.theme) }
            ?: IntroPaints(this, okRadius, bloomRadius, scene.colours, scene.theme).also { paints = it }

    /** The backdrop's alpha as it opens: whole until halfway, then gone by the end. */
    protected fun backdropAlpha(open: Float): Float = if (open < 0.5f) 1f else 1f - (open - 0.5f) * 2f

    /** The mark's body and line step back, with the first sheen across them; the OK is drawn apart. */
    protected fun DrawScope.drawBodyStepBack(ms: Float, scene: IntroScene, paint: IntroPaints) {
        val bodyAlpha = body.at(ms)
        if (bodyAlpha <= 0f) return
        drawLaunchMark(RestingPose.copy(markAlpha = bodyAlpha, backdropAlpha = 0f), scene.colours, scene.icon, withOk = false)
        val g = glint?.at(ms) ?: 0f
        if (g <= 0f || g >= 1f) return
        val u = scene.icon.width / 108f
        translate(scene.icon.left, scene.icon.top) {
            scale(u, u, pivot = Offset.Zero) {
                clipPath(paint.bodyClip) {
                    rotate(GLINT_TILT, pivot = Offset(54f, 54f)) {
                        translate(6f + 92f * g - MARK_BAND / 2f, -10f) {
                            drawRect(paint.markGlint, Offset.Zero, Size(MARK_BAND, 128f), alpha = bodyAlpha)
                        }
                    }
                }
            }
        }
    }

    /** Where the OK is: from the mark's OK to [landing] along [travel]. */
    protected fun okCentre(ms: Float, scene: IntroScene, landing: Offset): Offset {
        val u = scene.icon.width / 108f
        return lerp(scene.icon.topLeft + Offset(MarkGeometry.CENTRE * u, MarkGeometry.OK_Y * u), landing, travel.at(ms))
    }

    /** The OK's radius: the mark's OK, swelling, then [landing]'s. */
    protected fun okRadius(ms: Float, scene: IntroScene, landing: Float): Float {
        val start = MarkGeometry.OK_RADIUS * scene.icon.width / 108f * swell.at(ms)
        return start + (landing - start) * travel.at(ms)
    }

    /** The OK's colour: the mark's, warming, then [landing]. */
    protected fun okInk(ms: Float, scene: IntroScene, landing: Color): Color =
        lerp(lerp(scene.colours.button, scene.colours.buttonWarm, warmth.at(ms)), landing, travel.at(ms).coerceIn(0f, 1f))

    /** The halo, over the body and under the OK; one unit-radius brush, scaled to the OK. */
    protected fun DrawScope.drawHalo(ms: Float, centre: Offset, radius: Float, paint: IntroPaints) {
        val glow = halo.at(ms)
        if (glow <= 0f) return
        translate(centre.x, centre.y) {
            scale(radius * HALO_REACH / UNIT, pivot = Offset.Zero) {
                drawCircle(paint.halo, UNIT, Offset.Zero, alpha = glow)
            }
        }
    }

    /** The OK's rim while the halo is up. */
    protected fun DrawScope.drawRim(ms: Float, centre: Offset, radius: Float, scene: IntroScene, paint: IntroPaints) {
        val glow = halo.at(ms)
        if (glow > 0f) drawCircle(scene.theme.accent, radius, centre, style = paint.rim, alpha = RIM_ALPHA * glow)
    }

    /** The landing's bloom at [at]: the theme's accent, low; additive on a dark theme, plain over light. */
    protected fun DrawScope.drawBloom(ms: Float, at: Offset, scene: IntroScene, paint: IntroPaints) {
        val b = bloom.at(ms)
        if (b <= 0f) return
        translate(at.x, at.y) {
            drawCircle(paint.bloom, paint.bloomRadius, Offset.Zero, alpha = b, blendMode = if (scene.theme.dark) BlendMode.Plus else BlendMode.SrcOver)
        }
    }
}

/**
 * A, "classy, richer" (Iris's second pass, chosen by Erwann 2026-10-24), with B's shimmering ring,
 * toned down to the theme's colours. Cold start, 800 ms:
 * - 0-240 a soft sheen crosses the mark; the OK warms and swells (x1.18) with a halo and rim in the theme's accent.
 * - 160-300 the body and the line step back; 160-470 the OK slides onto the pad's OK ([Motion.snappy]).
 * - 220-470 ring 1 (accent, shimmering at low contrast) runs out to the disc's edge and the disc
 *   fills behind it; 290-530 a thin accent ring echoes it.
 * - 470 the landing: the click and a low bloom in the accent (never white), gone by 740.
 * - 470-655 the chevrons pop clockwise from the top, 35 ms apart; 560-760 the sheen signs the pad's OK.
 * - 500-800 the backdrop opens from the disc's edge.
 * Warm start ([warm]), 300 ms: the slide with ring 1 and half a bloom; no sheen, no echo, the
 * chevrons together, no click.
 *
 * The overlay draws its own disc and chevrons over the pad until the end, identical to it, so the
 * hand-over is invisible. Cost: a handful of gradient brushes and two clip paths, built once per
 * pad geometry and theme ([IntroPaints]) and moved with canvas transforms, so their shaders are
 * never rebuilt; per frame, a dozen primitives, two strokes and the four chevron paths the pad itself draws.
 */
internal class OkBecomesPad(warm: Boolean) : OkLanding(warm) {
    val okGlint = if (warm) null else Track(Key(0f, 0f), Key(560f, 0f), Key(760f, 1f, Breath))

    /** When each chevron (up, right, down, left) starts to pop. */
    val chevronStart: List<Float> = List(4) { i -> if (warm) 220f else 470f + 35f * i }
    private val pop = if (warm) 60f else 80f
    val chevronScale = chevronStart.map { s -> Track(Key(s, 0.4f), Key(s + pop * 0.62f, 1.06f, Motion.Emphasized), Key(s + pop, 1f, Breath)) }
    val chevronAlpha = chevronStart.map { s -> Track(Key(s, 0f), Key(s + pop + 10f, 1f, Motion.Emphasized)) }

    override fun DrawScope.draw(ms: Float, scene: IntroScene) {
        val pad = scene.pad ?: return drawLaunchMark(RestingPose, scene.colours, scene.icon)
        val colours = scene.colours
        val theme = scene.theme
        val paint = paintsFor(pad.okRadius, pad.edgeRadius * if (theme.dark) 1.15f else 1f, scene)
        val open = reveal.at(ms)

        // The backdrop, with a hole that opens from the disc's edge to the farthest corner.
        val backdrop = backdropAlpha(open)
        if (backdrop > 0f) {
            val alpha = colours.background.alpha * backdrop
            if (open <= 0f) {
                drawRect(colours.background, alpha = alpha)
            } else {
                val far = maxOf(
                    hypot(pad.centre.x, pad.centre.y),
                    hypot(size.width - pad.centre.x, pad.centre.y),
                    hypot(pad.centre.x, size.height - pad.centre.y),
                    hypot(size.width - pad.centre.x, size.height - pad.centre.y),
                )
                val r = pad.edgeRadius + (far - pad.edgeRadius) * open
                paint.backdrop.asAndroidPath().apply {
                    reset()
                    fillType = android.graphics.Path.FillType.EVEN_ODD
                    addRect(0f, 0f, size.width, size.height, android.graphics.Path.Direction.CW)
                    addCircle(pad.centre.x, pad.centre.y, r, android.graphics.Path.Direction.CW)
                }
                drawPath(paint.backdrop, colours.background, alpha = alpha)
            }
        }
        drawOpeningHalo(scene, openingExit.at(ms) * backdrop)

        drawBodyStepBack(ms, scene, paint)

        val t = travel.at(ms)
        val centre = okCentre(ms, scene, pad.centre)
        val radius = okRadius(ms, scene, pad.okRadius)
        drawHalo(ms, centre, radius, paint)

        // Ring 1 runs out and the disc fills behind it, from the moat (growing in as the OK lands) out.
        val reach = ring.at(ms)
        val ringRadius = radius + (pad.edgeRadius - radius) * reach
        if (reach > 0f) {
            val inner = radius + (pad.innerRadius - pad.okRadius) * t.coerceIn(0f, 1f)
            if (ringRadius > inner) {
                val width = ringRadius - inner
                drawCircle(scene.padDisc, inner + width / 2f, centre, style = Stroke(width))
            }
        }

        // The chevrons pop clockwise, the pad's own drawing at the pad's own size.
        for (i in DPAD_CLOCKWISE.indices) {
            val a = chevronAlpha[i].at(ms)
            if (ms < chevronStart[i] || a <= 0f) continue
            val code = DPAD_CLOCKWISE[i]
            val pivot = pad.centre + direction(code) * pad.ringMid
            scale(chevronScale[i].at(ms), pivot) {
                drawChevron(code, pad.ringMid, pad.chevronArm, scene.padChevron.copy(alpha = scene.padChevron.alpha * a), paint.chevron, pad.centre)
            }
        }

        // Ring 2, thin, a beat behind; then ring 1 on top, shimmering (a turning sweep).
        val e = echo?.at(ms) ?: 0f
        if (e > 0f && e < 1f) {
            drawCircle(theme.accent, radius + (pad.edgeRadius - radius) * e, centre, style = paint.echo, alpha = ECHO_ALPHA * (1f - e))
        }
        val ringAlpha = ringFade.at(ms)
        if (reach > 0f && ringAlpha > 0f) {
            translate(centre.x, centre.y) {
                rotate(ms * SHIMMER_DEGREES_PER_MS, pivot = Offset.Zero) {
                    drawCircle(paint.shimmer, ringRadius, Offset.Zero, style = paint.ring, alpha = RING_ALPHA * ringAlpha)
                }
            }
        }

        drawCircle(okInk(ms, scene, scene.padOk), radius, centre)
        drawRim(ms, centre, radius, scene, paint)

        // The sheen signs the pad's OK, once.
        val sign = okGlint?.at(ms) ?: 0f
        if (sign > 0f && sign < 1f) {
            val r = pad.okRadius
            translate(pad.centre.x, pad.centre.y) {
                clipPath(paint.okClip) {
                    rotate(GLINT_TILT, pivot = Offset.Zero) {
                        translate(-1.8f * r + 3.6f * r * sign - paint.okBand / 2f, -2f * r) {
                            drawRect(paint.okGlint, Offset.Zero, Size(paint.okBand, 4f * r))
                        }
                    }
                }
            }
        }

        drawBloom(ms, pad.centre, scene, paint)
    }
}

/**
 * The touchpad's landing (Erwann, 2026-10-24: "il manque l'anim quand on a choisi le trackpad"):
 * the same beats and budget as [OkBecomesPad], with the touchpad's shape. Cold start, 800 ms:
 * - 0-240 the sheen, the warmth and the halo, as on the D-pad.
 * - 160-470 the OK slides to the touchpad's centre ([Motion.snappy]).
 * - 220-470 the shimmering ring runs out from the OK and morphs from a circle into the touchpad's
 *   rounded rectangle (half-width, half-height and corner all grow from the OK's radius to the
 *   pad's), and the surface fills behind it; 290-530 a thin echo morphs a beat behind.
 * - 470 the landing: the click and the bloom, centred on the pad like the touchpad's own tap halo.
 *   The touchpad has no OK, so the OK sinks into the surface as the bloom rises (470-620): it
 *   becomes the touch, which is what the touchpad answers to. No chevrons, no sheen on an OK.
 * - 500-800 the backdrop opens from the pad's outline, corners and all.
 * Warm, 300 ms: the slide, the morph and half a bloom; the OK sinks 220-300.
 *
 * The morph is drawn as plain rounded rectangles (no path per frame), and the shimmer turns by
 * the cached shader's local matrix, since a rounded rectangle cannot be turned with the canvas.
 */
internal class OkBecomesTouchpad(warm: Boolean) : OkLanding(warm) {
    val okFade = if (warm) Track(Key(0f, 1f), Key(220f, 1f), Key(300f, 0f, Motion.Exit)) else Track(Key(0f, 1f), Key(470f, 1f), Key(620f, 0f, Motion.Exit))

    override fun DrawScope.draw(ms: Float, scene: IntroScene) {
        val pad = scene.touchpad ?: return drawLaunchMark(RestingPose, scene.colours, scene.icon)
        val colours = scene.colours
        val theme = scene.theme
        val box = pad.bounds
        val half = min(box.width, box.height) / 2f
        val landingRadius = half * TOUCHPAD_OK_FRACTION
        val paint = paintsFor(landingRadius, half * TOUCHPAD_BLOOM_FRACTION, scene)
        val open = reveal.at(ms)

        // The backdrop, with a hole that opens from the pad's outline until it clears the screen.
        val backdrop = backdropAlpha(open)
        if (backdrop > 0f) {
            val alpha = colours.background.alpha * backdrop
            if (open <= 0f) {
                drawRect(colours.background, alpha = alpha)
            } else {
                val grow = hypot(size.width, size.height) * open
                val corner = pad.cornerRadius + grow
                paint.backdrop.asAndroidPath().apply {
                    reset()
                    fillType = android.graphics.Path.FillType.EVEN_ODD
                    addRect(0f, 0f, size.width, size.height, android.graphics.Path.Direction.CW)
                    addRoundRect(box.left - grow, box.top - grow, box.right + grow, box.bottom + grow, corner, corner, android.graphics.Path.Direction.CW)
                }
                drawPath(paint.backdrop, colours.background, alpha = alpha)
            }
        }
        drawOpeningHalo(scene, openingExit.at(ms) * backdrop)

        drawBodyStepBack(ms, scene, paint)

        val centre = okCentre(ms, scene, box.center)
        val radius = okRadius(ms, scene, landingRadius)
        drawHalo(ms, centre, radius, paint)

        // The ring runs out and becomes the pad: the surface fills behind it, then the ring shimmers on top.
        val reach = ring.at(ms)
        if (reach > 0f) {
            drawMorph(scene.padDisc, centre, radius, pad, reach, style = Fill, alpha = 1f)
        }
        val e = echo?.at(ms) ?: 0f
        if (e > 0f && e < 1f) {
            drawMorph(theme.accent, centre, radius, pad, e, style = paint.echo, alpha = ECHO_ALPHA * (1f - e))
        }
        val ringAlpha = ringFade.at(ms)
        if (reach > 0f && ringAlpha > 0f) {
            paint.shimmerMatrix.setRotate(ms * SHIMMER_DEGREES_PER_MS)
            paint.shimmerMatrix.postTranslate(centre.x, centre.y)
            paint.shimmerShader.setLocalMatrix(paint.shimmerMatrix)
            drawMorph(paint.turningShimmer, centre, radius, pad, reach, paint.ring, RING_ALPHA * ringAlpha)
        }

        val sink = okFade.at(ms)
        if (sink > 0f) drawCircle(okInk(ms, scene, scene.padOk), radius, centre, alpha = sink)
        drawRim(ms, centre, radius, scene, paint)

        drawBloom(ms, box.center, scene, paint)
    }

    /** The shape at [reach] of the way from a circle of [radius] round [centre] to the pad's rounded rectangle. */
    private fun DrawScope.drawMorph(colour: Color, centre: Offset, radius: Float, pad: TouchpadAnchor, reach: Float, style: DrawStyle, alpha: Float) {
        val hw = radius + (pad.bounds.width / 2f - radius) * reach
        val hh = radius + (pad.bounds.height / 2f - radius) * reach
        val corner = (radius + (pad.cornerRadius - radius) * reach).coerceAtMost(min(hw, hh))
        drawRoundRect(colour, Offset(centre.x - hw, centre.y - hh), Size(2f * hw, 2f * hh), CornerRadius(corner), style = style, alpha = alpha)
    }

    private fun DrawScope.drawMorph(brush: Brush, centre: Offset, radius: Float, pad: TouchpadAnchor, reach: Float, style: DrawStyle, alpha: Float) {
        val hw = radius + (pad.bounds.width / 2f - radius) * reach
        val hh = radius + (pad.bounds.height / 2f - radius) * reach
        val corner = (radius + (pad.cornerRadius - radius) * reach).coerceAtMost(min(hw, hh))
        drawRoundRect(brush, Offset(centre.x - hw, centre.y - hh), Size(2f * hw, 2f * hh), CornerRadius(corner), style = style, alpha = alpha)
    }
}

private fun direction(code: Int): Offset = when (code) {
    V2Key.DPAD_UP -> Offset(0f, -1f)
    V2Key.DPAD_DOWN -> Offset(0f, 1f)
    V2Key.DPAD_LEFT -> Offset(-1f, 0f)
    else -> Offset(1f, 0f)
}

/**
 * The intro's brushes, paths and strokes, built once for a landing's geometry and the theme. Every
 * gradient is laid out in fixed local coordinates and moved by translate, rotate or scale (or, for
 * the touchpad's shimmer, the shader's own local matrix), so its shader is made once, not per frame.
 */
internal class IntroPaints(scope: DrawScope, private val okRadius: Float, val bloomRadius: Float, colours: MarkColours, private val theme: IntroTheme) {
    private val ivory = colours.ivory

    fun fits(okRadius: Float, bloomRadius: Float, colours: MarkColours, theme: IntroTheme) =
        okRadius == this.okRadius && bloomRadius == this.bloomRadius && colours.ivory == ivory && theme == this.theme

    val backdrop = Path()
    val bodyClip = Path().apply {
        addRoundRect(
            RoundRect(MarkGeometry.BODY_LEFT, MarkGeometry.BODY_TOP, MarkGeometry.BODY_RIGHT, MarkGeometry.BODY_BOTTOM, CornerRadius(MarkGeometry.BODY_CORNER)),
        )
    }
    val okClip = Path().apply { addOval(Rect(Offset.Zero, okRadius)) }

    // A soft sheen, not a glint: wide, no core, a low peak that eases in and out.
    val markGlint = Brush.horizontalGradient(*sheenStops(ivory, MARK_SHEEN_PEAK), startX = 0f, endX = MARK_BAND)
    val okBand = maxOf(okRadius * OK_BAND, 1f)
    val okGlint = Brush.horizontalGradient(*sheenStops(theme.sheen, OK_SHEEN_PEAK), startX = 0f, endX = okBand)
    val halo = haloBrush(theme.accent, HALO_PEAK, UNIT)
    val bloom = haloBrush(theme.accent, BLOOM_PEAK, bloomRadius)
    private val shimmerColours = shimmerStops(theme)
    val shimmer = Brush.sweepGradient(*shimmerColours, center = Offset.Zero)
    val shimmerShader = SweepGradientShader(Offset.Zero, shimmerColours.map { it.second }, shimmerColours.map { it.first })
    val shimmerMatrix = android.graphics.Matrix()
    val turningShimmer = ShaderBrush(shimmerShader)

    val ring = with(scope) { Stroke(2.5.dp.toPx()) }
    val echo = with(scope) { Stroke(1.dp.toPx()) }
    val rim = with(scope) { Stroke(1.5.dp.toPx()) }
    val chevron = with(scope) { Stroke(DPAD_CHEVRON_STROKE.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) }
}

/** A band that rises to [peak] and falls away smoothly, transparent at both ends. */
internal fun sheenStops(colour: Color, peak: Float): Array<Pair<Float, Color>> = arrayOf(
    0f to colour.copy(alpha = 0f),
    0.2f to colour.copy(alpha = peak * 0.2f),
    0.38f to colour.copy(alpha = peak * 0.7f),
    0.5f to colour.copy(alpha = peak),
    0.62f to colour.copy(alpha = peak * 0.7f),
    0.8f to colour.copy(alpha = peak * 0.2f),
    1f to colour.copy(alpha = 0f),
)

/** A radial halo centred on the origin: [peak] at the middle, gone at [radius]. */
private fun haloBrush(colour: Color, peak: Float, radius: Float) = Brush.radialGradient(
    0f to colour.copy(alpha = peak),
    0.45f to colour.copy(alpha = peak * 0.45f),
    1f to colour.copy(alpha = 0f),
    center = Offset.Zero,
    radius = radius,
)

/** The shimmering ring's turning sweep: accent and its dimmer self, two soft crests a turn. */
private fun shimmerStops(theme: IntroTheme): Array<Pair<Float, Color>> = arrayOf(
    0f to theme.dim,
    0.14f to theme.accent,
    0.3f to theme.dim,
    0.64f to theme.dim,
    0.78f to theme.accent,
    0.94f to theme.dim,
    1f to theme.dim,
)

private const val GLINT_TILT = 25f
private const val MARK_BAND = 26f
private const val OK_BAND = 1.3f
private const val UNIT = 100f
private const val HALO_REACH = 2.2f
private const val SHIMMER_DEGREES_PER_MS = 0.6f

/** The OK lands at this share of the touchpad's half-size, and the bloom reaches this far. */
internal const val TOUCHPAD_OK_FRACTION = 0.14f
internal const val TOUCHPAD_BLOOM_FRACTION = 0.9f

/** Peak alphas: the glint's core was 0.95 white, the bloom 0.6 and the halo 0.55 amber. */
internal const val MARK_SHEEN_PEAK = 0.3f
internal const val OK_SHEEN_PEAK = 0.28f
internal const val HALO_PEAK = 0.3f
internal const val BLOOM_PEAK = 0.3f
private const val RING_ALPHA = 0.8f
private const val ECHO_ALPHA = 0.4f
private const val RIM_ALPHA = 0.6f

/**
 * The very first launch: the OK breathes while the mark rises and grows onto the welcome screen's
 * own mark ([Motion.HERO], [Motion.snappy]); the backdrop fades from 600 ms. Without a welcome
 * mark on screen the mark stays put and fades with the backdrop.
 */
object HandOffToWelcome : IntroChoreography {
    val okWarmth = Track(Key(0f, 0f), Key(300f, 1f, Breath), Key(700f, 0f, Breath))
    val okScale = Track(Key(0f, 1f), Key(300f, 1.04f, Breath), Key(700f, 1f, Breath))
    val travel = Track(Key(0f, 0f), Key(150f, 0f), Key(600f, 1f, SpringEasing(450f)))
    val backdropAlpha = Track(Key(0f, 1f), Key(600f, 1f), Key(Motion.HERO.toFloat(), 0f, Motion.Emphasized))

    override val durationMs: Float = Motion.HERO.toFloat()

    override fun DrawScope.draw(ms: Float, scene: IntroScene) {
        val target = scene.welcome
        val backdrop = backdropAlpha.at(ms)
        val box = if (target == null) scene.icon else lerpRect(scene.icon, target, travel.at(ms))
        val pose = MarkPose(
            markScale = 1f,
            markAlpha = if (target == null) backdrop else 1f,
            okWarmth = okWarmth.at(ms),
            okScale = okScale.at(ms),
            backdropAlpha = 0f,
        )
        drawBackdrop(scene.colours, backdrop)
        drawOpeningHalo(scene, openingExit.at(ms) * backdrop)
        drawLaunchMark(pose, scene.colours, box)
    }
}

/** Reduced motion, and anything with nowhere to land: the mark and its backdrop fade, 200 ms. */
object FadeIntro : IntroChoreography {
    val alpha = Track(Key(0f, 1f), Key(200f, 0f, Motion.Exit))

    override val durationMs: Float = 200f

    /** The wordmark and the halo fade with the mark, nothing moves. */
    override val openingExit: Track get() = alpha

    override fun DrawScope.draw(ms: Float, scene: IntroScene) {
        val a = alpha.at(ms)
        drawBackdrop(scene.colours, a)
        drawOpeningHalo(scene, a)
        drawLaunchMark(RestingPose.copy(markAlpha = a, backdropAlpha = 0f), scene.colours, scene.icon)
    }
}

/*
 * The opening: PR #11's splash composition, in the app (Erwann, 2026-10-24: "oui, fais-le avec le
 * mot et le halo"). The system splash takes an icon only, so the mark is all it shows; the intro's
 * first frame is that same picture, and from there the lowercase wordmark (its i dotted in amber)
 * arrives under the mark and, on a dark splash, a soft halo in the theme's accent rises behind it,
 * [SplashOpening.MS]. The mark never moves for it (the MarkGeometry contract). Once the choreography
 * starts, both leave over its first beat ([OpeningExit]) while the OK warms, and the rest plays as
 * before. The opening runs while the intro waits for the stores and the remote's layout, so it adds
 * at most its own 200 ms to a cold start, usually less; a warm start (after Back) has none.
 */

/** The opening's tracks, on the overlay's clock from its first frame. [reduced]: opacity only. */
class SplashOpening(reduced: Boolean) {
    val word = Track(Key(0f, 0f), Key(MS, 1f, Motion.Emphasized))
    val rise = if (reduced) Track(Key(0f, 0f)) else Track(Key(0f, MarkGeometry.WORDMARK_RISE), Key(MS, 0f, Motion.Emphasized))
    val halo = Track(Key(0f, 0f), Key(MS, 1f, Breath))

    fun pose(ms: Float, into: OpeningFrame) {
        into.word = word.at(ms)
        into.rise = rise.at(ms)
        into.halo = halo.at(ms)
    }

    companion object {
        const val MS = 200f

        /** Which launches open on the composition: cold ones only; a warm start stays short. */
        fun runsFor(kind: IntroKind): Boolean = kind == IntroKind.COLD
    }
}

/** The opening as the choreographies leave it: 160 ms, while the OK warms and before the body steps back. */
val OpeningExit = Track(Key(0f, 1f), Key(160f, 0f, Motion.Exit))

/** The opening at one frame: the wordmark's alpha and rise (mark units), the halo's alpha. */
class OpeningFrame {
    var word = 0f
    var rise = 0f
    var halo = 0f

    // The wordmark's outline and the halo's paint, made once (the halo's again only for a new accent).
    internal val ink: Path by lazy { PathParser().parsePathString(Wordmark.PATH).toPath() }
    private var cachedHalo: android.graphics.Paint? = null
    private var haloColour = Color.Unspecified

    /** A dithered radial paint, unit [HALO_UNIT] radius, so a low alpha on a dark ground never bands. */
    internal fun haloPaint(accent: Color): android.graphics.Paint {
        cachedHalo?.takeIf { haloColour == accent }?.let { return it }
        haloColour = accent
        val stops = OPENING_HALO_STOPS
        val shader = android.graphics.RadialGradient(
            0f, 0f, HALO_UNIT,
            IntArray(stops.size) { accent.copy(alpha = stops[it].second).toArgb() },
            FloatArray(stops.size) { stops[it].first },
            android.graphics.Shader.TileMode.CLAMP,
        )
        return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            isDither = true
        }.also { cachedHalo = it }
    }
}

/** The splash's backdrop over the whole scope. */
internal fun DrawScope.drawBackdrop(colours: MarkColours, alpha: Float) {
    if (alpha > 0f) drawRect(colours.background, alpha = colours.background.alpha * alpha)
}

/**
 * The opening's halo, over the backdrop and under the mark, at [level] of its arrival: dark splash
 * only (the light study has none), the theme's accent, low and soft.
 */
internal fun DrawScope.drawOpeningHalo(scene: IntroScene, level: Float) {
    val a = scene.opening.halo * level
    if (a <= 0f || !scene.colours.dark) return
    val paint = scene.opening.haloPaint(scene.theme.accent)
    val u = scene.icon.width / MarkGeometry.VIEWPORT
    paint.alpha = (a * 255f).toInt().coerceIn(0, 255)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.translate(scene.icon.left + MarkGeometry.CENTRE * u, scene.icon.top + MarkGeometry.HALO_Y * u)
        native.scale(MarkGeometry.HALO_RX * u / HALO_UNIT, MarkGeometry.HALO_RY * u / HALO_UNIT)
        native.drawCircle(0f, 0f, HALO_UNIT, paint)
        native.restore()
    }
}

/**
 * The wordmark under the mark, in the mark's ink (its body colour: ivory on a dark splash, ink on a
 * light one) with the i's dot in the pill's amber, at [level] of its arrival. Left out when it would
 * not clear the bottom of the window (a phone held sideways): the mark alone is the splash there.
 */
internal fun DrawScope.drawWordmark(scene: IntroScene, level: Float) {
    val a = scene.opening.word * level
    if (a <= 0f) return
    val u = scene.icon.width / MarkGeometry.VIEWPORT
    val s = Wordmark.SCALE * u
    val baseline = scene.icon.top + (MarkGeometry.WORDMARK_BASELINE + scene.opening.rise) * u
    if (baseline + Wordmark.BOTTOM * s > size.height - WORDMARK_CLEARANCE.toPx()) return
    val left = scene.icon.left + MarkGeometry.CENTRE * u - (Wordmark.LEFT + Wordmark.RIGHT) / 2f * s
    translate(left, baseline) {
        scale(s, s, pivot = Offset.Zero) {
            drawPath(scene.opening.ink, scene.colours.body, alpha = a)
            drawCircle(scene.colours.line, Wordmark.DOT_RADIUS, Offset(Wordmark.DOT_X, Wordmark.DOT_Y), alpha = a)
        }
    }
}

private const val HALO_UNIT = 100f

/** The halo's falloff, softened from the study's (0.25, 0.07 at 0.55, 0) like the rest of the light. */
internal val OPENING_HALO_STOPS = listOf(
    0f to 0.16f,
    0.25f to 0.12f,
    0.55f to 0.045f,
    0.8f to 0.012f,
    1f to 0f,
)

/** Room kept under the wordmark for the navigation bar. */
private val WORDMARK_CLEARANCE = 56.dp

private fun lerpRect(from: Rect, to: Rect, t: Float) = Rect(
    from.left + (to.left - from.left) * t,
    from.top + (to.top - from.top) * t,
    from.right + (to.right - from.right) * t,
    from.bottom + (to.bottom - from.bottom) * t,
)

/** The splash's colours, from values/ and values-night/, the same resources the system splash uses. */
data class MarkColours(val background: Color, val body: Color, val button: Color, val buttonWarm: Color, val line: Color) {
    /** A dark splash (night): the bloom adds light there, and only there. */
    val dark: Boolean get() = background.luminance() < 0.5f

    /** The mark's ivory, whichever of body and backdrop it is in this theme: the glint and the echo ring. */
    val ivory: Color get() = if (dark) body else background
}

@Composable
fun markColours() = MarkColours(
    background = colorResource(R.color.splash_background),
    body = colorResource(R.color.splash_body),
    button = colorResource(R.color.splash_button),
    buttonWarm = colorResource(R.color.splash_button_warm),
    line = colorResource(R.color.splash_line),
)

/** Draws the mark at [pose] into [icon] (the 108-unit viewport's box), backdrop over the whole scope. */
fun DrawScope.drawLaunchMark(pose: MarkPose, colours: MarkColours, icon: Rect, withOk: Boolean = true) {
    if (pose.backdropAlpha > 0f) drawRect(colours.background.copy(alpha = colours.background.alpha * pose.backdropAlpha))
    if (pose.markAlpha <= 0f) return
    val u = icon.width / 108f
    val a = pose.markAlpha
    translate(icon.left, icon.top) {
        scale(u, u, pivot = Offset.Zero) {
            scale(pose.markScale, pivot = Offset(54f, 54f)) {
                with(MarkGeometry) {
                    drawRoundRect(
                        colours.body,
                        Offset(BODY_LEFT, BODY_TOP),
                        Size(BODY_RIGHT - BODY_LEFT, BODY_BOTTOM - BODY_TOP),
                        CornerRadius(BODY_CORNER),
                        alpha = a,
                    )
                    // The top button is the OK's colour, still: it never warms or travels.
                    drawCircle(colours.button, TOP_RADIUS, Offset(CENTRE, TOP_Y), alpha = a)
                    if (withOk) drawCircle(lerp(colours.button, colours.buttonWarm, pose.okWarmth), OK_RADIUS * pose.okScale, Offset(CENTRE, OK_Y), alpha = a)
                    val pill = (PILL_BOTTOM - PILL_TOP) / 2f
                    drawRoundRect(colours.line, Offset(PILL_LEFT, PILL_TOP), Size(PILL_RIGHT - PILL_LEFT, PILL_BOTTOM - PILL_TOP), CornerRadius(pill), alpha = a)
                }
            }
        }
    }
}

/**
 * The launcher mark, still, for the welcome screen: the first launch's intro lands on it. Its box
 * is the mark's 108-unit viewport (the visible remote is its middle third, like the launcher
 * icon), so size it at three times the mark you want to see.
 */
@Composable
fun LaunchMark(modifier: Modifier = Modifier) {
    val anchors = LocalIntroAnchors.current
    val colours = markColours()
    DisposableEffect(anchors) { onDispose { anchors.welcome = null } }
    Canvas(modifier.onGloballyPositioned { anchors.welcome = it.boundsInWindow() }) {
        drawLaunchMark(RestingPose.copy(backdropAlpha = 0f), colours, Rect(Offset.Zero, size))
    }
}

/** Which intro a launch gets (Iris's splash notes). */
enum class IntroKind {
    /** A fresh process: the full intro. */
    COLD,

    /** A live process, a new activity (after Back): the short one. */
    WARM,

    /** A recreated activity: none. */
    NONE,
}

/**
 * Hand-over from the system splash. MainActivity passes [takeOver] to setOnExitAnimationListener;
 * [LaunchIntroOverlay] then plays on top of the app.
 */
class LaunchIntro(val kind: IntroKind) {
    internal var icon by mutableStateOf<Rect?>(null)
        private set
    private var provider: SplashScreenViewProvider? = null

    fun takeOver(splash: SplashScreenViewProvider) {
        if (kind == IntroKind.NONE) {
            splash.remove()
            return
        }
        val view = splash.iconView
        val at = IntArray(2).also(view::getLocationInWindow)
        provider = splash
        icon = Rect(at[0].toFloat(), at[1].toFloat(), (at[0] + view.width).toFloat(), (at[1] + view.height).toFloat())
    }

    /** Called once the overlay has drawn the mark over the system splash: the swap is invisible. */
    internal fun covered() {
        provider?.remove()
        provider = null
    }

    internal fun finish() {
        covered()
        icon = null
    }

    companion object {
        // Process-wide: savedInstanceState is null after Back too, so it cannot tell cold from warm.
        @Volatile private var processStarted = false

        /** The intro for an activity created with [restored] state; the first call per process is cold. */
        fun kindFor(restored: Boolean): IntroKind = synchronized(this) {
            val cold = !processStarted
            processStarted = true
            when {
                restored -> IntroKind.NONE
                cold -> IntroKind.COLD
                else -> IntroKind.WARM
            }
        }
    }
}

/** Which face of the pad is on screen for the intro to land on. */
enum class PadShown { NONE, DPAD, TOUCHPAD }

/**
 * Picks the choreography: [reduced] → fade; a cold first launch ([firstLaunch]) → the hand-off
 * to the welcome screen; no pad on screen → fade; otherwise the OK lands on the D-pad or the
 * touchpad, cold or warm.
 */
fun choreographyFor(kind: IntroKind, reduced: Boolean, firstLaunch: Boolean, pad: PadShown): IntroChoreography = when {
    reduced -> FadeIntro
    kind == IntroKind.COLD && firstLaunch -> HandOffToWelcome
    pad == PadShown.DPAD -> OkBecomesPad(warm = kind == IntroKind.WARM)
    pad == PadShown.TOUCHPAD -> OkBecomesTouchpad(warm = kind == IntroKind.WARM)
    else -> FadeIntro
}

/**
 * The overlay. [firstLaunch] is null until the stores have loaded; the intro waits for it at most
 * [DECIDE_WAIT_MS], holding the system splash's own picture, and then never waits for anything.
 */
@Composable
fun LaunchIntroOverlay(intro: LaunchIntro, firstLaunch: Boolean?) {
    val icon = intro.icon ?: return
    val colours = markColours()
    val anchors = LocalIntroAnchors.current
    val reduced = LocalReducedMotion.current
    // Latest prefs at landing: the stores may load after the intro starts, and the key sound follows them.
    val haptics by rememberUpdatedState(rememberHaptics())
    val padOk = MaterialTheme.colorScheme.secondaryContainer
    val padDisc = MaterialTheme.colorScheme.surfaceContainerHigh
    val padChevron = MaterialTheme.colorScheme.onSurfaceVariant
    val theme = introTheme()
    val first by rememberUpdatedState(firstLaunch)
    var elapsed by remember { mutableFloatStateOf(0f) }
    var script by remember { mutableStateOf<IntroChoreography?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val scenes = remember { SceneCache() }
    val opening = remember(intro, reduced) { if (SplashOpening.runsFor(intro.kind)) SplashOpening(reduced) else null }
    val frame = remember { OpeningFrame() }
    var openMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(intro) {
        // The opening's clock, from the first frame: it runs while the intro decides.
        val clock = opening?.let {
            launch {
                val zero = withFrameNanos { it }
                while (openMs < SplashOpening.MS) withFrameNanos { now -> openMs = (now - zero) / 1_000_000f }
            }
        }
        // The first frame draws the resting pose (the system splash's own picture, no wordmark yet);
        // once it is on screen, the system splash goes.
        withFrameNanos { }
        withFrameNanos { }
        intro.covered()
        val known = withTimeoutOrNull(DECIDE_WAIT_MS) { snapshotFlow { first }.filterNotNull().first() } ?: false
        // One more frame, so the remote (or the welcome screen) has laid out its anchor.
        withFrameNanos { }
        // The opening composition is whole before anything leaves it.
        clock?.join()
        val shown = when {
            anchors.pad != null -> PadShown.DPAD
            anchors.touchpad != null -> PadShown.TOUCHPAD
            else -> PadShown.NONE
        }
        val chosen = choreographyFor(intro.kind, reduced, known, shown)
        script = chosen
        val start = withFrameNanos { it }
        var landed = false
        while (elapsed < chosen.durationMs) {
            withFrameNanos { now -> elapsed = (now - start) / 1_000_000f }
            val landing = chosen.landingMs
            if (!landed && landing != null && elapsed >= landing) {
                landed = true
                haptics.click()
            }
        }
        intro.finish()
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(intro) {
                // A tap skips; nothing reaches the remote underneath while the intro is up.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    intro.finish()
                }
            },
    ) {
        val scene = scenes.get(
            this, colours, icon, origin, anchors.pad, anchors.touchpad, anchors.welcome,
            padOk, padDisc, padChevron, theme, frame,
        )
        opening?.pose(openMs, frame)
        val now = script
        if (now == null) {
            drawBackdrop(colours, 1f)
            drawOpeningHalo(scene, 1f)
            drawLaunchMark(RestingPose.copy(backdropAlpha = 0f), colours, scene.icon)
            drawWordmark(scene, 1f)
        } else {
            with(now) { draw(elapsed, scene) }
            drawWordmark(scene, now.openingExit.at(elapsed))
        }
    }
}

private const val DECIDE_WAIT_MS = 300L

/**
 * The frame's [IntroScene], rebuilt only when something it is made of changes (a layout pass,
 * a theme change), so a steady frame allocates nothing.
 */
private class SceneCache {
    private var scene: IntroScene? = null
    private var colours: MarkColours? = null
    private var icon: Rect? = null
    private var origin = Offset.Unspecified
    private var pad: Rect? = null
    private var touchpad: TouchpadAnchor? = null
    private var welcome: Rect? = null
    private var padOk = Color.Unspecified
    private var padDisc = Color.Unspecified
    private var padChevron = Color.Unspecified
    private var theme: IntroTheme? = null
    private var density = 0f

    fun get(
        scope: DrawScope,
        colours: MarkColours,
        icon: Rect,
        origin: Offset,
        pad: Rect?,
        touchpad: TouchpadAnchor?,
        welcome: Rect?,
        padOk: Color,
        padDisc: Color,
        padChevron: Color,
        theme: IntroTheme,
        opening: OpeningFrame,
    ): IntroScene {
        val same = colours == this.colours && icon == this.icon && origin == this.origin && pad == this.pad &&
            touchpad == this.touchpad && welcome == this.welcome && padOk == this.padOk && padDisc == this.padDisc &&
            padChevron == this.padChevron && theme == this.theme && scope.density == density
        scene?.let { if (same) return it }
        this.colours = colours
        this.icon = icon
        this.origin = origin
        this.pad = pad
        this.touchpad = touchpad
        this.welcome = welcome
        this.padOk = padOk
        this.padDisc = padDisc
        this.padChevron = padChevron
        this.theme = theme
        density = scope.density
        val moat = with(scope) { 4.dp.toPx() }
        val target = pad?.let { box ->
            PadTarget(
                centre = box.center - origin,
                okRadius = dpadOkRadius(box.width, box.height, moat),
                edgeRadius = dpadEdgeRadius(box.width, box.height),
                innerRadius = dpadRingInner(box.width, box.height),
                chevronArm = dpadChevronArm(box.width, box.height),
            )
        }
        return IntroScene(
            colours = colours,
            icon = icon.translate(-origin),
            pad = target,
            welcome = welcome?.translate(-origin),
            padOk = padOk,
            padDisc = padDisc,
            padChevron = padChevron,
            theme = theme,
            touchpad = touchpad?.let { TouchpadAnchor(it.bounds.translate(-origin), it.cornerRadius) },
            opening = opening,
        ).also { scene = it }
    }
}

/** The intro's light from the live theme: what the pad itself is drawn in, nothing fixed. */
@Composable
private fun introTheme(): IntroTheme {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return remember(scheme.primary, scheme.surfaceContainerHigh, scheme.surfaceBright, dark) {
        IntroTheme(
            accent = scheme.primary,
            dim = lerp(scheme.primary, scheme.surfaceContainerHigh, 0.5f),
            // A lift: the accent on a dark theme, the brightest surface on a light one, so it never darkens the OK.
            sheen = if (dark) scheme.primary else scheme.surfaceBright,
            dark = dark,
        )
    }
}

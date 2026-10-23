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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreenViewProvider
import io.kud.tutti.R
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.remote.dpadEdgeRadius
import io.kud.tutti.ui.remote.dpadOkRadius
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The launch intro, in three beats (Iris's splash notes). The system splash shows the launcher
 * mark still (res/drawable/splash_icon.xml): it is only the bridge, since the system plays
 * animated icons in its own process. Once the app has drawn its first frame, this overlay draws
 * the very same mark at the very same place, removes the system splash under it, and plays on the
 * app's own frame clock. Then the remote, already composed underneath and never held back.
 *
 * Concept B, "the OK becomes the pad" (Iris, 2026-10-23): the mark's OK button warms and swells,
 * the body steps back, the OK slides and grows onto the live D-pad's OK while a wave runs out to
 * the disc's edge; a click on landing, then the backdrop opens from the pad outwards. 650 ms on a
 * cold start, 250 ms (the OK alone) on a warm one, nothing on a hot resume. The first launch ever
 * hands the mark to the welcome screen instead. Reduced motion, or no D-pad on screen (touchpad
 * mode, not drawn yet), is a 200 ms fade. A tap skips any of them.
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

    /** The welcome screen's mark: the box of its 108-unit viewport, drawn by [LaunchMark]. */
    var welcome by mutableStateOf<Rect?>(null)
}

val LocalIntroAnchors = staticCompositionLocalOf { IntroAnchors() }

/** The live D-pad's OK and disc edge, in the overlay's coordinates. */
data class PadTarget(val centre: Offset, val okRadius: Float, val edgeRadius: Float)

/** Everything a choreography draws from at one frame. */
class IntroScene(
    val colours: MarkColours,
    /** The launcher mark's 108-unit viewport, where the system splash drew it. */
    val icon: Rect,
    val pad: PadTarget?,
    val welcome: Rect?,
    /** The D-pad's OK and disc colours, so the landing is seamless. */
    val padOk: Color,
    val padDisc: Color,
)

/**
 * A choreography: everything the intro draws, as a function of time. At 0 ms it must draw exactly
 * what the system splash shows. [landingMs] is when the click haptic lands, if any.
 */
interface IntroChoreography {
    val durationMs: Float
    val landingMs: Float? get() = null

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
 * B, the OK becomes the pad. Cold start: 0-150 ms the OK warms and swells; 150-300 the body and
 * the line step back; 150-460 the OK slides onto the pad's OK and grows to its size along
 * [Motion.snappy], from warm to the pad's colour, while a wave (200-460) runs out to the disc's
 * edge; click at 460; 460-650 the backdrop opens from the pad outwards and fades.
 * Warm start ([warm]): no breath, no wave, the OK alone travels over 250 ms.
 */
class OkBecomesPad(warm: Boolean) : IntroChoreography {
    val warmth = if (warm) Track(Key(0f, 0f)) else Track(Key(0f, 0f), Key(150f, 1f, Motion.Emphasized))
    val swell = if (warm) Track(Key(0f, 1f)) else Track(Key(0f, 1f), Key(150f, 1.15f, Motion.Emphasized))
    val body = if (warm) Track(Key(0f, 1f), Key(100f, 0f, Motion.Exit)) else Track(Key(0f, 1f), Key(150f, 1f), Key(300f, 0f, Motion.Exit))
    val travel = if (warm) Track(Key(0f, 0f), Key(250f, 1f, SpringEasing(250f))) else Track(Key(0f, 0f), Key(150f, 0f), Key(460f, 1f, SpringEasing(310f)))
    val wave = if (warm) null else Track(Key(0f, 0f), Key(200f, 0f), Key(460f, 1f, Motion.Emphasized))
    val reveal = if (warm) Track(Key(0f, 0f), Key(100f, 0f), Key(250f, 1f, Motion.Emphasized)) else Track(Key(0f, 0f), Key(460f, 0f), Key(650f, 1f, Motion.Emphasized))

    override val durationMs: Float = if (warm) 250f else 650f
    override val landingMs: Float? = if (warm) null else 460f

    override fun DrawScope.draw(ms: Float, scene: IntroScene) {
        val pad = scene.pad ?: return drawLaunchMark(RestingPose, scene.colours, scene.icon)
        val colours = scene.colours
        val u = scene.icon.width / 108f
        val open = reveal.at(ms)

        // The backdrop, with a hole that opens from the disc's edge to the farthest corner.
        val far = maxOf(
            hypot(pad.centre.x, pad.centre.y),
            hypot(size.width - pad.centre.x, pad.centre.y),
            hypot(pad.centre.x, size.height - pad.centre.y),
            hypot(size.width - pad.centre.x, size.height - pad.centre.y),
        )
        val backdropAlpha = if (open < 0.5f) 1f else 1f - (open - 0.5f) * 2f
        if (backdropAlpha > 0f) {
            val backdrop = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                if (open > 0f) addOval(Rect(pad.centre, pad.edgeRadius + (far - pad.edgeRadius) * open))
            }
            drawPath(backdrop, colours.background, alpha = colours.background.alpha * backdropAlpha)
        }

        // The body and the line step back; the OK is drawn on its own below.
        val bodyAlpha = body.at(ms)
        if (bodyAlpha > 0f) {
            drawLaunchMark(RestingPose.copy(markAlpha = bodyAlpha, backdropAlpha = 0f), colours, scene.icon, withOk = false)
        }

        val t = travel.at(ms)
        val startCentre = scene.icon.topLeft + Offset(54f * u, 48f * u)
        val startRadius = 9f * u * swell.at(ms)
        val centre = lerp(startCentre, pad.centre, t)
        val radius = startRadius + (pad.okRadius - startRadius) * t
        val ink = lerp(colours.button, colours.buttonWarm, warmth.at(ms))
        val okColour = lerp(ink, scene.padOk, t.coerceIn(0f, 1f))

        val w = wave?.at(ms) ?: 0f
        if (w > 0f && open <= 0f) {
            val ring = radius + (pad.edgeRadius - radius) * w
            drawCircle(scene.padDisc, ring, centre, style = Stroke(width = 3.dp.toPx()), alpha = 0.5f + 0.5f * w)
        }
        drawCircle(okColour, radius, centre)
    }
}

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
            backdropAlpha = backdrop,
        )
        drawLaunchMark(pose, scene.colours, box)
    }
}

/** Reduced motion, and anything with nowhere to land: the mark and its backdrop fade, 200 ms. */
object FadeIntro : IntroChoreography {
    val alpha = Track(Key(0f, 1f), Key(200f, 0f, Motion.Exit))

    override val durationMs: Float = 200f

    override fun DrawScope.draw(ms: Float, scene: IntroScene) {
        val a = alpha.at(ms)
        drawLaunchMark(RestingPose.copy(markAlpha = a, backdropAlpha = a), scene.colours, scene.icon)
    }
}

private fun lerpRect(from: Rect, to: Rect, t: Float) = Rect(
    from.left + (to.left - from.left) * t,
    from.top + (to.top - from.top) * t,
    from.right + (to.right - from.right) * t,
    from.bottom + (to.bottom - from.bottom) * t,
)

/** The splash's colours, from values/ and values-night/, the same resources the system splash uses. */
data class MarkColours(val background: Color, val body: Color, val button: Color, val buttonWarm: Color, val line: Color)

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
                drawRoundRect(colours.body, Offset(36f, 28f), Size(36f, 52f), CornerRadius(14f), alpha = a)
                if (withOk) drawCircle(lerp(colours.button, colours.buttonWarm, pose.okWarmth), 9f * pose.okScale, Offset(54f, 48f), alpha = a)
                drawLine(colours.line, Offset(48f, 68f), Offset(60f, 68f), strokeWidth = 4f, cap = StrokeCap.Round, alpha = a)
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

/**
 * Picks the choreography: [reduced] → fade; a cold first launch ([firstLaunch]) → the hand-off
 * to the welcome screen; no D-pad on screen ([padShown]) → fade; otherwise B, cold or warm.
 */
fun choreographyFor(kind: IntroKind, reduced: Boolean, firstLaunch: Boolean, padShown: Boolean): IntroChoreography = when {
    reduced -> FadeIntro
    kind == IntroKind.COLD && firstLaunch -> HandOffToWelcome
    !padShown -> FadeIntro
    else -> OkBecomesPad(warm = kind == IntroKind.WARM)
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
    val haptics = rememberHaptics()
    val padOk = MaterialTheme.colorScheme.secondaryContainer
    val padDisc = MaterialTheme.colorScheme.surfaceContainerHigh
    val first by rememberUpdatedState(firstLaunch)
    var elapsed by remember { mutableFloatStateOf(0f) }
    var script by remember { mutableStateOf<IntroChoreography?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(intro) {
        // The first frame draws the resting pose (the system splash's own picture); once it is on
        // screen, the system splash goes.
        withFrameNanos { }
        withFrameNanos { }
        intro.covered()
        val known = withTimeoutOrNull(DECIDE_WAIT_MS) { snapshotFlow { first }.filterNotNull().first() } ?: false
        // One more frame, so the remote (or the welcome screen) has laid out its anchor.
        withFrameNanos { }
        val chosen = choreographyFor(intro.kind, reduced, known, anchors.pad != null)
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
        val moat = 4.dp.toPx()
        val pad = anchors.pad?.let { box ->
            PadTarget(box.center - origin, dpadOkRadius(box.width, box.height, moat), dpadEdgeRadius(box.width, box.height))
        }
        val scene = IntroScene(colours, icon.translate(-origin), pad, anchors.welcome?.translate(-origin), padOk, padDisc)
        val now = script
        if (now == null) {
            drawLaunchMark(RestingPose, colours, scene.icon)
        } else {
            with(now) { draw(elapsed, scene) }
        }
    }
}

private const val DECIDE_WAIT_MS = 300L

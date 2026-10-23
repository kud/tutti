package io.kud.tutti.ui.intro

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.colorResource
import androidx.core.splashscreen.SplashScreenViewProvider
import io.kud.tutti.R

/*
 * The launch intro. The system splash shows the launcher mark still (res/drawable/splash_icon.xml):
 * the system plays animated icons in its own process, often janky and partly before the app is up,
 * so it is only the bridge. Once the app has drawn its first frame, this
 * overlay draws the very same mark at the very same place, removes the system splash under it,
 * and plays the real animation on the app's own frame clock: the OK button breathes, then the splash dissolves
 * into the remote screen, which is already composed underneath and is never held back.
 *
 * The choreography is data (IntroScript): one keyframe track per property of the mark. A new
 * storyboard replaces the keys; nothing else needs to change.
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

private val Breath = CubicBezierEasing(0.37f, 0f, 0.63f, 1f) // sine in-out: a breath, no snap
private val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f) // Material emphasized decelerate
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * A choreography: everything the intro draws, as a function of time. [draw] paints the backdrop
 * over the whole scope and the mark into [icon], the box of the launcher mark's 108-unit viewport
 * (where the system splash drew it). At 0 ms it must draw exactly what the system splash shows.
 */
interface IntroChoreography {
    val durationMs: Float

    fun DrawScope.draw(ms: Float, colours: MarkColours, icon: Rect)
}

/** Where the mark is at one instant, in the launcher mark's 108-unit viewport. */
data class MarkPose(
    val markScale: Float,
    val markAlpha: Float,
    val okWarmth: Float,
    val okScale: Float,
    val backdropAlpha: Float,
)

/**
 * Sacha's B (Erwann, 2026-10-23): the shipped mark, its OK button breathing. One breath, not his
 * loop, so the remote arrives quickly: 0-300 ms the button warms from ink to its glow and swells a
 * hair, 300-700 it cools back; from 560 the backdrop dissolves while the mark lifts and fades, all
 * gone by 900. Interpolated on every frame, never stepped through his GIF's frames.
 * Everything stays inside the splash's visible circle (radius 36 around 54,54).
 */
object IntroScript : IntroChoreography {
    val okWarmth = Track(Key(0f, 0f), Key(300f, 1f, Breath), Key(700f, 0f, Breath))
    val okScale = Track(Key(0f, 1f), Key(300f, 1.04f, Breath), Key(700f, 1f, Breath))
    val markScale = Track(Key(0f, 1f), Key(560f, 1f), Key(880f, 1.06f, EaseOut))
    val markAlpha = Track(Key(0f, 1f), Key(600f, 1f), Key(860f, 0f, Standard))
    val backdropAlpha = Track(Key(0f, 1f), Key(560f, 1f), Key(900f, 0f, Standard))

    override val durationMs: Float = listOf(okWarmth, okScale, markScale, markAlpha, backdropAlpha).maxOf { it.endMs }

    fun at(ms: Float) = MarkPose(
        markScale = markScale.at(ms),
        markAlpha = markAlpha.at(ms),
        okWarmth = okWarmth.at(ms),
        okScale = okScale.at(ms),
        backdropAlpha = backdropAlpha.at(ms),
    )

    override fun DrawScope.draw(ms: Float, colours: MarkColours, icon: Rect) = drawLaunchMark(at(ms), colours, icon)
}

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
fun DrawScope.drawLaunchMark(pose: MarkPose, colours: MarkColours, icon: Rect) {
    if (pose.backdropAlpha > 0f) drawRect(colours.background.copy(alpha = colours.background.alpha * pose.backdropAlpha))
    if (pose.markAlpha <= 0f) return
    val u = icon.width / 108f
    val a = pose.markAlpha
    translate(icon.left, icon.top) {
        scale(u, u, pivot = Offset.Zero) {
            scale(pose.markScale, pivot = Offset(54f, 54f)) {
                drawRoundRect(colours.body, Offset(36f, 28f), Size(36f, 52f), CornerRadius(14f), alpha = a)
                drawCircle(lerp(colours.button, colours.buttonWarm, pose.okWarmth), 9f * pose.okScale, Offset(54f, 48f), alpha = a)
                drawLine(colours.line, Offset(48f, 68f), Offset(60f, 68f), strokeWidth = 4f, cap = StrokeCap.Round, alpha = a)
            }
        }
    }
}

/**
 * Hand-over from the system splash. MainActivity passes [takeOver] to setOnExitAnimationListener;
 * [LaunchIntroOverlay] then plays on top of the app. [enabled] is false on anything but a cold
 * start (a recreated activity has no intro).
 */
class LaunchIntro(private val enabled: Boolean) {
    internal var icon by mutableStateOf<Rect?>(null)
        private set
    private var provider: SplashScreenViewProvider? = null

    fun takeOver(splash: SplashScreenViewProvider) {
        if (!enabled || !ValueAnimator.areAnimatorsEnabled()) {
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
}

@Composable
fun LaunchIntroOverlay(intro: LaunchIntro, script: IntroChoreography = IntroScript) {
    val icon = intro.icon ?: return
    val colours = markColours()
    var elapsed by remember { mutableFloatStateOf(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(intro) {
        // The first frame draws the pose at 0 (the system splash's own picture); once it is on
        // screen, the system splash goes and the clock starts.
        withFrameNanos { }
        withFrameNanos { }
        intro.covered()
        val start = withFrameNanos { it }
        while (elapsed < script.durationMs) {
            withFrameNanos { now -> elapsed = (now - start) / 1_000_000f }
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
        with(script) { draw(elapsed, colours, icon.translate(-origin)) }
    }
}

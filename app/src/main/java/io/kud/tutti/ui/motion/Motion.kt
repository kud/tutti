package io.kud.tutti.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import io.kud.tutti.ui.common.LocalReducedMotion

/**
 * One motion vocabulary for the whole app (the motion spec): acknowledge, wait, succeed, fail.
 * Durations are the tokens; springs and easings are the only curves. With reduced motion every
 * movement becomes a [Motion.SHORT] fade; haptics stay, since they carry the state.
 */
object Motion {
    /** The press itself: 0 ms, carried by a haptic before any network. */
    const val INSTANT = 0

    /** State fades, the system chip, and the wait before a spinner may show. */
    const val SHORT = 150

    /** A button changing shape, a card arriving, a tick being drawn. */
    const val MEDIUM = 250

    /** A step change, and the least time a spinner stays once shown. */
    const val LONG = 400

    /** Welcome and the final success. */
    const val HERO = 900

    /** Most shapes: button to circle, the TV card. Damping 0.8, stiffness 600. */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 600f)

    /** Once per screen at most: the TV found, the final tick. Damping 0.6, stiffness 400. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.6f, stiffness = 400f)

    /** A press landing: critically damped, no overshoot, about 60 ms (the interaction spec). */
    fun <T> pressIn(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessHigh)

    /** The pressed layer landing, in step with [pressIn]. */
    const val PRESS_IN = 60

    /** Layers and rings leaving, and arriving when motion is reduced. */
    const val FADE_OUT = 120

    /** M3 emphasized: for things entering. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 emphasized accelerate: for things leaving, over [SHORT]. */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    fun <T> enter(durationMillis: Int = MEDIUM): FiniteAnimationSpec<T> = tween(durationMillis, easing = Emphasized)

    fun <T> exit(): FiniteAnimationSpec<T> = tween(SHORT, easing = Exit)

    fun <T> fade(): FiniteAnimationSpec<T> = tween(SHORT)
}

/** [Motion.snappy], or a [Motion.SHORT] fade when motion is reduced. */
@Composable
fun <T> snappyOrFade(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) Motion.fade() else Motion.snappy()

/**
 * The TV's link is being won back on the app's return ([io.kud.tutti.controller.TvShown.resuming]):
 * the remote comes back already in the down dress, so [linkFade] snaps down instead of fading.
 */
val LocalLinkResuming = compositionLocalOf { false }

/**
 * The down dress coming and going with its device's link (Iris, 2026-11-07): [Motion.MEDIUM]
 * going down, so a blip of a second or two is softened without being hidden, and [Motion.SHORT]
 * coming back, so keys that work again look ready at once. On a resume ([LocalLinkResuming]) the
 * first frame back is already down: one transition, up, never normal, down, normal. Opacity
 * only; reduced motion snaps.
 */
@Composable
fun <T> linkFade(down: Boolean): FiniteAnimationSpec<T> = when {
    LocalReducedMotion.current -> snap()
    down && LocalLinkResuming.current -> snap()
    else -> tween(if (down) Motion.MEDIUM else Motion.SHORT, easing = LinearOutSlowInEasing)
}

/** [Motion.bouncy], or a [Motion.SHORT] fade when motion is reduced. */
@Composable
fun <T> bouncyOrFade(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) Motion.fade() else Motion.bouncy()

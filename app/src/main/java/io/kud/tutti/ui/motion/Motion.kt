package io.kud.tutti.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import io.kud.tutti.ui.common.LocalReducedMotion

/**
 * One motion vocabulary for the whole app (Iris's motion spec): acknowledge, wait, succeed, fail.
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

/** [Motion.bouncy], or a [Motion.SHORT] fade when motion is reduced. */
@Composable
fun <T> bouncyOrFade(): FiniteAnimationSpec<T> = if (LocalReducedMotion.current) Motion.fade() else Motion.bouncy()

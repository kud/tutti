package io.kud.tutti.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.kud.tutti.ui.common.Feedback
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one refusal shake (Iris, 2026-10-25): it goes with the reject haptic, never with the bump.
 * Play it only when a command that was sent came back refused (the bar's ERR, a wrong code, a
 * retry that failed), on the control that was pressed. A control already in its down dress was
 * refused at the tap, and its dashed edge said so first: that is a bump, and no shake.
 *
 * - [refuse] plays reject and, on the same frame, a damped sideways shake over [Motion.MEDIUM]:
 *   +A, −A, +0.6A, −0.4A, 0 at 40, 100, 160, 210 and 250 ms, linear between keyframes (the damping
 *   is the deceleration; a spring would overshoot). A is 4 dp for controls up to about 96 dp
 *   ([ShakeSize.CONTROL]) and 6 dp for fields and wide buttons ([ShakeSize.WIDE]).
 * - At most one shake per control every [GAP_MS]: a refusal inside that window still plays its
 *   haptic, but the shake already running is not restarted.
 * - Reduced motion: no travel. The control dips to 50 % opacity and back over [Motion.SHORT], once.
 * - Never on the pad, the rail, a whole sheet or the screen: a large surface shaking reads as an
 *   earthquake. There the chip and the message carry it.
 */
@Stable
class RefusalShake internal constructor(
    private val scope: CoroutineScope,
    private val feedback: Feedback,
    private val amplitude: Float,
    private val reduced: () -> Boolean,
) {
    internal val offset = Animatable(0f)
    internal val opacity = Animatable(1f)
    private var lastAt = Long.MIN_VALUE / 2

    /** The reject haptic and, unless one ran in the last [GAP_MS], the shake (or the dip). */
    fun refuse() {
        feedback.reject()
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < GAP_MS) return
        lastAt = now
        scope.launch {
            if (reduced()) {
                opacity.snapTo(1f)
                opacity.animateTo(
                    1f,
                    keyframes {
                        durationMillis = Motion.SHORT
                        0.5f at Motion.SHORT / 2 using LinearEasing
                    },
                )
            } else {
                offset.snapTo(0f)
                offset.animateTo(
                    0f,
                    keyframes {
                        durationMillis = Motion.MEDIUM
                        amplitude at 40 using LinearEasing
                        -amplitude at 100 using LinearEasing
                        amplitude * 0.6f at 160 using LinearEasing
                        -amplitude * 0.4f at 210 using LinearEasing
                    },
                )
            }
        }
    }

    companion object {
        /** One shake per control per this long, as the same words never repeat while shown. */
        const val GAP_MS = 600L
    }
}

/** How far a refusal travels: 4 dp for keys, pins, tiles and segments; 6 dp for fields and wide buttons. */
enum class ShakeSize(val dp: Float) { CONTROL(4f), WIDE(6f) }

@Composable
fun rememberRefusalShake(size: ShakeSize = ShakeSize.CONTROL): RefusalShake {
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    // Read at play time, so a change to "Remove animations" while the app runs is followed.
    val reduced = rememberUpdatedState(LocalReducedMotion.current)
    return remember(scope, feedback, size) { RefusalShake(scope, feedback, size.dp) { reduced.value } }
}

/** Applies a [RefusalShake]: sideways travel only, never scale or rotation; or the opacity dip. */
fun Modifier.refusalShake(shake: RefusalShake): Modifier = graphicsLayer {
    translationX = shake.offset.value * density
    alpha = shake.opacity.value
}

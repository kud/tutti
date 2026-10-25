package io.kud.tutti.platform.display

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import io.kud.tutti.controller.EveningDim
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.dimTarget
import io.kud.tutti.ui.remote.tvPowered
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * "Salle obscure" on the activity's window, while it is started ([follow]): the window's own
 * `screenBrightness` fades to [dimTarget]'s level over [EveningDim.FADE_MS], and back to the
 * phone's level when the rule lets go (the TV off, the switch off, morning). The phone's
 * brightness setting is only read, never written, so a change seen on it is his: tutti then drops
 * the override at once and leaves it alone until the next evening ([touched]). Whether
 * auto-brightness also writes that setting on some phones, which would read as his change, is
 * unverified. The animator follows Android's animation scale, so with animations off it jumps.
 */
class EveningDimmer(private val window: Window, private val resolver: ContentResolver) {
    private var animator: ValueAnimator? = null

    /** True once his change asked for the override to go now rather than fade out. */
    private var releaseNow = false

    suspend fun follow(state: Flow<TuttiUiState>) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val now = LocalDateTime.now()
                if (!EveningDim.isEvening(now.hour)) return
                releaseNow = true
                touched.value = EveningDim.eveningOf(now)
            }
        }
        resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer)
        try {
            combine(
                state.map { it.prefs.eveningDim to (tvPowered(it) == true) }.distinctUntilChanged(),
                minutes(),
                touched,
            ) { setup, now, touchedOn ->
                dimTarget(enabled = setup.first, tvOn = setup.second, hour = now.hour, userChangedBrightness = touchedOn == EveningDim.eveningOf(now))
            }
                .distinctUntilChanged()
                .collect { fadeTo(it) }
        } finally {
            resolver.unregisterContentObserver(observer)
            animator?.cancel()
            animator = null
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }

    private fun fadeTo(target: Float?) {
        val overriding = window.attributes.screenBrightness >= 0f
        val from = if (overriding) window.attributes.screenBrightness else systemLevel()
        animator?.cancel()
        animator = null
        val snap = releaseNow
        releaseNow = false
        if (target == null && (!overriding || snap)) {
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            return
        }
        animator = ValueAnimator.ofFloat(from, target ?: systemLevel()).apply {
            duration = EveningDim.FADE_MS
            addUpdateListener { setBrightness(it.animatedValue as Float) }
            if (target == null) {
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                    }
                })
            }
            start()
        }
    }

    /** The phone's own level on the window's 0–1 scale; the setting is 0–255. */
    private fun systemLevel(): Float =
        (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0.01f, 1f)

    private fun setBrightness(value: Float) {
        val params = window.attributes
        if (params.screenBrightness == value) return
        params.screenBrightness = value
        window.attributes = params
    }

    /** Now, then again at the start of every minute, so the evening begins and ends on time. */
    private fun minutes(): Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    companion object {
        /** The evening in which he last changed the brightness; for the process, across activities. */
        private val touched = MutableStateFlow<LocalDate?>(null)
    }
}

package io.kud.tutti.quick

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiEvent
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.SceneStatus
import io.kud.tutti.controller.VolumeSource
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.quick.notification.tvStepsTo
import io.kud.tutti.ui.remote.CardShowing
import io.kud.tutti.ui.remote.NowShowing
import io.kud.tutti.ui.remote.cardShowing
import kotlin.math.abs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [QuickControls] over [TuttiController]. The quick surfaces live outside the main
 * activity, so each action holds the sessions open for its own duration (the
 * controller lingers after the last release) and waits briefly for the bar link,
 * so a widget tap on a cold process is not dropped.
 */
@Singleton
class ControllerQuickControls @Inject constructor(
    private val controller: TuttiController,
) : QuickControls {

    override val state: StateFlow<QuickState> = combine(controller.state, controller.castNowPlaying) { state, cast ->
        state.toQuick().copy(cast = cast)
    }
        .stateIn(controller.scope, SharingStarted.Eagerly, controller.state.value.toQuick())

    override fun retain() = controller.retain()

    override fun release() = controller.release()

    /**
     * Power acts on the TV's power as heard in this session, never on what an earlier one heard:
     * a TV switched off with its own remote meanwhile must be switched on, not "off" again.
     * It waits until power is known, or every configured link has had its first say.
     */
    override suspend fun togglePower() = held(until = ::powerSettled) { controller.power() }

    override suspend fun volumeUp() = step(+1)

    override suspend fun volumeDown() = step(-1)

    private suspend fun step(delta: Int) = held {
        if (onBar()) {
            controller.volumeStep(delta, VolumeSource.QUICK)
        } else {
            controller.key(if (delta > 0) V2Key.VOLUME_UP else V2Key.VOLUME_DOWN)
        }
    }

    override suspend fun setVolume(level: Int) = held {
        if (onBar()) {
            controller.setVolume(level, VolumeSource.QUICK)
        } else {
            // The remote link has no absolute volume: walk there in key presses, bounded.
            val tv = state.value.level ?: return@held
            val steps = tvStepsTo(tv.level, level, tv.max)
            repeat(abs(steps)) { controller.key(if (steps > 0) V2Key.VOLUME_UP else V2Key.VOLUME_DOWN) }
        }
    }

    override suspend fun toggleMute() = held {
        if (onBar()) controller.toggleMute() else controller.key(V2Key.VOLUME_MUTE)
    }

    override suspend fun playPause() = held(onTv = true) { controller.key(V2Key.MEDIA_PLAY_PAUSE) }

    /** The bar takes volume whenever one is configured, else the TV (CLAUDE.md: one rule for every surface). */
    private fun onBar(): Boolean = controller.state.value.devices.bar != null

    override suspend fun applyScene(id: String) = held {
        // Subscribed before the scene starts, so a scene that finishes at once is not missed.
        coroutineScope {
            val finished = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(SCENE_TIMEOUT_MS) {
                    controller.events.filter { it is TuttiEvent.SceneFinished && it.sceneId == id }.first()
                }
            }
            controller.applyScene(id)
            finished.await()
        }
    }

    /**
     * Holds the sessions for [action], after waiting (bounded) for a link of this session's
     * epoch: a link the stopped sessions reported is never taken for a live one, so a cold
     * widget tap waits for the connect instead of going nowhere.
     */
    private suspend fun held(
        onTv: Boolean = false,
        until: ((TuttiUiState) -> Boolean)? = null,
        action: suspend () -> Unit,
    ) {
        val epoch = controller.retainSession()
        try {
            val bar = !onTv && onBar()
            val ready = until ?: { s: TuttiUiState -> (if (bar) s.links.bar else s.links.tv) == Link.ONLINE }
            withTimeoutOrNull(LINK_TIMEOUT_MS) {
                controller.state.first { it.links.epoch == epoch && ready(it) }
            }
            action()
        } finally {
            controller.release()
        }
    }

    private fun TuttiUiState.toQuick(): QuickState {
        val active = activeScene
        val hasBar = devices.bar != null
        val tvVolume = remote.volume
        val app = remote.currentApp
        return QuickState(
            // The bar's link when one is configured (volume needs it), else the TV's remote link.
            connected = (if (hasBar) links.bar else links.tv) == Link.ONLINE,
            configured = hasBar || devices.tv != null,
            power = powered == true,
            volume = volume ?: 0,
            volumeLimit = volumeCap ?: 100,
            guardLine = volumeGuardLine.takeIf { hasBar },
            guardHeld = volumeGuardNotice && hasBar && volumeGuardLine != null,
            muted = muted,
            activeSceneId = active?.scene?.id,
            activeSceneModified = active?.status == SceneStatus.MODIFIED,
            scenes = scenes.map { QuickScene(it.scene.id, it.scene.name, it.scene.color) },
            vibration = prefs.vibrationLevel,
            keySounds = prefs.keySounds,
            level = audioLevelOf(
                barConfigured = hasBar,
                // Only a level the bar has reported (or that we just set): before that, unknown, never 0.
                barVolume = volume.takeIf { bar.connected },
                barMax = volumeCap ?: TuttiController.MAX_VOLUME,
                barMuted = muted,
                tvVolume = tvVolume?.level,
                tvMax = tvVolume?.max,
                tvMuted = tvVolume?.muted == true,
            ),
            nowShowing = app?.let { pkg ->
                QuickNowShowing(pkg, apps.firstOrNull { it.packageName == pkg }?.name, appIcons[pkg])
            },
            tvConfigured = devices.tv != null,
            showing = cardShowing(this)?.toQuick(),
            tvName = devices.tv?.name,
            barName = devices.bar?.name,
        )
    }

    private fun CardShowing.toQuick(): QuickShowing = when (this) {
        is CardShowing.Showing -> when (val now = now) {
            is NowShowing.App -> QuickShowing.App(now.name, now.packageName)
            is NowShowing.Input -> QuickShowing.Input(now.title)
            is NowShowing.Channel -> QuickShowing.Channel(now.text)
        }
        is CardShowing.OnTheBar -> QuickShowing.OnTheBar(bar.input, bar.tvAsleep)
        is CardShowing.Standby -> QuickShowing.Standby(barOn)
        CardShowing.Home -> QuickShowing.Home
        CardShowing.Reconnecting -> QuickShowing.Reconnecting
        CardShowing.Offline -> QuickShowing.Offline
    }

    internal companion object {
        /** Power is known, or no configured link is still trying: Power can decide (and wake) now. */
        fun powerSettled(state: TuttiUiState): Boolean {
            if (state.powered != null) return true
            val links = state.links
            val tvTrying = state.devices.tv != null && (links.tv == Link.CONNECTING || links.tv == Link.ONLINE)
            val barTrying = state.devices.bar != null && (links.bar == Link.CONNECTING || links.bar == Link.ONLINE)
            return !tvTrying && !barTrying
        }

        const val LINK_TIMEOUT_MS = 4_000L
        const val SCENE_TIMEOUT_MS = 30_000L
    }
}

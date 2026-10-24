package io.kud.tutti.quick

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiEvent
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.SceneStatus
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.quick.notification.tvStepsTo
import kotlin.math.abs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

    override val state: StateFlow<QuickState> = controller.state
        .map { it.toQuick() }
        .stateIn(controller.scope, SharingStarted.Eagerly, controller.state.value.toQuick())

    override fun retain() = controller.retain()

    override fun release() = controller.release()

    override suspend fun togglePower() = held { controller.power() }

    override suspend fun volumeUp() = step(+1)

    override suspend fun volumeDown() = step(-1)

    private suspend fun step(delta: Int) = held {
        if (onBar()) {
            controller.volumeStep(delta)
        } else {
            controller.key(if (delta > 0) V2Key.VOLUME_UP else V2Key.VOLUME_DOWN)
        }
    }

    override suspend fun setVolume(level: Int) = held {
        if (onBar()) {
            controller.setVolume(level)
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
        controller.applyScene(id)
        withTimeoutOrNull(SCENE_TIMEOUT_MS) {
            controller.events.filter { it is TuttiEvent.SceneFinished && it.sceneId == id }.first()
        }
    }

    private suspend fun held(onTv: Boolean = false, action: suspend () -> Unit) {
        controller.retain()
        try {
            val bar = !onTv && onBar()
            withTimeoutOrNull(LINK_TIMEOUT_MS) {
                controller.state.first { (if (bar) it.links.bar else it.links.tv) == Link.ONLINE }
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
            power = powered == true,
            volume = volume ?: 0,
            volumeLimit = volumeCap ?: 100,
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
            tvName = devices.tv?.name,
            barName = devices.bar?.name,
        )
    }

    private companion object {
        const val LINK_TIMEOUT_MS = 4_000L
        const val SCENE_TIMEOUT_MS = 30_000L
    }
}

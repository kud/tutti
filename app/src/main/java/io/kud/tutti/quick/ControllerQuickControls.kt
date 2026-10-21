package io.kud.tutti.quick

import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiEvent
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.SceneStatus
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

    override suspend fun volumeUp() = held { controller.volumeStep(+1) }

    override suspend fun volumeDown() = held { controller.volumeStep(-1) }

    override suspend fun setVolume(level: Int) = held { controller.setVolume(level) }

    override suspend fun toggleMute() = held { controller.toggleMute() }

    override suspend fun applyScene(id: String) = held {
        controller.applyScene(id)
        withTimeoutOrNull(SCENE_TIMEOUT_MS) {
            controller.events.filter { it is TuttiEvent.SceneFinished && it.sceneId == id }.first()
        }
    }

    private suspend fun held(action: suspend () -> Unit) {
        controller.retain()
        try {
            withTimeoutOrNull(LINK_TIMEOUT_MS) { controller.state.first { it.links.bar == Link.ONLINE } }
            action()
        } finally {
            controller.release()
        }
    }

    private fun TuttiUiState.toQuick(): QuickState {
        val active = activeScene
        return QuickState(
            connected = links.bar == Link.ONLINE,
            power = powered == true,
            volume = volume ?: 0,
            volumeLimit = volumeCap ?: 100,
            muted = muted,
            activeSceneId = active?.scene?.id,
            activeSceneModified = active?.status == SceneStatus.MODIFIED,
            scenes = scenes.map { QuickScene(it.scene.id, it.scene.name, it.scene.color) },
        )
    }

    private companion object {
        const val LINK_TIMEOUT_MS = 4_000L
        const val SCENE_TIMEOUT_MS = 30_000L
    }
}

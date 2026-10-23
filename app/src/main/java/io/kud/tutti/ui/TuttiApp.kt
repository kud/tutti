package io.kud.tutti.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiEvent
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.diagnostic.Diagnostics
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.onboarding.OnboardingScreen
import io.kud.tutti.ui.remote.RemoteScreen
import io.kud.tutti.ui.settings.BarSettingsScreen
import io.kud.tutti.ui.settings.DiagnosticScreen
import io.kud.tutti.ui.settings.SettingsScreen
import io.kud.tutti.ui.settings.TvSettingsScreen
import io.kud.tutti.ui.scenes.SceneEditorScreen
import io.kud.tutti.ui.scenes.ScenesScreen

/** Three places, opening on the remote; two levels deep at most. */
sealed interface Screen {
    data object Remote : Screen

    data object Scenes : Screen

    /** Null id: a new scene. */
    data class SceneEditor(val sceneId: String?) : Screen

    data object Settings : Screen

    data object TvSettings : Screen

    data object BarSettings : Screen

    data object Diagnostic : Screen

    data object Onboarding : Screen
}

@Composable
fun TuttiApp(controller: TuttiController, discovery: TvDiscovery, diagnostics: Diagnostics, state: TuttiUiState) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Remote) }
    val haptics = rememberHaptics()
    val context = LocalContext.current

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    LaunchedEffect(controller, haptics) {
        controller.events.collect { event ->
            when (event) {
                is TuttiEvent.SceneFinished -> haptics.doublePulse()
                TuttiEvent.VolumeLimit -> haptics.bump()
                TuttiEvent.ActionFailed -> Toast.makeText(context, R.string.error_action_failed, Toast.LENGTH_SHORT).show()
                TuttiEvent.MicUnavailable -> Toast.makeText(context, R.string.voice_mic_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    LaunchedEffect(state.loaded) {
        if (state.loaded && state.devices.isEmpty && !state.prefs.onboarded && Screen.Onboarding !in stack) push(Screen.Onboarding)
    }

    if (!state.loaded) {
        // Dark from the first frame; no flash of onboarding before the stores load.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    AnimatedContent(
        targetState = stack.last(),
        transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
        label = "screen",
    ) { screen ->
        when (screen) {
            Screen.Remote -> RemoteScreen(
                state = state,
                controller = controller,
                onOpenScenes = { push(Screen.Scenes) },
                onOpenSettings = { push(Screen.Settings) },
                onEditScene = { push(Screen.SceneEditor(it)) },
            )
            Screen.Scenes -> ScenesScreen(
                state = state,
                controller = controller,
                onBack = ::pop,
                onEdit = { push(Screen.SceneEditor(it)) },
            )
            is Screen.SceneEditor -> SceneEditorScreen(
                sceneId = screen.sceneId,
                state = state,
                controller = controller,
                onDone = ::pop,
            )
            Screen.Settings -> SettingsScreen(
                state = state,
                controller = controller,
                onBack = ::pop,
                onOpenTv = { push(Screen.TvSettings) },
                onOpenBar = { push(Screen.BarSettings) },
                onOpenDiagnostic = { push(Screen.Diagnostic) },
                onPair = {
                    controller.resetPairing()
                    push(Screen.Onboarding)
                },
            )
            Screen.TvSettings -> TvSettingsScreen(state = state, controller = controller, onBack = ::pop)
            Screen.BarSettings -> BarSettingsScreen(state = state, controller = controller, onBack = ::pop)
            Screen.Diagnostic -> DiagnosticScreen(diagnostics = diagnostics, onBack = ::pop)
            Screen.Onboarding -> OnboardingScreen(
                state = state,
                controller = controller,
                discovery = discovery,
                onDone = ::pop,
            )
        }
    }
}

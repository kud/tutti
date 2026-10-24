package io.kud.tutti.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiEvent
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.diagnostic.Diagnostics
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.protocol.net.WakeAttempt
import io.kud.tutti.ui.common.LocalDeveloperMode
import io.kud.tutti.ui.common.LocalProblems
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.Problems
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.onboarding.OnboardingScreen
import io.kud.tutti.ui.remote.RemoteScreen
import io.kud.tutti.ui.scenes.SceneEditorScreen
import io.kud.tutti.ui.scenes.ScenesScreen
import io.kud.tutti.ui.about.AboutScreen
import io.kud.tutti.ui.settings.BarSettingsScreen
import io.kud.tutti.ui.settings.DiagnosticScreen
import io.kud.tutti.ui.settings.SettingsScreen
import io.kud.tutti.ui.settings.TvSettingsScreen

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

    data object About : Screen
}

@Composable
fun TuttiApp(controller: TuttiController, discovery: TvDiscovery, diagnostics: Diagnostics, state: TuttiUiState) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Remote) }
    // The remote's edit mode is a state of the remote screen, not a screen of its own.
    var editingLayout by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val context = LocalContext.current

    // Which way the screens slide: deeper comes in from the end, back from the start.
    var forward by remember { mutableStateOf(true) }

    fun push(screen: Screen) {
        forward = true
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) {
            forward = false
            stack.removeAt(stack.lastIndex)
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val problemScope = rememberCoroutineScope()
    val problems = remember(snackbar, problemScope, context) { Problems(snackbar, problemScope, context) }
    problems.developer = state.prefs.developerMode

    LaunchedEffect(controller, haptics, problems) {
        controller.events.collect { event ->
            when (event) {
                is TuttiEvent.SceneFinished -> {
                    if (event.failedSteps == 0) haptics.doublePulse() else haptics.reject()
                    if (event.detail != null) problems.report(context.getString(R.string.scene_failed_steps), event.detail)
                }
                TuttiEvent.VolumeLimit -> haptics.bump()
                is TuttiEvent.ActionFailed -> problems.report(context.getString(R.string.error_action_failed), event.detail)
                is TuttiEvent.MicUnavailable -> problems.report(context.getString(R.string.voice_mic_unavailable), event.detail)
                is TuttiEvent.WakeFailed -> problems.report(
                    context.getString(
                        when (event.cause) {
                            WakeAttempt.Cause.TV_NO_MAC -> R.string.power_wake_failed_tv_no_mac
                            WakeAttempt.Cause.TV_NOT_FOUND -> R.string.power_wake_failed_tv_not_found
                            WakeAttempt.Cause.TV_ASLEEP -> R.string.power_wake_failed_tv
                            WakeAttempt.Cause.BAR_SILENT -> R.string.power_wake_failed_bar
                        },
                    ),
                    event.detail,
                )
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

    CompositionLocalProvider(LocalProblems provides problems, LocalDeveloperMode provides state.prefs.developerMode) {
        val reducedMotion = LocalReducedMotion.current
        val slide = with(LocalDensity.current) { SCREEN_SLIDE.roundToPx() }
        Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = stack.last(),
                    transitionSpec = { screenTransition(forward, reducedMotion, slide) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Remote -> RemoteScreen(
                            state = state,
                            controller = controller,
                            onOpenScenes = { push(Screen.Scenes) },
                            onOpenSettings = { push(Screen.Settings) },
                            onEditScene = { push(Screen.SceneEditor(it)) },
                            editing = editingLayout,
                            onEditingChange = { editingLayout = it },
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
                            onOpenAbout = { push(Screen.About) },
                            onEditLayout = {
                                editingLayout = true
                                pop()
                            },
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
                        Screen.About -> AboutScreen(onBack = ::pop)
                    }
                }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
    }
}

/** How far a screen travels as it comes or goes (Iris's motion spec: 24 dp with a crossfade). */
private val SCREEN_SLIDE: Dp = 24.dp

/**
 * One calm transition for every screen change: the new screen slides [slide] px in from the end
 * (from the start on the way back) and fades in along [Motion.Emphasized] over [Motion.MEDIUM],
 * while the old one drifts the other way and fades out over [Motion.SHORT]. Reduced motion: the
 * same fades, no travel.
 */
private fun screenTransition(forward: Boolean, reduced: Boolean, slide: Int): ContentTransform {
    if (reduced) return fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
    val towards = if (forward) 1 else -1
    val enter = slideInHorizontally(Motion.enter()) { towards * slide } + fadeIn(Motion.enter())
    val exit = slideOutHorizontally(Motion.exit()) { -towards * slide } + fadeOut(Motion.exit())
    return enter togetherWith exit
}

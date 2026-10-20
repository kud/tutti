package io.kud.salon.ui

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
import io.kud.salon.R
import io.kud.salon.controller.SalonController
import io.kud.salon.controller.SalonEvent
import io.kud.salon.controller.SalonUiState
import io.kud.salon.platform.discovery.TvDiscovery
import io.kud.salon.ui.common.rememberHaptics
import io.kud.salon.ui.remote.RemoteScreen

/** Three places, opening on the remote; two levels deep at most. */
sealed interface Screen {
    data object Remote : Screen
}

@Composable
fun SalonApp(controller: SalonController, discovery: TvDiscovery, state: SalonUiState) {
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
                is SalonEvent.SceneFinished -> haptics.doublePulse()
                SalonEvent.VolumeLimit -> haptics.bump()
                SalonEvent.ActionFailed -> Toast.makeText(context, R.string.error_action_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

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
                onOpenScenes = {},
                onOpenSettings = {},
                onEditScene = {},
            )
        }
    }
}

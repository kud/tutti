package io.kud.tutti.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/**
 * Each screen's saved state, its scroll above all, held for as long as the screen is in the back
 * stack (Erwann, 2026-11-12: coming back from a page must find Settings where it was, not at the
 * top). Without it a screen left for a deeper one is disposed, and its `rememberScrollState` with it.
 * Wrap each screen in `SaveableStateProvider(key)`; the holder is itself saveable, so the positions
 * survive rotation too. A screen popped off the stack drops its state, so opening it again starts
 * at the top.
 */
@Composable
internal fun rememberScreenStates(keys: List<String>): SaveableStateHolder {
    val holder = rememberSaveableStateHolder()
    val known = remember { mutableSetOf<String>().apply { addAll(keys) } }
    LaunchedEffect(keys) {
        (known - keys.toSet()).forEach(holder::removeState)
        known.clear()
        known.addAll(keys)
    }
    return holder
}

/**
 * The screens that keep their state in the stack: Settings at every level. The remote, the scenes
 * and onboarding start fresh as before, so a sheet open when Settings was pushed never reopens.
 */
internal fun keepsState(screen: Screen): Boolean = when (screen) {
    Screen.Settings, is Screen.SettingsPage, Screen.TvSettings, is Screen.TvSettingsPage, Screen.BarSettings,
    Screen.Diagnostic, Screen.About, is Screen.Unlock, Screen.Technical, Screen.EventLog,
    -> true
    Screen.Remote, Screen.Scenes, is Screen.SceneEditor, is Screen.Onboarding, Screen.WhatsNew -> false
}

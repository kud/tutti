package io.kud.tutti.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.TonalRoundButton
import io.kud.tutti.ui.common.rememberHaptics

private sealed interface PickerTarget {
    data class Slot(val index: Int) : PickerTarget

    data class LongPress(val button: SecondaryButton) : PickerTarget

    /** A pin's editor (long press on it). */
    data class Edit(val index: Int) : PickerTarget
}

/**
 * The home: system chip + one Power, scene pills, the pad with the volume rail on its right,
 * Back / Home / Play / Keyboard / Mute, and the 4-slot dock. Every tap gives its haptic first,
 * then hands the action to the controller, which updates state before the network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: TuttiUiState,
    controller: TuttiController,
    onOpenScenes: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditScene: (String) -> Unit,
) {
    val haptics = rememberHaptics()
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var keyboardRequested by remember { mutableStateOf(false) }
    var sheet2 by remember { mutableStateOf<RemoteSheet?>(null) }
    val imeOpen = state.remote.ime != null
    val keyboardOpen = imeOpen || keyboardRequested

    // The TV field lost focus (or the app changed): close what it opened.
    LaunchedEffect(imeOpen) {
        if (!imeOpen) keyboardRequested = false
    }

    DisposableEffect(controller) {
        controller.remoteInFront = true
        onDispose { controller.remoteInFront = false }
    }

    fun secondary(button: SecondaryButton) {
        val action = state.dock.longPress[button]
        haptics.longPress()
        if (action != null) controller.run(action) else picker = PickerTarget.LongPress(button)
    }

    val sheet = rememberBottomSheetScaffoldState()
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = if (keyboardOpen) 0.dp else SHEET_PEEK + navigationInset,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        containerColor = MaterialTheme.colorScheme.background,
        sheetContent = { SoundSheet(state, controller) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SystemChip(state, onClick = onOpenSettings, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onOpenScenes) {
                    Icon(painterResource(R.drawable.ic_scene), contentDescription = stringResource(R.string.action_scenes))
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.action_settings))
                }
                TonalRoundButton(
                    icon = R.drawable.ic_power,
                    contentDescription = stringResource(R.string.action_power),
                    onClick = {
                        haptics.select()
                        controller.power()
                    },
                    active = state.powered == true,
                    size = 52.dp,
                )
            }

            SceneStrip(
                scenes = state.scenes,
                onApply = {
                    haptics.click()
                    controller.applyScene(it)
                },
                onEdit = onEditScene,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            if (keyboardOpen) {
                KeyboardPanel(
                    field = state.remote.ime,
                    onText = controller::text,
                    onEnter = { controller.key(V2Key.ENTER) },
                    onClose = {
                        controller.dismissKeyboard()
                        keyboardRequested = false
                    },
                    onTypingChange = { controller.typing = it },
                )
            }

            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Pad(
                    mode = state.prefs.padMode,
                    onModeChange = controller::setPadMode,
                    onKey = controller::key,
                    onLongSelect = { controller.longPress(V2Key.DPAD_CENTER) },
                    onPlayPause = { controller.key(V2Key.MEDIA_PLAY_PAUSE) },
                    onVolumeStep = controller::volumeStep,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Spacer(Modifier.width(12.dp))
                VolumeRail(
                    volume = state.volume,
                    cap = state.volumeCap,
                    muted = state.muted,
                    enabled = state.bar.connected,
                    onVolume = controller::setVolume,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TonalRoundButton(
                    icon = R.drawable.ic_back,
                    contentDescription = stringResource(R.string.action_back),
                    onClick = {
                        haptics.click()
                        controller.key(V2Key.BACK)
                    },
                    onLongClick = { secondary(SecondaryButton.BACK) },
                    size = ROW_BUTTON,
                )
                TonalRoundButton(
                    icon = R.drawable.ic_home,
                    contentDescription = stringResource(R.string.action_home),
                    onClick = {
                        haptics.click()
                        controller.key(V2Key.HOME)
                    },
                    onLongClick = { secondary(SecondaryButton.HOME) },
                    size = ROW_BUTTON,
                )
                TonalRoundButton(
                    icon = R.drawable.ic_play_pause,
                    contentDescription = stringResource(R.string.action_play_pause),
                    onClick = {
                        haptics.click()
                        controller.key(V2Key.MEDIA_PLAY_PAUSE)
                    },
                    onLongClick = { secondary(SecondaryButton.PLAY) },
                    size = ROW_BUTTON,
                )
                VoiceButton(controller = controller, enabled = state.remote.ready, size = ROW_BUTTON)
                TonalRoundButton(
                    icon = R.drawable.ic_keyboard,
                    contentDescription = stringResource(R.string.action_keyboard),
                    onClick = {
                        haptics.click()
                        if (keyboardOpen) {
                            controller.dismissKeyboard()
                            keyboardRequested = false
                        } else {
                            keyboardRequested = true
                        }
                    },
                    active = keyboardOpen,
                    size = ROW_BUTTON,
                )
                TonalRoundButton(
                    icon = if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                    contentDescription = stringResource(R.string.action_mute),
                    onClick = {
                        haptics.click()
                        controller.toggleMute()
                    },
                    active = state.muted,
                    enabled = state.bar.connected,
                    size = ROW_BUTTON,
                )
            }

            if (!keyboardOpen) {
                Spacer(Modifier.height(12.dp))
                Dock(
                    state = state,
                    onApps = {
                        haptics.click()
                        sheet2 = RemoteSheet.APPS
                    },
                    onKeys = {
                        haptics.click()
                        sheet2 = RemoteSheet.KEYS
                    },
                    onRun = { _, slot ->
                        haptics.click()
                        slot.primary?.let(controller::run)
                    },
                    onAssign = { index ->
                        haptics.click()
                        picker = PickerTarget.Slot(index)
                    },
                    onEdit = { index ->
                        haptics.longPress()
                        picker = PickerTarget.Edit(index)
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    when (sheet2) {
        RemoteSheet.APPS -> AppsSheet(
            state = state,
            onLaunch = { app ->
                haptics.click()
                controller.launchApp(app.packageName)
                sheet2 = null
            },
            onAllApps = {
                haptics.click()
                controller.openAllApps()
                sheet2 = null
            },
            onPin = { app, index ->
                haptics.longPress()
                controller.assignDock(index, DockAction.LaunchApp(app.packageName, app.name), state.dock.slots.getOrNull(index)?.secondary)
            },
            onDismiss = { sheet2 = null },
        )
        RemoteSheet.KEYS -> MoreKeysSheet(
            state = state,
            onKey = {
                haptics.click()
                controller.key(it)
            },
            onLaunch = {
                haptics.click()
                controller.launchApp(it)
            },
            onDismiss = { sheet2 = null },
        )
        null -> Unit
    }

    when (val target = picker) {
        is PickerTarget.Slot -> ActionPickerSheet(
            title = stringResource(R.string.dock_assign_title, target.index + 1),
            state = state,
            allowRemove = state.dock.slots.getOrNull(target.index)?.primary != null,
            onPick = { action ->
                controller.assignDock(target.index, action, state.dock.slots.getOrNull(target.index)?.secondary)
                picker = null
            },
            onLoadTv = controller::loadTvSettings,
            onDismiss = { picker = null },
        )
        is PickerTarget.LongPress -> ActionPickerSheet(
            title = stringResource(R.string.longpress_assign_title, secondaryLabel(target.button)),
            state = state,
            allowRemove = state.dock.longPress[target.button] != null,
            onPick = { action ->
                controller.assignLongPress(target.button, action)
                picker = null
            },
            onLoadTv = controller::loadTvSettings,
            onDismiss = { picker = null },
        )
        is PickerTarget.Edit -> PinEditorSheet(
            index = target.index,
            slot = state.dock.slots.getOrNull(target.index) ?: DockSlot(),
            state = state,
            onSave = { slot ->
                controller.saveSlot(target.index, slot)
                picker = null
            },
            onLoadTv = controller::loadTvSettings,
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

@Composable
fun secondaryLabel(button: SecondaryButton): String = when (button) {
    SecondaryButton.HOME -> stringResource(R.string.action_home)
    SecondaryButton.BACK -> stringResource(R.string.action_back)
    SecondaryButton.PLAY -> stringResource(R.string.action_play_pause)
}

/** Six round buttons (with the mic) must fit a 360dp phone: 6 × 52 + gaps inside 328dp. */
private val ROW_BUTTON = 52.dp

/** The full-height sheets the bottom row's doors open. */
private enum class RemoteSheet { APPS, KEYS }

/** The “Son” handle and its one-line summary. */
private val SHEET_PEEK = 72.dp

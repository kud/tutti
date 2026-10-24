package io.kud.tutti.ui.remote

import io.kud.tutti.ui.common.FeedbackEvent
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.PadSize
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.TonalRoundButton
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.rememberWaiting
import kotlinx.coroutines.delay

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
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val layout = state.prefs.layout
    BackHandler(enabled = editing) { onEditingChange(false) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var sheet2 by remember { mutableStateOf<RemoteSheet?>(null) }
    // The full input view is a state of the remote, not a screen: InputRules moves it.
    var input by remember { mutableStateOf(InputMode()) }
    val hasField = state.remote.ime != null
    LaunchedEffect(hasField, state.remote.imeShows) {
        input = InputRules.tv(input, hasField, state.remote.imeShows, SystemClock.uptimeMillis())
    }
    // The TV closed the field: the closed frame holds, then the remote comes back, unless a new field took over.
    LaunchedEffect(input.closedAt) {
        if (input.closedAt == null) return@LaunchedEffect
        delay(InputRules.GRACE_MS)
        input = InputRules.tick(input, SystemClock.uptimeMillis())
    }
    val inputOpen = input.open
    val tvReachable = !(state.devices.tv != null && state.links.tv == Link.UNREACHABLE)
    val reduced = LocalReducedMotion.current
    val rise = with(LocalDensity.current) { INPUT_RISE.roundToPx() }

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
    val sheetScope = rememberCoroutineScope()
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = if (inputOpen || editing) 0.dp else SOUND_HANDLE_HEIGHT + navigationInset,
        sheetSwipeEnabled = !editing && !inputOpen,
        sheetDragHandle = {
            val expanded = sheet.bottomSheetState.currentValue == SheetValue.Expanded
            SoundHandle(expanded = expanded) {
                sheetScope.launch { if (expanded) sheet.bottomSheetState.partialExpand() else sheet.bottomSheetState.expand() }
            }
        },
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        containerColor = MaterialTheme.colorScheme.background,
        sheetContent = { SoundSheet(state, controller) },
    ) { padding ->
        if (editing) {
            LayoutEditor(
                state = state,
                onLayout = controller::updateLayout,
                onReset = controller::resetLayout,
                onMovePin = controller::moveDockSlot,
                onEditPin = { picker = PickerTarget.Edit(it) },
                onAddPage = controller::addDockPage,
                onDone = { onEditingChange(false) },
                modifier = Modifier.padding(padding),
            )
            return@BottomSheetScaffold
        }
        AnimatedContent(
            targetState = inputOpen,
            transitionSpec = {
                when {
                    reduced -> fadeIn(tween(Motion.SHORT)) togetherWith fadeOut(tween(Motion.SHORT))
                    // Going home shouldn't perform: a plain cross-fade.
                    !targetState -> fadeIn(tween(INPUT_BACK_MS)) togetherWith fadeOut(tween(INPUT_BACK_MS))
                    // From the key: the view grows out of it (a stand-in for the shared-bounds morph).
                    input.origin == InputOrigin.KEY ->
                        (fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM, easing = Motion.Emphasized), initialScale = 0.92f)) togetherWith
                            fadeOut(tween(Motion.FADE_OUT))
                    // The TV opened it: no source key, so it rises.
                    else -> (fadeIn(tween(Motion.MEDIUM)) + slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { rise }) togetherWith
                        fadeOut(tween(Motion.FADE_OUT))
                }
            },
            label = "input view",
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { open ->
            if (open) {
                InputView(
                    field = state.remote.ime,
                    mode = input,
                    phase = InputRules.phase(input, tvReachable),
                    tvName = state.devices.tv?.name.orEmpty(),
                    volume = state.volume.takeIf { state.bar.connected },
                    onText = controller::text,
                    onEnter = {
                        input = InputRules.enter(input, SystemClock.uptimeMillis())
                        controller.key(V2Key.ENTER)
                    },
                    onKey = controller::key,
                    onLongSelect = { controller.longPress(V2Key.DPAD_CENTER) },
                    onLeave = { input = InputRules.leave(input) },
                    onTypingChange = { controller.typing = it },
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding().imePadding(),
                )
                return@AnimatedContent
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
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
                    // Waking a sleeping TV takes seconds: a turning ring says so (not a colour), and the label follows.
                    // The ring keeps the loading rules: not before 150 ms, then at least 400 ms; held still when motion is reduced.
                    val wakingRing = rememberWaiting(state.waking)
                    Box(contentAlignment = Alignment.Center) {
                        TonalRoundButton(
                            icon = R.drawable.ic_power,
                            contentDescription = stringResource(if (state.waking) R.string.power_waking else R.string.action_power),
                            onClick = { controller.power() },
                            feedback = FeedbackEvent.SELECT,
                            active = state.powered == true,
                            size = 52.dp,
                        )
                        // Box scope inside a Row: the plain overload, named, so the Row's one isn't picked.
                        androidx.compose.animation.AnimatedVisibility(
                            visible = wakingRing.spinner,
                            enter = fadeIn(Motion.fade()),
                            exit = fadeOut(Motion.exit()),
                            modifier = Modifier.matchParentSize(),
                        ) {
                            if (LocalReducedMotion.current) {
                                CircularProgressIndicator(progress = { 0.75f }, modifier = Modifier.fillMaxSize(), strokeWidth = 3.dp)
                            } else {
                                CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 3.dp)
                            }
                        }
                    }
                }

                if (layout.scenes) {
                    SceneStrip(
                        scenes = state.scenes,
                        onApply = { controller.applyScene(it) },
                        onEdit = onEditScene,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                // The thumb reaches about half the screen (Erwann, 2026-10-24): the rarely used mode
                // switch stands at the top, the pad and the rail stand low, one height side by side,
                // and the free room goes between them (placePad). Large takes the margins back.
                val large = layout.padSize == PadSize.LARGE
                val railGap = if (large) 8.dp else 12.dp
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = if (large) 8.dp else 16.dp),
                ) {
                    // Fixed under the pills (or the header), centred on the pad's axis, not the
                    // screen's: the rail's slot stands empty beside it (Iris, 2026-10-24).
                    Spacer(Modifier.height(SWITCH_TOP))
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            PadModeSwitch(
                                mode = state.prefs.padMode,
                                onModeChange = controller::setPadMode,
                            )
                        }
                        Spacer(Modifier.width(railGap + RAIL_WIDTH))
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val place = placePad(maxHeight.value, (maxWidth - railGap - RAIL_WIDTH).value, layout.padSize)
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(bottom = place.below.dp)
                                .fillMaxWidth()
                                .height(place.height.dp),
                        ) {
                            Pad(
                                mode = state.prefs.padMode,
                                onKey = controller::key,
                                onLongSelect = { controller.longPress(V2Key.DPAD_CENTER) },
                                onPlayPause = { controller.key(V2Key.MEDIA_PLAY_PAUSE) },
                                onVolumeStep = controller::volumeStep,
                                volume = state.volume.takeIf { state.bar.connected },
                                volumeCap = state.volumeCap,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                            Spacer(Modifier.width(railGap))
                            // The rail fills the row, so it takes the pad's height, top and foot.
                            VolumeRail(
                                volume = state.volume,
                                cap = state.volumeCap,
                                muted = state.muted,
                                enabled = state.bar.connected,
                                onVolume = controller::setVolume,
                            )
                        }
                    }
                }

                val row = layout.shownRow
                if (row.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = rowArrangement(row.size),
                    ) {
                        row.forEach { button ->
                            LiveRowButton(
                                button = button,
                                state = state,
                                controller = controller,
                                haptics = haptics,
                                fieldWaiting = input.fieldWaiting,
                                onKeyboard = { input = InputRules.keyboardKey(input) },
                                onSecondary = ::secondary,
                                size = ROW_BUTTON,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Dock(
                    state = state,
                    onApps = {
                        sheet2 = RemoteSheet.APPS
                    },
                    onKeys = {
                        sheet2 = RemoteSheet.KEYS
                    },
                    onRun = { _, slot ->
                        slot.primary?.let(controller::run)
                    },
                    onAssign = { index ->
                        picker = PickerTarget.Slot(index)
                    },
                    onEdit = { index ->
                        picker = PickerTarget.Edit(index)
                    },
                    onRunSecondary = { _, action ->
                        haptics.click()
                        controller.run(action)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    when (sheet2) {
        RemoteSheet.APPS -> AppsSheet(
            state = state,
            // The tile plays its own click on down and confirm or reject on the outcome.
            onLaunch = { app -> controller.launchApp(app.packageName) },
            onLaunched = { sheet2 = null },
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
                controller.key(it)
            },
            onLaunch = {
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
            slot = state.dock.slot(target.index),
            state = state,
            onSave = { slot ->
                controller.saveSlot(target.index, slot)
                picker = null
            },
            onLoadTv = controller::loadTvSettings,
            onDismiss = { picker = null },
            onTry = { action ->
                haptics.click()
                controller.run(action)
            },
            onAddPage = controller::addDockPage,
            onRemovePage = { page ->
                controller.removeDockPage(page)
                picker = null
            },
            onEditLayout = if (editing) {
                null
            } else {
                {
                    picker = null
                    onEditingChange(true)
                }
            },
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

/** The full-height sheets the bottom row's doors open. */
private enum class RemoteSheet { APPS, KEYS }

/** Above the mode switch's 48 dp target, so its 36 dp track is drawn 12 dp under the pills (Iris). */
private val SWITCH_TOP = 6.dp

/** How far the input view rises when the TV opens it. */
private val INPUT_RISE = 24.dp

/** Back to the remote: a plain cross-fade. */
private const val INPUT_BACK_MS = 200

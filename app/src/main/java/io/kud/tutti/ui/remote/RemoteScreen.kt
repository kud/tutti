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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import androidx.compose.runtime.saveable.rememberSaveable
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PowerTarget
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.protocol.apps.KnownApp
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
 * Back / Home / Play / Mic / Keyboard (mute is the rail's foot), and the 4-slot dock. Every tap gives its haptic first,
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
    onAddBar: () -> Unit = {},
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val layout = state.prefs.layout
    BackHandler(enabled = editing) { onEditingChange(false) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var sheet2 by remember { mutableStateOf<RemoteSheet?>(null) }
    var powerSheet by remember { mutableStateOf(false) }
    // The now-showing card's sheets (Erwann, 2026-10-25), each holding what the card showed when
    // opened, so a blip that changes the card never yanks the sheet away.
    var playbackFor by remember { mutableStateOf<CardShowing?>(null) }
    var actionsFor by remember { mutableStateOf<CardShowing?>(null) }
    var pinningNow by remember { mutableStateOf<NowShowing.App?>(null) }
    // The full input view is a state of the remote, not a screen: InputRules moves it.
    var input by remember { mutableStateOf(InputMode()) }
    // Whether the now-showing card fits above the pad: the pad block measures, the chip reads it.
    var cardShown by remember { mutableStateOf(false) }
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
    val tvReachable = !(state.devices.tv != null && state.links.tvShown.link == Link.UNREACHABLE)
    // TalkBack hears the TV's link change once per real change: the shown link (TvLinkRule) never
    // moves on a blip, so neither does this. The first connect says nothing; the chip already does.
    val view = LocalView.current
    val tvLinkSay = state.devices.tv?.name?.let { name ->
        when {
            state.links.tvShown.reconnecting -> stringResource(R.string.system_reconnecting_tv)
            state.links.tvShown.link == Link.UNREACHABLE -> stringResource(R.string.system_unreachable, name)
            state.links.tvShown.link == Link.ONLINE -> stringResource(R.string.tv_link_back)
            else -> null
        }
    }
    var lastTvLinkSay by remember { mutableStateOf(tvLinkSay) }
    LaunchedEffect(tvLinkSay) {
        val was = lastTvLinkSay
        lastTvLinkSay = tvLinkSay
        @Suppress("DEPRECATION")
        if (tvLinkSay != null && was != null && tvLinkSay != was) view.announceForAccessibility(tvLinkSay)
    }
    val reduced = LocalReducedMotion.current
    val rise = with(LocalDensity.current) { INPUT_RISE.roundToPx() }

    DisposableEffect(controller) {
        controller.remoteInFront = true
        onDispose { controller.remoteInFront = false }
    }


    val sheet = rememberBottomSheetScaffoldState()

    // The bar on its own (Erwann, 2026-10-24): the TV keys have nothing to reach while the TV
    // sleeps, so a pad press says so and sends nothing; the card's button or Power wakes it.
    val alone = barAlone(state)
    val messages = LocalMessages.current
    val standbyWords = stringResource(R.string.key_tv_standby)
    val wakeWords = stringResource(R.string.card_wake_tv_short)
    fun tvAsleepAlone(): Boolean {
        if (alone?.tvAsleep != true) return false
        haptics.reject()
        if (messages?.current?.text != standbyWords) {
            messages?.show(standbyWords, actionLabel = wakeWords, onAction = { controller.power(PowerTarget.TV, on = true) })
        }
        return true
    }

    // The round keys' default long presses (RowGestures) say themselves once per visit, with
    // "Change" to pick another: proposed, never imposed in silence.
    val defaultsSaid = remember { mutableSetOf<SecondaryButton>() }
    val longSaidWords = stringResource(R.string.row_long_default_said)
    val changeWords = stringResource(R.string.row_long_change)
    val noPreviousWords = stringResource(R.string.row_long_no_previous)
    val defaultLabels = RowDefault.entries.associateWith { stringResource(it.label) }
    fun secondary(button: SecondaryButton) {
        val action = state.dock.longPress[button]
        haptics.longPress()
        if (action != null) {
            controller.run(action)
            return
        }
        val fallback = rowDefault(button)
        if (fallback == null) {
            picker = PickerTarget.LongPress(button)
            return
        }
        if (tvAsleepAlone()) return
        val change = { picker = PickerTarget.LongPress(button) }
        val ran = when (fallback) {
            RowDefault.PREVIOUS_APP -> controller.launchPreviousApp()
            RowDefault.RECENT_APPS -> {
                controller.recentApps()
                true
            }
        }
        if (!ran) {
            messages?.show(noPreviousWords, actionLabel = changeWords, onAction = change)
        } else if (defaultsSaid.add(button)) {
            messages?.show(longSaidWords.format(defaultLabels.getValue(fallback)), actionLabel = changeWords, onAction = change)
        }
    }

    // « Et la feuille Son peut monter toute seule » (Erwann, 2026-10-24): when the bar starts
    // playing on its own the sheet rises once, its controls being the ones that matter now. Folded
    // again, it stays folded until the state ends; saved, so coming back to the remote doesn't
    // raise it a second time.
    var rose by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(alone != null) {
        if (alone == null) {
            rose = false
        } else if (!rose && !inputOpen && !editing) {
            rose = true
            sheet.bottomSheetState.expand()
        }
    }

    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BottomSheetScaffold(
        scaffoldState = sheet,
        sheetPeekHeight = if (inputOpen || editing) 0.dp else SHEET_PEEK + navigationInset,
        sheetSwipeEnabled = !editing && !inputOpen,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        containerColor = MaterialTheme.colorScheme.background,
        sheetContent = {
            SoundSheet(state, controller, peeking = sheet.bottomSheetState.targetValue != SheetValue.Expanded, onAddBar = onAddBar)
        },
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
                    // The chip's "On the TV" line steps aside while the card below says it.
                    SystemChip(state, onClick = onOpenSettings, modifier = Modifier.weight(1f), showNow = !cardShown)
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
                            // TV only, soundbar only or both (Erwann, 2026-10-24); only with the two set up.
                            onLongClick = { powerSheet = true }.takeIf { state.devices.tv != null && state.devices.bar != null },
                            onLongClickLabel = stringResource(R.string.power_long_label),
                            feedback = FeedbackEvent.SELECT,
                            active = state.powered == true,
                            size = 52.dp,
                            // Unknown power says nothing rather than "Off".
                            stateDescription = state.powered?.let { stringResource(if (it) R.string.toggle_state_on else R.string.toggle_state_off) },
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

                // The thumb reaches about half the screen (Erwann, 2026-10-24): the pad and the rail
                // stand low, one height side by side, the mode switch a glyph on the pad's corner,
                // and the free room goes above them to the card (placePad). Large takes the margins back.
                val large = layout.padSize == PadSize.LARGE
                val railGap = if (large) 8.dp else 12.dp
                val showing = cardShowing(state)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = if (large) 8.dp else 16.dp),
                ) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val place = placePad(maxHeight.value, (maxWidth - railGap - RAIL_WIDTH).value, layout.padSize, LocalDensity.current.fontScale)
                        val shown = showing?.takeIf { place.card.size != CardSize.HIDDEN }
                        SideEffect { cardShown = shown != null }
                        // The card centres in the room above the pad (Erwann, 2026-10-24: the band
                        // must read as meant, not as a void), full width less 16 dp margins.
                        if (shown != null) {
                            Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .fillMaxWidth()
                                    .height(place.cardRoom.dp)
                                    .padding(horizontal = if (large) 8.dp else 0.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                NowShowingCard(
                                    showing = shown,
                                    fit = place.card,
                                    tvName = state.devices.tv?.name.orEmpty(),
                                    appIcons = state.appIcons,
                                    // The TV alone; the bar plays on (CEC may still move it to the TV input).
                                    onWakeTv = { controller.power(PowerTarget.TV, on = true) },
                                    waking = state.waking,
                                    onOpen = if (cardOpensPlayback(shown)) {
                                        {
                                            haptics.click()
                                            playbackFor = shown
                                        }
                                    } else {
                                        null
                                    },
                                    onActions = cardActionsApp(shown)?.let {
                                        {
                                            haptics.longPress()
                                            actionsFor = shown
                                        }
                                    },
                                )
                            }
                        }
                        // The hand picks the pad's side once (Erwann, 2026-10-24: right by default): the pad
                        // under that thumb, the rail on the other side, the switch in the pad's corner facing
                        // the rail. TalkBack always reads the pad, then the rail, whichever side each stands on.
                        val padOnRight = layout.hand.padOnRight
                        val density = LocalDensity.current
                        val padLink = state.controlLink(Owner.TV)
                        val padReason = controlLinkReason(padLink, Owner.TV, state)
                        val pad = @Composable { m: Modifier ->
                            Pad(
                                down = padLink.down,
                                downReason = padReason,
                                mode = state.prefs.padMode,
                                onKey = { if (!tvAsleepAlone()) controller.key(it) },
                                onLongSelect = { if (!tvAsleepAlone()) controller.longPress(V2Key.DPAD_CENTER) },
                                onPlayPause = { if (!tvAsleepAlone()) controller.key(V2Key.MEDIA_PLAY_PAUSE) },
                                onVolumeStep = controller::volumeStep,
                                onSkip = { if (!tvAsleepAlone()) controller.key(skipKey(it)) },
                                onModeChange = controller::setPadMode,
                                volume = state.volume.takeIf { state.bar.connected },
                                volumeCap = state.volumeCap,
                                switchOnLeft = layout.hand.switchOnLeft,
                                modifier = m
                                    .semantics { traversalIndex = 0f }
                                    // A swipe from the pad's outer edge steps rather than going back.
                                    .systemGestureExclusion { coords ->
                                        val px = coords.size
                                        with(density) {
                                            padEdgeExclusion(
                                                width = px.width.toFloat(),
                                                height = px.height.toFloat(),
                                                edgeOnRight = padOnRight,
                                                strip = PAD_EXCLUSION_STRIP.dp.toPx(),
                                                tall = PAD_EXCLUSION_TALL.dp.toPx(),
                                            )
                                        }
                                    },
                            )
                        }
                        // The rail fills the row: the pad's height, top and foot, the same in either mode
                        // (Erwann, 2026-10-24: "la taille du volume devrait être toujours la même").
                        val rail = @Composable {
                            VolumeRail(
                                volume = state.volume,
                                cap = state.volumeCap,
                                muted = state.muted,
                                enabled = state.bar.connected,
                                onVolume = controller::setVolume,
                                onStep = controller::volumeStep,
                                onMute = controller::toggleMute,
                                modifier = Modifier.semantics { traversalIndex = 1f },
                            )
                        }
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(bottom = place.below.dp)
                                .fillMaxWidth()
                                .height(place.height.dp)
                                .semantics { isTraversalGroup = true },
                        ) {
                            if (padOnRight) {
                                rail()
                                Spacer(Modifier.width(railGap))
                                pad(Modifier.weight(1f).fillMaxHeight())
                            } else {
                                pad(Modifier.weight(1f).fillMaxHeight())
                                Spacer(Modifier.width(railGap))
                                rail()
                            }
                        }
                    }
                }

                val row = layout.rowOnScreen
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
                        // A bar toggle with no bar to answer is refused, not guessed: the Sound
                        // tile's bump, and the pin's dashed badge already says why.
                        when (pinToggle(slot.primary, state.bar, state.devices.bar != null)) {
                            PinToggle.Unreachable, PinToggle.NoBar -> haptics.bump()
                            else -> slot.primary?.let(controller::run)
                        }
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

    if (powerSheet) {
        PowerSheet(
            choices = powerChoices(
                hasTv = state.devices.tv != null,
                tvPowered = tvPowered(state),
                hasBar = state.devices.bar != null,
                barPowered = barPowered(state),
                powered = state.powered,
            ),
            onChoose = { choice ->
                haptics.select()
                controller.power(choice.target, choice.on)
                powerSheet = false
            },
            onDismiss = { powerSheet = false },
        )
    }

    val liveCard = cardShowing(state)
    playbackFor?.let { opened ->
        PlaybackSheet(
            showing = liveCard?.takeIf(::cardOpensPlayback) ?: opened,
            tvName = state.devices.tv?.name.orEmpty(),
            appIcons = state.appIcons,
            downReason = controlLinkReason(state.controlLink(Owner.TV), Owner.TV, state),
            onKey = { code ->
                haptics.click()
                controller.key(code)
            },
            onDismiss = { playbackFor = null },
        )
    }
    actionsFor?.let { opened ->
        val showing = liveCard?.takeIf { cardActionsApp(it) != null } ?: opened
        val app = cardActionsApp(showing)
        if (app != null) {
            val pinnedAt = pinnedIndex(state.dock, app.packageName)
            AppActionsSheet(
                showing = showing,
                app = app,
                tvName = state.devices.tv?.name.orEmpty(),
                appIcons = state.appIcons,
                actions = appActions(pinnedAt, state.pskSet),
                pinnedAt = pinnedAt,
                closeDown = state.links.rest != Link.ONLINE,
                onPin = {
                    haptics.click()
                    pinningNow = app
                },
                onUnpin = {
                    haptics.select()
                    pinnedAt?.let { controller.saveSlot(it, DockSlot()) }
                    actionsFor = null
                },
                onCloseApps = {
                    haptics.confirm()
                    controller.closeTvApps()
                    actionsFor = null
                },
                onDismiss = { actionsFor = null },
            )
        }
    }
    pinningNow?.let { app ->
        PinDialog(
            app = state.apps.firstOrNull { it.packageName == app.packageName } ?: KnownApp(app.packageName, app.name),
            state = state,
            onPick = { slot ->
                haptics.longPress()
                controller.assignDock(slot, DockAction.LaunchApp(app.packageName, app.name), state.dock.slot(slot).secondary)
                pinningNow = null
                actionsFor = null
            },
            onDismiss = { pinningNow = null },
        )
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
            onKey = {
                controller.key(it)
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

/** How far the input view rises when the TV opens it. */
private val INPUT_RISE = 24.dp

/** Back to the remote: a plain cross-fade. */
private const val INPUT_BACK_MS = 200

/** The “Son” handle and its one-line summary. */
private val SHEET_PEEK = 72.dp

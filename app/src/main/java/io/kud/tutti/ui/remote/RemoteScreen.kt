package io.kud.tutti.ui.remote

import io.kud.tutti.sleep.SleepTimers
import io.kud.tutti.sleep.gentleOffRow
import io.kud.tutti.protocol.sleep.SleepFade
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import io.kud.tutti.controller.TargetController
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.protocol.devices.KeyAvailability
import io.kud.tutti.protocol.hid.RemoteLink
import io.kud.tutti.platform.data.PadMode
import androidx.compose.runtime.mutableStateMapOf
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.platform.data.PadSize
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.controller.TvLinkRule
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.TonalRoundButton
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.rememberWaiting
import kotlinx.coroutines.delay

private sealed interface PickerTarget {
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
    target: TargetController,
    onOpenScenes: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditScene: (String) -> Unit,
    onAddBar: () -> Unit = {},
    /** A Connections step that leaves the remote (pairing, a setup step, Settings › Devices). */
    onConnectionStep: (ConnAction) -> Unit = { onOpenSettings() },
    editing: Boolean = false,
    onEditingChange: (Boolean) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val layout = state.prefs.layout
    BackHandler(enabled = editing) { onEditingChange(false) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var sheet2 by remember { mutableStateOf<RemoteSheet?>(null) }
    var powerSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The gentle switch-off timer: its end, and a clock that ticks only while one runs.
    val gentleTimer by SleepTimers.state.collectAsState()
    val gentleNow by produceState(System.currentTimeMillis(), gentleTimer) {
        while (gentleTimer != null) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000L)
        }
    }
    // The "?" in the top bar: every hidden gesture (discoverability rule 2).
    var gesturesSheet by remember { mutableStateOf(false) }
    // Iris's gesture audit (2026-10-25): the volume sheet (the rail's number), a scene pinned from its menu.
    var volumeSheet by remember { mutableStateOf(false) }
    var pinningScene by remember { mutableStateOf<String?>(null) }
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
    // The launcher's Keyboard shortcut: open the keyboard view once, whatever the TV shows.
    val keyboardAsked by controller.keyboardAsked.collectAsState()
    val recentApps by controller.recentApps.collectAsState()
    val castNowPlaying by controller.castNowPlaying.collectAsState()
    LaunchedEffect(keyboardAsked) {
        if (!keyboardAsked) return@LaunchedEffect
        if (!input.open) input = InputRules.keyboardKey(input)
        controller.keyboardAnswered()
    }
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
    // The target engine (TargetController): the chip, the sheet and where each key goes.
    val targets by target.state.collectAsState()
    var targetSheet by remember { mutableStateOf(false) }
    // The device pill opens « Connexions » (Erwann, 2026-10-25), no longer Settings directly.
    var connectionsSheet by remember { mutableStateOf(false) }
    // The remote is on screen: with the app in front, the HID connection may be held.
    DisposableEffect(target) {
        target.remoteShown(true)
        onDispose { target.remoteShown(false) }
    }
    val onComputer = targets.target?.kind == DeviceKind.COMPUTER
    // Keys go through the router whenever a key may leave the LAN: a computer target, or the TV over Bluetooth.
    val routed = onComputer || targets.link == RemoteLink.BLUETOOTH
    val computerUnavailable = stringResource(R.string.computer_unavailable)
    val notOverBluetooth = stringResource(R.string.target_not_over_bluetooth)
    val linkDown = stringResource(R.string.target_link_down)
    val computerMeanings = computerMeaningWords()
    fun keyDown(code: Int): Boolean = routed && targets.availability(code) != KeyAvailability.AVAILABLE
    fun keyMeaning(code: Int): String? = if (onComputer && !keyDown(code)) computerMeanings(code) else null

    // The pad on a Bluetooth target: trackpad, D-pad or gyro, remembered per target id for the
    // session; the TV on its LAN keeps the stored mode (Iris, 2026-10-25).
    val padTarget = targets.target?.id.orEmpty()
    val padModesByTarget = remember { mutableStateMapOf<String, PadMode>() }
    val padBefore = remember { mutableStateMapOf<String, PadMode>() }
    val rememberedMode = padModesByTarget[padTarget] ?: PadMode.TOUCHPAD
    val previousMode = padBefore[padTarget] ?: PadMode.TOUCHPAD
    val btMode = padModeShown(rememberedMode, bluetooth = routed, gyroPossible = targets.gyroPossible, previous = previousMode)
    val pointerPad = routed && btMode != PadMode.DPAD
    val gyroWhy = when (gyroBlock(targets.hasGyroscope, targets.gyroPossible)) {
        GyroBlock.NO_SENSOR -> stringResource(R.string.gyro_no_sensor)
        GyroBlock.LINK_DOWN -> stringResource(R.string.gyro_link_down)
        GyroBlock.NONE -> null
    }
    fun pickPadMode(mode: PadMode) {
        if (mode == PadMode.GYRO && gyroWhy != null) {
            haptics.bump()
            messages?.show(gyroWhy)
            return
        }
        if (rememberedMode != PadMode.GYRO) padBefore[padTarget] = rememberedMode
        padModesByTarget[padTarget] = mode
    }
    // The link dropped under the gyro pointer: back to the mode before it, said once.
    val droppedTo = stringResource(if (previousMode == PadMode.DPAD) R.string.pad_mode_dpad else R.string.pad_mode_touchpad)
    val gyroDropped = stringResource(R.string.gyro_dropped, droppedTo.lowercase())
    LaunchedEffect(rememberedMode == PadMode.GYRO && routed && !targets.gyroPossible) {
        if (rememberedMode == PadMode.GYRO && routed && !targets.gyroPossible) {
            padModesByTarget[padTarget] = previousMode
            messages?.show(gyroDropped)
        }
    }
    // Typing on a computer (or the TV over Bluetooth) goes through the target's layout; what it can't type is said.
    val untypable = stringResource(R.string.target_typing_unsupported)
    fun typeText(text: String) {
        if (!routed) {
            controller.text(text)
            return
        }
        val missed = target.text(text)
        if (missed.isNotEmpty()) messages?.show(String.format(untypable, missed.joinToString(" ")))
    }
    LaunchedEffect(input.open, routed) { if (input.open && routed) target.textReset() }

    // Home and Play/Pause wake a TV known asleep, then go (TvLinkRule.wakesTv); the rest keep standby's refusal.
    val wakingWords = stringResource(R.string.key_waking_tv)
    fun wakesTv(code: Int): Boolean = TvLinkRule.wakesTv(code, long = false, tvPowered = tvPowered(state), barAlone = alone != null)

    /** One key, wherever it belongs. A key that goes nowhere bumps and says why; glyphs never change. */
    fun sendKey(code: Int): Boolean {
        if (!routed) {
            if (wakesTv(code)) {
                messages?.show(wakingWords)
                controller.wakeThenKey(code)
            } else {
                controller.key(code)
            }
            return true
        }
        val why = when (target.key(code).availability) {
            KeyAvailability.AVAILABLE -> return true
            KeyAvailability.NOT_ON_COMPUTER -> computerUnavailable
            KeyAvailability.NOT_OVER_BLUETOOTH -> notOverBluetooth
            KeyAvailability.LINK_DOWN -> linkDown
        }
        haptics.bump()
        messages?.show(why)
        return false
    }
    /**
     * OK held on the pad ([OkHold]): down puts the key down where it belongs (the TV's remote key,
     * the TV's Bluetooth key, or one plain Enter on a computer: [Targeting.hold]); up lifts it on
     * whichever path holds it, so a route that changed mid-hold never leaves OK down.
     */
    fun holdSelect(down: Boolean) {
        if (!down) {
            target.releaseKey()
            controller.releaseKey()
            return
        }
        if (!routed) {
            controller.holdKey(V2Key.DPAD_CENTER)
            return
        }
        val why = when (target.holdKey(V2Key.DPAD_CENTER).availability) {
            KeyAvailability.AVAILABLE -> return
            KeyAvailability.NOT_ON_COMPUTER -> computerUnavailable
            KeyAvailability.NOT_OVER_BLUETOOTH -> notOverBluetooth
            KeyAvailability.LINK_DOWN -> linkDown
        }
        haptics.bump()
        messages?.show(why)
    }
    // A key that opens a menu on the TV closes its sheet, so the pad is in view to drive that
    // menu (keyClosesSheet); TalkBack hears where to go next, since the sheet vanished under it.
    val tvMenuSay = stringResource(R.string.sheet_key_tv_menu)
    fun sheetKey(code: Int, close: () -> Unit) {
        val sent = sendKey(code)
        if (!keyClosesSheet(code)) return
        close()
        @Suppress("DEPRECATION")
        if (sent) view.announceForAccessibility(tvMenuSay)
    }
    val standbyWords = stringResource(R.string.key_tv_standby)
    val wakeWords = stringResource(R.string.card_wake_tv_short)
    fun tvAsleepAlone(code: Int? = null): Boolean {
        if (alone?.tvAsleep != true) return false
        if (code != null && wakesTv(code)) return false
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
            // Recent apps live at the top of the Apps sheet (Google TV has no switcher of its own).
            RowDefault.RECENT_APPS -> {
                sheet2 = RemoteSheet.APPS
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
                    onText = ::typeText,
                    onEnter = {
                        input = InputRules.enter(input, SystemClock.uptimeMillis())
                        sendKey(V2Key.ENTER)
                    },
                    onKey = ::sendKey,
                    onSelectHold = ::holdSelect,
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
                // One bar at every width (TopBar.kt; Erwann, 2026-10-25): the chip, the "⋮" against it,
                // a clear gap, then Power at the far edge.
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The chip's "On the TV" line steps aside while the card below says it.
                    SystemChip(state, onClick = { connectionsSheet = true }, modifier = Modifier.weight(1f), showNow = !cardShown)
                    val rows = targetRows(targets)
                    if (linkIndicatorShown(targets.link)) {
                        Spacer(Modifier.width(4.dp))
                        LinkIndicator(targets.link)
                    }
                    if (targetChipShown(rows)) {
                        currentTarget(rows)?.let { current ->
                            Spacer(Modifier.width(4.dp))
                            TargetChip(current, onOpen = { targetSheet = true })
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TopBarOverflow(
                        onScenes = onOpenScenes,
                        onEditDock = { onEditingChange(true) },
                        onSettings = onOpenSettings,
                        onGestures = { gesturesSheet = true },
                    )
                    Spacer(Modifier.width(POWER_GAP_DP.dp))
                    // Waking a sleeping TV takes seconds: a turning ring says so (not a colour), and the label follows.
                    // The ring keeps the loading rules: not before 150 ms, then at least 400 ms; held still when motion is reduced.
                    val wakingRing = rememberWaiting(state.waking)
                    Box(contentAlignment = Alignment.Center) {
                        TonalRoundButton(
                            icon = R.drawable.ic_power,
                            contentDescription = stringResource(if (state.waking) R.string.power_waking else R.string.action_power),
                            onClick = { controller.power() },
                            // TV only, soundbar only or both (Erwann, 2026-10-24), and the TV's sleep timer:
                            // with a TV set up, alone or with the bar (Iris's gesture audit, 2026-10-25).
                            onLongClick = { powerSheet = true }.takeIf { state.devices.tv != null },
                            onLongClickLabel = stringResource(if (state.devices.bar != null) R.string.power_long_label else R.string.power_long_label_tv),
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
                        undoSceneId = state.sceneUndo?.sceneId,
                        onUndo = {
                            haptics.confirm()
                            controller.undoLastScene()
                        },
                        onPin = { pinningScene = it },
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
                        val place = placePad(maxHeight.value, (maxWidth - railGap - RAIL_WIDTH).value, layout.padSize, LocalDensity.current.fontScale, pointer = pointerPad)
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
                                    cast = castNowPlaying,
                                    timer = gentleTimer?.let { timer ->
                                        stringResource(
                                            R.string.gentle_off_card,
                                            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(timer.endsAt)),
                                            SleepFade.minutesLeft(gentleNow, timer.endsAt),
                                        )
                                    },
                                    onOpen = when (cardTap(shown, targets.computerOnInput != null)) {
                                        CardTap.PLAYBACK -> {
                                            {
                                                haptics.click()
                                                playbackFor = shown
                                            }
                                        }
                                        CardTap.CONTROL_COMPUTER -> {
                                            {
                                                haptics.click()
                                                targets.computerOnInput?.let { target.setTarget(it.id) }
                                            }
                                        }
                                        CardTap.NONE -> null
                                    },
                                    controls = targets.computerOnInput?.name?.takeIf { cardTap(shown, true) == CardTap.CONTROL_COMPUTER },
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
                        val pointer = @Composable { m: Modifier ->
                            PointerPad(
                                mode = btMode,
                                onMove = { target.move(it) },
                                onClick = target::click,
                                onNextMode = { pickPadMode(nextPadMode(btMode, bluetooth = true)) },
                                onPickMode = ::pickPadMode,
                                modifier = m.semantics { traversalIndex = 0f },
                                stripOnLeft = !padOnRight,
                                down = targets.link == RemoteLink.NONE,
                                downReason = linkDown,
                                gyroReason = gyroWhy,
                            )
                        }
                        val pad = @Composable { m: Modifier ->
                            if (pointerPad) pointer(m) else Pad(
                                down = if (routed) targets.link == RemoteLink.NONE else padLink.down,
                                downReason = if (routed) linkDown else padReason,
                                mode = if (routed) PadMode.DPAD else state.prefs.padMode,
                                onKey = { if (routed || !tvAsleepAlone(it)) sendKey(it) },
                                onSelectHold = { down -> if (!down || routed || !tvAsleepAlone()) holdSelect(down) },
                                onPlayPause = { if (routed || !tvAsleepAlone(V2Key.MEDIA_PLAY_PAUSE)) sendKey(V2Key.MEDIA_PLAY_PAUSE) },
                                onVolumeStep = controller::volumeStep,
                                onSkip = { if (routed || !tvAsleepAlone()) sendKey(skipKey(it)) },
                                onModeChange = if (routed) {
                                    { pickPadMode(it) }
                                } else {
                                    controller::setPadMode
                                },
                                volume = state.volume.takeIf { state.bar.connected },
                                volumeCap = state.volumeCap,
                                volumeLandmarks = state.volumeLandmarks,
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
                                guardLine = state.volumeGuardLine,
                                landmarks = state.volumeLandmarks,
                                onOpenLevel = {
                                    haptics.click()
                                    volumeSheet = true
                                },
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
                                onKey = ::sendKey,
                                keyDown = ::keyDown,
                                keyDownReason = computerUnavailable,
                                keyMeaning = ::keyMeaning,
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
                            else -> when (val action = slot.primary) {
                                // A pinned key follows the target like the remote's own keys.
                                is DockAction.Key -> if (routed && !action.long) sendKey(action.keyCode) else controller.run(action)
                                null -> Unit
                                else -> controller.run(action)
                            }
                        }
                    },
                    onEdit = { index ->
                        picker = PickerTarget.Edit(index)
                    },
                    // An empty dock's one way in: the same editor as ⋮ › Edit dock and Settings.
                    onCustomise = { onEditingChange(true) },
                    onRunSecondary = { _, action ->
                        haptics.click()
                        controller.run(action)
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (connectionsSheet) {
        // A pill that says something is wrong lands on that device's line (problemLine).
        val connDevices = connections(connInput(state, targets))
        ConnectionsSheet(
            devices = connDevices,
            focus = problemLine(connDevices),
            onAction = { device, action ->
                connectionsSheet = false
                when (action) {
                    ConnAction.WAKE_TV -> controller.power(PowerTarget.TV, on = true)
                    ConnAction.CONTROL -> target.setTarget(device.id)
                    else -> onConnectionStep(action)
                }
            },
            onOpenSettings = {
                connectionsSheet = false
                onOpenSettings()
            },
            onDismiss = { connectionsSheet = false },
        )
    }

    if (targetSheet) {
        TargetSheet(
            rows = targetRows(targets, tvInput = state.tv.input, tvPowered = tvPowered(state), ipControl = state.ipControlSet),
            onPick = { row ->
                target.setTarget(row.id)
                targetSheet = false
            },
            onShowOnTv = { row ->
                haptics.select()
                target.showOnTv(row.id)
                targetSheet = false
            },
            onDismiss = { targetSheet = false },
        )
    }

    if (powerSheet) {
        // Asked again on every opening: the system page that grants exact alarms is one tap away.
        val exactAllowed = remember(powerSheet) { SleepTimers.exactAllowed(context) }
        // The sleep key comes from the TV's IRCC list, which only TV settings load: ask once.
        LaunchedEffect(Unit) { controller.loadSleepTimer() }
        val powerPinner = rememberDockPinner(state, controller)
        val pinScreenOff = stringResource(R.string.screen_off_title)
        val pinScreenBack = stringResource(R.string.screen_off_back)
        PowerSheet(
            screen = screenOffRow(
                ipControl = state.ipControlSet,
                supported = state.tv.settings.powerSavingSupported,
                loaded = state.tv.settings.loaded,
                powerSaving = state.tv.settings.powerSaving,
                restOnline = state.links.rest == Link.ONLINE,
                tvPowered = tvPowered(state),
                downReason = stringResource(R.string.power_sleep_down),
                standbyReason = stringResource(R.string.power_sleep_standby),
            ),
            onScreen = { off ->
                haptics.click()
                controller.setPictureOff(off)
            },
            onPinScreen = { off -> powerPinner.request(DockAction.PictureOff(off), if (off) pinScreenOff else pinScreenBack) },
            sleep = sleepTimerRow(
                sleepCode = state.tv.settings.sleepCode,
                restOnline = state.links.rest == Link.ONLINE,
                tvPowered = tvPowered(state),
                downReason = stringResource(R.string.power_sleep_down),
                standbyReason = stringResource(R.string.power_sleep_standby),
            ),
            onSleep = {
                haptics.click()
                controller.pressSleepTimer()
            },
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
            gentle = gentleOffRow(
                hasDevice = state.devices.tv != null || state.devices.bar != null,
                powered = state.powered,
                running = gentleTimer,
                offReason = stringResource(R.string.gentle_off_all_off),
            ),
            now = gentleNow,
            exactAllowed = exactAllowed,
            onGentle = { minutes ->
                haptics.select()
                SleepTimers.start(context, minutes)
                powerSheet = false
            },
            onGentleCancel = {
                haptics.click()
                SleepTimers.cancel(context)
            },
            onAllowExact = SleepTimers.exactSettings(context)?.let { page ->
                {
                    runCatching { context.startActivity(page) }
                    Unit
                }
            },
        )
    }

    if (volumeSheet) {
        VolumeSheet(
            state = state,
            onSet = { controller.setVolume(it) },
            onSavePreset = controller::saveVolumePreset,
            onCap = controller::setTonightCap,
            onClearCap = controller::clearTonightCap,
            onDismiss = { volumeSheet = false },
        )
    }
    // The high-volume guard's question, over the remote or the volume sheet alike.
    state.volumeAsk?.let { ask ->
        VolumeGuardDialog(
            ask = ask,
            onGo = {
                haptics.confirm()
                controller.confirmLoud()
            },
            onStay = {
                haptics.select()
                controller.stayBelowGuard()
            },
        )
    }
    pinningScene?.let { id ->
        val scene = state.scenes.firstOrNull { it.scene.id == id }?.scene
        if (scene != null) {
            ScenePinDialog(
                sceneName = scene.name,
                state = state,
                onPick = { slot ->
                    haptics.longPress()
                    controller.assignDock(slot, DockAction.RunScene(id), state.dock.slot(slot).secondary)
                    pinningScene = null
                },
                onDismiss = { pinningScene = null },
            )
        }
    }

    val liveCard = cardShowing(state)
    val keysPinner = rememberDockPinner(state, controller)
    playbackFor?.let { opened ->
        PlaybackSheet(
            showing = liveCard?.takeIf(::cardOpensPlayback) ?: opened,
            tvName = state.devices.tv?.name.orEmpty(),
            appIcons = state.appIcons,
            downReason = controlLinkReason(state.controlLink(Owner.TV), Owner.TV, state),
            onKey = { code ->
                haptics.click()
                sheetKey(code) { playbackFor = null }
            },
            onDismiss = { playbackFor = null },
            barToggles = if (state.links.bar == Link.ABSENT) null else {
                {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (feature in PLAYBACK_BAR_TOGGLES) {
                            SoundTile(
                                state = state,
                                feature = feature,
                                onTap = {
                                    haptics.select()
                                    controller.toggleBar(feature)
                                },
                                onPin = { name ->
                                    haptics.longPress()
                                    keysPinner.request(DockAction.ToggleBar(feature.key), name)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            cast = castNowPlaying,
            onScrub = { code -> sendKey(code) },
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
                actions = appActions(pinnedAt, state.ipControlSet),
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
            recents = recentApps,
            inputs = tvInputsRow(
                ipControl = state.ipControlSet,
                inputs = state.tv.inputs,
                currentUri = state.tv.input,
                restOnline = state.links.rest == Link.ONLINE,
                tvPowered = tvPowered(state),
                downReason = stringResource(R.string.power_sleep_down),
                standbyReason = stringResource(R.string.power_sleep_standby),
            ),
            // Picked like an app: it switches and the sheet goes, the card showing the input.
            onInput = { uri ->
                haptics.select()
                controller.switchInput(uri)
                sheet2 = null
            },
            onPinInput = { uri, name ->
                haptics.longPress()
                keysPinner.request(DockAction.Input(uri, name), name)
            },
        )
        RemoteSheet.KEYS -> MoreKeysSheet(
            onKey = { code -> sheetKey(code) { sheet2 = null } },
            onDismiss = { sheet2 = null },
            pin = KeyPin(enabled = !state.controlLink(Owner.TV).down) { code, name -> keysPinner.request(DockAction.Key(code), name) },
            downReason = controlLinkReason(state.controlLink(Owner.TV), Owner.TV, state),
        )
        null -> Unit
    }

    if (gesturesSheet) GesturesSheet(onDismiss = { gesturesSheet = false })

    when (val target = picker) {
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

/** The bar's two film-night switches, repeated in the playback sheet (benchmark item 5). */
private val PLAYBACK_BAR_TOGGLES = listOf(TheatreFeature.VOICE, TheatreFeature.NIGHT)

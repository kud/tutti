package io.kud.tutti.ui

import io.kud.tutti.ui.remote.InputRules
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.KeyOutcome
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiEvent
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.diagnostic.Diagnostics
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.protocol.apps.AppsScreenVerdict
import io.kud.tutti.protocol.net.WakeAttempt
import io.kud.tutti.ui.about.AboutScreen
import io.kud.tutti.ui.common.LocalDeveloperMode
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.LocalProblems
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.Messages
import io.kud.tutti.ui.common.Problems
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.onboarding.OnboardingScreen
import io.kud.tutti.ui.remote.RemoteScreen
import io.kud.tutti.ui.scenes.SceneEditorScreen
import io.kud.tutti.ui.scenes.ScenesScreen
import io.kud.tutti.ui.settings.BarSettingsScreen
import io.kud.tutti.ui.settings.DiagnosticScreen
import io.kud.tutti.ui.settings.SettingsCategory
import io.kud.tutti.ui.settings.SettingsMenu
import io.kud.tutti.ui.settings.SettingsPageScreen
import io.kud.tutti.ui.settings.SettingsScreen
import io.kud.tutti.ui.settings.TechnicalScreen
import io.kud.tutti.ui.settings.TvPage
import io.kud.tutti.ui.settings.TvSettingsPageScreen
import io.kud.tutti.ui.settings.TvSettingsScreen
import io.kud.tutti.ui.settings.UnlockScreen
import io.kud.tutti.ui.remote.ConnAction

/** Three places, opening on the remote; Settings › TV › Picture › Advanced picture is the deepest. */
sealed interface Screen {
    data object Remote : Screen

    data object Scenes : Screen

    /** Null id: a new scene. */
    data class SceneEditor(val sceneId: String?) : Screen

    /** Settings' top level: one row per category. */
    data object Settings : Screen

    /** A category with its own screen under Settings ([SettingsMenu.pages]): Devices, Remote & dock, Display & feedback, Advanced. */
    data class SettingsPage(val category: SettingsCategory) : Screen

    /** The TV hub: Picture, Sound, Inputs, Power, About this TV. */
    data object TvSettings : Screen

    /** One screen under the TV hub. */
    data class TvSettingsPage(val page: TvPage) : Screen

    data object BarSettings : Screen

    data object Diagnostic : Screen

    /** The onboarding; [barOnly] opens it on the bar step alone, for a bar added after the TV. */
    data class Onboarding(val barOnly: Boolean = false, val cancellable: Boolean = false) : Screen

    data object About : Screen

    /** What tutti can do; [focus] opens one step (from a nudge). */
    data class Unlock(val focus: io.kud.tutti.ui.settings.Unlock? = null) : Screen

    data object Technical : Screen
}

/**
 * A [Screen] as a string, so the stack outlives the activity being recreated: choosing a theme
 * recreates it, and a plain `remember` sent Erwann back to the remote from Settings.
 */
internal object ScreenKey {
    fun of(screen: Screen): String = when (screen) {
        Screen.Remote -> "remote"
        Screen.Scenes -> "scenes"
        is Screen.SceneEditor -> "scene:" + screen.sceneId.orEmpty()
        Screen.Settings -> "settings"
        is Screen.SettingsPage -> "settings:" + screen.category.name
        Screen.TvSettings -> "tv-settings"
        is Screen.TvSettingsPage -> "tv-settings:" + screen.page.name
        Screen.BarSettings -> "bar-settings"
        Screen.Diagnostic -> "diagnostic"
        is Screen.Onboarding -> "onboarding:${screen.barOnly}:${screen.cancellable}"
        Screen.About -> "about"
        is Screen.Unlock -> "unlock:" + screen.focus?.name.orEmpty()
        Screen.Technical -> "technical"
    }

    /** Null for a key this version does not know. */
    fun screen(key: String): Screen? = when {
        key == "remote" -> Screen.Remote
        key == "scenes" -> Screen.Scenes
        key.startsWith("scene:") -> Screen.SceneEditor(key.removePrefix("scene:").ifEmpty { null })
        key == "settings" -> Screen.Settings
        key.startsWith("settings:") -> SettingsMenu.pages.firstOrNull { it.name == key.removePrefix("settings:") }?.let { Screen.SettingsPage(it) }
        key == "tv-settings" -> Screen.TvSettings
        key.startsWith("tv-settings:") -> TvPage.entries.firstOrNull { it.name == key.removePrefix("tv-settings:") }?.let { Screen.TvSettingsPage(it) }
        key == "bar-settings" -> Screen.BarSettings
        key == "diagnostic" -> Screen.Diagnostic
        key.startsWith("onboarding:") -> key.split(':').let { Screen.Onboarding(it.getOrNull(1) == "true", it.getOrNull(2) == "true") }
        key == "about" -> Screen.About
        key.startsWith("unlock:") -> Screen.Unlock(io.kud.tutti.ui.settings.Unlock.entries.firstOrNull { it.name == key.removePrefix("unlock:") })
        key == "technical" -> Screen.Technical
        else -> null
    }

    /** The stack back from its keys; always starts on the remote. */
    fun stack(keys: List<String>): List<Screen> {
        val screens = keys.mapNotNull(::screen)
        return if (screens.firstOrNull() == Screen.Remote) screens else listOf(Screen.Remote) + screens.filter { it != Screen.Remote }
    }
}

private val StackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.map(ScreenKey::of) },
    restore = { keys -> mutableStateListOf(*ScreenKey.stack(keys).toTypedArray()) },
)

@Composable
fun TuttiApp(
    controller: TuttiController,
    discovery: TvDiscovery,
    diagnostics: Diagnostics,
    hid: HidLink,
    state: TuttiUiState,
    target: io.kud.tutti.controller.TargetController,
) {
    val stack = rememberSaveable(saver = StackSaver) { mutableStateListOf<Screen>(Screen.Remote) }
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

    /** Back to the remote, whatever is open. */
    fun home() {
        forward = false
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun pushAll(screens: List<Screen>) {
        forward = true
        stack.addAll(screens)
    }

    fun pop() {
        if (stack.size > 1) {
            forward = false
            stack.removeAt(stack.lastIndex)
        }
    }

    val messages = LocalMessages.current ?: remember { Messages() }
    val problems = remember(messages, context) { Problems(messages, context) }
    problems.developer = state.prefs.developerMode

    LaunchedEffect(controller, haptics, problems) {
        controller.events.collect { event ->
            when (event) {
                is TuttiEvent.SceneFinished -> {
                    if (event.failedSteps == 0) haptics.doublePulse() else haptics.reject()
                    if (event.detail != null) {
                        problems.report(context.getString(R.string.scene_failed_steps), event.detail)
                    } else if (event.undoable) {
                        // One step back in one gesture (Iris's gesture audit): the message carries Undo,
                        // and the pill's menu keeps it until the undo goes stale.
                        val name = controller.state.value.scenes.firstOrNull { it.scene.id == event.sceneId }?.scene?.name
                        if (name != null) {
                            messages.show(
                                context.getString(R.string.scene_applied, name),
                                actionLabel = context.getString(R.string.scene_undo),
                                onAction = { controller.undoLastScene() },
                            )
                        }
                    }
                }
                is TuttiEvent.SceneUndone -> {
                    if (event.failedSteps == 0) haptics.confirm() else haptics.reject()
                    if (event.detail != null) {
                        problems.report(context.getString(R.string.scene_undo_failed), event.detail)
                    } else {
                        messages.show(event.sceneName?.let { context.getString(R.string.scene_undone, it) } ?: context.getString(R.string.scene_undone_generic))
                    }
                }
                TuttiEvent.VolumeLimit -> haptics.bump()
                is TuttiEvent.ActionFailed -> problems.report(context.getString(if (event.keyRefused) R.string.error_key_refused else R.string.error_action_failed), event.detail)
                is TuttiEvent.KeyNotSent -> {
                    // The press's own click already played: a held key gets a soft bump, a dropped
                    // one the reject. The words repeat only once the last ones have gone.
                    if (event.outcome == KeyOutcome.HELD) haptics.bump() else haptics.reject()
                    val text = context.getString(
                        when (event.outcome) {
                            KeyOutcome.HELD -> R.string.key_held
                            KeyOutcome.DROPPED_WAITING -> R.string.key_dropped_waiting
                            KeyOutcome.DROPPED_DOWN -> R.string.key_dropped_down
                            KeyOutcome.DROPPED_PAIRING -> R.string.key_dropped_pairing
                            KeyOutcome.EXPIRED -> R.string.key_expired
                        },
                    )
                    if (messages.current?.text != text) messages.show(text)
                }
                is TuttiEvent.AppsScreen -> {
                    // The tap's own click already played. Opened shows on the TV and needs no words;
                    // anything else says what the remote link could, and could not, see.
                    val text = when (event.verdict) {
                        AppsScreenVerdict.OPENED -> null
                        AppsScreenVerdict.SENT -> R.string.apps_screen_unconfirmed
                        AppsScreenVerdict.FAILED -> R.string.apps_screen_failed
                        AppsScreenVerdict.NOT_SENT -> R.string.key_dropped_waiting
                    }?.let(context::getString)
                    if (event.verdict == AppsScreenVerdict.FAILED || event.verdict == AppsScreenVerdict.NOT_SENT) haptics.reject()
                    if (text != null && messages.current?.text != text) messages.show(text)
                }
                is TuttiEvent.AppLaunchHeld -> {
                    // The tap's own click already played: a soft bump, as for a held key.
                    haptics.bump()
                    messages.show(context.getString(R.string.apps_launch_held, event.appName))
                }
                is TuttiEvent.AppNotOpened -> {
                    // The optimistic app has already rolled back on the card; say so quietly.
                    haptics.reject()
                    val text = context.getString(if (event.expired) R.string.apps_launch_expired else R.string.apps_launch_not_opened, event.appName)
                    if (event.detail != null && problems.developer) problems.report(text, event.detail) else messages.show(text)
                }
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
                    help = {
                        push(
                            Screen.Unlock(
                                when (event.cause) {
                                    WakeAttempt.Cause.TV_NO_MAC -> io.kud.tutti.ui.settings.Unlock.IP_CONTROL
                                    WakeAttempt.Cause.TV_NOT_FOUND, WakeAttempt.Cause.TV_ASLEEP -> io.kud.tutti.ui.settings.Unlock.REMOTE_START
                                    WakeAttempt.Cause.BAR_SILENT -> io.kud.tutti.ui.settings.Unlock.SOUNDBAR
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    // The TV's settings load once on the way into the hub, and again when IP control comes back while
    // any TV screen is open: moving between the hub and its screens asks the TV nothing new.
    val inTvSettings = stack.any { it == Screen.TvSettings || it is Screen.TvSettingsPage }
    val rest = state.links.rest
    LaunchedEffect(inTvSettings, rest) { if (inTvSettings && rest == io.kud.tutti.controller.Link.ONLINE) controller.loadTvSettings() }

    // The keyboard comes to him: a field the TV opens while another screen is in front is offered
    // with "Type" (InputRules.offerAway); the Keyboard tile and shortcut ask too. Either way the
    // remote comes forward and opens its keyboard view.
    val keyboardAsked by controller.keyboardAsked.collectAsState()
    LaunchedEffect(keyboardAsked) { if (keyboardAsked && stack.last() != Screen.Remote) home() }
    var seenShows by remember { mutableIntStateOf(state.remote.imeShows) }
    val fieldWords = stringResource(R.string.keyboard_field_open)
    val typeWords = stringResource(R.string.keyboard_field_type)
    LaunchedEffect(state.remote.ime != null, state.remote.imeShows) {
        val shows = state.remote.imeShows
        if (InputRules.offerAway(seenShows, state.remote.ime != null, shows, onRemote = stack.last() == Screen.Remote)) {
            messages.show(fieldWords, actionLabel = typeWords, onAction = { controller.askKeyboard() })
        }
        seenShows = shows
    }

    LaunchedEffect(state.loaded) {
        if (state.loaded && state.devices.isEmpty && !state.prefs.onboarded && stack.none { it is Screen.Onboarding }) push(Screen.Onboarding())
    }

    if (!state.loaded) {
        // Dark from the first frame; no flash of onboarding before the stores load.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    CompositionLocalProvider(LocalProblems provides problems, LocalDeveloperMode provides state.prefs.developerMode) {
        val reducedMotion = LocalReducedMotion.current
        val slide = with(LocalDensity.current) { SCREEN_SLIDE.roundToPx() }
        // Sideways, a camera cutout sits at one side: every screen keeps clear of it rather than the app
        // locking portrait (Iris, 2026-10-25), so auto-rotate never hides a control. Upright, the status
        // bar already covers it and this pads nothing.
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
                AnimatedContent(
                    targetState = stack.last(),
                    transitionSpec = { screenTransition(forward, reducedMotion, slide) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Remote -> RemoteScreen(
                            state = state,
                            controller = controller,
                            target = target,
                            onOpenScenes = { push(Screen.Scenes) },
                            onOpenSettings = { push(Screen.Settings) },
                            onEditScene = { push(Screen.SceneEditor(it)) },
                            onAddBar = { push(Screen.Onboarding(barOnly = true, cancellable = true)) },
                            onConnectionStep = { step ->
                                // IP control's home is its checklist step: the TV's menu path and tutti's
                                // own field for the key; a computer's pairing is its row in Settings › Devices.
                                if (step == ConnAction.PAIR_TV) controller.resetPairing()
                                pushAll(SettingsRoutes.connectionStep(step))
                            },
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
                            onBack = ::pop,
                            onOpen = { push(SettingsRoutes.category(it)) },
                        )
                        is Screen.SettingsPage -> SettingsPageScreen(
                            category = screen.category,
                            state = state,
                            controller = controller,
                            onBack = ::pop,
                            onOpenDiagnostic = { push(Screen.Diagnostic) },
                            hid = hid,
                            onEditLayout = {
                                editingLayout = true
                                home()
                            },
                            onPair = {
                                controller.resetPairing()
                                push(Screen.Onboarding(cancellable = true))
                            },
                            onAddBar = { push(Screen.Onboarding(barOnly = true, cancellable = true)) },
                            onOpenTechnical = { push(Screen.Technical) },
                        )
                        Screen.TvSettings -> TvSettingsScreen(
                            state = state,
                            controller = controller,
                            onBack = ::pop,
                            onOpenPage = { push(Screen.TvSettingsPage(it)) },
                            onOpenUnlock = { push(Screen.Unlock(io.kud.tutti.ui.settings.Unlock.IP_CONTROL)) },
                        )
                        is Screen.TvSettingsPage -> TvSettingsPageScreen(
                            page = screen.page,
                            state = state,
                            controller = controller,
                            onBack = ::pop,
                            onOpenPage = { push(Screen.TvSettingsPage(it)) },
                            onOpenUnlock = { push(Screen.Unlock(io.kud.tutti.ui.settings.Unlock.IP_CONTROL)) },
                        )
                        is Screen.Unlock -> UnlockScreen(
                            state = state,
                            controller = controller,
                            focus = screen.focus,
                            onBack = ::pop,
                            onPair = { push(Screen.Onboarding(cancellable = true)) },
                            onAddBar = { push(Screen.Onboarding(barOnly = true, cancellable = true)) },
                        )
                        Screen.Technical -> TechnicalScreen(state = state, onOpen = controller::loadTechnical, onBack = ::pop)
                        Screen.BarSettings -> BarSettingsScreen(state = state, controller = controller, onBack = ::pop)
                        Screen.Diagnostic -> DiagnosticScreen(diagnostics = diagnostics, onBack = ::pop)
                        is Screen.Onboarding -> OnboardingScreen(
                            state = state,
                            controller = controller,
                            discovery = discovery,
                            barOnly = screen.barOnly,
                            // The bar alone lands back on the remote, where it now plays (Erwann, 2026-10-24).
                            onDone = if (screen.barOnly) ::home else ::pop,
                            onCancel = if (screen.cancellable) ::pop else null,
                        )
                        Screen.About -> AboutScreen(onBack = ::pop)
                    }
                }
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

package io.kud.tutti.ui.onboarding

import io.kud.tutti.ui.intro.LaunchMark
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.PairingError
import io.kud.tutti.controller.PairingUi
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.discovery.DiscoveredTv
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.protocol.atv.PairingCode
import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.ProblemNote
import io.kud.tutti.ui.common.rememberFeedback
import io.kud.tutti.ui.common.rememberPressSource
import io.kud.tutti.ui.motion.ActionPhase
import io.kud.tutti.ui.motion.Motion
import io.kud.tutti.ui.motion.ProgressButton
import io.kud.tutti.ui.settings.linkLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The intro (Iris's motion spec, "Intro, écran par écran"): Welcome, then four numbered steps,
 * TV (search), Code, Bar, Ready. The TV's PSK is no longer asked here: Ready points to Settings.
 * The flow follows the controller's pairing state; the step bar and the screen slide 24 dp with a
 * crossfade on each change (a [Motion.SHORT] fade with reduced motion). Welcome shows only on a
 * first setup; from Settings' "Pair" it opens on the search. [barOnly] (Settings' "Add the
 * soundbar", the Son sheet's) is the Bar step alone, no step bar: done or "Later", it leaves.
 * [onCancel], given whenever the flow is opened from an app already set up (Erwann, 2026-10-24:
 * "il faudrait pas un bouton annuler ou retour ici ?"), puts a Back at the top left and on the
 * system back: it drops any pairing in progress and leaves, changing nothing. The first launch
 * has none: there is nowhere to go back to.
 */
@Composable
fun OnboardingScreen(
    state: TuttiUiState,
    controller: TuttiController,
    discovery: TvDiscovery,
    onDone: () -> Unit,
    barOnly: Boolean = false,
    onCancel: (() -> Unit)? = null,
) {
    val reduced = LocalReducedMotion.current
    val discovered by remember(discovery) { discovery.discover() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val first = when {
        barOnly -> IntroStep.BAR
        state.prefs.onboarded || !state.devices.isEmpty -> IntroStep.TV
        else -> IntroStep.WELCOME
    }
    var step by rememberSaveable { mutableStateOf(first) }
    var tvHost by rememberSaveable { mutableStateOf(state.devices.tv?.host.orEmpty()) }
    var pairPressed by remember { mutableStateOf(false) }
    var codeSent by remember { mutableStateOf(false) }
    val pairing = state.pairing

    val pair = { host: String ->
        tvHost = host
        pairPressed = true
        codeSent = false
        controller.pairTv(host)
    }

    // The steps follow the pairing: a code on the TV opens Code; paired, the tick shows, then Bar.
    LaunchedEffect(pairing, pairPressed) {
        when {
            pairing is PairingUi.AwaitingCode && step == IntroStep.TV -> step = IntroStep.CODE
            pairing == PairingUi.Done && pairPressed && step == IntroStep.CODE -> {
                delay(Motion.HERO.toLong())
                step = IntroStep.BAR
            }
        }
    }
    val cancel = {
        if (state.pairing != PairingUi.Idle && state.pairing != PairingUi.Done) controller.resetPairing()
        onCancel?.invoke()
        Unit
    }
    // Declared first, so the step-back below (Code to TV) still wins where it applies.
    BackHandler(enabled = onCancel != null) { cancel() }
    BackHandler(enabled = step == IntroStep.CODE || (step == IntroStep.TV && first == IntroStep.WELCOME)) {
        if (step == IntroStep.CODE) {
            controller.resetPairing()
            pairPressed = false
            step = IntroStep.TV
        } else {
            step = IntroStep.WELCOME
        }
    }

    val segments = listOf(
        stringResource(R.string.intro_seg_tv),
        stringResource(R.string.intro_seg_code),
        stringResource(R.string.intro_seg_bar),
        stringResource(R.string.intro_seg_ready),
    )
    val shift = with(LocalDensity.current) { STEP_SHIFT.roundToPx() }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            if (onCancel != null) {
                // 48 dp, pulled into the margin so the arrow lines up with the content's edge.
                IconButton(onClick = cancel, modifier = Modifier.offset(x = (-12).dp, y = (-8).dp).size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.intro_leave))
                }
            }
            AnimatedVisibility(step.segment >= 0 && !barOnly, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.exit())) {
                StepBar(current = step.segment.coerceAtLeast(0), labels = segments, modifier = Modifier.padding(bottom = 24.dp))
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                    } else {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (slideInHorizontally(tween(Motion.LONG, easing = Motion.Emphasized)) { dir * shift } + fadeIn(tween(Motion.LONG))) togetherWith
                            (slideOutHorizontally(tween(Motion.SHORT, easing = Motion.Exit)) { -dir * shift } + fadeOut(Motion.exit()))
                    }
                },
                modifier = Modifier.weight(1f),
                label = "step",
            ) { now ->
                when (now) {
                    IntroStep.WELCOME -> WelcomeStep(onStart = { step = IntroStep.TV })
                    IntroStep.TV -> SearchStep(
                        found = discovered,
                        initialHost = tvHost,
                        phaseFor = { host ->
                            if (pairPressed && host == tvHost) pairStep(pairing, codeSent = codeSent, pressed = true).phase else ActionPhase.IDLE
                        },
                        // Found by mDNS yet silent: the TV is advertised (by its Wi-Fi chip in standby, or a cache)
                        // while it sleeps, so the address is right and the fix is to switch it on.
                        failure = (pairing as? PairingUi.Failed)?.let { failed ->
                            if (failed.reason == PairingError.UNREACHABLE && discovered.any { it.host == tvHost }) {
                                stringResource(R.string.pairing_unreachable_found)
                            } else {
                                pairingStatus(failed)?.first
                            }
                        },
                        failedHost = tvHost.takeIf { pairing is PairingUi.Failed && pairPressed },
                        onPair = pair,
                    )
                    IntroStep.CODE -> CodeStep(
                        pairing = pairing,
                        codeSent = codeSent,
                        onSubmit = { code ->
                            codeSent = true
                            controller.submitPairingCode(code)
                        },
                        onRetry = { pair(tvHost) },
                    )
                    IntroStep.BAR -> BarStep(state = state, controller = controller, onNext = { if (barOnly) onDone() else step = IntroStep.READY })
                    IntroStep.READY -> ReadyStep(
                        state = state,
                        tvName = discovered.firstOrNull { it.host == tvHost }?.name ?: stringResource(R.string.onboarding_tv_title),
                        onOpen = {
                            controller.setOnboarded()
                            onDone()
                        },
                    )
                }
            }
        }
    }
}

/** The intro's one full-width action: ivory on night blue, 96 % on press with a click. */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val feedback = rememberFeedback()
    val press = rememberPressSource(PressStyle.SURFACE, onDown = feedback::click)
    val colors = MaterialTheme.colorScheme
    Button(
        onClick = onClick,
        interactionSource = press.interaction,
        colors = ButtonDefaults.buttonColors(containerColor = colors.onBackground, contentColor = colors.background),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer {
                val scale = 1f - (1f - PRESSED_SCALE) * press.state.fraction
                scaleX = scale
                scaleY = scale
            },
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** 1 Welcome: three waves leave the OK, one every 1.2 s; title, line and button rise in turn. */
@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val clock by rememberClock(running = !reduced)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            IntroMark(
                waves = if (reduced) emptyList() else wavePhases(clock, limit = 3),
                modifier = Modifier.matchParentSize(),
                showMark = false,
            )
            WelcomeMark(Modifier.matchParentSize())
        }
        Rise(0) { Text(stringResource(R.string.intro_welcome_title), style = MaterialTheme.typography.headlineLarge) }
        Spacer(Modifier.height(12.dp))
        Rise(1) {
            Text(
                stringResource(R.string.intro_welcome_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
        Rise(2) { PrimaryButton(stringResource(R.string.intro_start), onStart) }
    }
}

/**
 * The Welcome brand mark, its one call site: the waves are drawn around it separately. The
 * first-launch splash hand-off lands on this mark, so at merge it becomes
 * `io.kud.tutti.ui.intro.LaunchMark(Modifier.align(Alignment.Center).size(…))`, sized at three
 * times the visible remote (its box is the full 108-unit square), which reports its position.
 */
@Composable
private fun WelcomeMark(modifier: Modifier = Modifier) {
    // The launcher mark itself, so the first launch's intro lands on it (LaunchMark reports where
    // it is). Its box is three times the visible remote, which IntroMark draws at a fifth of the
    // smaller side: three fifths of that side, centred.
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        LaunchMark(Modifier.size(minOf(maxWidth, maxHeight) * 0.6f))
    }
}

/**
 * 2 Search: the radar turns, a dot and a card per TV found (the card rises on a bouncy spring
 * with a tick). "Enter IP address" is always there, and comes forward after [SEARCH_NUDGE_MS]
 * with nothing found. Reduced motion: no radar, the line "Searching… 1 found" alone.
 */
@Composable
private fun SearchStep(
    found: List<DiscoveredTv>,
    initialHost: String,
    phaseFor: (String) -> ActionPhase,
    failure: String?,
    failedHost: String?,
    onPair: (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf(initialHost) }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SEARCH_NUDGE_MS)
        waited = true
    }
    val forward = waited && found.isEmpty()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.intro_search_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.intro_search_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!reduced) Radar(found.map { it.host }, Modifier.fillMaxWidth(0.8f).aspectRatio(1f).align(Alignment.CenterHorizontally))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!reduced) {
                CircularProgressIndicator(Modifier.size(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (found.isEmpty()) {
                    stringResource(R.string.intro_searching)
                } else {
                    pluralStringResource(R.plurals.intro_search_found, found.size, found.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (tv in found) {
            androidx.compose.runtime.key(tv.host) {
                TvCard(tv, phaseFor(tv.host), failure.takeIf { failedHost == tv.host }, onPair = { onPair(tv.host) })
            }
        }
        if (manual) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.trim() },
                label = { Text(stringResource(R.string.onboarding_ip)) },
                singleLine = true,
                isError = typed.isNotEmpty() && !LanAddress.isPrivate(typed),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            ProgressButton(
                phase = phaseFor(typed),
                label = stringResource(R.string.onboarding_pair),
                onClick = { onPair(typed) },
                enabled = LanAddress.isPrivate(typed),
                pendingLabel = stringResource(R.string.progress_connecting),
                failureMessage = failure.takeIf { failedHost == typed && found.none { it.host == typed } },
            )
        } else {
            // Comes forward once the search has had its chance: tonal, where it was a text link.
            Crossfade(forward, animationSpec = Motion.fade(), label = "manual", modifier = Modifier.align(Alignment.CenterHorizontally)) { prominent ->
                if (prominent) {
                    FilledTonalButton(onClick = { manual = true }) { Text(stringResource(R.string.intro_enter_ip)) }
                } else {
                    TextButton(onClick = { manual = true }) { Text(stringResource(R.string.intro_enter_ip)) }
                }
            }
        }
    }
}

@Composable
private fun TvCard(tv: DiscoveredTv, phase: ActionPhase, failure: String?, onPair: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val feedback = rememberFeedback()
    val colors = MaterialTheme.colorScheme
    val arrive = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        feedback.tick()
        arrive.animateTo(1f, if (reduced) Motion.fade() else Motion.bouncy())
    }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceContainer,
        border = BorderStroke(1.5.dp, colors.outline),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = arrive.value.coerceIn(0f, 1f)
                if (!reduced) translationY = (1f - arrive.value) * CARD_RISE.toPx()
            },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_tv), contentDescription = null, tint = colors.onSurface)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tv.name, style = MaterialTheme.typography.titleSmall)
                    Text(tv.host, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                ProgressButton(
                    phase = phase,
                    label = stringResource(R.string.onboarding_pair),
                    onClick = onPair,
                    pendingLabel = stringResource(R.string.progress_connecting),
                )
            }
            if (failure != null) FailureLine(failure)
        }
    }
}

@Composable
private fun FailureLine(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 3 Code and 4 Verify. The TV pulses once; each character pops with a tick; the sixth sends it
 * (unchanged: [completesCode]). Sent, the cells lock under a padlock and the button is its own
 * gauge. A typo the TV asks again for shakes and clears the cells with a reject; a rejected code
 * clears them and the button says why, with "Try again".
 */
@Composable
private fun CodeStep(pairing: PairingUi, codeSent: Boolean, onSubmit: (String) -> Unit, onRetry: () -> Unit) {
    val attempt = (pairing as? PairingUi.AwaitingCode)?.attempt
    var code by remember { mutableStateOf("") }
    var mismatch by remember { mutableIntStateOf(0) }
    var lastAttempt by remember { mutableIntStateOf(attempt ?: 1) }
    LaunchedEffect(attempt) {
        if (attempt != null && attempt != lastAttempt) {
            if (attempt > lastAttempt) mismatch++
            code = ""
            lastAttempt = attempt
        }
    }
    LaunchedEffect(pairing) {
        if (pairing is PairingUi.Failed && pairing.reason == PairingError.BAD_CODE) code = ""
    }
    val sent = codeSent && pairing !is PairingUi.AwaitingCode
    val failed = pairing is PairingUi.Failed
    val submit = { onSubmit(code) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.intro_code_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.intro_code_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        TvIllustration(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        PairingCodeField(
            code = code,
            onCodeChange = {
                // The sixth character sends it: nothing is left to confirm.
                val completed = completesCode(code, it)
                code = it
                if (completed) submit()
            },
            onDone = { if (PairingCode.isComplete(code)) submit() },
            locked = sent && !failed,
            mismatch = mismatch,
        )
        when {
            sent && !failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_lock), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.intro_code_sent), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            mismatch > 0 && !failed -> FailureLine(stringResource(R.string.pairing_code_retry))
            else -> Text(
                stringResource(R.string.intro_code_count, code.length, PairingCode.LENGTH),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        // No Confirm: the sixth character sends the code. The button only appears to show it
        // going, and to retry a failure, right under the field so the keyboard never hides it.
        if (sent || failed || pairing == PairingUi.Done) ProgressButton(
            phase = when {
                failed -> ActionPhase.FAILURE
                pairing == PairingUi.Done -> ActionPhase.SUCCESS
                else -> ActionPhase.PENDING
            },
            label = stringResource(R.string.action_retry),
            onClick = {
                if (failed) {
                    code = ""
                    onRetry()
                }
            },
            enabled = failed,
            pendingLabel = stringResource(R.string.progress_checking),
            successLabel = stringResource(R.string.pairing_done),
            failureMessage = (pairing as? PairingUi.Failed)?.let { pairingStatus(it)?.first },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        if (pairing is PairingUi.Failed) ProblemNote(message = null, detail = pairing.detail)
    }
}

/**
 * 5 Bar: the address, then a checklist that ticks line by line (a 250 ms stroke and a tick each),
 * staged by [stageChecks] so answers landing together still tick in turn. A failed line keeps its
 * cross and sentence; the ones after stay dashed. All ticked, it moves on; "Later" skips.
 */
@Composable
private fun BarStep(state: TuttiUiState, controller: TuttiController, onNext: () -> Unit) {
    val feedback = rememberFeedback()
    var barHost by rememberSaveable { mutableStateOf(state.devices.bar?.host.orEmpty()) }
    var barError by remember { mutableStateOf(false) }
    var barAttempt by remember { mutableIntStateOf(0) }
    var barAdded by remember { mutableStateOf<String?>(null) }
    var barResult by remember { mutableStateOf<BarOutcome?>(null) }

    // Adding the bar only writes its address: the answer is its link settling, read here
    // rather than in the controller, with an end to the wait.
    val latest by rememberUpdatedState(state)
    LaunchedEffect(barAttempt) {
        val host = barAdded ?: return@LaunchedEffect
        barResult = withTimeoutOrNull(BAR_ADD_TIMEOUT_MS) {
            snapshotFlow { barOutcome(host, latest.devices.bar?.host, latest.links.bar) }.filterNotNull().first()
        } ?: BarOutcome.UNREACHABLE
    }

    val ours = barAdded != null && state.devices.bar?.host == barAdded
    val online = ours && state.links.bar == Link.ONLINE
    var graceOver by remember { mutableStateOf(false) }
    LaunchedEffect(online, barAttempt) {
        graceOver = false
        if (online) {
            delay(BAR_READ_GRACE_MS)
            graceOver = true
        }
    }
    val target = barChecks(
        added = barAdded != null,
        link = if (ours) state.links.bar else Link.CONNECTING,
        read = state.bar.confirmed.isNotEmpty() || graceOver,
        outcome = barResult,
    )
    val doneCount = target.takeWhile { it == CheckState.DONE }.size
    var revealed by remember { mutableIntStateOf(0) }
    val next by rememberUpdatedState(onNext)
    LaunchedEffect(doneCount) {
        if (doneCount < revealed) revealed = doneCount
        while (revealed < doneCount) {
            delay(CHECK_STAGGER_MS)
            revealed++
            feedback.tick()
        }
        if (revealed == target.size) {
            delay(Motion.HERO.toLong())
            next()
        }
    }
    val lines = stageChecks(target, revealed)
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.intro_bar_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_bar_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        OutlinedTextField(
            value = barHost,
            onValueChange = {
                barHost = it.trim()
                barError = false
                barAdded = null
                barResult = null
                revealed = 0
            },
            label = { Text(stringResource(R.string.onboarding_ip)) },
            singleLine = true,
            isError = barError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(barAdded != null, enter = fadeIn(Motion.fade()), exit = fadeOut(Motion.exit())) {
            Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_speaker), contentDescription = null, tint = colors.onSurface)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.onboarding_bar_title), style = MaterialTheme.typography.titleSmall)
                            Text(barAdded.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    CheckLine(lines[0], stringResource(R.string.intro_bar_check_reach), failure = stringResource(R.string.bar_add_unreachable))
                    CheckLine(lines[1], stringResource(R.string.intro_bar_check_read))
                    CheckLine(lines[2], stringResource(R.string.intro_bar_check_ready))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        ProgressButton(
            phase = when {
                barError -> ActionPhase.FAILURE
                barAdded == null -> ActionPhase.IDLE
                barResult == BarOutcome.UNREACHABLE -> ActionPhase.FAILURE
                revealed == target.size -> ActionPhase.SUCCESS
                else -> ActionPhase.PENDING
            },
            label = stringResource(R.string.onboarding_add_bar),
            onClick = {
                val added = controller.addBar(barHost)
                barError = !added
                barAdded = barHost.takeIf { added }
                barResult = null
                revealed = 0
                barAttempt++
            },
            enabled = barHost.isNotBlank(),
            pendingLabel = stringResource(R.string.progress_connecting),
            successLabel = stringResource(R.string.bar_added),
            // The checklist carries the unreachable sentence; the button only says a typo's.
            failureMessage = if (barError) stringResource(R.string.onboarding_lan_only) else null,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        TextButton(onClick = onNext, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.intro_skip))
        }
    }
}

/**
 * 6 Ready: the OK fills with amber and turns into a tick (bouncy), two waves, a double pulse.
 * "Open the remote" shrinks the mark up toward the system chip while the rest fades, then leaves.
 * Reduced motion: fades only, the haptic stays.
 */
@Composable
private fun ReadyStep(state: TuttiUiState, tvName: String, onOpen: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val feedback = rememberFeedback()
    val scope = rememberCoroutineScope()
    val fill = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    val clock by rememberClock(running = !reduced)
    LaunchedEffect(Unit) {
        feedback.doublePulse()
        if (reduced) {
            fill.animateTo(1f, Motion.fade())
            check.snapTo(1f)
        } else {
            fill.animateTo(1f, tween(Motion.MEDIUM, easing = Motion.Emphasized))
            check.animateTo(1f, Motion.bouncy())
        }
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        IntroMark(
            waves = if (reduced) emptyList() else wavePhases(clock, every = READY_WAVE_EVERY_MS, limit = 2),
            okFill = fill.value,
            check = check.value,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .graphicsLayer {
                    if (reduced) {
                        alpha = 1f - leave.value
                    } else {
                        // Toward the system chip, top start of the remote.
                        val s = 1f - LEAVE_SHRINK * leave.value
                        scaleX = s
                        scaleY = s
                        translationX = -leave.value * size.width * 0.38f
                        translationY = -leave.value * size.height * 0.5f
                    }
                },
        )
        Column(Modifier.graphicsLayer { alpha = 1f - leave.value }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Rise(0, Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.intro_ready_title), style = MaterialTheme.typography.headlineMedium)
            }
            Rise(1) {
                Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DeviceRow(R.drawable.ic_tv, tvName, stringResource(R.string.intro_ready_paired), ok = state.devices.tv != null)
                        val bar = state.devices.bar
                        DeviceRow(
                            R.drawable.ic_speaker,
                            stringResource(R.string.onboarding_bar_title),
                            when {
                                bar == null -> stringResource(R.string.intro_ready_not_added)
                                state.links.bar == Link.ONLINE -> stringResource(R.string.intro_ready_connected)
                                else -> linkLabel(state.links.bar)
                            },
                            ok = bar != null && state.links.bar == Link.ONLINE,
                        )
                    }
                }
            }
            if (!state.ipControl) {
                Rise(2) {
                    Text(
                        stringResource(R.string.intro_ready_psk),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Rise(3) {
                PrimaryButton(
                    stringResource(R.string.intro_open_remote),
                    onClick = {
                        if (leave.value > 0f) return@PrimaryButton
                        scope.launch {
                            leave.animateTo(1f, if (reduced) Motion.fade() else tween(Motion.LONG, easing = Motion.Exit))
                            onOpen()
                        }
                    },
                )
            }
        }
    }
}

/** A device and its state, by glyph and word: ✓ Paired, or the link's own word. */
@Composable
private fun DeviceRow(icon: Int, name: String, status: String, ok: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.onSurface)
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (ok) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (ok) colors.onSurface else colors.onSurfaceVariant)
    }
}

private val STEP_SHIFT = 24.dp
private val CARD_RISE = 40.dp
private const val PRESSED_SCALE = 0.96f
private const val CHECK_STAGGER_MS = 300L
private const val READY_WAVE_EVERY_MS = 400L
private const val LEAVE_SHRINK = 0.8f

@Composable
private fun pairingStatus(pairing: PairingUi): Pair<String, Boolean>? = when (pairing) {
    PairingUi.Idle -> null
    PairingUi.Connecting -> stringResource(R.string.pairing_connecting) to false
    is PairingUi.AwaitingCode -> stringResource(R.string.pairing_look_at_tv) to false
    PairingUi.Done -> stringResource(R.string.pairing_done) to false
    is PairingUi.Failed -> stringResource(
        when (pairing.reason) {
            PairingError.UNREACHABLE -> R.string.pairing_unreachable
            PairingError.BAD_CODE -> R.string.pairing_bad_code
            PairingError.REFUSED -> R.string.pairing_refused
        },
    ) to true
}

/**
 * The 6-hex-character code the TV shows, as [PairingCodeField]. A typo is caught locally and asked
 * again: a new [attempt] clears the cells and shows why.
 */
@Composable
fun PairingCodeDialog(attempt: Int, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember(attempt) { mutableStateOf("") }
    var sent by remember(attempt) { mutableStateOf(false) }
    val submit = {
        sent = true
        onSubmit(code)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pairing_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attempt > 1) ProblemNote(message = stringResource(R.string.pairing_code_retry), detail = null)
                PairingCodeField(
                    code = code,
                    onCodeChange = {
                        // The sixth character sends it: nothing is left to confirm.
                        val completed = completesCode(code, it)
                        code = it
                        if (completed) submit()
                    },
                    onDone = { if (PairingCode.isComplete(code)) submit() },
                )
            }
        },
        // No Confirm: the code sends itself, and the slot only shows it going. A code asked again
        // clears the cells and the note above says why.
        confirmButton = {
            if (sent) {
                ProgressButton(
                    phase = ActionPhase.PENDING,
                    label = stringResource(R.string.pairing_step_sending),
                    onClick = {},
                    enabled = false,
                )
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The TV's pre-shared key, for picture, sound output and inputs over its IP control. */
@Composable
fun PskDialog(onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var psk by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_psk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_psk_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = psk,
                    onValueChange = { psk = it },
                    label = { Text(stringResource(R.string.onboarding_psk_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(psk.ifBlank { null }) }) { Text(stringResource(R.string.onboarding_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

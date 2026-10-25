package io.kud.tutti.ui.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.MessageKind
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.protocol.atv.ImeField
import io.kud.tutti.protocol.atv.V2Key
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.PressStyle
import io.kud.tutti.ui.common.ValueTag
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.common.rememberPressSource
import io.kud.tutti.ui.motion.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full input view: while the TV has a text field, tutti stops being a remote and becomes
 * the field. The TV's label as the title, the text big and mirrored live, Enter, arrows to move
 * on the TV; with the phone keyboard down, a D-pad takes its space. A state of [RemoteScreen]:
 * [mode] and [phase] come from [InputRules], every keystroke goes out at once as the whole value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InputView(
    field: ImeField?,
    mode: InputMode,
    phase: FieldPhase,
    tvName: String,
    volume: Int?,
    onText: (String) -> Unit,
    onEnter: () -> Unit,
    onKey: (Int) -> Unit,
    onLongSelect: () -> Unit,
    onLeave: () -> Unit,
    onTypingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val messages = LocalMessages.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val reduced = LocalReducedMotion.current
    val imeVisible = WindowInsets.isImeVisible
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val live = phase == FieldPhase.LIVE
    // Waiting is typable too: opened from the Keyboard key before the TV said it has a field,
    // the first character goes out with the counters the TV gave on focus, and its echo makes
    // the field live (RemoteSession). Erwann, 2026-10-25: show it, and type, from the start.
    val typable = live || phase == FieldPhase.WAITING
    var focused by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<String?>(null) }
    var value by remember {
        val initial = field?.text.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }

    // Follow the TV when its field changes on its side (autocomplete, another remote); our own
    // echoes never get here (RemoteSession drops them). Never while the phone keyboard is
    // composing a word: replacing the text under Gboard's composing region makes it commit the
    // word again on top, which fed the "pphppph" loop. The next keystroke sends ours instead.
    LaunchedEffect(field?.text) {
        val current = field ?: return@LaunchedEffect
        if (current.text != value.text && value.composition == null) {
            value = TextFieldValue(current.text, TextRange(current.selectionEnd.coerceIn(0, current.text.length)))
        }
    }
    // A new field took over in place: its text, the caret at the end, the keyboard stays up.
    LaunchedEffect(mode.handOvers) {
        if (mode.handOvers == 0) return@LaunchedEffect
        val text = field?.text.orEmpty()
        value = TextFieldValue(text, TextRange(text.length))
        undo = null
        haptics.select()
    }
    var lastPhase by remember { mutableStateOf<FieldPhase?>(null) }
    LaunchedEffect(phase) {
        val was = lastPhase
        lastPhase = phase
        when (phase) {
            FieldPhase.LIVE -> {
                focus.requestFocus()
                keyboard?.show()
            }
            FieldPhase.CLOSED -> {
                keyboard?.hide()
                if (mode.sent) haptics.confirm()
            }
            // Once, not per keystroke.
            FieldPhase.UNREACHABLE -> if (was != null) haptics.reject()
            FieldPhase.WAITING -> if (mode.origin == InputOrigin.KEY) {
                focus.requestFocus()
                keyboard?.show()
            }
        }
    }
    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_MS)
            undo = null
        }
    }
    // The rail is gone, so a hardware volume press shows its value for a moment.
    var shownVolume by remember { mutableStateOf<Int?>(null) }
    var lastVolume by remember { mutableStateOf(volume) }
    LaunchedEffect(volume) {
        val was = lastVolume
        lastVolume = volume
        if (volume == null || was == null || volume == was) return@LaunchedEffect
        haptics.tick()
        shownVolume = volume
        delay(VOLUME_TAG_MS)
        shownVolume = null
    }
    DisposableEffect(Unit) {
        onDispose { onTypingChange(false) }
    }
    BackHandler {
        when (InputRules.back(imeVisible)) {
            BackAction.HIDE_KEYBOARD -> keyboard?.hide()
            BackAction.LEAVE -> onLeave()
        }
    }

    fun replace(text: String) {
        value = TextFieldValue(text, TextRange(text.length))
        onText(text)
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) replace(join(value.text, spoken))
    }

    val label = field?.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.keyboard_label_default)

    val header: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeave) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.keyboard_back))
            }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { fadeIn(tween(Motion.SHORT)) togetherWith fadeOut(tween(Motion.SHORT)) },
                    label = "field label",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusLine(phase, mode.sent, tvName)
            }
        }
    }

    val fieldBox: @Composable (Modifier) -> Unit = { m ->
        val scheme = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(FIELD_CORNER)
        val outline = when (phase) {
            FieldPhase.LIVE -> if (focused && imeVisible) Modifier.border(2.dp, scheme.onSurface, shape) else Modifier.border(1.dp, scheme.outline, shape)
            FieldPhase.WAITING -> Modifier.border(1.dp, scheme.outline, shape)
            FieldPhase.CLOSED -> Modifier
            FieldPhase.UNREACHABLE -> Modifier.drawBehind {
                val dash = 6.dp.toPx()
                val width = 1.5.dp.toPx()
                drawRoundRect(
                    color = scheme.outline,
                    topLeft = Offset(width / 2, width / 2),
                    size = size.copy(width = size.width - width, height = size.height - width),
                    cornerRadius = CornerRadius(FIELD_CORNER.toPx()),
                    style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                )
            }
        }
        val textColor = if (phase == FieldPhase.CLOSED) scheme.onSurface.copy(alpha = 0.6f) else scheme.onSurface
        BasicTextField(
            value = value,
            onValueChange = { next ->
                // TV fields are single-line: a newline is the IME's Enter, never text.
                if (next.text.contains('\n')) {
                    onEnter()
                    return@BasicTextField
                }
                val changed = next.text != value.text
                value = next
                if (changed) {
                    undo = null
                    onText(next.text)
                }
            },
            enabled = typable,
            textStyle = MaterialTheme.typography.headlineSmall.copy(color = textColor),
            cursorBrush = SolidColor(scheme.onSurface),
            minLines = 1,
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onEnter() }),
            modifier = m
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged {
                    focused = it.isFocused
                    onTypingChange(it.isFocused)
                },
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = FIELD_HEIGHT)
                        .clip(shape)
                        .background(if (phase == FieldPhase.CLOSED) scheme.surfaceContainer else scheme.surfaceContainerHigh)
                        .then(outline)
                        .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.text.isEmpty() && phase == FieldPhase.WAITING) {
                            Text(
                                stringResource(R.string.keyboard_waiting_hint),
                                style = MaterialTheme.typography.headlineSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                    val restore = undo
                    if (restore != null) {
                        TextButton(onClick = {
                            haptics.click()
                            undo = null
                            replace(restore)
                        }) { Text(stringResource(R.string.keyboard_undo)) }
                    } else if (typable && value.text.isNotEmpty()) {
                        IconButton(onClick = {
                            haptics.click()
                            undo = value.text
                            replace("")
                        }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.keyboard_clear))
                        }
                    }
                }
            },
        )
    }

    val chips: @Composable () -> Unit = {
        // The chips click on down, like every key; their command waits for release.
        val paste = rememberPressSource(PressStyle.ROW) { haptics.click() }
        val dictation = rememberPressSource(PressStyle.ROW) { haptics.click() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                enabled = typable,
                interactionSource = paste.interaction,
                onClick = {
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { replace(join(value.text, it)) }
                },
                label = { Text(stringResource(R.string.keyboard_paste)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                enabled = typable,
                interactionSource = dictation.interaction,
                onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    try {
                        dictate.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        messages?.show(context.getString(R.string.keyboard_dictation_unavailable), MessageKind.PROBLEM)
                    }
                },
                label = { Text(stringResource(R.string.keyboard_dictate)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }

    val enter: @Composable (Modifier) -> Unit = { m ->
        val press = rememberPressSource(PressStyle.KEY) { haptics.click() }
        Button(
            onClick = onEnter,
            enabled = typable,
            interactionSource = press.interaction,
            modifier = m.height(BAR_HEIGHT),
        ) { Text(stringResource(R.string.keyboard_enter)) }
    }

    val arrows: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            ArrowBar(enabled = phase != FieldPhase.CLOSED, onKey = onKey)
            Text(
                stringResource(R.string.keyboard_move),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }

    val showKeyboard: @Composable (Modifier) -> Unit = { m ->
        val press = rememberPressSource(PressStyle.KEY) { haptics.click() }
        FilledTonalButton(
            onClick = {
                focus.requestFocus()
                keyboard?.show()
            },
            enabled = typable,
            interactionSource = press.interaction,
            modifier = m.height(BAR_HEIGHT),
        ) {
            Icon(painterResource(R.drawable.ic_keyboard), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.keyboard_show))
        }
    }

    // With the phone keyboard down you are almost always picking a suggestion or a result: a
    // D-pad, whatever the pad mode says, since that wants discrete steps.
    val dpad: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.Center) {
            AnimatedVisibility(
                visible = !imeVisible && phase != FieldPhase.CLOSED,
                enter = if (reduced) fadeIn(tween(Motion.SHORT)) else fadeIn(tween(Motion.MEDIUM)) + scaleIn(tween(Motion.MEDIUM, easing = Motion.Emphasized), initialScale = 0.96f),
                exit = if (reduced) fadeOut(tween(Motion.SHORT)) else fadeOut(tween(Motion.SHORT)) + scaleOut(tween(Motion.SHORT), targetScale = 0.96f),
            ) {
                Pad(
                    mode = PadMode.DPAD,
                    onKey = onKey,
                    onLongSelect = onLongSelect,
                    onPlayPause = { onKey(V2Key.MEDIA_PLAY_PAUSE) },
                    onVolumeStep = {},
                    onSkip = { onKey(skipKey(it)) },
                    // Always a D-pad here: no mode switch on it.
                    onModeChange = null,
                    modifier = Modifier.size(DPAD_SIZE),
                )
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (!landscape) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                header(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp))
                fieldBox(Modifier)
                Spacer(Modifier.height(12.dp))
                chips()
                // The flexible zone: it takes whatever the phone keyboard leaves, and the D-pad when it's down.
                dpad(Modifier.weight(1f).fillMaxWidth())
                if (imeVisible) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        arrows(Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        enter(Modifier.width(ENTER_WIDTH))
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        showKeyboard(Modifier.weight(1f))
                        enter(Modifier.weight(1f))
                    }
                }
            }
        } else if (imeVisible) {
            // Landscape, keyboard up: two bands, so the field never sits under the keyboard.
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    header(Modifier.weight(0.4f))
                    fieldBox(Modifier.weight(0.6f))
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    chips()
                    Spacer(Modifier.width(16.dp))
                    arrows(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    enter(Modifier.width(ENTER_WIDTH))
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                LandscapeLeft(Modifier.weight(1f)) {
                    header(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp))
                    fieldBox(Modifier)
                    Spacer(Modifier.height(8.dp))
                    chips()
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        showKeyboard(Modifier.weight(1f))
                        enter(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.width(16.dp))
                dpad(Modifier.weight(1f).fillMaxHeight())
            }
        }
        AnimatedVisibility(
            visible = shownVolume != null,
            enter = fadeIn(tween(Motion.SHORT)),
            exit = fadeOut(tween(Motion.SHORT)),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = VOLUME_TAG_TOP),
        ) {
            ValueTag(stringResource(R.string.keyboard_volume, shownVolume ?: lastVolume ?: 0))
        }
    }
}

@Composable
private fun LandscapeLeft(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxHeight(), content = content)
}

/**
 * "Move on the TV": four segments, each its own key, a tick on down like every D-pad direction.
 * A quick press sends its key on release; a held one repeats on the [KeyRepeat] schedule from
 * the press and sends nothing more on release.
 */
@Composable
private fun ArrowBar(enabled: Boolean, onKey: (Int) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            Triple(V2Key.DPAD_LEFT, R.drawable.ic_chevron_left, R.string.pad_left),
            Triple(V2Key.DPAD_UP, R.drawable.ic_arrow_up, R.string.pad_up),
            Triple(V2Key.DPAD_DOWN, R.drawable.ic_arrow_down, R.string.pad_down),
            Triple(V2Key.DPAD_RIGHT, R.drawable.ic_chevron_right, R.string.pad_right),
        ).forEach { (key, icon, name) ->
            val press = rememberPressSource(PressStyle.KEY) { haptics.tick() }
            val description = stringResource(name)
            val held = remember { HeldKey() }
            val send by rememberUpdatedState(onKey)
            LaunchedEffect(press.interaction, enabled) {
                if (!enabled) return@LaunchedEffect
                press.interaction.interactions.collect { event ->
                    when (event) {
                        is PressInteraction.Press -> {
                            held.stop()
                            held.repeats = 0
                            held.job = launch {
                                KeyRepeat.run {
                                    held.repeats++
                                    haptics.tick()
                                    send(key)
                                }
                            }
                        }
                        is PressInteraction.Release, is PressInteraction.Cancel -> held.stop()
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = press.interaction,
                        indication = LocalIndication.current,
                        enabled = enabled,
                    ) {
                        // The click lands on release: stop the repeat here too, before it can fire once more.
                        val repeated = held.repeats > 0
                        held.stop()
                        if (!repeated) onKey(key)
                    }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
    }
}

/** One arrow's hold: the running repeat and how many keys it has sent this press. */
private class HeldKey {
    var job: Job? = null
    var repeats = 0

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** The field's state in a mark and words: a dot, a ring, a check, a slashed ring. Never colour. */
@Composable
private fun StatusLine(phase: FieldPhase, sent: Boolean, tvName: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val text = when (phase) {
        FieldPhase.LIVE -> stringResource(R.string.keyboard_live)
        FieldPhase.WAITING -> stringResource(R.string.keyboard_waiting)
        FieldPhase.CLOSED -> stringResource(if (sent) R.string.keyboard_sent_closed else R.string.keyboard_closed)
        FieldPhase.UNREACHABLE -> stringResource(R.string.keyboard_unreachable, tvName)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (phase == FieldPhase.CLOSED) {
            Box(
                Modifier.size(MARK).clip(RoundedCornerShape(50)).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(MARK - 2.dp),
                )
            }
        } else {
            Canvas(Modifier.size(MARK)) {
                val stroke = 1.5.dp.toPx()
                val radius = size.minDimension / 2
                when (phase) {
                    FieldPhase.LIVE -> drawCircle(color, radius * 0.8f)
                    FieldPhase.WAITING -> drawCircle(color, radius - stroke, style = Stroke(stroke))
                    else -> {
                        drawCircle(color, radius - stroke, style = Stroke(stroke))
                        drawLine(color, Offset(size.width * 0.15f, size.height * 0.85f), Offset(size.width * 0.85f, size.height * 0.15f), stroke)
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun join(current: String, addition: String): String =
    if (current.isEmpty() || current.endsWith(' ')) current + addition else "$current $addition"

private val FIELD_HEIGHT = 76.dp
private val FIELD_CORNER = 16.dp
private val BAR_HEIGHT = 52.dp
private val ENTER_WIDTH = 112.dp
private val DPAD_SIZE = 240.dp
private val MARK = 12.dp
private val VOLUME_TAG_TOP = 72.dp
private const val UNDO_MS = 4_000L
private const val VOLUME_TAG_MS = 1_500L

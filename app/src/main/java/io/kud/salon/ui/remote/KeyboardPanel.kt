package io.kud.salon.ui.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.kud.salon.R
import io.kud.salon.protocol.atv.ImeField
import io.kud.salon.ui.common.rememberHaptics

/**
 * The live keyboard. Opens on its own when the TV reports a focused field; the pad shrinks
 * above it and stays live. The mirror shows the TV's text and label; every keystroke is sent
 * at once, Enter carries the TV field's action. Closes when the TV field goes away or on a
 * swipe down.
 */
@Composable
fun KeyboardPanel(
    field: ImeField?,
    onText: (String) -> Unit,
    onEnter: () -> Unit,
    onClose: () -> Unit,
    onTypingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    var value by remember {
        val initial = field?.text.orEmpty()
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }

    // Follow the TV when its field changes on its side (autocomplete, another remote).
    LaunchedEffect(field?.text) {
        val current = field ?: return@LaunchedEffect
        if (current.text != value.text) {
            value = TextFieldValue(current.text, TextRange(current.selectionEnd.coerceIn(0, current.text.length)))
        }
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    DisposableEffect(Unit) {
        onDispose { onTypingChange(false) }
    }

    fun replace(text: String) {
        value = TextFieldValue(text, TextRange(text.length))
        onText(text)
    }

    fun close() {
        keyboard?.hide()
        onClose()
    }

    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !spoken.isNullOrBlank()) replace(join(value.text, spoken))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged > SWIPE_CLOSE_DP.dp.toPx()) close() },
                ) { change, dy ->
                    change.consume()
                    dragged += dy
                }
            }
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 32.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = field?.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.keyboard_label_default),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                val changed = next.text != value.text
                value = next
                if (changed) onText(next.text)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                haptics.select()
                onEnter()
            }),
            trailingIcon = {
                IconButton(onClick = ::close) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.keyboard_close))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp)
                .focusRequester(focus)
                .onFocusChanged { onTypingChange(it.isFocused) },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {
                    haptics.click()
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }?.let { replace(join(value.text, it)) }
                },
                label = { Text(stringResource(R.string.keyboard_paste)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = {
                    haptics.click()
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    try {
                        dictate.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.keyboard_dictation_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
                label = { Text(stringResource(R.string.keyboard_dictate)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

private fun join(current: String, addition: String): String =
    if (current.isEmpty() || current.endsWith(' ')) current + addition else "$current $addition"

private const val SWIPE_CLOSE_DP = 56

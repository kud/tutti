package io.kud.tutti.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Every failure the user sees goes through here, so each one offers the same "Copy details":
 * [ProblemNote] for a failure that stays on screen, [Problems.report] for a passing one (a snackbar).
 * The detail is already redacted by the controller (FailureReport); this only shows and copies it.
 * Clipboard only: nothing is ever sent anywhere.
 */

/** Puts [detail] on the clipboard. Android 13+ confirms clipboard writes itself, so the toast is for older phones. */
fun copyDetails(context: Context, detail: String, label: String = context.getString(R.string.problem_clip_label)) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, detail))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.problem_copied, Toast.LENGTH_SHORT).show()
    }
}

/**
 * Developer mode (Settings, off by default, the same in every build): the one flag that decides
 * whether errors show their technical detail and "Copy details". Off, they show the short message only.
 */
val LocalDeveloperMode = staticCompositionLocalOf { false }

/** Passing failures: a snackbar with the short message and, in developer mode, "Copy details". */
class Problems(private val host: SnackbarHostState, private val scope: CoroutineScope, private val context: Context) {
    /** Kept in step with the developer-mode pref by the app root. */
    @Volatile var developer: Boolean = false

    fun report(message: String, detail: String?) {
        val shown = detail?.takeIf { developer }
        scope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = shown?.let { context.getString(R.string.problem_copy) },
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && shown != null) copyDetails(context, shown)
        }
    }
}

/** Provided by the app root; null in previews and the quick overlay, where callers fall back to a toast. */
val LocalProblems = staticCompositionLocalOf<Problems?> { null }

/** Reports through [LocalProblems], or a plain toast where there is no snackbar host. */
@Composable
fun rememberProblemReporter(): (String, String?) -> Unit {
    val problems = LocalProblems.current
    val context = LocalContext.current
    return remember(problems, context) {
        { message, detail ->
            if (problems != null) problems.report(message, detail) else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * A failure that stays on screen: a warning icon and the short [message] (the icon and the words
 * carry it, never colour alone), then "Details" to unfold the redacted technical text, small and
 * selectable, and "Copy details", both in developer mode only. [message] may be null when the screen
 * already says what is wrong: outside developer mode such a note then shows nothing.
 */
@Composable
fun ProblemNote(message: String?, detail: String?, modifier: Modifier = Modifier) {
    val shown = detail?.takeIf { LocalDeveloperMode.current }
    ProblemNoteContent(message, shown, modifier)
}

@Composable
private fun ProblemNoteContent(message: String?, detail: String?, modifier: Modifier) {
    if (message == null && detail == null) return
    val context = LocalContext.current
    var open by rememberSaveable(detail) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (message != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (detail != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open = !open }) {
                    Icon(
                        painterResource(if (open) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    )
                    Text(stringResource(if (open) R.string.problem_details_hide else R.string.problem_details_show))
                }
                TextButton(onClick = { copyDetails(context, detail) }) {
                    Text(stringResource(R.string.problem_copy))
                }
            }
            if (open) {
                SelectionContainer {
                    Text(
                        detail,
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

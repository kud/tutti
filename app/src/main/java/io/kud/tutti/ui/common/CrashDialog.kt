package io.kud.tutti.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.kud.tutti.R

/** Shown once after a crash: the scrubbed stack trace, to copy into a report. Copyable whatever the developer mode. */
@Composable
fun CrashDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            SelectionContainer {
                Text(
                    report,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { copyDetails(context, report) }) {
                Text(stringResource(R.string.crash_copy))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_close)) } },
    )
}

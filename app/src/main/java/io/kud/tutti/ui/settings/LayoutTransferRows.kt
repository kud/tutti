package io.kud.tutti.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockLabels
import io.kud.tutti.platform.data.Hand
import io.kud.tutti.platform.data.LayoutTransfer
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.protocol.layout.LayoutCodec
import io.kud.tutti.protocol.layout.LayoutRead
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.MessageKind
import io.kud.tutti.ui.remote.secondaryLabel
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings › Remote & dock, at the foot: the layout out to a file and back in, through the system's
 * file picker (Drive or the phone, no account of ours; Erwann, 2026-10-31). Import asks first, says
 * what it will set and what it leaves out, and offers Undo after (Iris, 2026-10-31).
 */
@Composable
internal fun LayoutTransferRows(state: TuttiUiState, controller: TuttiController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    var outgoing by remember { mutableStateOf<LayoutTransfer.Export?>(null) }
    var incoming by remember { mutableStateOf<LayoutTransfer.Import?>(null) }

    val saved = stringResource(R.string.layout_exported)
    val savedPartly = stringResource(R.string.layout_exported_partial)
    val writeFailed = stringResource(R.string.layout_export_failed)
    val readFailed = stringResource(R.string.layout_read_failed)
    val notOurs = stringResource(R.string.layout_not_a_layout)
    val tooLarge = stringResource(R.string.layout_too_large)
    val future = stringResource(R.string.layout_from_future)
    val imported = stringResource(R.string.layout_imported)
    val undo = stringResource(R.string.pin_undo)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val export = outgoing ?: return@rememberLauncherForActivityResult
        outgoing = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { write(context, uri, LayoutCodec.encode(export.file)) }
            when {
                !ok -> messages?.show(writeFailed, MessageKind.PROBLEM)
                export.leftOut > 0 -> messages?.show(savedPartly)
                else -> messages?.show(saved)
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { read(context, uri) }
            val result = if (text == null) null else LayoutCodec.decode(text)
            when (result) {
                null -> messages?.show(readFailed, MessageKind.PROBLEM)
                LayoutRead.NotALayout -> messages?.show(notOurs, MessageKind.PROBLEM)
                LayoutRead.TooLarge -> messages?.show(tooLarge, MessageKind.PROBLEM)
                is LayoutRead.FromTheFuture -> messages?.show(future, MessageKind.PROBLEM)
                is LayoutRead.Ok -> incoming = LayoutTransfer.import(result.file, result.skipped, state.prefs.layout, state.scenes.map { it.scene })
            }
        }
    }

    LinkRow(
        title = stringResource(R.string.layout_export),
        detail = stringResource(R.string.layout_export_detail),
        enabled = true,
        onClick = {
            outgoing = LayoutTransfer.export(state.prefs.layout, state.dock, state.scenes.map { it.scene })
            create.launch("tutti-layout-${LocalDate.now()}.json")
        },
        icon = R.drawable.ic_download,
    )
    LinkRow(
        title = stringResource(R.string.layout_import),
        detail = stringResource(R.string.layout_import_detail),
        enabled = true,
        // Drive and some file managers report a .json as plain text or bytes; the codec decides.
        onClick = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        icon = R.drawable.ic_folder,
    )

    incoming?.let { plan ->
        ReplaceLayoutDialog(
            plan = plan,
            onReplace = {
                val before = state.prefs.layout to state.dock
                controller.replaceLayout(plan.layout, plan.dock)
                incoming = null
                messages?.show(imported, actionLabel = undo, onAction = { controller.replaceLayout(before.first, before.second) })
            },
            onDismiss = { incoming = null },
        )
    }
}

/** "Replace this layout?": what the remote will be, one line each, then what stays out and why. */
@Composable
private fun ReplaceLayoutDialog(plan: LayoutTransfer.Import, onReplace: () -> Unit, onDismiss: () -> Unit) {
    val longPresses = SecondaryButton.entries.filter { it in plan.dock.longPress }.map { secondaryLabel(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_replace_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Line(pluralStringResource(R.plurals.layout_line_pins, plan.pins, plan.pins))
                if (plan.dock.spare.isNotEmpty()) {
                    Line(pluralStringResource(R.plurals.layout_line_spare, plan.dock.spare.size, plan.dock.spare.size))
                }
                Line(stringResource(R.string.layout_line_hand, stringResource(if (plan.layout.hand == Hand.LEFT) R.string.layout_hand_left else R.string.layout_hand_right)))
                Line(
                    stringResource(
                        R.string.layout_line_labels,
                        stringResource(
                            when (plan.layout.dockLabels) {
                                DockLabels.ICON -> R.string.layout_labels_icon
                                DockLabels.BOTH -> R.string.layout_labels_both
                                DockLabels.NAME -> R.string.layout_labels_name
                            },
                        ),
                    ),
                )
                Line(
                    stringResource(
                        R.string.layout_line_long_presses,
                        longPresses.joinToString(", ").ifEmpty { stringResource(R.string.layout_none) },
                    ),
                )
                Line(stringResource(if (plan.layout.scenes) R.string.layout_line_scenes_shown else R.string.layout_line_scenes_hidden))
                for (skip in plan.skipped) {
                    // The glyph and the word "Skipped" carry it, never the colour alone.
                    Line(skipText(skip), warning = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = onReplace) { Text(stringResource(R.string.layout_replace)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.layout_cancel)) } },
    )
}

@Composable
private fun skipText(skip: LayoutTransfer.Skip): String = when (skip) {
    is LayoutTransfer.Skip.SceneMissing -> stringResource(R.string.layout_skip_scene, skip.label)
    is LayoutTransfer.Skip.Unreadable -> when {
        skip.label != null -> stringResource(R.string.layout_skip_unreadable, skip.label)
        skip.place != null -> stringResource(R.string.layout_skip_unreadable_place, skip.place)
        else -> stringResource(R.string.layout_skip_spare)
    }
    is LayoutTransfer.Skip.LongPress -> stringResource(R.string.layout_skip_long_press, secondaryLabel(skip.button))
}

@Composable
private fun Line(text: String, warning: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        if (warning) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun write(context: Context, uri: Uri, text: String): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    true
}.getOrDefault(false)

/** The file's text, read no further than one byte past [LayoutCodec.MAX_BYTES] so the codec can refuse it. */
private fun read(context: Context, uri: Uri): String? = runCatching {
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    stream.use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (out.size() <= LayoutCodec.MAX_BYTES) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        out.toByteArray().toString(Charsets.UTF_8)
    }
}.getOrNull()

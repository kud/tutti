package io.kud.tutti.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.SettingsBackup
import io.kud.tutti.protocol.sync.SyncFile
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.MessageKind
import io.kud.tutti.ui.common.SettingsGroup
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Settings › Backup (2026-11-12): every setting that travels, to a file and back, through the
 * system's file picker; then the layout alone, as before (Erwann, 2026-10-31), so a layout file
 * written by an older tutti still comes in. What the file holds and never holds: [SettingsBackup].
 * Import asks first and offers Undo after, like the layout's.
 */
@Composable
internal fun BackupPage(state: TuttiUiState, controller: TuttiController) {
    val sync = rememberSettingsSync()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    var outgoing by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf<Map<String, JsonElement>?>(null) }

    val saved = stringResource(R.string.backup_exported)
    val writeFailed = stringResource(R.string.layout_export_failed)
    val readFailed = stringResource(R.string.layout_read_failed)
    val notOurs = stringResource(R.string.backup_not_ours)
    val layoutFile = stringResource(R.string.backup_is_layout)
    val newer = stringResource(R.string.backup_newer)
    val restored = stringResource(R.string.backup_imported)
    val undo = stringResource(R.string.pin_undo)

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SyncFile.MIME)) { uri ->
        val text = outgoing ?: return@rememberLauncherForActivityResult
        outgoing = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { writeText(context, uri, text) }
            if (ok) messages?.show(saved) else messages?.show(writeFailed, MessageKind.PROBLEM)
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { readText(context, uri) }
            when (val read = text?.let(SettingsBackup::read)) {
                null -> messages?.show(readFailed, MessageKind.PROBLEM)
                is SettingsBackup.Read.Ok -> incoming = read.groups
                SettingsBackup.Read.LayoutFile -> messages?.show(layoutFile, MessageKind.PROBLEM)
                is SettingsBackup.Read.Newer -> messages?.show(newer, MessageKind.PROBLEM)
                SettingsBackup.Read.NotOurs -> messages?.show(notOurs, MessageKind.PROBLEM)
            }
        }
    }

    SettingsGroup(stringResource(R.string.backup_all)) {
        LinkRow(
            title = stringResource(R.string.backup_export),
            detail = stringResource(R.string.backup_export_detail),
            enabled = sync != null,
            onClick = {
                scope.launch {
                    outgoing = sync?.backup() ?: return@launch
                    create.launch("tutti-settings-${LocalDate.now()}.json")
                }
            },
            icon = R.drawable.ic_download,
        )
        LinkRow(
            title = stringResource(R.string.backup_import),
            detail = stringResource(R.string.backup_import_detail),
            enabled = sync != null,
            // Drive and some file managers report a .json as plain text or bytes; the codec decides.
            onClick = { open.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            icon = R.drawable.ic_folder,
        )
    }

    SettingsGroup(stringResource(R.string.settings_layout_file)) {
        LayoutTransferRows(state, controller)
    }

    incoming?.let { groups ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text(stringResource(R.string.backup_replace_title)) },
            text = { Text(stringResource(R.string.backup_replace_body)) },
            confirmButton = {
                TextButton(onClick = {
                    incoming = null
                    val target = sync ?: return@TextButton
                    scope.launch {
                        val before = target.restore(groups)
                        messages?.show(restored, actionLabel = undo, onAction = { scope.launch { target.restore(before) } })
                    }
                }) { Text(stringResource(R.string.backup_replace)) }
            },
            dismissButton = { TextButton(onClick = { incoming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun writeText(context: Context, uri: Uri, text: String): Boolean = runCatching {
    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    true
}.getOrDefault(false)

/** The file's text, read no further than one byte past [SyncFile.MAX_CHARS] so the codec can refuse it. */
private fun readText(context: Context, uri: Uri): String? = runCatching {
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    stream.use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (out.size() <= SyncFile.MAX_CHARS) {
            val n = input.read(chunk)
            if (n < 0) break
            out.write(chunk, 0, n)
        }
        out.toByteArray().toString(Charsets.UTF_8)
    }
}.getOrNull()

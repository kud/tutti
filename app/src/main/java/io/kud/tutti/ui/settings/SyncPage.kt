package io.kud.tutti.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.kud.tutti.R
import io.kud.tutti.platform.sync.SettingsSync
import io.kud.tutti.platform.sync.SyncProblem
import io.kud.tutti.platform.sync.SyncState
import io.kud.tutti.protocol.sync.SyncFile
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.MessageKind
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.ui.common.SwitchRow
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncEntryPoint {
    fun settingsSync(): SettingsSync
}

private fun Context.settingsSync(): SettingsSync =
    EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java).settingsSync()

/** The singleton [SettingsSync], or null in a preview with no graph. */
@Composable
internal fun rememberSettingsSync(): SettingsSync? {
    val context = LocalContext.current
    return remember(context) { runCatching { context.settingsSync() }.getOrNull() }
}

@Composable
internal fun rememberSyncState(): SyncState =
    rememberSettingsSync()?.state?.collectAsStateWithLifecycle()?.value ?: SyncState()

@Composable
private fun ago(at: Long): String = ageWords(syncAge(at, System.currentTimeMillis()))

/**
 * Settings › Sync (docs/adr/0001-settings-sync.md): one "Sync" switch while nothing is chosen;
 * turning it on asks once where, in one dialog: a folder first (tutti finds or creates the file in
 * it), else an existing sync file or a new one. What travels and what stays is written out, never
 * left to guess.
 */
@Composable
internal fun SyncPage() {
    val sync = rememberSettingsSync()
    val state = rememberSyncState()
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    var confirmStop by remember { mutableStateOf(false) }
    var choosePlace by remember { mutableStateOf(false) }
    val synced = stringResource(R.string.sync_done)
    val failed = stringResource(R.string.sync_failed)

    fun chosen(ok: Boolean) {
        messages?.show(if (ok) synced else failed, if (ok) MessageKind.INFO else MessageKind.PROBLEM)
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SyncFile.MIME)) { uri ->
        if (uri != null && sync != null) scope.launch { chosen(sync.choose(uri, created = true)) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && sync != null) scope.launch { chosen(sync.choose(uri, created = false)) }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null && sync != null) scope.launch { chosen(sync.chooseFolder(tree)) }
    }

    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
        if (!state.hasFile) {
            SwitchRow(
                label = stringResource(R.string.sync_title),
                supporting = stringResource(R.string.sync_intro),
                checked = false,
                onCheckedChange = { on -> if (on && sync != null) choosePlace = true },
                icon = R.drawable.ic_refresh,
            )
        } else {
            SettingsRow(
                title = placeName(state),
                detail = statusLine(state),
                icon = R.drawable.ic_refresh,
            )
            SwitchRow(
                label = stringResource(R.string.sync_auto),
                supporting = stringResource(R.string.sync_auto_detail),
                checked = state.auto,
                onCheckedChange = { sync?.setAuto(it) },
                icon = R.drawable.ic_settings,
            )
            LinkRow(
                title = stringResource(R.string.sync_now),
                detail = stringResource(R.string.sync_now_detail),
                enabled = sync != null,
                onClick = { sync?.let { s -> scope.launch { chosen(s.syncNow()) } } },
                icon = R.drawable.ic_refresh,
            )
            LinkRow(
                title = stringResource(R.string.sync_change),
                detail = stringResource(R.string.sync_change_detail),
                enabled = sync != null,
                onClick = { choosePlace = true },
                icon = R.drawable.ic_folder,
            )
            LinkRow(
                title = stringResource(R.string.sync_stop),
                detail = stringResource(R.string.sync_stop_detail),
                enabled = sync != null,
                onClick = { confirmStop = true },
                icon = R.drawable.ic_info,
            )
        }
    }
    SettingsGroup(stringResource(R.string.sync_what_title)) {
        SettingsRow(title = stringResource(R.string.sync_synced), detail = stringResource(R.string.sync_synced_list))
        SettingsRow(title = stringResource(R.string.sync_local), detail = stringResource(R.string.sync_local_list))
        SettingsRow(title = stringResource(R.string.sync_how), detail = stringResource(R.string.sync_how_detail))
    }

    if (choosePlace) {
        SyncPlaceDialog(
            onFolder = {
                choosePlace = false
                folder.launch(null)
            },
            onExisting = {
                choosePlace = false
                open.launch(arrayOf(SyncFile.MIME, "text/*", "application/octet-stream"))
            },
            onCreate = {
                choosePlace = false
                create.launch(SyncFile.NAME)
            },
            onCancel = { choosePlace = false },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.sync_stop_title)) },
            text = { Text(stringResource(R.string.sync_stop_body), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    sync?.stop()
                }) { Text(stringResource(R.string.sync_stop)) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.layout_cancel)) } },
        )
    }
}

/** The place in words: "Drive folder › tutti-sync.json", or the file alone. */
private fun placeName(state: SyncState): String {
    val file = state.fileName ?: SyncFile.NAME
    return state.folderName?.let { "$it › $file" } ?: file
}

/**
 * Where to sync, asked once when Sync turns on. Android has no picker that both opens a file and
 * creates one, and Drive allows two files with one name without saying so, so the choice is the
 * person's. The folder leads: it finds or creates the file by itself, and the same folder on every
 * phone is all there is to remember. Picking an existing file comes before creating one, so a
 * second file where one exists takes a deliberate tap. Each choice is a title and a sentence.
 */
@Composable
private fun SyncPlaceDialog(onFolder: () -> Unit, onExisting: () -> Unit, onCreate: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.sync_place_title)) },
        text = {
            Column {
                PlaceOption(stringResource(R.string.sync_folder), stringResource(R.string.sync_folder_detail), onFolder)
                PlaceOption(stringResource(R.string.sync_use_existing), stringResource(R.string.sync_use_existing_detail), onExisting)
                PlaceOption(stringResource(R.string.sync_create), stringResource(R.string.sync_create_detail), onCreate)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.layout_cancel)) } },
    )
}

@Composable
private fun PlaceOption(title: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Where sync stands, in words: the problem first, with what to do, else the last good sync. */
@Composable
private fun statusLine(state: SyncState): String = when (state.problem) {
    SyncProblem.UNREACHABLE -> stringResource(R.string.sync_problem_unreachable)
    SyncProblem.NEWER -> stringResource(R.string.sync_problem_newer)
    SyncProblem.NOT_OURS -> stringResource(R.string.sync_problem_not_ours)
    SyncProblem.WAITING -> stringResource(R.string.sync_problem_waiting)
    null -> listOfNotNull(
        stringResource(if (state.auto) R.string.sync_line_on else R.string.sync_line_paused),
        state.lastSyncedAt?.let { stringResource(R.string.sync_last, ago(it)) } ?: stringResource(R.string.sync_never),
    ).joinToString(" · ")
}

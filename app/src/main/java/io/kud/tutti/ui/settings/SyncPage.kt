package io.kud.tutti.ui.settings

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
private fun rememberSettingsSync(): SettingsSync? {
    val context = LocalContext.current
    return remember(context) { runCatching { context.settingsSync() }.getOrNull() }
}

@Composable
private fun rememberSyncState(): SyncState =
    rememberSettingsSync()?.state?.collectAsStateWithLifecycle()?.value ?: SyncState()

/** Sync's live line under Settings: "Off", "On · tutti-sync.json · 2 min ago", or what to do. */
@Composable
internal fun syncLine(): String {
    val state = rememberSyncState()
    return when {
        !state.hasFile -> stringResource(R.string.sync_line_off)
        state.problem != null -> stringResource(R.string.sync_line_problem)
        else -> listOfNotNull(
            stringResource(if (state.auto) R.string.sync_line_on else R.string.sync_line_paused),
            state.fileName,
            state.lastSyncedAt?.let(::ago),
        ).joinToString(" · ")
    }
}

private fun ago(at: Long): String =
    DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/**
 * Settings › Sync (docs/adr/0001-settings-sync.md): one file, picked once through the system's
 * picker, kept merged with this phone's settings. Google Drive offers single files to other apps,
 * not folders, so the choice is a file: a new one on the first phone, the same one on the next.
 * What travels and what stays is written out, never left to guess.
 */
@Composable
internal fun SyncPage() {
    val sync = rememberSettingsSync()
    val state = rememberSyncState()
    val scope = rememberCoroutineScope()
    val messages = LocalMessages.current
    var confirmStop by remember { mutableStateOf(false) }
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
    val pickExisting = { open.launch(arrayOf(SyncFile.MIME, "text/*", "application/octet-stream")) }

    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
        if (!state.hasFile) {
            SettingsRow(title = stringResource(R.string.sync_intro_title), detail = stringResource(R.string.sync_intro), icon = R.drawable.ic_refresh)
            LinkRow(
                title = stringResource(R.string.sync_create),
                detail = stringResource(R.string.sync_create_detail),
                enabled = sync != null,
                onClick = { create.launch(SyncFile.NAME) },
                icon = R.drawable.ic_download,
            )
            LinkRow(
                title = stringResource(R.string.sync_use_existing),
                detail = stringResource(R.string.sync_use_existing_detail),
                enabled = sync != null,
                onClick = pickExisting,
                icon = R.drawable.ic_folder,
            )
        } else {
            SettingsRow(
                title = state.fileName ?: SyncFile.NAME,
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
                title = stringResource(R.string.sync_use_existing),
                detail = stringResource(R.string.sync_switch_detail),
                enabled = sync != null,
                onClick = pickExisting,
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

/** Where sync stands, in words: the problem first, with what to do, else the last good sync. */
@Composable
private fun statusLine(state: SyncState): String = when (state.problem) {
    SyncProblem.UNREACHABLE -> stringResource(R.string.sync_problem_unreachable)
    SyncProblem.NEWER -> stringResource(R.string.sync_problem_newer)
    SyncProblem.NOT_OURS -> stringResource(R.string.sync_problem_not_ours)
    null -> listOfNotNull(
        stringResource(if (state.auto) R.string.sync_line_on else R.string.sync_line_paused),
        state.lastSyncedAt?.let { stringResource(R.string.sync_last, ago(it)) } ?: stringResource(R.string.sync_never),
    ).joinToString(" · ")
}

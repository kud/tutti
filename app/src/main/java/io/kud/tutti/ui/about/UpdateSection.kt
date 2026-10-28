package io.kud.tutti.ui.about

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.BuildConfig
import io.kud.tutti.R
import io.kud.tutti.platform.update.UpdateProblem
import io.kud.tutti.platform.update.UpdateState
import io.kud.tutti.platform.update.Updater
import io.kud.tutti.protocol.update.AppVersion
import io.kud.tutti.protocol.update.UpdateCue
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.TuttiSheet
import kotlinx.coroutines.launch

/**
 * About › Version: one row with the installed version and where the updater stands, and one sheet
 * that checks GitHub, downloads and installs (docs/design.md, "Updates"). Every state is a
 * sentence; a failure says what failed and what to do, never by colour alone.
 */
@Composable
internal fun UpdateRow() {
    val context = LocalContext.current
    val updater = remember(context) { Updater.from(context) }
    val state by updater.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                open = true
                if (state is UpdateState.Idle || state is UpdateState.UpToDate || state is UpdateState.Failed) {
                    scope.launch { updater.check() }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.about_version_detail, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                summary(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (open) UpdateSheet(updater, state, onDismiss = { open = false })
}

/**
 * Everything the rest of the app knows of the updater, in one place (Iris, 2026-10-28): the GitHub
 * updater is meant to be temporary (final releases will likely go to F-Droid), so a build without it
 * needs only these three to return nothing. [newer] is a newer build, set aside or not; [notice] is
 * true until "Later".
 */
data class UpdateCueState(val newer: AppVersion?, val notice: Boolean)

@Composable
fun rememberUpdateCue(): UpdateCueState {
    val context = LocalContext.current
    val updater = remember(context) { Updater.from(context) }
    val newer by updater.newer.collectAsStateWithLifecycle()
    val cue by updater.cue.collectAsStateWithLifecycle()
    return UpdateCueState(newer, newer != null && cue == UpdateCue.NOTICE)
}

/**
 * The automatic check, while the app is in front: on opening and on coming back, at most once an
 * hour for a dev build and once a day for a final release ([io.kud.tutti.protocol.update.CheckSchedule]).
 * A build found for the first time says so once, with Install ([onInstall]); after that the ⋮'s dot
 * and row carry it, so nothing repeats. Never an install by itself.
 */
@Composable
fun UpdateCheckEffect(onInstall: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val updater = remember(context) { Updater.from(context) }
    val messages = LocalMessages.current
    val scope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleStartEffect(updater) {
        val job = scope.launch {
            kotlinx.coroutines.delay(3_000)
            val update = updater.checkIfDue() ?: return@launch
            messages?.show(
                resources.getString(R.string.update_available, update.version.toString()),
                actionLabel = resources.getString(R.string.update_message_install),
                onAction = onInstall,
                long = true,
            )
        }
        onStopOrDispose { job.cancel() }
    }
}

/**
 * The same sheet opened from outside About, by the remote's ⋮ row or the "Install" on the message
 * (docs/design.md, "Updates"): one tap means install, so it asks GitHub for the build if this run
 * doesn't hold it yet, then starts the download at once. Android still asks to confirm the install.
 */
@Composable
fun UpdateNowSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updater = remember(context) { Updater.from(context) }
    val state by updater.state.collectAsStateWithLifecycle()
    var started by rememberSaveable { mutableStateOf(false) }
    // One run, keyed on nothing: a state change must not cancel the check or the download it starts.
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        started = true
        val update = when (val now = updater.state.value) {
            is UpdateState.Available -> now.update
            // A check that finds nothing or fails leaves the sheet to say so.
            UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Failed -> updater.check()
            else -> null
        }
        update?.let { updater.downloadAndInstall(it) }
    }
    UpdateSheet(updater, state, onDismiss)
}

@Composable
private fun summary(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_check)
    else -> statusText(state)
}

@Composable
private fun statusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_note)
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, state.update.version.toString())
    is UpdateState.Downloading -> state.percent?.let { stringResource(R.string.update_downloading_percent, it) }
        ?: stringResource(R.string.update_downloading)
    is UpdateState.NeedsPermission -> stringResource(R.string.update_permission_title)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> when (state.problem) {
        UpdateProblem.NETWORK -> stringResource(R.string.update_failed_network)
        UpdateProblem.FEED -> stringResource(R.string.update_failed_feed)
        UpdateProblem.DOWNLOAD -> stringResource(R.string.update_failed_download)
        UpdateProblem.DIGEST -> stringResource(R.string.update_failed_digest)
        UpdateProblem.PACKAGE -> stringResource(R.string.update_failed_package)
        UpdateProblem.INSTALL -> state.detail?.let { stringResource(R.string.update_failed_install_detail, it) }
            ?: stringResource(R.string.update_failed_install)
    }
}

@Composable
private fun UpdateSheet(updater: Updater, state: UpdateState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Back from Android's "Install unknown apps" page: carry on if it was allowed.
    LifecycleResumeEffect(state) {
        if (state is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            updater.install(state.update)
        }
        onPauseOrDispose { }
    }
    TuttiSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.update_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.update_installed, updater.installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            // The state, as a sentence TalkBack reads when it changes.
            Text(
                statusText(state),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state is UpdateState.NeedsPermission) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.update_permission_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is UpdateState.Downloading || state is UpdateState.Checking) {
                Spacer(Modifier.height(12.dp))
                val percent = (state as? UpdateState.Downloading)?.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(20.dp))
            when (state) {
                is UpdateState.Available -> {
                    Button(
                        onClick = { scope.launch { updater.downloadAndInstall(state.update) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.update_download_install)) }
                    // "Later" sets this build aside: the remote's dot goes, the ⋮ row and About keep it.
                    TextButton(
                        onClick = {
                            updater.setAside(state.update.version)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.update_later)) }
                }
                is UpdateState.NeedsPermission -> Button(
                    onClick = {
                        try {
                            context.startActivity(updater.permissionIntent())
                        } catch (_: ActivityNotFoundException) {
                            // No such settings page on this phone: the install itself will ask.
                            updater.install(state.update)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_permission_open)) }
                is UpdateState.Failed -> OutlinedButton(
                    onClick = {
                        val update = state.update
                        if (update != null && state.problem == UpdateProblem.INSTALL) {
                            updater.install(update)
                        } else {
                            scope.launch { updater.check() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_try_again)) }
                UpdateState.UpToDate, UpdateState.Idle -> OutlinedButton(
                    onClick = { scope.launch { updater.check() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_check)) }
                else -> Unit
            }
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.update_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

package io.kud.tutti.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.platform.diagnostic.Diagnostics
import io.kud.tutti.protocol.diagnostic.DiagnosticSteps
import io.kud.tutti.protocol.diagnostic.Plane
import io.kud.tutti.protocol.diagnostic.StepResult
import io.kud.tutti.protocol.diagnostic.StepStatus
import io.kud.tutti.ui.common.SectionTitle

/** Settings › Diagnostic: every probe live, then a scrubbed plain-text report to copy or share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(diagnostics: Diagnostics, onBack: () -> Unit) {
    val ui by diagnostics.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val started = ui.report != null
    val counts = ui.steps.groupingBy { it.status }.eachCount()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostic_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.diagnostic_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.running) {
                            FilledTonalButton(onClick = diagnostics::cancel) { Text(stringResource(R.string.diagnostic_stop)) }
                        } else {
                            Button(onClick = diagnostics::start) {
                                Text(stringResource(if (started) R.string.diagnostic_rerun else R.string.diagnostic_run))
                            }
                        }
                    }
                    if (started) {
                        Text(
                            stringResource(
                                R.string.diagnostic_summary,
                                counts[StepStatus.OK] ?: 0,
                                counts[StepStatus.FAILED] ?: 0,
                                counts[StepStatus.SKIPPED] ?: 0,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val report = ui.report.orEmpty()
                            TextButton(onClick = { copy(context, report) }) { Text(stringResource(R.string.diagnostic_copy)) }
                            TextButton(onClick = { share(context, report) }) { Text(stringResource(R.string.diagnostic_share)) }
                        }
                    }
                }
            }
            for (plane in Plane.entries) {
                val steps = ui.steps.filter { it.plane == plane }
                if (steps.isEmpty()) continue
                item(key = "plane-${plane.name}") { SectionTitle(planeLabel(plane)) }
                items(steps, key = { it.id }) { step -> StepRow(step) }
            }
            item { Text("", Modifier.padding(bottom = 32.dp)) }
        }
    }
}

@Composable
private fun StepRow(step: StepResult) {
    var open by rememberSaveable(step.id) { mutableStateOf(false) }
    val failed = step.status == StepStatus.FAILED
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = step.facts.isNotEmpty()) { open = !open }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stepLabel(step.id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            // The word carries the status; colour only repeats it.
            Text(
                statusLabel(step.status) + (step.millis?.let { " · " + stringResource(R.string.diagnostic_millis, it.toInt()) } ?: ""),
                style = MaterialTheme.typography.labelLarge,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        val detail = listOfNotNull(step.errorClass, step.message).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            // Shown on this phone only; the copied report is scrubbed again.
            for ((key, value) in step.facts) {
                Text(
                    "$key: $value",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun statusLabel(status: StepStatus): String = stringResource(
    when (status) {
        StepStatus.PENDING -> R.string.diagnostic_status_pending
        StepStatus.RUNNING -> R.string.diagnostic_status_running
        StepStatus.OK -> R.string.diagnostic_status_ok
        StepStatus.FAILED -> R.string.diagnostic_status_failed
        StepStatus.SKIPPED -> R.string.diagnostic_status_skipped
    },
)

@Composable
private fun planeLabel(plane: Plane): String = stringResource(
    when (plane) {
        Plane.DISCOVERY -> R.string.diagnostic_plane_discovery
        Plane.REST -> R.string.diagnostic_plane_rest
        Plane.REMOTE -> R.string.diagnostic_plane_remote
        Plane.BAR -> R.string.diagnostic_plane_bar
        Plane.CLOUD -> R.string.diagnostic_plane_cloud
    },
)

@Composable
private fun stepLabel(id: String): String {
    DiagnosticSteps.REST_READS.firstOrNull { it.id == id }?.let {
        return stringResource(R.string.diagnostic_step_rest_read, it.method)
    }
    return when (id) {
        DiagnosticSteps.DISCOVERY -> stringResource(R.string.diagnostic_step_discovery)
        DiagnosticSteps.REST_INTERFACE -> stringResource(R.string.diagnostic_step_rest_interface)
        DiagnosticSteps.REST_AUTH -> stringResource(R.string.diagnostic_step_rest_auth)
        DiagnosticSteps.REST_METHODS -> stringResource(R.string.diagnostic_step_rest_methods)
        DiagnosticSteps.REMOTE_IDENTITY -> stringResource(R.string.diagnostic_step_remote_identity)
        DiagnosticSteps.REMOTE_PAIRING_PORT -> stringResource(R.string.diagnostic_step_remote_pairing_port)
        DiagnosticSteps.REMOTE_TLS -> stringResource(R.string.diagnostic_step_remote_tls)
        DiagnosticSteps.REMOTE_SESSION -> stringResource(R.string.diagnostic_step_remote_session)
        DiagnosticSteps.BAR_CONNECT -> stringResource(R.string.diagnostic_step_bar_connect)
        DiagnosticSteps.BAR_FEATURES -> stringResource(R.string.diagnostic_step_bar_features)
        DiagnosticSteps.BAR_VOICE_ZOOM -> stringResource(R.string.diagnostic_step_bar_voicezoom)
        DiagnosticSteps.CLOUD -> stringResource(R.string.diagnostic_step_cloud)
        else -> id
    }
}

private fun copy(context: Context, report: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostic_share_subject), report))
    // Android 13+ confirms clipboard writes itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.diagnostic_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostic_share_subject))
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

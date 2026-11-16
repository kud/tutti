package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.platform.diagnostic.AppLog
import io.kud.tutti.protocol.diagnostic.EventLog
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.ReportButton
import io.kud.tutti.ui.common.copyDetails
import io.kud.tutti.ui.common.shareText

/**
 * Settings › Advanced › Recent events (developer mode): what tutti did lately and what came back,
 * newest first, already scrubbed. Copy and Share carry the whole log; "Report on GitHub" opens a
 * new issue with as much of it as a link holds. Nothing leaves the phone unless asked.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventLogScreen(onBack: () -> Unit) {
    val entries by AppLog.events.entries.collectAsStateWithLifecycle()
    val crash by AppLog.events.lastCrash.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messages = LocalMessages.current
    val empty = entries.isEmpty() && crash == null
    val subject = stringResource(R.string.event_log_share_subject)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.event_log_title)) },
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.event_log_intro, AppLog.events.capacity),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!empty) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                copyDetails(context, AppLog.events.render(), messages, subject)
                            }) { Text(stringResource(R.string.event_log_copy)) }
                            TextButton(onClick = { shareText(context, AppLog.events.render()) }) {
                                Text(stringResource(R.string.event_log_share))
                            }
                            ReportButton(what = stringResource(R.string.report_log_title), detail = null)
                            TextButton(onClick = { AppLog.events.clear() }) { Text(stringResource(R.string.event_log_clear)) }
                        }
                    }
                }
            }
            if (empty) {
                item {
                    Text(
                        stringResource(R.string.event_log_empty),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            crash?.let { trace ->
                item(key = "crash") { EntryRow(stringResource(R.string.event_log_last_crash), trace) }
            }
            items(entries.asReversed()) { entry ->
                EntryRow(EventLog.line(entry).substringBefore(": ${entry.text}"), entry.text)
            }
            item { Text("", Modifier.padding(bottom = 32.dp)) }
        }
    }
}

@Composable
private fun EntryRow(heading: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

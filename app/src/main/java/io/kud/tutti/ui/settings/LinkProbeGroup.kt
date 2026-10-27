package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.apps.LinkOutcome
import io.kud.tutti.protocol.apps.LinkProbe
import io.kud.tutti.protocol.apps.LinkProbeResult
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import kotlinx.coroutines.launch

/**
 * "Open a link on the TV" (developer mode, Technical details): a text field and a few candidate
 * YouTube links, each sent as a Remote v2 app-link launch, with what the TV reported afterwards
 * said in words. It exists to learn which deep links the TV's apps honour before any app-specific
 * remote is built on them. The result lives on this screen only and is never logged.
 */
@Composable
fun LinkProbeGroup(onProbe: suspend (String) -> LinkProbeResult) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LinkProbeResult?>(null) }
    fun send(target: String) {
        if (busy) return
        link = target
        busy = true
        result = null
        scope.launch {
            try {
                result = onProbe(target)
            } finally {
                busy = false
            }
        }
    }
    SettingsGroup(stringResource(R.string.link_probe_title)) {
        SettingsRow(title = stringResource(R.string.link_probe_intro))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text(stringResource(R.string.link_probe_field)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send(link.trim()) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = { send(link.trim()) }, enabled = !busy && link.isNotBlank()) {
                Text(stringResource(R.string.link_probe_send))
            }
        }
        SettingsRow(
            title = stringResource(R.string.link_probe_result),
            detail = when {
                busy -> stringResource(R.string.link_probe_watching, (LinkProbe.WATCH_MS / 1000).toInt())
                result == null -> stringResource(R.string.link_probe_nothing_yet)
                else -> outcomeWords(result!!)
            },
        )
        for (preset in LinkProbe.YOUTUBE_PRESETS) {
            SettingsRow(
                title = preset,
                detail = stringResource(R.string.link_probe_preset_detail),
                enabled = !busy,
                onClick = { send(preset) },
            )
        }
    }
}

@Composable
private fun outcomeWords(result: LinkProbeResult): String {
    val none = stringResource(R.string.link_probe_no_app)
    return when (result.outcome) {
        LinkOutcome.INVALID -> stringResource(R.string.link_probe_invalid)
        LinkOutcome.NOT_SENT -> stringResource(R.string.link_probe_not_sent)
        LinkOutcome.SENT_UNOBSERVED -> stringResource(R.string.link_probe_unobserved)
        LinkOutcome.OPENED -> stringResource(R.string.link_probe_opened, result.after ?: none, result.before ?: none)
        LinkOutcome.SAME_APP -> stringResource(R.string.link_probe_same, result.after ?: none)
        LinkOutcome.NO_APP -> stringResource(R.string.link_probe_gone, result.before ?: none)
    }
}

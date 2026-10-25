package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TargetController
import io.kud.tutti.controller.TargetState
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.devices.Device
import io.kud.tutti.protocol.hid.KeyLayout
import io.kud.tutti.ui.common.SettingsRow
import kotlinx.coroutines.launch

private sealed interface MacDialog {
    val id: String

    data class Rename(override val id: String) : MacDialog
    data class Input(override val id: String) : MacDialog
    data class Layout(override val id: String) : MacDialog
    data class Forget(override val id: String) : MacDialog
}

/** The Mac's link and screen in words: "Bluetooth connected · Shown on HDMI 2". */
@Composable
internal fun macStatus(mac: Device, target: TargetState, inputs: List<ExternalInput>): String {
    val link = stringResource(if (target.hidConnectedTo == mac.id) R.string.devices_mac_bt_connected else R.string.devices_mac_bt_idle)
    val shown = mac.shownOn?.hdmiInput?.let { stringResource(R.string.devices_mac_on_input, inputName(it, inputs)) }
        ?: stringResource(R.string.devices_mac_airplay)
    return "$link · $shown"
}

@Composable
internal fun layoutLabel(layout: KeyLayout): String = stringResource(
    if (layout == KeyLayout.MAC_US) R.string.devices_layout_mac_us else R.string.devices_layout_mac_fr,
)

/**
 * One row per Mac (Iris, 2026-10-25): the laptop glyph, its name, its link and input in words,
 * and its actions as text buttons (each its own 48 dp target, named for TalkBack by its label).
 */
@Composable
internal fun MacRows(targets: TargetController, target: TargetState, inputs: List<ExternalInput>) {
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<MacDialog?>(null) }

    for (mac in target.list.macs) {
        SettingsRow(
            title = mac.name,
            icon = R.drawable.ic_laptop,
            detail = macStatus(mac, target, inputs),
            footer = {
                FlowRow(Modifier.offset(x = (-12).dp)) {
                    TextButton(onClick = { dialog = MacDialog.Rename(mac.id) }) { Text(stringResource(R.string.devices_rename)) }
                    TextButton(onClick = { dialog = MacDialog.Input(mac.id) }) { Text(stringResource(R.string.devices_input_action)) }
                    TextButton(onClick = { dialog = MacDialog.Layout(mac.id) }) { Text(stringResource(R.string.devices_layout_action)) }
                    TextButton(onClick = { targets.pairAgain(mac.id) }) { Text(stringResource(R.string.devices_pair_again)) }
                    TextButton(onClick = { dialog = MacDialog.Forget(mac.id) }) { Text(stringResource(R.string.settings_forget)) }
                }
            },
        )
    }

    val open = dialog ?: return
    // A Mac forgotten meanwhile (from another screen) just closes nothing: its dialog is not drawn.
    val mac = target.list[open.id] ?: return
    val close = { dialog = null }
    when (open) {
        is MacDialog.Rename -> RenameDialog(mac.name, onSave = { name ->
            scope.launch { targets.rename(mac.id, name) }
            close()
        }, onDismiss = close)
        is MacDialog.Input -> ChoiceDialog(
            title = stringResource(R.string.devices_input_title, mac.name),
            explain = if (inputs.none { it.isHdmi }) stringResource(R.string.devices_input_unknown) else null,
            options = InputOptions.of(inputs, mac.shownOn?.hdmiInput),
            selected = mac.shownOn?.hdmiInput,
            onPick = { uri ->
                scope.launch { targets.setMacInput(mac.id, uri) }
                close()
            },
            onDismiss = close,
        )
        is MacDialog.Layout -> ChoiceDialog(
            title = stringResource(R.string.devices_layout_title, mac.name),
            explain = stringResource(R.string.devices_layout_explain),
            options = listOf(KeyLayout.MAC_FR, KeyLayout.MAC_US).map { it to (layoutLabel(it) to null) },
            selected = mac.layout(),
            onPick = { layout ->
                if (layout != null) scope.launch { targets.setLayout(mac.id, layout) }
                close()
            },
            onDismiss = close,
        )
        is MacDialog.Forget -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.settings_forget_named, mac.name)) },
            text = { Text(stringResource(R.string.devices_forget_mac_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { targets.forget(mac.id) }
                    close()
                }) { Text(stringResource(R.string.settings_forget)) }
            },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** HDMI inputs as choices, each with whether something is plugged in, said in words; then AirPlay only. */
internal object InputOptions {
    @Composable
    fun of(inputs: List<ExternalInput>, current: String?, newly: String? = null): List<Pair<String?, Pair<String, String?>>> {
        val hdmi = inputs.filter { it.isHdmi }
        val listed = hdmi.map { input ->
            val note = when {
                input.uri == newly -> stringResource(R.string.devices_input_new)
                input.connected -> stringResource(R.string.devices_input_plugged)
                else -> stringResource(R.string.devices_input_empty)
            }
            input.uri as String? to (input.spoken() to note)
        }
        // An input set before IP control went off stays choosable, by name.
        val kept = current?.takeIf { uri -> hdmi.none { it.uri == uri } }?.let { listOf(it as String? to (inputName(it, inputs) to null)) }.orEmpty()
        return listed + kept + (null to (stringResource(R.string.devices_mac_input_none) to null))
    }
}

/** A list of radio rows: whole row tappable, 48 dp, read by TalkBack as one radio button with its note. */
@Composable
internal fun <T> RadioList(options: List<Pair<T, Pair<String, String?>>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for ((value, text) in options) {
            val (label, note) = text
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (note != null) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    explain: String?,
    options: List<Pair<T, Pair<String, String?>>>,
    selected: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (explain != null) {
                    Text(explain, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.padding(top = 8.dp))
                }
                RadioList(options, choice) { choice = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(choice) }) { Text(stringResource(R.string.devices_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_rename_title, current)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.devices_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.devices_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

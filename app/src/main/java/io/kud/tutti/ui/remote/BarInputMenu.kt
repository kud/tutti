package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.TuttiSheet

/** A row of a bar input's long-press menu, in the order the menu shows them. */
enum class BarInputItem { PAIR, RENAME, HIDE, PIN }

/**
 * The menu's rows (gesture audit item 7): pairing first on Bluetooth, the most useful there;
 * Rename; Hide, except for the input playing now (hiding it would leave the row without its
 * tick); "Pin to dock" last, as everywhere.
 */
fun barInputMenu(value: String, current: String?): List<BarInputItem> = buildList {
    if (value == "bluetooth") add(BarInputItem.PAIR)
    add(BarInputItem.RENAME)
    if (value != current) add(BarInputItem.HIDE)
    add(BarInputItem.PIN)
}

/** The row's inputs once his hidden ones are out; the current input always stays. */
fun visibleBarInputs(choices: List<String>, hidden: Set<String>, current: String?): List<String> =
    choices.filter { it !in hidden || it == current }

/** His name for an input when he gave one, else the segment's own word. */
fun barInputName(value: String, full: String, names: Map<String, String>): String =
    names[value]?.trim()?.takeIf { it.isNotEmpty() } ?: barInputSegmentLabel(value, full)

@Composable
fun BarInputMenuSheet(
    name: String,
    items: List<BarInputItem>,
    onItem: (BarInputItem) -> Unit,
    onDismiss: () -> Unit,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.bar_input_menu_title, name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            for (item in items) {
                when (item) {
                    BarInputItem.PAIR -> MenuRow(R.drawable.ic_bluetooth, stringResource(R.string.bar_input_pair), stringResource(R.string.bar_input_pair_detail)) { onItem(item) }
                    BarInputItem.RENAME -> MenuRow(R.drawable.ic_edit, stringResource(R.string.bar_input_rename), stringResource(R.string.bar_input_rename_detail)) { onItem(item) }
                    BarInputItem.HIDE -> MenuRow(R.drawable.ic_visibility_off, stringResource(R.string.bar_input_hide), stringResource(R.string.bar_input_hide_detail)) { onItem(item) }
                    BarInputItem.PIN -> MenuRow(R.drawable.ic_add, stringResource(R.string.pin_to_dock), stringResource(R.string.bar_input_pin_detail)) { onItem(item) }
                }
            }
        }
    }
}

/** His own name for an input; empty, or "Original name", goes back to the bar's. */
@Composable
fun RenameBarInputDialog(original: String, current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current ?: original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bar_input_rename_title, original)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(MAX_NAME) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text.trim().takeIf { it.isNotEmpty() && it != original }) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.bar_input_rename_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private const val MAX_NAME = 16

@Composable
private fun MenuRow(@DrawableRes icon: Int, name: String, detail: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .clickable(role = Role.Button, onClickLabel = name, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

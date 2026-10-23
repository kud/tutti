package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import io.kud.tutti.R

/** A settings row that opens a menu of options. [reason] greys it and says why. */
@Composable
fun <T> ChoiceRow(
    label: String,
    valueLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,
    @DrawableRes icon: Int? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        SettingsRow(
            title = label,
            detail = reason ?: valueLabel,
            icon = icon,
            enabled = reason == null && options.isNotEmpty(),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/**
 * A settings row with a switch. [notice] says the setting is inactive for a reason outside tutti
 * while the switch keeps tutti's own preference; [footer] carries an action that explains it.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    reason: String? = null,
    @DrawableRes icon: Int? = null,
    notice: String? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val feedback = rememberFeedback()
    val toggle: (Boolean) -> Unit = {
        feedback.select()
        onCheckedChange(it)
    }
    SettingsRow(
        title = label,
        detail = reason ?: supporting,
        icon = icon,
        notice = notice.takeIf { reason == null },
        enabled = reason == null,
        onClick = { toggle(!checked) },
        role = Role.Switch,
        footer = footer,
        modifier = modifier,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = toggle,
                enabled = reason == null,
                thumbContent = if (checked) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    )
}

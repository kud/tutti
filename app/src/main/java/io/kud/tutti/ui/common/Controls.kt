package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import io.kud.tutti.R

/**
 * A settings row that opens a menu of options. [reason] greys it and says why. [refused] options
 * stay listed and tappable (a retry), marked with the warning glyph and the word, and the row
 * says which value the bar refused. [onPin] lets a long press on an option pin it to the dock.
 */
@Composable
fun <T> ChoiceRow(
    label: String,
    valueLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,
    @DrawableRes icon: Int? = null,
    refused: Set<T> = emptySet(),
    onPin: ((T, String) -> Unit)? = null,
    canPin: (T) -> Boolean = { true },
) {
    var expanded by remember { mutableStateOf(false) }
    val pinLabel = stringResource(R.string.pin_to_dock)
    val pinFeedback = rememberFeedback()
    val refusedWord = stringResource(R.string.choice_refused)
    val refusedNote = options.filter { it.first in refused }.joinToString { it.second }.ifEmpty { null }
        ?.let { stringResource(R.string.choice_refused_note, it) }
    Box(modifier.fillMaxWidth()) {
        SettingsRow(
            title = label,
            detail = reason ?: valueLabel,
            notice = if (reason == null) refusedNote?.let { "$valueLabel · $it" } else null,
            icon = icon,
            enabled = reason == null && options.isNotEmpty(),
            onClick = { expanded = true },
        )
        TuttiMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((value, text) in options) {
                val isRefused = value in refused
                DropdownMenuItem(
                    text = { Text(if (isRefused) "$text · $refusedWord" else text) },
                    leadingIcon = if (isRefused) {
                        { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    // A value's menu would have one row, so its long press is "Pin to dock" itself
                    // (design.md, gestures). Only offered where the caller can pin that value.
                    modifier = if (onPin != null && canPin(value)) {
                        Modifier.holdForMenu(pinLabel, RectangleShape) {
                            pinFeedback.longPress()
                            expanded = false
                            onPin(value, text)
                        }
                    } else {
                        Modifier
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

/**
 * Every pop-up menu in tutti: the theme's medium shape (12 dp, the corners the rows and cards use)
 * on a raised tonal surface, rather than Material's 4 dp default, which read as a system popup.
 */
@Composable
fun TuttiMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

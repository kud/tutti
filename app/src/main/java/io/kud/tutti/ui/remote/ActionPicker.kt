package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.ShortcutKeys
import io.kud.tutti.ui.common.ToggleFeatures
import io.kud.tutti.ui.common.featureLabel
import io.kud.tutti.ui.common.glyphRes
import io.kud.tutti.ui.common.keyLabel

private data class Choice(
    val action: DockAction,
    val label: String,
    @DrawableRes val icon: Int,
    /** For an app: its package, so the row shows the TV's cached icon. */
    val app: String? = null,
)

/** Picks what a dock slot or a long press does: an app, an input, a bar toggle, a scene or a TV key. */
@Composable
fun ActionPickerDialog(
    title: String,
    state: TuttiUiState,
    allowRemove: Boolean,
    onPick: (DockAction?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sections = buildList {
        add(
            stringResource(R.string.picker_apps) to state.apps.ifEmpty { AppLibrary.all(emptyList()) }.filterNot { it.packageName in state.prefs.hiddenApps }.map {
                Choice(DockAction.LaunchApp(it.packageName, it.name), it.name, R.drawable.ic_apps, it.packageName)
            },
        )
        if (state.tv.inputs.isNotEmpty()) {
            add(
                stringResource(R.string.picker_inputs) to state.tv.inputs.map {
                    val label = it.label.ifBlank { it.title }
                    Choice(DockAction.Input(it.uri, label), label, R.drawable.ic_input)
                },
            )
        }
        add(
            stringResource(R.string.picker_bar) to ToggleFeatures.map {
                val label = stringResource(featureLabel(it))
                Choice(DockAction.ToggleBar(it.key), label, R.drawable.ic_speaker)
            },
        )
        if (state.scenes.isNotEmpty()) {
            add(
                stringResource(R.string.picker_scenes) to state.scenes.map {
                    Choice(DockAction.RunScene(it.scene.id), it.scene.name, glyphRes(it.scene.glyph))
                },
            )
        }
        add(stringResource(R.string.picker_keys) to ShortcutKeys.map { Choice(DockAction.Key(it), keyLabel(it), R.drawable.ic_tv) })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                for ((header, choices) in sections) {
                    item(key = "h-$header") {
                        Text(
                            header,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(choices, key = { "$header-${it.label}-${it.action.hashCode()}" }) { choice ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(choice.action) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (choice.app != null) {
                                AppIcon(choice.label, state.appIcons[choice.app], size = 24.dp, corner = 6.dp, packageName = choice.app)
                            } else {
                                Icon(painterResource(choice.icon), contentDescription = null, modifier = Modifier.size(24.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (allowRemove) {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.picker_remove)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

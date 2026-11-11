package io.kud.tutti.ui.remote

import android.content.ClipboardManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.apps.AppDestinations
import io.kud.tutti.protocol.apps.AppShortcut
import io.kud.tutti.protocol.apps.LinkProbeResult
import io.kud.tutti.ui.common.TuttiSheet
import io.kud.tutti.ui.settings.outcomeWords
import kotlinx.coroutines.launch

/**
 * Where an app opens (Erwann, 2026-11-11: « configurer directement un bouton sur un simple clic…
 * aller directement à souscription »). The words and glyph for a destination link: null or blank is
 * "As usual" (the app resumes where it was), a curated link its tab, anything else "Link".
 */
@Composable
internal fun destinationLabel(link: String?): String {
    if (link.isNullOrBlank()) return stringResource(R.string.dest_as_usual)
    return AppDestinations.shortcutFor(link)?.let { stringResource(shortcutLabel(it)) } ?: stringResource(R.string.dest_link_label)
}

@DrawableRes
internal fun destinationIcon(link: String): Int =
    AppDestinations.shortcutFor(link)?.let(::shortcutIcon) ?: R.drawable.ic_link

/**
 * The destination's glyph on a pin or app tile, bottom-end, ringed in the tile's own colour like the
 * Sound badge. Silent to TalkBack: the tile says the destination in words.
 */
@Composable
internal fun DestinationBadge(link: String, color: Color, cutout: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(18.dp)
            .clearAndSetSemantics {}
            .clip(CircleShape)
            .background(cutout)
            .padding(2.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(destinationIcon(link)), contentDescription = null, tint = cutout, modifier = Modifier.size(10.dp))
    }
}

/** "Opens at · Subscriptions ›": the row that opens the picker, in the app's menu and the pin editor. */
@Composable
internal fun OpensAtRow(choice: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.dest_opens_at_row, choice),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * "Open YouTube at": As usual, the curated tabs, then a link. With [forPin], "Same as the app"
 * leads (a null link) and "As usual" is the explicit [AppDestinations.AS_USUAL]; for the app's own
 * default, "As usual" is null. [onPick] gets the chosen link.
 */
@Composable
internal fun DestinationPicker(
    appName: String,
    packageName: String,
    current: String?,
    forPin: Boolean,
    appDefault: String?,
    onPick: (String?) -> Unit,
    onTry: suspend (String) -> LinkProbeResult,
    onDismiss: () -> Unit,
) {
    val curated = remember(packageName) { AppDestinations.curated(packageName) }
    val startCustom = !current.isNullOrBlank() && AppDestinations.shortcutFor(current) == null
    var linkOpen by remember { mutableStateOf(startCustom) }
    var text by remember { mutableStateOf(if (startCustom) current.orEmpty() else "") }
    var checked by remember { mutableStateOf(startCustom) }
    var tried by remember { mutableStateOf<LinkProbeResult?>(null) }
    var trying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val valid = AppDestinations.accepts(packageName, text)
    val schemes = remember(packageName) { AppDestinations.ownSchemes(packageName).sorted() }

    TuttiSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.dest_picker_title, appName), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.selectableGroup()) {
                if (forPin) {
                    Choice(
                        R.drawable.ic_apps,
                        stringResource(R.string.dest_same_as_app),
                        stringResource(R.string.dest_same_as_app_detail, destinationLabel(appDefault)),
                        selected = !linkOpen && current == null,
                    ) { onPick(null) }
                }
                Choice(
                    R.drawable.ic_history,
                    stringResource(R.string.dest_as_usual),
                    stringResource(R.string.dest_as_usual_detail),
                    selected = !linkOpen && if (forPin) current == AppDestinations.AS_USUAL else current.isNullOrBlank(),
                ) { onPick(if (forPin) AppDestinations.AS_USUAL else null) }
                curated.forEach { shortcut ->
                    Choice(shortcutIcon(shortcut), stringResource(shortcutLabel(shortcut)), null, selected = !linkOpen && current == shortcut.link) {
                        onPick(shortcut.link)
                    }
                }
                Choice(R.drawable.ic_link, stringResource(R.string.dest_link), null, selected = linkOpen) { linkOpen = true }
            }
            if (linkOpen) {
                val showError = checked && text.isNotBlank() && !valid
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim(); tried = null },
                    singleLine = true,
                    label = { Text(stringResource(R.string.dest_link_label)) },
                    isError = showError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    supportingText = {
                        if (showError) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.dest_link_invalid))
                            }
                        } else {
                            Text(
                                if (schemes.isEmpty()) stringResource(R.string.dest_link_hint_web)
                                else stringResource(R.string.dest_link_hint, schemes.joinToString { "$it://" }),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused && text.isNotEmpty()) checked = true },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                        if (!pasted.isNullOrEmpty()) {
                            text = pasted
                            checked = true
                            tried = null
                        }
                    }) {
                        Icon(painterResource(R.drawable.ic_paste), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.dest_paste))
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        enabled = valid && !trying,
                        onClick = {
                            trying = true
                            scope.launch {
                                tried = onTry(text)
                                trying = false
                            }
                        },
                    ) { Text(stringResource(R.string.dest_try)) }
                    Button(enabled = valid, onClick = { onPick(text) }) { Text(stringResource(R.string.dest_done)) }
                }
                tried?.let {
                    Text(
                        outcomeWords(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Choice(@DrawableRes icon: Int, label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

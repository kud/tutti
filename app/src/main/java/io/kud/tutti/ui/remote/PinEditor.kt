package io.kud.tutti.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.PinGlyphs
import io.kud.tutti.ui.common.actionIcon
import io.kud.tutti.ui.common.actionLabel
import io.kud.tutti.ui.common.pinGlyphLabel
import io.kud.tutti.ui.common.pinGlyphRes

/**
 * A pin's editor, opened by a long press on it: a name, an icon and what a tap does. Phase one of
 * "Mes boutons": one action per pin; steps, waits and a pin's own long press come later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinEditorSheet(
    index: Int,
    slot: DockSlot,
    state: TuttiUiState,
    onSave: (DockSlot) -> Unit,
    onLoadTv: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        PinEditorContent(index, slot, state, onSave, onLoadTv)
    }
}

@Composable
fun PinEditorContent(
    index: Int,
    slot: DockSlot,
    state: TuttiUiState,
    onSave: (DockSlot) -> Unit,
    onLoadTv: () -> Unit,
    startPicking: Boolean = false,
) {
    var action by remember(slot) { mutableStateOf(slot.primary) }
    var name by rememberSaveable(slot) { mutableStateOf(slot.label.orEmpty()) }
    var icon by rememberSaveable(slot) { mutableStateOf(slot.icon) }
    var picking by remember { mutableStateOf(startPicking) }
    val auto = action?.let { actionLabel(it, state) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pin_editor_title, index + 1),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Button(onClick = {
                onSave(slot.copy(primary = action, label = name.trim().ifBlank { null }, icon = icon))
            }) { Text(stringResource(R.string.pin_save)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinPreview(action, name.trim().ifBlank { auto.orEmpty() }, icon, state)
            Spacer(Modifier.width(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                singleLine = true,
                label = { Text(stringResource(R.string.pin_name)) },
                placeholder = auto?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                supportingText = { Text(stringResource(R.string.pin_name_hint)) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.pin_icon), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        val autoDescription = stringResource(R.string.pin_icon_auto_desc)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item(key = "auto") {
                GlyphChoice(
                    icon = action?.let { actionIcon(it, state) } ?: R.drawable.ic_add,
                    description = autoDescription,
                    selected = icon == null,
                    outlined = true,
                ) { icon = null }
            }
            items(PinGlyphs, key = { it }) { glyph ->
                GlyphChoice(
                    icon = pinGlyphRes(glyph),
                    description = stringResource(R.string.pin_icon_desc, stringResource(pinGlyphLabel(glyph))),
                    selected = icon == glyph,
                ) { icon = glyph }
            }
        }
        if (icon == null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pin_icon_auto),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pin_on_tap), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { picking = true }
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val current = action
            if (current is DockAction.LaunchApp) {
                AppIcon(current.label, state.appIcons[current.packageName], size = 24.dp, corner = 6.dp, packageName = current.packageName)
            } else {
                Icon(
                    painterResource(current?.let { actionIcon(it, state) } ?: R.drawable.ic_add),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                auto ?: stringResource(R.string.editor_none),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { picking = true }) {
                Text(stringResource(if (current == null) R.string.pin_choose else R.string.pin_change))
            }
        }

        if (slot.primary != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onSave(DockSlot(secondary = slot.secondary)) }) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pin_remove))
            }
        }
    }

    if (picking) {
        ActionPickerSheet(
            title = stringResource(R.string.picker_title),
            state = state,
            allowRemove = false,
            onPick = { picked ->
                if (picked != null) action = picked
                picking = false
            },
            onLoadTv = onLoadTv,
            onDismiss = { picking = false },
        )
    }
}

/** The pin as the dock will draw it. */
@Composable
private fun PinPreview(action: DockAction?, label: String, icon: String?, state: TuttiUiState) {
    Column(
        Modifier
            .size(width = 80.dp, height = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon == null && action is DockAction.LaunchApp) {
            AppIcon(action.label, state.appIcons[action.packageName], size = 24.dp, corner = 6.dp, packageName = action.packageName)
        } else {
            Icon(
                painterResource(icon?.let(::pinGlyphRes) ?: action?.let { actionIcon(it, state) } ?: R.drawable.ic_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label.ifBlank { stringResource(R.string.dock_add) },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GlyphChoice(icon: Int, description: String, selected: Boolean, outlined: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainerHighest)
            .then(if (outlined && !selected) Modifier.border(1.dp, colors.outline, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Long enough for "Ciné" or "Netflix ado", short enough to stay one line on a pin. */
private const val MAX_NAME = 24

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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.MacroStep
import io.kud.tutti.platform.data.asAction
import io.kud.tutti.platform.data.asSteps
import io.kud.tutti.platform.data.flattened
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.PinGlyphs
import io.kud.tutti.ui.common.actionIcon
import io.kud.tutti.ui.common.actionLabel
import io.kud.tutti.ui.common.pinGlyphLabel
import io.kud.tutti.ui.common.pinGlyphRes

/**
 * A pin's editor, opened by a long press on it: a name, an icon (or two letters), what a tap
 * does and what its own long press does. A tap runs one action or several steps with waits in
 * between; "Try" plays them once on the TV before anything is saved, which is what makes a wait
 * easy to tune. Steps move with up / down (TalkBack reads them as such) and go with their ✕.
 * No conditions, no loops: a list of steps, and waits capped by the controller.
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
    onTry: (DockAction) -> Unit = {},
    onAddPage: () -> Unit = {},
    onRemovePage: (page: Int) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        PinEditorContent(index, slot, state, onSave, onLoadTv, onTry = onTry, onAddPage = onAddPage, onRemovePage = onRemovePage)
    }
}

/** Which picker is open over the editor. */
private sealed interface Picking {
    data object AddStep : Picking
    data class ReplaceStep(val at: Int) : Picking
    data object LongPress : Picking
}

@Composable
fun PinEditorContent(
    index: Int,
    slot: DockSlot,
    state: TuttiUiState,
    onSave: (DockSlot) -> Unit,
    onLoadTv: () -> Unit,
    startPicking: Boolean = false,
    onTry: (DockAction) -> Unit = {},
    onAddPage: () -> Unit = {},
    onRemovePage: (page: Int) -> Unit = {},
) {
    val steps = remember(slot) { mutableStateListOf<MacroStep>().apply { addAll(slot.primary.asSteps()) } }
    var secondary by remember(slot) { mutableStateOf(slot.secondary) }
    var name by rememberSaveable(slot) { mutableStateOf(slot.label.orEmpty()) }
    var icon by rememberSaveable(slot) { mutableStateOf(slot.icon) }
    var letters by rememberSaveable(slot) { mutableStateOf(slot.letters.orEmpty()) }
    var lettersOn by rememberSaveable(slot) { mutableStateOf(!slot.letters.isNullOrBlank()) }
    var picking by remember { mutableStateOf<Picking?>(if (startPicking) Picking.AddStep else null) }
    val action = steps.toList().asAction(name.trim())
    val auto = action?.let { actionLabel(it, state) }
    val pages = state.dock.pages.size
    val page = index / DockConfig.PAGE_SIZE

    fun built() = slot.copy(
        primary = action,
        secondary = secondary,
        label = name.trim().ifBlank { null },
        icon = icon.takeUnless { lettersOn },
        letters = letters.trim().takeIf { lettersOn && it.isNotEmpty() },
    )

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
            Button(onClick = { onSave(built()) }) { Text(stringResource(R.string.pin_save)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PinPreview(action, name.trim().ifBlank { auto.orEmpty() }, icon.takeUnless { lettersOn }, letters.takeIf { lettersOn }, state)
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
        val lettersDescription = stringResource(R.string.pin_letters_desc)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            item(key = "auto") {
                GlyphChoice(
                    icon = action?.let { actionIcon(it, state) } ?: R.drawable.ic_add,
                    description = autoDescription,
                    selected = icon == null && !lettersOn,
                    outlined = true,
                ) {
                    icon = null
                    lettersOn = false
                }
            }
            item(key = "letters") {
                LettersChoice(description = lettersDescription, selected = lettersOn) { lettersOn = true }
            }
            items(PinGlyphs, key = { it }) { glyph ->
                GlyphChoice(
                    icon = pinGlyphRes(glyph),
                    description = stringResource(R.string.pin_icon_desc, stringResource(pinGlyphLabel(glyph))),
                    selected = icon == glyph && !lettersOn,
                ) {
                    icon = glyph
                    lettersOn = false
                }
            }
        }
        when {
            lettersOn -> {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = letters,
                    onValueChange = { letters = it.filterNot(Char::isWhitespace).take(MAX_LETTERS) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.pin_letters_field)) },
                    supportingText = { Text(stringResource(R.string.pin_letters_hint)) },
                    modifier = Modifier.width(200.dp),
                )
            }
            icon == null -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.pin_icon_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pin_on_tap), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.pin_steps_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (steps.isEmpty()) {
            ActionRow(null, state, onClick = { picking = Picking.AddStep }) {
                TextButton(onClick = { picking = Picking.AddStep }) { Text(stringResource(R.string.pin_choose)) }
            }
        } else {
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                steps.forEachIndexed { at, step ->
                    if (at > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerLow)
                    StepRow(
                        number = at + 1,
                        step = step,
                        state = state,
                        first = at == 0,
                        last = at == steps.lastIndex,
                        alone = steps.size == 1,
                        onChange = { picking = Picking.ReplaceStep(at) },
                        onWait = { steps[at] = MacroStep.Wait(it) },
                        onMove = { by -> steps.add(at + by, steps.removeAt(at)) },
                        onRemove = { steps.removeAt(at) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val addStep = stringResource(R.string.pin_add_step_desc)
            val addWait = stringResource(R.string.pin_add_wait_desc)
            val tryDescription = stringResource(R.string.pin_try_desc)
            if (steps.isNotEmpty()) {
                OutlinedButton(onClick = { picking = Picking.AddStep }, modifier = Modifier.semantics { contentDescription = addStep }) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pin_add_step))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { if (steps.size < MAX_STEPS) steps += MacroStep.Wait(DEFAULT_WAIT_MS) },
                    enabled = steps.size < MAX_STEPS,
                    modifier = Modifier.semantics { contentDescription = addWait },
                ) {
                    Icon(painterResource(R.drawable.ic_timer), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pin_add_wait))
                }
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { action?.let(onTry) },
                enabled = action != null,
                modifier = Modifier.semantics { contentDescription = tryDescription },
            ) {
                Icon(painterResource(R.drawable.ic_play_pause), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pin_try))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pin_on_long_press), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        ActionRow(secondary, state, empty = stringResource(R.string.pin_long_none), onClick = { picking = Picking.LongPress }) {
            if (secondary != null) {
                TextButton(onClick = { secondary = null }) { Text(stringResource(R.string.pin_long_clear)) }
            } else {
                TextButton(onClick = { picking = Picking.LongPress }) { Text(stringResource(R.string.pin_choose)) }
            }
        }
        if (secondary != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pin_long_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pin_pages), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.pin_page_of, page + 1, pages) +
                if (pages >= DockConfig.MAX_PAGES) " · " + stringResource(R.string.pin_pages_full) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            if (pages < DockConfig.MAX_PAGES) {
                TextButton(onClick = onAddPage) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pin_page_add))
                }
            }
            if (pages > 1) {
                TextButton(onClick = { onRemovePage(page) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pin_page_remove))
                }
            }
        }
        if (pages > 1) {
            Text(
                stringResource(R.string.pin_page_remove_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (slot.primary != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { onSave(DockSlot()) }) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pin_remove))
            }
        }
    }

    when (val target = picking) {
        null -> Unit
        Picking.LongPress -> ActionPickerSheet(
            title = stringResource(R.string.pin_long_title, name.trim().ifBlank { auto ?: stringResource(R.string.pin_editor_title, index + 1) }),
            state = state,
            allowRemove = secondary != null,
            onPick = { picked ->
                // A long press is one gesture: a macro picked here keeps only its first action.
                secondary = (picked as? DockAction.Macro)?.steps?.flattened()?.filterIsInstance<MacroStep.Run>()?.firstOrNull()?.action ?: picked
                picking = null
            },
            onLoadTv = onLoadTv,
            onDismiss = { picking = null },
        )
        else -> ActionPickerSheet(
            title = stringResource(R.string.picker_title),
            state = state,
            allowRemove = false,
            onPick = { picked ->
                if (picked != null) {
                    val added = picked.asSteps()
                    if (target is Picking.ReplaceStep && target.at in steps.indices) {
                        steps.removeAt(target.at)
                        steps.addAll(target.at, added)
                    } else {
                        steps.addAll(added.take(MAX_STEPS - steps.size))
                    }
                }
                picking = null
            },
            onLoadTv = onLoadTv,
            onDismiss = { picking = null },
        )
    }
}

/** One action as a row: its icon, its name, and a trailing button. */
@Composable
private fun ActionRow(
    action: DockAction?,
    state: TuttiUiState,
    onClick: () -> Unit,
    empty: String = stringResource(R.string.editor_none),
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionGlyph(action, state)
        Spacer(Modifier.width(16.dp))
        Text(
            action?.let { actionLabel(it, state) } ?: empty,
            style = MaterialTheme.typography.bodyLarge,
            color = if (action == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

@Composable
private fun ActionGlyph(action: DockAction?, state: TuttiUiState) {
    if (action is DockAction.LaunchApp) {
        AppIcon(action.label, state.appIcons[action.packageName], size = 24.dp, corner = 6.dp, packageName = action.packageName)
    } else {
        Icon(
            painterResource(action?.let { actionIcon(it, state) } ?: R.drawable.ic_add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * One step: an action (tap to change it) or a wait (− / + by half a second). Up, down and ✕ sit
 * at the end of every row in the same place; a lone action keeps only its ✕-free row, since
 * emptying the pin has its own button.
 */
@Composable
private fun StepRow(
    number: Int,
    step: MacroStep,
    state: TuttiUiState,
    first: Boolean,
    last: Boolean,
    alone: Boolean,
    onChange: () -> Unit,
    onWait: (Long) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = when (step) {
        is MacroStep.Run -> actionLabel(step.action, state)
        is MacroStep.Wait -> stringResource(R.string.pin_wait, seconds(step.millis))
    }
    val description = stringResource(R.string.pin_step_desc, number, label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (step is MacroStep.Run) Modifier.clickable(onClick = onChange) else Modifier)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (step) {
            is MacroStep.Run -> {
                Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
                    ActionGlyph(step.action, state)
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            is MacroStep.Wait -> {
                Icon(painterResource(R.drawable.ic_timer), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(4.dp))
                SmallIcon(R.drawable.ic_remove, stringResource(R.string.pin_wait_less), enabled = step.millis > WAIT_STEP_MS) {
                    onWait((step.millis - WAIT_STEP_MS).coerceAtLeast(WAIT_STEP_MS))
                }
                Text(
                    stringResource(R.string.pin_wait_seconds, seconds(step.millis)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurface,
                    modifier = Modifier.semantics { contentDescription = description },
                )
                SmallIcon(R.drawable.ic_add, stringResource(R.string.pin_wait_more), enabled = step.millis < MAX_WAIT_MS) {
                    onWait((step.millis + WAIT_STEP_MS).coerceAtMost(MAX_WAIT_MS))
                }
                Spacer(Modifier.weight(1f))
            }
        }
        if (!alone) {
            SmallIcon(R.drawable.ic_arrow_up, stringResource(R.string.pin_step_up), enabled = !first) { onMove(-1) }
            SmallIcon(R.drawable.ic_arrow_down, stringResource(R.string.pin_step_down), enabled = !last) { onMove(1) }
            SmallIcon(R.drawable.ic_close, stringResource(R.string.pin_step_remove), enabled = true, onClick = onRemove)
        } else if (step is MacroStep.Run) {
            TextButton(onClick = onChange) { Text(stringResource(R.string.pin_change)) }
        }
    }
}

@Composable
private fun SmallIcon(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(20.dp))
    }
}

/** "1,5" in French, "1.5" in English; whole seconds drop the decimal. */
@Composable
private fun seconds(millis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (millis % 1000 == 0L) (millis / 1000).toString() else String.format(locale, "%.1f", millis / 1000.0)
}

@Composable
private fun LettersChoice(description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.pin_letters),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

/** The pin as the dock will draw it. */
@Composable
private fun PinPreview(action: DockAction?, label: String, icon: String?, letters: String?, state: TuttiUiState) {
    Column(
        Modifier
            .size(width = 80.dp, height = 72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PinFace(action, icon, letters, state, MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 22.dp)
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

/** Two letters fit a pin's face at the icon's size; a third would shrink or clip. */
private const val MAX_LETTERS = 2

/** Enough for "TV on, wait, HDMI 2, wait, Cinema" and then some; the list stays scannable. */
private const val MAX_STEPS = 12

private const val DEFAULT_WAIT_MS = 1_000L
private const val WAIT_STEP_MS = 500L

/** The controller caps a wait at 30 s too; the editor never offers more than it will honour. */
private const val MAX_WAIT_MS = 30_000L

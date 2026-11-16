package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import io.kud.tutti.R

/**
 * A settings row whose options are too many or too long for segments: a tap opens a [ChoiceSheet]
 * of radio rows, never a floating menu (Erwann, 2026-11-12, on dev.6: the "Vibrate for" menu opened
 * at the far left, a row too high, over the row above; « On doit revoir ça partout »). Short lists
 * go through [OptionsRow], which picks segments when they fit and this row when they don't.
 * [selected] puts the tick on the current option; when null it is the option named [valueLabel].
 * [reason] greys the row and says why. [refused] options stay listed and tappable (a retry),
 * marked with the warning glyph and the word, and the row says which value the bar refused.
 * [onPin] lets a long press on an option pin it to the dock.
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
    selected: T? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val refusedNote = options.filter { it.first in refused }.joinToString { it.second }.ifEmpty { null }
        ?.let { stringResource(R.string.choice_refused_note, it) }
    SettingsRow(
        title = label,
        detail = reason ?: valueLabel,
        notice = if (reason == null) refusedNote?.let { "$valueLabel · $it" } else null,
        icon = icon,
        enabled = reason == null && options.isNotEmpty(),
        onClick = { open = true },
        role = Role.Button,
        modifier = modifier,
    )
    if (open) {
        ChoiceSheet(
            title = label,
            selected = selected ?: options.firstOrNull { it.second == valueLabel }?.first,
            options = options,
            onSelect = {
                open = false
                onSelect(it)
            },
            onDismiss = { open = false },
            refused = refused,
            onPin = onPin,
            canPin = canPin,
        )
    }
}

/**
 * Every list of choices in tutti that doesn't fit segments: a [TuttiSheet] titled with the
 * setting, one radio row per option. The chosen row carries the tick and its name in semibold, so
 * it never rests on colour; TalkBack hears each row as a radio button, selected or not. A refused
 * option wears the warning glyph and the word. Choosing closes the sheet; a long press pins an
 * option where [onPin] allows it (design.md, gestures: a value's menu would have one row).
 */
@Composable
fun <T> ChoiceSheet(
    title: String,
    selected: T?,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    refused: Set<T> = emptySet(),
    onPin: ((T, String) -> Unit)? = null,
    canPin: (T) -> Boolean = { true },
) {
    val feedback = rememberFeedback()
    val pinLabel = stringResource(R.string.pin_to_dock)
    val refusedWord = stringResource(R.string.choice_refused)
    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .selectableGroup()
                    .padding(bottom = 16.dp),
            ) {
                for ((value, text) in options) {
                    val chosen = value == selected
                    val isRefused = value in refused
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = chosen,
                                role = Role.RadioButton,
                                onClick = {
                                    if (!chosen) feedback.select()
                                    onSelect(value)
                                },
                            )
                            .then(
                                if (onPin != null && canPin(value)) {
                                    Modifier.holdForMenu(pinLabel, RectangleShape) {
                                        feedback.longPress()
                                        onDismiss()
                                        onPin(value, text)
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            when {
                                chosen -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                isRefused -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            if (isRefused) "$text · $refusedWord" else text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
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
 * Every pop-up menu of actions in tutti (⋮, a long press's menu), never a list of values to choose
 * from, which is [OptionsRow] or [ChoiceSheet]: the theme's medium shape (12 dp, the corners the rows and cards use)
 * on a raised tonal surface, rather than Material's 4 dp default, which read as a system popup.
 */
@Composable
fun TuttiMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CoversRemote(active = expanded)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

/**
 * A choice among a short list: segments when [SegmentFit] says every label fits at this width and
 * font size, else the [ChoiceRow] menu (2026-10-26). Segments show every option in place;
 * the chosen one is filled and carries a tick above its name, so it never rests on colour. The
 * name and, while the setting can't change, its reason sit above the row, whose segments are then
 * disabled. A tap is optimistic like the menu's: the caller's state moves first, and a failure
 * puts the tick back where the device says it is. TalkBack hears the setting, then each option as
 * a radio button; a long press pins an option where [onPin] allows it, as in the menu.
 */
@Composable
fun <T> OptionsRow(
    label: String,
    selected: T?,
    valueLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    reason: String? = null,
    onPin: ((T, String) -> Unit)? = null,
    canPin: (T) -> Boolean = { true },
    supporting: String? = null,
    notice: String? = null,
    @DrawableRes icon: Int? = null,
    refused: Set<T> = emptySet(),
    footer: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.labelMedium
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val count = options.size.coerceAtLeast(1)
        val textWidth = (maxWidth - ROW_PADDING * 2) / count - SEGMENT_INSET * 2
        val lines = remember(options, textWidth, style, density) {
            val px = with(density) { textWidth.roundToPx() }
            if (px <= 0) {
                null
            } else {
                SegmentFit.lines(
                    options.map { (_, text) ->
                        val words = text.split(' ').filter { it.isNotEmpty() }
                        SegmentFit.Label(
                            longestWordFits = words.all { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= px },
                            lines = measurer.measure(text, style, constraints = Constraints(maxWidth = px)).lineCount,
                        )
                    },
                )
            }
        }
        if (lines == null) {
            ChoiceRow(
                label, valueLabel, options, onSelect,
                reason = reason, icon = icon, refused = refused, onPin = onPin, canPin = canPin, selected = selected,
            )
        } else {
            val refusedNote = options.filter { it.first in refused }.joinToString { it.second }.ifEmpty { null }
                ?.let { stringResource(R.string.choice_refused_note, it) }
            Segments(
                label, selected, options, onSelect, reason, onPin, canPin, textWidth, lines, style,
                detail = supporting,
                notice = notice ?: refusedNote,
                icon = icon,
                refused = refused,
                footer = footer,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(
    label: String,
    selected: T?,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    reason: String?,
    onPin: ((T, String) -> Unit)?,
    canPin: (T) -> Boolean,
    textWidth: Dp,
    lines: Int,
    style: TextStyle,
    detail: String?,
    notice: String?,
    @DrawableRes icon: Int?,
    refused: Set<T>,
    footer: (@Composable () -> Unit)?,
) {
    val feedback = rememberFeedback()
    val pinLabel = stringResource(R.string.pin_to_dock)
    val refusedWord = stringResource(R.string.choice_refused)
    val said = reason ?: notice ?: detail
    val described = if (said == null) label else "$label, $said"
    Column(
        Modifier
            .fillMaxWidth()
            .background(LocalRowSurface.current)
            .padding(horizontal = ROW_PADDING, vertical = 12.dp),
    ) {
        val fade = Modifier.alpha(if (reason == null) 1f else DISABLED_ALPHA)
        // The name and its line are said once, as the row's description, not again as loose text.
        // The header wears a settings row's dress (icon, name, line, notice), the segments under it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                RowIcon(icon, fade)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = fade.clearAndSetSemantics {})
                when {
                    reason != null -> Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = fade.clearAndSetSemantics {},
                    )
                    notice != null -> Box(Modifier.clearAndSetSemantics {}) { Notice(notice) }
                    detail != null -> Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
                footer?.invoke()
            }
        }
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics { contentDescription = described },
        ) {
            options.forEachIndexed { index, (value, text) ->
                val chosen = value == selected
                val isRefused = value in refused
                val shape = SegmentedButtonDefaults.itemShape(index, options.size)
                SegmentedButton(
                    selected = chosen,
                    onClick = {
                        if (!chosen) {
                            feedback.select()
                            onSelect(value)
                        }
                    },
                    enabled = reason == null,
                    shape = shape,
                    // The tick sits above the name instead, so the name keeps the segment's width.
                    icon = {},
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { if (isRefused) stateDescription = refusedWord }
                        .then(
                            if (onPin != null && canPin(value)) {
                                Modifier.holdForMenu(pinLabel, shape, enabled = reason == null, onRefused = feedback::bump) { onPin(value, text) }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // Wider than Material's padded slot: the name may use the segment's inset too.
                    Column(Modifier.requiredWidth(textWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(SegmentedButtonDefaults.IconSize)) {
                            when {
                                chosen -> Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                                isRefused -> Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(SegmentedButtonDefaults.IconSize))
                            }
                        }
                        Text(
                            text,
                            style = style,
                            fontWeight = if (chosen) FontWeight.SemiBold else null,
                            textAlign = TextAlign.Center,
                            minLines = lines,
                            maxLines = lines,
                        )
                    }
                }
            }
        }
    }
}

private val ROW_PADDING = 16.dp

/** How much of a segment's side the name may not use: its border and a hair. */
private val SEGMENT_INSET = 4.dp

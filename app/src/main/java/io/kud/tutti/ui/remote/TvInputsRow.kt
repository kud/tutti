package io.kud.tutti.ui.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.ui.settings.spoken

/** One of the TV's inputs in the Apps sheet: its [name] in words, whether it is on screen, whether something is plugged in. */
data class TvInputChip(val uri: String, val name: String, val current: Boolean, val connected: Boolean)

/** The Apps sheet's inputs row: the TV's inputs in its own order, and [reason] when a tap can't reach the TV now. */
data class TvInputsRow(val inputs: List<TvInputChip>, val reason: String?)

/**
 * The TV's inputs in the Apps sheet (benchmark idea, Iris, 2026-10-25; moved there from the Power
 * sheet the same day: an input is what's on the TV, like an app), or null to hide them. They come from `getCurrentExternalInputsStatus`, so only IP control lists them: without
 * it, or before the TV answered, there is no row rather than a guessed "HDMI 1…4". Each is named
 * as the TV names it ([spoken]): the label the TV holds for it, which is the plugged-in device's
 * CEC name when it sent one, else the port's title. A switch rides IP control like the screen-off
 * row, so it goes down with REST and while the TV is on standby.
 */
fun tvInputsRow(
    ipControl: Boolean,
    inputs: List<ExternalInput>,
    currentUri: String?,
    restOnline: Boolean,
    tvPowered: Boolean?,
    downReason: String,
    standbyReason: String,
): TvInputsRow? {
    if (!ipControl || inputs.isEmpty()) return null
    val chips = inputs.map { TvInputChip(it.uri, it.spoken(), current = it.uri == currentUri, connected = it.connected) }
    val reason = when {
        !restOnline -> downReason
        tvPowered == false -> standbyReason
        else -> null
    }
    return TvInputsRow(chips, reason)
}

/**
 * The TV's inputs ([tvInputsRow]) as one row of chips, scrolling sideways when they don't fit:
 * each named as the TV names it, the one on screen marked with a tick and said "Current input",
 * one with nothing plugged in quieter. A tap switches to it (the caller closes the sheet, as an
 * app tile does), a long press pins it to the dock.
 * Down: the reason in words above them, the chips in the dashed dress and not clickable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvInputChips(row: TvInputsRow, onInput: (String) -> Unit, onPin: ((String, String) -> Unit)?) {
    val down = row.reason != null
    val currentWords = stringResource(R.string.tv_input_current)
    val emptyWords = stringResource(R.string.tv_input_disconnected)
    val pinWords = stringResource(R.string.tv_input_pin)
    val dash = androidx.compose.runtime.remember { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) }
    val outline = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.tv_inputs),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        row.reason?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (input in row.inputs) {
                val fill = if (input.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                val ink = when {
                    down || !input.connected -> MaterialTheme.colorScheme.onSurfaceVariant
                    input.current -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .then(
                            if (down) {
                                Modifier.drawBehind {
                                    drawRoundRect(
                                        outline,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                                    )
                                }
                            } else {
                                Modifier.background(fill, RoundedCornerShape(12.dp))
                            },
                        )
                        .combinedClickable(
                            enabled = !down,
                            role = Role.Button,
                            onClickLabel = input.name,
                            onLongClickLabel = onPin?.let { pinWords },
                            onLongClick = onPin?.let { pin -> { pin(input.uri, input.name) } },
                            onClick = { onInput(input.uri) },
                        )
                        .semantics(mergeDescendants = true) {
                            selected = input.current
                            when {
                                row.reason != null -> stateDescription = row.reason
                                input.current -> stateDescription = currentWords
                                !input.connected -> stateDescription = emptyWords
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(if (input.current) R.drawable.ic_check else R.drawable.ic_input),
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(input.name, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

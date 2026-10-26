package io.kud.tutti.ui.remote

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiSheet

/**
 * What the Input button's sheet shows (Erwann, 2026-10-26): the TV's inputs as the Apps sheet's
 * row has them ([tvInputsRow]: same order, names, current and empty flags), and [reason] when no
 * input can be chosen now, in words. The TV's own input menu is always offered below them.
 */
data class TvInputSheet(val inputs: List<TvInputChip>, val reason: String?) {
    /** Down: the rows are drawn but can't be tapped, under [reason]. */
    val down: Boolean get() = reason != null
}

/**
 * The Input sheet's model. Unlike the Apps sheet's row, which simply isn't there without a list,
 * this sheet was asked for, so it always says something: without IP control set up, [noIpReason]
 * and no rows (never a guessed "HDMI 1…4"); with the list known, the rows, down under the Apps
 * sheet's own reasons while IP control isn't answering or the TV is on standby; with IP control
 * set but no list yet, the down or standby reason when there is one, else [unlistedReason].
 */
fun tvInputSheet(
    ipControl: Boolean,
    inputs: List<ExternalInput>,
    currentUri: String?,
    restOnline: Boolean,
    tvPowered: Boolean?,
    noIpReason: String,
    downReason: String,
    standbyReason: String,
    unlistedReason: String,
): TvInputSheet {
    if (!ipControl) return TvInputSheet(emptyList(), noIpReason)
    val row = tvInputsRow(ipControl, inputs, currentUri, restOnline, tvPowered, downReason, standbyReason)
    if (row != null) return TvInputSheet(row.inputs, row.reason)
    val reason = when {
        !restOnline -> downReason
        tvPowered == false -> standbyReason
        else -> unlistedReason
    }
    return TvInputSheet(emptyList(), reason)
}

/**
 * The Input button's tap (Erwann, 2026-10-26): tutti's input chooser, a `TuttiSheet` titled "TV
 * input". On a phone the inputs read better as a vertical list of large rows than as the Apps
 * sheet's sideways chips: every name shows whole, the thumb doesn't hunt sideways, and there is
 * room to say each input's state in words. The rows are the Power sheet's (64 dp, 16 dp corners)
 * with no glyph per row, since one would repeat down the list (design.md, Visual): the current
 * input wears a tick and "Current input", an empty port "Nothing connected", never colour alone.
 * A tap switches and closes the sheet; a long press pins the input to the dock. Down, the reason
 * sits above the rows, which take the dashed dress and can't be tapped. Below a rule, "Open the
 * TV's input menu" sends the TV's Input key and closes, as every menu key in a sheet does
 * ([keyClosesSheet]). [onOpen] runs once as the sheet opens, to refresh what is plugged in.
 */
@Composable
fun InputSheet(
    sheet: TvInputSheet,
    onInput: (uri: String) -> Unit,
    onPin: ((uri: String, name: String) -> Unit)?,
    onTvMenu: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onOpen() }
    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = {
                Text(
                    stringResource(R.string.tv_input_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sheet.reason?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                for (input in sheet.inputs) InputRow(input, sheet.reason, onInput, onPin)
                if (sheet.inputs.isNotEmpty() || sheet.reason != null) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
                PowerRow(
                    R.drawable.ic_input,
                    stringResource(R.string.tv_input_menu),
                    stringResource(R.string.tv_input_menu_detail),
                    onClick = onTvMenu,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputRow(
    input: TvInputChip,
    /** Why no input can be chosen now; the row is down while it is set. */
    reason: String?,
    onInput: (String) -> Unit,
    onPin: ((String, String) -> Unit)?,
) {
    val currentWords = stringResource(R.string.tv_input_current)
    val emptyWords = stringResource(R.string.tv_input_disconnected)
    val pinWords = stringResource(R.string.tv_input_pin)
    val down = reason != null
    val detail = when {
        input.current -> currentWords
        !input.connected -> emptyWords
        else -> null
    }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) }
    val outline = MaterialTheme.colorScheme.outline
    val fill = if (input.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = when {
        down || !input.connected -> MaterialTheme.colorScheme.onSurfaceVariant
        input.current -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                // Down wears the remote's one down dress: no fill, a dashed ring, the reason in words above.
                if (down) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            outline,
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                        )
                    }
                } else {
                    Modifier.background(fill, RoundedCornerShape(16.dp))
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
                // Down, TalkBack hears why first, as on the Apps sheet's chips.
                (reason ?: detail)?.let { stateDescription = it }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tick's place is kept on every row, so the names line up whichever is current.
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (input.current) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(input.name, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

package io.kud.tutti.ui.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.hid.RemoteLink
import io.kud.tutti.ui.common.TuttiSheet
import kotlinx.coroutines.delay

internal fun TargetKind.glyph(): Int = when (this) {
    TargetKind.TV -> R.drawable.ic_tv
    TargetKind.COMPUTER -> R.drawable.ic_laptop
}

@Composable
internal fun linkWords(link: RemoteLink): String = stringResource(
    when (link) {
        RemoteLink.LAN -> R.string.target_link_lan
        RemoteLink.BLUETOOTH -> R.string.target_link_bluetooth
        RemoteLink.NONE -> R.string.target_link_none
    },
)

/**
 * Where the remote's keys go (Iris, 2026-10-25): the target's glyph and its name in words, a quiet
 * outlined chip beside the system chip. A tap opens [TargetSheet]. Shown only when there is a
 * choice ([targetChipShown]); the caller doesn't place it otherwise.
 *
 * TalkBack hears the chip as "Remote controls Laptop" and, once per real change, "Controlling
 * Laptop" ([targetAnnounced]): a target must hold [TARGET_SETTLE_MS] before it is said, so a blip
 * of the TV's input says nothing.
 */
@Composable
fun TargetChip(current: TargetRow, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.target_chip_label, current.name)
    val openLabel = stringResource(R.string.target_sheet_open)
    val announce = stringResource(R.string.target_announce, current.name)
    val view = LocalView.current
    var announced by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current.id) {
        if (announced == null) {
            announced = current.id
            return@LaunchedEffect
        }
        delay(TARGET_SETTLE_MS)
        if (targetAnnounced(announced, current.id)) {
            view.announceForAccessibility(announce)
            announced = current.id
        }
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(max = 160.dp)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick(label = openLabel) {
                    onOpen()
                    true
                }
            },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(painterResource(current.kind.glyph()), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text(
                current.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The devices present now, nothing configured-but-absent (that lives in Settings › Devices). One
 * 48 dp row each: glyph, name, the link its keys use in words; the current one ticked, never told
 * by colour alone. TalkBack reads each as a radio button. A tap points the remote and closes.
 */
@Composable
fun TargetSheet(rows: List<TargetRow>, onPick: (TargetRow) -> Unit, onDismiss: () -> Unit, onShowOnTv: (TargetRow) -> Unit = {}) {
    TuttiSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.target_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .semantics { heading() },
        )
        Column(Modifier.padding(bottom = 16.dp)) {
            for (row in rows) TargetSheetRow(row, onShowOnTv = { onShowOnTv(row) }) { onPick(row) }
        }
    }
}

@Composable
private fun TargetSheetRow(row: TargetRow, onShowOnTv: () -> Unit = {}, onClick: () -> Unit) {
    val words = linkWords(row.link)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = row.current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(row.kind.glyph()), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(words, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // One gesture for "the Mac on the TV": wake, switch input, target it (item 10).
        if (row.showOnTv) {
            androidx.compose.material3.TextButton(onClick = onShowOnTv, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.target_show_on_tv), maxLines = 1)
            }
        }
        if (row.current) {
            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}

/**
 * The quiet link indicator (Iris, 2026-10-25): glyph and the word "Bluetooth", only while keys go
 * over Bluetooth ([linkIndicatorShown]). Not a button and not a status colour; TalkBack reads
 * "Keys go over Bluetooth".
 */
@Composable
fun LinkIndicator(link: RemoteLink, modifier: Modifier = Modifier) {
    if (!linkIndicatorShown(link)) return
    val said = stringResource(R.string.link_indicator_bluetooth)
    Row(
        modifier.clearAndSetSemantics { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_bluetooth), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(R.string.link_bluetooth),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

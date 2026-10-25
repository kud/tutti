package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.devices.DeviceKind
import io.kud.tutti.ui.common.TuttiSheet

@DrawableRes
internal fun ConnMark.glyph(): Int = when (this) {
    ConnMark.OK -> R.drawable.ic_check_circle
    ConnMark.WORKING -> R.drawable.ic_refresh
    ConnMark.ASLEEP -> R.drawable.ic_bedtime
    ConnMark.TODO -> R.drawable.ic_circle_outline
    ConnMark.PROBLEM -> R.drawable.ic_warning
}

@DrawableRes
private fun DeviceKind.glyph(): Int = when (this) {
    DeviceKind.TV -> R.drawable.ic_tv
    DeviceKind.SOUNDBAR -> R.drawable.ic_speaker
    DeviceKind.COMPUTER -> R.drawable.ic_laptop
}

@Composable
private fun linkName(link: ConnLink): String = stringResource(
    when (link) {
        ConnLink.GOOGLE_TV -> R.string.conn_link_google_tv
        ConnLink.SONY_IP -> R.string.conn_link_sony_ip
        ConnLink.NETWORK -> R.string.conn_link_network
        ConnLink.BLUETOOTH -> R.string.conn_link_bluetooth
    },
)

@Composable
private fun stateWords(words: ConnWords): String = stringResource(
    when (words) {
        ConnWords.CONNECTED -> R.string.conn_connected
        ConnWords.CONNECTING -> R.string.conn_connecting
        ConnWords.RECONNECTING -> R.string.conn_reconnecting
        ConnWords.STANDBY -> R.string.conn_standby
        ConnWords.NOT_SET_UP -> R.string.conn_not_set_up
        ConnWords.UNREACHABLE -> R.string.conn_unreachable
        ConnWords.KEY_REFUSED -> R.string.conn_key_refused
        ConnWords.CODE_TO_RENEW -> R.string.conn_code_to_renew
        ConnWords.NEEDS_PAIRING -> R.string.conn_needs_pairing
        ConnWords.READY -> R.string.conn_ready
        ConnWords.NOT_PAIRED -> R.string.conn_not_paired
        ConnWords.BLUETOOTH_OFF -> R.string.conn_bluetooth_off
        ConnWords.NEEDS_PERMISSION -> R.string.conn_needs_permission
        ConnWords.BUSY -> R.string.conn_busy
        ConnWords.NOT_ADDED -> R.string.conn_not_added
    },
)

/** What a link gives, in the remote's own words, so the reader knows what a missing one costs. */
@Composable
private fun gives(kind: DeviceKind, link: ConnLink): String = stringResource(
    when (link) {
        ConnLink.GOOGLE_TV -> R.string.conn_gives_google_tv
        ConnLink.SONY_IP -> R.string.conn_gives_sony_ip
        ConnLink.NETWORK -> R.string.conn_gives_bar
        ConnLink.BLUETOOTH -> if (kind == DeviceKind.COMPUTER) R.string.conn_gives_bt_computer else R.string.conn_gives_bt_tv
    },
)

@Composable
private fun actionLabel(action: ConnAction): String = stringResource(
    when (action) {
        ConnAction.WAKE_TV -> R.string.card_wake_tv_short
        ConnAction.PAIR_TV, ConnAction.PAIR_COMPUTER -> R.string.conn_action_pair
        ConnAction.SET_UP_IP, ConnAction.SET_UP_BAR -> R.string.conn_action_set_up
        ConnAction.ADD_BAR -> R.string.conn_action_add
        ConnAction.CONTROL -> R.string.conn_action_control
        ConnAction.OPEN_DEVICES -> R.string.conn_action_devices
    },
)

/**
 * The « Connexions » sheet, from the device pill (Erwann, 2026-10-25). Which device the remote is
 * aiming at first, then each device with each link on its own line: the state's glyph and its
 * words, what the link gives, and its one next step as a 48 dp text button. TalkBack reads a line
 * as one sentence ("Google TV, connected: keys, keyboard, apps"), the button after it. Settings
 * stays one row away at the bottom.
 */
@Composable
fun ConnectionsSheet(
    devices: List<ConnDevice>,
    /** The line the pill's trouble points at ([problemLine]): brought into view and set apart. */
    focus: ConnFocus? = null,
    onAction: (ConnDevice, ConnAction) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.conn_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .semantics { heading() },
        )
        devices.firstOrNull { it.targeted }?.name?.let { name ->
            Text(
                stringResource(R.string.conn_target, name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 8.dp)) {
            for (device in devices) DeviceBlock(device, focus) { onAction(device, it) }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val settings = stringResource(R.string.settings_title)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClick = onOpenSettings)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(settings, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceBlock(device: ConnDevice, focus: ConnFocus?, onAction: (ConnAction) -> Unit) {
    val name = device.name ?: stringResource(
        when (device.kind) {
            DeviceKind.TV -> R.string.conn_device_tv
            DeviceKind.SOUNDBAR -> R.string.conn_device_bar
            DeviceKind.COMPUTER -> R.string.conn_device_computer
        },
    )
    val heading = if (device.targeted) stringResource(R.string.conn_device_targeted, name) else name
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 24.dp, end = 24.dp, top = 12.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = heading
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(device.kind.glyph()), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        // Where the keys go: a tick and the word, never colour alone.
        if (device.targeted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.conn_targeted_tag), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    for (line in device.lines) LinkLine(device.kind, line, focus == ConnFocus(device.id, line.link), onAction)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkLine(kind: DeviceKind, line: ConnLine, focused: Boolean, onAction: (ConnAction) -> Unit) {
    // The pill's trouble: this line comes into view on a tonal band, its step at the thumb. The
    // glyph and words already say what is wrong; the band only says "this one".
    val bring = remember { BringIntoViewRequester() }
    if (focused) LaunchedEffect(Unit) { bring.bringIntoView() }
    val title = stringResource(R.string.conn_line, linkName(line.link), stateWords(line.words))
    val what = gives(kind, line.link)
    val said = stringResource(R.string.conn_line_said, linkName(line.link), stateWords(line.words), what)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .bringIntoViewRequester(bring)
            .padding(start = 52.dp, end = 4.dp)
            .then(
                if (focused) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                },
            )
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .semantics(mergeDescendants = true) { contentDescription = said },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painterResource(line.words.mark.glyph()),
                null,
                Modifier.padding(top = 2.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        line.action?.let { action ->
            TextButton(onClick = { onAction(action) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel(action)) }
        }
    }
}

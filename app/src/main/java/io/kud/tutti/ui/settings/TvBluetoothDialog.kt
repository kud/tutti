package io.kud.tutti.ui.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.controller.TargetController
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.bluetooth.HidPhase
import io.kud.tutti.platform.bluetooth.HidSupport
import kotlinx.coroutines.launch

/**
 * The TV row's « Bluetooth de secours » (Erwann, 2026-10-25): setup only, never a remote. Make the
 * phone visible, pair from the TV's Remotes & accessories, done; its state in words, and
 * « Oublier » once paired. The touchpad, pointer and keyboard live on the main remote, which uses
 * this link by itself when the TV's network doesn't answer. The first host that connects while
 * this is open becomes the TV's link, unless it is a computer.
 */
@Composable
fun TvBluetoothDialog(targets: TargetController, hid: HidLink, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hidState by hid.state.collectAsStateWithLifecycle()
    val target by targets.state.collectAsStateWithLifecycle()
    val tv = target.list.tv
    val linked = tv?.bluetooth?.address
    val connected = linked != null && target.hidConnectedTo == tv.id

    DisposableEffect(targets) {
        targets.tvPairingShown()
        onDispose { }
    }
    LaunchedEffect(hidState.phase, hidState.host) {
        val host = hidState.host
        if (hidState.phase == HidPhase.CONNECTED && host != null) targets.linkTvBluetooth(host)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { targets.tvPairingShown() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { targets.tvPairingShown() }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val (status, action) = when {
        hidState.support == HidSupport.UNSUPPORTED -> stringResource(R.string.bt_unsupported) to null
        hidState.phase == HidPhase.NEEDS_PERMISSION ->
            stringResource(R.string.bt_status_permission) to (stringResource(R.string.bt_allow) to { permissions.launch(hid.permissions()) })
        hidState.phase == HidPhase.BLUETOOTH_OFF ->
            stringResource(R.string.bt_status_off) to (stringResource(R.string.bt_turn_on) to { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) })
        hidState.phase == HidPhase.BUSY ->
            stringResource(R.string.bt_status_busy) to (stringResource(R.string.bt_retry) to { targets.settingsLeft(); targets.tvPairingShown() })
        connected -> stringResource(R.string.bt_backup_connected) to null
        linked != null -> stringResource(R.string.bt_backup_paired) to null
        hidState.phase == HidPhase.STOPPED || hidState.phase == HidPhase.REGISTERING -> stringResource(R.string.bt_status_starting) to null
        else -> stringResource(R.string.bt_backup_not_paired) to (stringResource(R.string.bt_make_visible) to {
            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null) },
        title = { Text(stringResource(R.string.bt_backup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bt_backup_explain), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(status, style = MaterialTheme.typography.titleSmall)
                }
                if (action != null) {
                    Button(onClick = action.second, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action.first) }
                }
                if (linked == null && hidState.support != HidSupport.UNSUPPORTED) {
                    Text(stringResource(R.string.bt_pair_steps), style = MaterialTheme.typography.bodyMedium)
                }
                if (tv != null && linked != null) {
                    Text(stringResource(R.string.bt_layout), style = MaterialTheme.typography.titleSmall)
                    val layouts = tv.layouts()
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        layouts.forEachIndexed { index, layout ->
                            SegmentedButton(
                                selected = layout == tv.layout(),
                                onClick = { scope.launch { targets.setLayout(tv.id, layout) } },
                                shape = SegmentedButtonDefaults.itemShape(index, layouts.size),
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(layoutLabel(layout), maxLines = 1) }
                        }
                    }
                    Text(stringResource(R.string.bt_layout_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.bt_role_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.bt_backup_done)) } },
        dismissButton = if (linked != null) {
            {
                TextButton(onClick = { scope.launch { targets.forgetTvBluetooth() } }) { Text(stringResource(R.string.settings_forget)) }
            }
        } else {
            null
        },
    )
}

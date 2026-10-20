package io.kud.salon.quick.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.salon.R
import io.kud.salon.quick.QuickPreferences
import io.kud.salon.quick.notification.QuickNotifications
import kotlinx.coroutines.launch

/**
 * The "Réglages › Préférences" row for the control notification. Drop it into the
 * preferences screen. Turning it on asks for the Android 13+
 * notification permission; a refusal leaves it off.
 */
@Composable
fun QuickNotificationSetting(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember(context) { QuickPreferences(context) }
    val wanted by preferences.notificationEnabled.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    // Bumped after a permission answer so `granted` is re-read.
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val granted = remember(permissionEpoch) { QuickNotifications.canPost(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permissionEpoch++
        scope.launch { preferences.setNotificationEnabled(ok) }
    }
    val checked = wanted && granted

    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { on ->
                    if (on && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        scope.launch { preferences.setNotificationEnabled(on) }
                    }
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.quick_pref_notification_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (wanted && !granted) R.string.quick_pref_notification_denied else R.string.quick_pref_notification_summary,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

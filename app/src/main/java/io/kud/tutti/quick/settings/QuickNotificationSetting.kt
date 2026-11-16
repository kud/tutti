package io.kud.tutti.quick.settings

import android.Manifest
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.quick.QuickPreferences
import io.kud.tutti.quick.notification.QuickNotifications
import io.kud.tutti.ui.common.SettingsRow
import kotlinx.coroutines.launch

/**
 * The "Réglages › Préférences" row for the control notification. Drop it into the
 * preferences screen. Turning it on asks for the Android 13+
 * notification permission; a refusal leaves it off.
 */
@Composable
fun QuickNotificationSetting(modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
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

    SettingsRow(
        title = stringResource(R.string.quick_pref_notification_title),
        detail = stringResource(
            if (wanted && !granted) R.string.quick_pref_notification_denied else R.string.quick_pref_notification_summary,
        ),
        icon = icon,
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = { on ->
                if (on && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    scope.launch { preferences.setNotificationEnabled(on) }
                }
            },
        ),
        trailing = { Switch(checked = checked, onCheckedChange = null) },
    )
}

package io.kud.tutti.quick.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.quick.QuickPreferences
import io.kud.tutti.ui.common.SettingsRow
import kotlinx.coroutines.launch

/** The switch that opens `tutti://scene/<id>` ([io.kud.tutti.quick.automation.SceneTriggerActivity]) to NFC and automation apps. */
@Composable
fun QuickAutomationSetting(modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val context = LocalContext.current
    val preferences = remember(context) { QuickPreferences(context) }
    val on by preferences.automationEnabled.collectAsStateWithLifecycle(initialValue = false)
    val scope = rememberCoroutineScope()
    SettingsRow(
        title = stringResource(R.string.quick_pref_automation_title),
        detail = stringResource(R.string.quick_pref_automation_summary),
        icon = icon,
        modifier = modifier.toggleable(
            value = on,
            role = Role.Switch,
            onValueChange = { scope.launch { preferences.setAutomationEnabled(it) } },
        ),
        trailing = { Switch(checked = on, onCheckedChange = null) },
    )
}

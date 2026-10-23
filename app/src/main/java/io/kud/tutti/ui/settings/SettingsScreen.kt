package io.kud.tutti.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.protocol.grpc.CloudPlaneState
import io.kud.tutti.quick.settings.QuickNotificationSetting
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.ProblemNote
import io.kud.tutti.ui.common.SectionTitle
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.common.actionLabel
import io.kud.tutti.ui.common.openBraviaConnect
import io.kud.tutti.ui.onboarding.PskDialog
import io.kud.tutti.ui.remote.ActionPickerSheet
import io.kud.tutti.ui.remote.secondaryLabel

private enum class Forget { TV, BAR }

@Composable
fun linkLabel(link: Link): String = stringResource(
    when (link) {
        Link.ABSENT -> R.string.link_absent
        Link.CONNECTING -> R.string.link_connecting
        Link.ONLINE -> R.string.link_online
        Link.UNREACHABLE -> R.string.link_unreachable
        Link.NEEDS_PAIRING -> R.string.link_needs_pairing
        Link.UNAUTHORIZED -> R.string.link_unauthorized
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: TuttiUiState,
    controller: TuttiController,
    onBack: () -> Unit,
    onOpenTv: () -> Unit,
    onOpenBar: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    onEditLayout: () -> Unit = {},
    onPair: () -> Unit,
) {
    val context = LocalContext.current
    var picker by remember { mutableStateOf<SecondaryButton?>(null) }
    var pskDialog by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<Forget?>(null) }
    var appsDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle(stringResource(R.string.settings_devices))
            val tv = state.devices.tv
            if (tv != null) {
                DeviceRow(
                    icon = R.drawable.ic_tv,
                    name = tv.name,
                    detail = stringResource(
                        R.string.settings_tv_status,
                        tv.host,
                        linkLabel(state.links.tv),
                        linkLabel(state.links.rest),
                    ),
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = onPair) { Text(stringResource(R.string.settings_pair_again)) }
                    TextButton(onClick = { pskDialog = true }) { Text(stringResource(R.string.settings_psk)) }
                    TextButton(onClick = { forget = Forget.TV }) { Text(stringResource(R.string.settings_forget)) }
                }
                ProblemNote(
                    message = null,
                    detail = listOfNotNull(state.linkNotes.tv, state.linkNotes.rest).joinToString("\n\n").ifEmpty { null },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val bar = state.devices.bar
            if (bar != null) {
                DeviceRow(
                    icon = R.drawable.ic_speaker,
                    name = bar.name,
                    detail = stringResource(R.string.settings_bar_status, bar.host, linkLabel(state.links.bar)),
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { forget = Forget.BAR }) { Text(stringResource(R.string.settings_forget)) }
                }
                ProblemNote(message = null, detail = state.linkNotes.bar, modifier = Modifier.padding(horizontal = 16.dp))
            }
            if (tv == null || bar == null) {
                TextButton(onClick = onPair, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.settings_add_device))
                }
            }

            SectionTitle(stringResource(R.string.settings_tv))
            LinkRow(
                title = stringResource(R.string.settings_tv_settings),
                detail = if (state.links.rest == Link.ONLINE) {
                    stringResource(R.string.settings_tv_settings_detail)
                } else {
                    stringResource(R.string.settings_tv_settings_needs, linkLabel(state.links.rest))
                },
                // Open whenever a TV is paired: offline, the screen explains how to turn IP control on.
                enabled = tv != null,
                onClick = onOpenTv,
            )

            SectionTitle(stringResource(R.string.settings_bar))
            LinkRow(
                title = stringResource(R.string.settings_bar_settings),
                detail = stringResource(R.string.settings_bar_settings_detail),
                enabled = bar != null,
                onClick = onOpenBar,
            )

            SectionTitle(stringResource(R.string.settings_shortcuts))
            LinkRow(
                title = stringResource(R.string.layout_entry),
                detail = stringResource(R.string.layout_entry_detail),
                enabled = true,
                onClick = onEditLayout,
            )
            for (button in SecondaryButton.entries) {
                val action = state.dock.longPress[button]
                LinkRow(
                    title = stringResource(R.string.settings_long_press, secondaryLabel(button)),
                    detail = action?.let { actionLabel(it, state) } ?: stringResource(R.string.editor_none),
                    enabled = true,
                    onClick = { picker = button },
                )
            }

            LinkRow(
                title = stringResource(R.string.settings_apps_shown),
                detail = stringResource(R.string.settings_apps_shown_detail, state.visibleApps.size, state.apps.size),
                enabled = state.apps.isNotEmpty(),
                onClick = { appsDialog = true },
            )

            SectionTitle(stringResource(R.string.settings_app))
            SwitchRow(
                label = stringResource(R.string.settings_haptics),
                supporting = stringResource(R.string.settings_haptics_detail),
                checked = state.prefs.haptics,
                onCheckedChange = controller::setHaptics,
            )
            ThemeRow(state.prefs.theme, controller::setTheme)
            SwitchRow(
                label = stringResource(R.string.settings_true_black),
                supporting = stringResource(R.string.settings_true_black_detail),
                checked = state.prefs.trueBlack,
                onCheckedChange = controller::setTrueBlack,
            )
            QuickNotificationSetting()

            SectionTitle(stringResource(R.string.settings_sony))
            LinkRow(
                title = stringResource(R.string.settings_sony_connection),
                detail = when (state.cloud) {
                    is CloudPlaneState.Unavailable -> stringResource(R.string.settings_sony_unavailable)
                    CloudPlaneState.Disabled -> stringResource(R.string.settings_sony_disabled)
                    CloudPlaneState.SignedOut -> stringResource(R.string.settings_sony_signed_out)
                    CloudPlaneState.Connecting -> stringResource(R.string.link_connecting)
                    is CloudPlaneState.Connected -> stringResource(R.string.link_online)
                },
                enabled = false,
                onClick = {},
            )
            Text(
                stringResource(R.string.settings_sony_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionTitle(stringResource(R.string.settings_developer))
            SwitchRow(
                label = stringResource(R.string.settings_developer_mode),
                supporting = stringResource(R.string.settings_developer_mode_detail),
                checked = state.prefs.developerMode,
                onCheckedChange = controller::setDeveloperMode,
            )
            if (state.prefs.developerMode) {
                LinkRow(
                    title = stringResource(R.string.settings_diagnostic),
                    detail = stringResource(R.string.settings_diagnostic_detail),
                    enabled = true,
                    onClick = onOpenDiagnostic,
                )
            }

            SectionTitle(stringResource(R.string.settings_bravia_connect))
            LinkRow(
                title = stringResource(R.string.sound_recalibrate),
                detail = stringResource(R.string.settings_recalibrate_detail),
                enabled = true,
                onClick = { openBraviaConnect(context) },
            )
        }
    }

    if (appsDialog) {
        AppsShownDialog(state, controller::setAppShown) { appsDialog = false }
    }
    picker?.let { button ->
        ActionPickerSheet(
            title = stringResource(R.string.longpress_assign_title, secondaryLabel(button)),
            state = state,
            allowRemove = state.dock.longPress[button] != null,
            onPick = {
                controller.assignLongPress(button, it)
                picker = null
            },
            onLoadTv = controller::loadTvSettings,
            onDismiss = { picker = null },
        )
    }
    if (pskDialog) {
        PskDialog(onSave = {
            controller.setPsk(it)
            pskDialog = false
        }, onDismiss = { pskDialog = false })
    }
    forget?.let { target ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(stringResource(R.string.settings_forget_title)) },
            text = {
                Text(stringResource(if (target == Forget.TV) R.string.settings_forget_tv_detail else R.string.settings_forget_bar_detail))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target == Forget.TV) controller.forgetTv() else controller.forgetBar()
                    forget = null
                }) { Text(stringResource(R.string.settings_forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DeviceRow(@DrawableRes icon: Int, name: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LinkRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            ThemeMode.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = entry == mode,
                    onClick = { onChange(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (entry) {
                                ThemeMode.SYSTEM -> R.string.settings_theme_system
                                ThemeMode.LIGHT -> R.string.settings_theme_light
                                ThemeMode.DARK -> R.string.settings_theme_dark
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** Every app tutti knows, each with a switch: hidden ones leave the Apps sheet and pickers. */
@Composable
private fun AppsShownDialog(state: TuttiUiState, onShown: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_apps_shown)) },
        text = {
            Column {
                Text(
                    stringResource(if (state.ipControl) R.string.settings_apps_shown_live else R.string.settings_apps_shown_static),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(state.apps, key = { it.packageName }) { app ->
                        val shown = app.packageName !in state.prefs.hiddenApps
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = shown, role = Role.Switch) { onShown(app.packageName, it) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.name, state.appIcons[app.packageName], packageName = app.packageName)
                            Spacer(Modifier.width(12.dp))
                            Text(app.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(checked = shown, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_apps_shown_done)) } },
    )
}

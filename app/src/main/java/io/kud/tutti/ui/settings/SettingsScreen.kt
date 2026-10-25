package io.kud.tutti.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.platform.data.VibrationLevel
import io.kud.tutti.protocol.grpc.CloudPlaneState
import io.kud.tutti.quick.settings.QuickNotificationSetting
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.ChoiceRow
import io.kud.tutti.ui.common.OpensChevron
import io.kud.tutti.ui.common.ProblemNote
import io.kud.tutti.ui.common.RevealRow
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.common.SystemFeedback
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

/**
 * Settings as grouped cards (Iris's Lawnchair-style mock, 2026-10-23). Every group is a
 * [SettingsGroup] and every line in it a [SettingsRow] or one of its wrappers, so the rhythm is
 * the same here, on the TV and soundbar screens.
 */
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
    onOpenAbout: () -> Unit = {},
    onAddBar: () -> Unit = {},
    onOpenUnlock: () -> Unit = {},
    onOpenTechnical: () -> Unit = {},
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
            val tv = state.devices.tv
            val bar = state.devices.bar
            SettingsGroup(stringResource(R.string.settings_devices)) {
                // What tutti can do, and what unlocks more: always first, so it's found.
                UnlockEntryRow(state, onOpenUnlock)
                if (tv != null) {
                    SettingsRow(
                        title = tv.name,
                        icon = R.drawable.ic_tv,
                        detail = stringResource(
                            R.string.settings_tv_status,
                            tv.host,
                            linkLabel(state.links.tv),
                            linkLabel(state.links.rest),
                        ),
                        footer = {
                            DeviceActions {
                                TextButton(onClick = onPair) { Text(stringResource(R.string.settings_pair_again)) }
                                TextButton(onClick = { pskDialog = true }) { Text(stringResource(R.string.settings_psk)) }
                                TextButton(onClick = { forget = Forget.TV }) { Text(stringResource(R.string.settings_forget)) }
                            }
                            ProblemNote(
                                message = null,
                                detail = listOfNotNull(state.linkNotes.tv, state.linkNotes.rest).joinToString("\n\n").ifEmpty { null },
                            )
                        },
                    )
                }
                if (bar != null) {
                    SettingsRow(
                        title = bar.name,
                        icon = R.drawable.ic_speaker,
                        detail = stringResource(R.string.settings_bar_status, bar.host, linkLabel(state.links.bar)),
                        footer = {
                            DeviceActions {
                                TextButton(onClick = { forget = Forget.BAR }) { Text(stringResource(R.string.settings_forget)) }
                            }
                            ProblemNote(message = null, detail = state.linkNotes.bar)
                        },
                    )
                }
                // No TV: the whole setup. A TV but no bar: the bar step alone (Erwann, 2026-10-24:
                // "c'est dur de revenir sur le onboarding"). Both: the whole setup again, on request.
                when {
                    tv == null -> SettingsRow(
                        title = stringResource(R.string.settings_add_device),
                        icon = R.drawable.ic_add,
                        onClick = onPair,
                        trailing = { OpensChevron() },
                    )
                    bar == null -> SettingsRow(
                        title = stringResource(R.string.settings_add_bar),
                        icon = R.drawable.ic_add,
                        detail = stringResource(R.string.settings_add_bar_detail),
                        onClick = onAddBar,
                        trailing = { OpensChevron() },
                    )
                }
                if (tv != null) {
                    SettingsRow(
                        title = stringResource(R.string.settings_setup_again),
                        icon = R.drawable.ic_refresh,
                        detail = stringResource(R.string.settings_setup_again_detail),
                        onClick = onPair,
                        trailing = { OpensChevron() },
                    )
                }
            }

            SettingsGroup(stringResource(R.string.settings_tv)) {
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
                    icon = R.drawable.ic_tv,
                    opens = true,
                )
            }

            SettingsGroup(stringResource(R.string.settings_bar)) {
                LinkRow(
                    title = stringResource(R.string.settings_bar_settings),
                    detail = stringResource(R.string.settings_bar_settings_detail),
                    enabled = bar != null,
                    onClick = onOpenBar,
                    icon = R.drawable.ic_speaker,
                    opens = true,
                )
            }

            SettingsGroup(stringResource(R.string.settings_volume_title)) {
                VolumeGuardRows(state.prefs, onChange = controller::setVolumeGuard)
            }

            SettingsGroup(stringResource(R.string.settings_shortcuts)) {
                LinkRow(
                    title = stringResource(R.string.layout_entry),
                    detail = stringResource(R.string.layout_entry_detail),
                    enabled = true,
                    onClick = onEditLayout,
                    icon = R.drawable.ic_edit,
                    opens = true,
                )
                for (button in SecondaryButton.entries) {
                    val action = state.dock.longPress[button]
                    LinkRow(
                        title = stringResource(R.string.settings_long_press, secondaryLabel(button)),
                        detail = action?.let { actionLabel(it, state) } ?: stringResource(R.string.editor_none),
                        enabled = true,
                        onClick = { picker = button },
                        icon = when (button) {
                            SecondaryButton.HOME -> R.drawable.ic_home
                            SecondaryButton.BACK -> R.drawable.ic_tv_back
                            SecondaryButton.PLAY -> R.drawable.ic_play_pause
                        },
                    )
                }
                LinkRow(
                    title = stringResource(R.string.settings_apps_shown),
                    detail = stringResource(R.string.settings_apps_shown_detail, state.visibleApps.size, state.apps.size),
                    enabled = state.apps.isNotEmpty(),
                    onClick = { appsDialog = true },
                    icon = R.drawable.ic_apps,
                )
            }

            SettingsGroup(stringResource(R.string.settings_app)) {
                ThemeRow(state.prefs.theme, controller::setTheme)
                SwitchRow(
                    label = stringResource(R.string.settings_true_black),
                    supporting = stringResource(R.string.settings_true_black_detail),
                    checked = state.prefs.trueBlack,
                    onCheckedChange = controller::setTrueBlack,
                    icon = R.drawable.ic_bedtime,
                )
                QuickNotificationSetting(icon = R.drawable.ic_bolt)
            }

            SettingsGroup(stringResource(R.string.settings_feedback)) {
                FeedbackRows(state.prefs, onVibration = controller::setVibration, onKeySounds = controller::setKeySounds)
            }

            SettingsGroup(stringResource(R.string.settings_sony)) {
                // Not a control yet: a quiet statement of where it stands, not a greyed row.
                SettingsRow(
                    title = stringResource(R.string.settings_sony_connection),
                    icon = R.drawable.ic_info,
                    detail = when (state.cloud) {
                        is CloudPlaneState.Unavailable -> stringResource(R.string.settings_sony_unavailable)
                        CloudPlaneState.Disabled -> stringResource(R.string.settings_sony_disabled)
                        CloudPlaneState.SignedOut -> stringResource(R.string.settings_sony_signed_out)
                        CloudPlaneState.Connecting -> stringResource(R.string.link_connecting)
                        is CloudPlaneState.Connected -> stringResource(R.string.link_online)
                    },
                    footer = {
                        Text(
                            stringResource(R.string.settings_sony_explain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    },
                )
            }

            // Advanced (Erwann, 2026-10-24: "une app de geek dans les options"): one switch, and
            // with it on, every technical screen in one place. The reverse map's readouts and
            // hidden options land here as rows, never on the everyday screens.
            SettingsGroup(stringResource(R.string.settings_advanced)) {
                SwitchRow(
                    label = stringResource(R.string.settings_developer_mode),
                    supporting = stringResource(R.string.settings_developer_mode_detail),
                    checked = state.prefs.developerMode,
                    onCheckedChange = controller::setDeveloperMode,
                    icon = R.drawable.ic_settings,
                )
                if (state.prefs.developerMode) {
                    LinkRow(
                        title = stringResource(R.string.settings_diagnostic),
                        detail = stringResource(R.string.settings_diagnostic_detail),
                        enabled = true,
                        onClick = onOpenDiagnostic,
                        icon = R.drawable.ic_article,
                        opens = true,
                    )
                    LinkRow(
                        title = stringResource(R.string.technical_title),
                        detail = stringResource(R.string.technical_entry_detail),
                        enabled = true,
                        onClick = onOpenTechnical,
                        icon = R.drawable.ic_code,
                        opens = true,
                    )
                }
            }

            SettingsGroup(stringResource(R.string.settings_bravia_connect)) {
                LinkRow(
                    title = stringResource(R.string.sound_recalibrate),
                    detail = stringResource(R.string.settings_recalibrate_detail),
                    enabled = true,
                    onClick = { openBraviaConnect(context) },
                    icon = R.drawable.ic_open,
                )
            }
            SettingsGroup(stringResource(R.string.about_entry)) {
                LinkRow(
                    title = stringResource(R.string.about_entry),
                    detail = stringResource(R.string.about_entry_detail),
                    enabled = true,
                    onClick = onOpenAbout,
                    icon = R.drawable.ic_info,
                )
            }
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
        PskDialog(hasKey = state.pskSet, onSave = {
            controller.setPsk(it)
            pskDialog = false
        }, onDismiss = { pskDialog = false })
    }
    forget?.let { target ->
        // Named, so the dialog says which device goes (Iris, 2026-10-25).
        val name = if (target == Forget.TV) state.devices.tv?.name else state.devices.bar?.name
        AlertDialog(
            onDismissRequest = { forget = null },
            title = {
                Text(if (name.isNullOrBlank()) stringResource(R.string.settings_forget_title) else stringResource(R.string.settings_forget_named, name))
            },
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

/** A device's actions under its status, wrapping rather than overflowing in French. */
@Composable
private fun DeviceActions(content: @Composable () -> Unit) {
    // Pulled left by the buttons' own padding, so their labels line up with the detail text.
    FlowRow(Modifier.offset(x = (-12).dp)) { content() }
}

/** A row that opens something: another screen ([opens] adds the chevron), a sheet or a dialog. */
@Composable
fun LinkRow(
    title: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
    @androidx.annotation.DrawableRes icon: Int? = null,
    opens: Boolean = false,
) {
    SettingsRow(
        title = title,
        detail = detail,
        icon = icon,
        enabled = enabled,
        onClick = onClick,
        trailing = if (opens) {
            { OpensChevron() }
        } else {
            null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    SettingsRow(
        title = stringResource(R.string.settings_theme),
        icon = R.drawable.ic_contrast,
        footer = {
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
                            maxLines = 1,
                        )
                    }
                }
            }
        },
    )
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

/**
 * Vibration and key sounds (Iris's spec, Erwann's decisions of 2026-10-23). tutti follows Android
 * and never overrides it: when the system's touch vibration or touch sounds are off, the row keeps
 * tutti's own switch but says it is inactive (warning glyph and words, never colour alone) and
 * carries the way to Android's settings, read again each time the screen resumes. The level is a
 * menu row rather than a segmented button, whose second label wrapped (Iris, 2026-10-23).
 */
@Composable
private fun FeedbackRows(prefs: AppPrefs, onVibration: (VibrationLevel) -> Unit, onKeySounds: (Boolean) -> Unit) {
    val context = LocalContext.current
    var system by remember { mutableStateOf(SystemFeedback.read(context)) }
    LifecycleResumeEffect(context) {
        system = SystemFeedback.read(context)
        onPauseOrDispose {}
    }
    val openSystem = {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Unit
    }
    val openLabel = stringResource(R.string.settings_open_system)
    val openAction = Modifier.semantics {
        customActions = listOf(CustomAccessibilityAction(openLabel) { openSystem(); true })
    }
    val level = prefs.vibrationLevel
    Column {
        SwitchRow(
            label = stringResource(R.string.settings_vibration),
            supporting = stringResource(R.string.settings_vibration_detail),
            notice = stringResource(R.string.settings_vibration_system_off).takeIf { !system.vibration },
            checked = level != VibrationLevel.OFF,
            onCheckedChange = { onVibration(if (it) VibrationLevel.ALL else VibrationLevel.OFF) },
            icon = R.drawable.ic_vibration,
            modifier = if (system.vibration) Modifier else openAction,
            footer = if (system.vibration) null else ({ OpenSystemLink(openLabel, openSystem) }),
        )
        RevealRow(visible = system.vibration && level != VibrationLevel.OFF) {
            val options = listOf(
                VibrationLevel.ALL to stringResource(R.string.settings_vibration_all),
                VibrationLevel.ESSENTIALS to stringResource(R.string.settings_vibration_essentials),
            )
            ChoiceRow(
                label = stringResource(R.string.settings_vibration_level),
                valueLabel = options.firstOrNull { it.first == level }?.second ?: options.first().second,
                options = options,
                onSelect = onVibration,
                icon = R.drawable.ic_tune,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    SwitchRow(
        label = stringResource(R.string.settings_key_sounds),
        supporting = stringResource(R.string.settings_key_sounds_detail),
        notice = stringResource(R.string.settings_key_sounds_system_off).takeIf { !system.sounds },
        checked = prefs.keySounds,
        onCheckedChange = onKeySounds,
        icon = R.drawable.ic_volume_up,
        modifier = if (system.sounds) Modifier else openAction,
        footer = if (system.sounds) null else ({ OpenSystemLink(openLabel, openSystem) }),
    )
}

/** The way to Android's settings, inside the row it explains: its own 48 dp target, not the row's tap. */
@Composable
private fun OpenSystemLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.offset(x = (-12).dp)) {
        Text(label)
    }
}

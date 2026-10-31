package io.kud.tutti.ui.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.bluetooth.HidSupport

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.kud.tutti.R
import io.kud.tutti.controller.IpCodeStatus
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.SecondaryButton
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.platform.data.VibrationLevel
import io.kud.tutti.protocol.bravia.TvSettingCatalog
import io.kud.tutti.protocol.grpc.CloudPlaneState
import io.kud.tutti.protocol.theatre.TheatreFeature
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
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.onboarding.IpControlDialog
import io.kud.tutti.ui.remote.ActionPickerSheet
import io.kud.tutti.ui.remote.barInputName
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
 * Settings' top level, arranged like Android's (Iris's proposal, approved by Erwann on 2026-10-26,
 * docs/proposals/settings-structure.md): one row per [SettingsCategory], each with its icon, its
 * name and one live line saying where it stands in words, opening its own screen. Every group
 * there is a [SettingsGroup] and every line a [SettingsRow], so the rhythm is the same on every screen.
 */
@Composable
fun SettingsScreen(
    state: TuttiUiState,
    onBack: () -> Unit,
    onOpen: (SettingsCategory) -> Unit,
) {
    val targets = rememberTargets()
    val computers = targets?.state?.collectAsStateWithLifecycle()?.value?.list?.computers?.map { it.name }.orEmpty()
    SettingsFrame(stringResource(R.string.settings_title), onBack) {
        SettingsGroup(null, Modifier.padding(top = 8.dp)) {
            for (category in SettingsCategory.entries) {
                SettingsRow(
                    title = categoryTitle(category),
                    icon = categoryIcon(category),
                    detail = categoryLine(category, state, computers),
                    detailMaxLines = 1,
                    // The TV's settings need a TV; the row says so in its line.
                    enabled = category != SettingsCategory.TV || state.devices.tv != null,
                    onClick = { onOpen(category) },
                    trailing = { OpensChevron() },
                )
            }
        }
    }
}

/** One of [SettingsMenu.pages], under Settings: its arrow and system back go up to the list. */
@Composable
fun SettingsPageScreen(
    category: SettingsCategory,
    state: TuttiUiState,
    controller: TuttiController,
    onBack: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    onEditLayout: () -> Unit,
    onPair: () -> Unit,
    onAddBar: () -> Unit,
    onOpenTechnical: () -> Unit,
    hid: HidLink? = null,
) {
    SettingsFrame(categoryTitle(category), onBack) {
        when (category) {
            SettingsCategory.DEVICES -> DevicesPage(state, controller, onPair, onAddBar, hid)
            SettingsCategory.REMOTE -> RemotePage(state, controller, onEditLayout)
            SettingsCategory.DISPLAY -> DisplayPage(state, controller)
            SettingsCategory.ADVANCED -> AdvancedPage(state, controller, onOpenDiagnostic, onOpenTechnical)
            // Their screens were there before Settings had categories (SettingsRoutes.category).
            SettingsCategory.UNLOCK, SettingsCategory.TV, SettingsCategory.SOUNDBAR, SettingsCategory.ABOUT -> Unit
        }
    }
}

/** What every Settings screen shares: the top bar whose arrow goes back one level, and the scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp), content = content)
    }
}

@Composable
private fun categoryTitle(category: SettingsCategory): String = stringResource(
    when (category) {
        SettingsCategory.UNLOCK -> R.string.unlock_entry
        SettingsCategory.DEVICES -> R.string.settings_devices
        SettingsCategory.TV -> R.string.settings_tv
        SettingsCategory.SOUNDBAR -> R.string.settings_bar
        SettingsCategory.REMOTE -> R.string.settings_remote_dock
        SettingsCategory.DISPLAY -> R.string.settings_display_feedback
        SettingsCategory.ADVANCED -> R.string.settings_advanced
        SettingsCategory.ABOUT -> R.string.about_entry
    },
)

@androidx.annotation.DrawableRes
private fun categoryIcon(category: SettingsCategory): Int = when (category) {
    SettingsCategory.UNLOCK -> R.drawable.ic_unlock
    SettingsCategory.DEVICES -> R.drawable.ic_laptop
    SettingsCategory.TV -> R.drawable.ic_tv
    SettingsCategory.SOUNDBAR -> R.drawable.ic_speaker
    SettingsCategory.REMOTE -> R.drawable.ic_dpad
    SettingsCategory.DISPLAY -> R.drawable.ic_contrast
    SettingsCategory.ADVANCED -> R.drawable.ic_code
    SettingsCategory.ABOUT -> R.drawable.ic_info
}

/** A category's live line: where it stands, in words, on one line ("Standard · Power saving: Off"). */
@Composable
private fun categoryLine(category: SettingsCategory, state: TuttiUiState, computers: List<String>): String = when (category) {
    SettingsCategory.UNLOCK -> unlockLine(state)
    SettingsCategory.DEVICES -> SettingsMenu.devicesLine(state, computers).let { line ->
        (listOfNotNull(line.trouble?.let { stringResource(R.string.settings_line_trouble, it.name, linkLabel(it.link)) }) + line.names)
            .joinToString(" · ")
            .ifEmpty { stringResource(R.string.settings_line_no_devices) }
    }
    SettingsCategory.TV -> when (val line = SettingsMenu.tvLine(state)) {
        SettingsMenu.TvLine.NoTv -> stringResource(R.string.settings_line_no_tv)
        is SettingsMenu.TvLine.IpControl -> stringResource(R.string.settings_tv_settings_needs, linkLabel(line.link))
        is SettingsMenu.TvLine.Settled -> listOfNotNull(
            line.pictureMode?.let { tvValueName(it, state.tv.settings.picture.firstOrNull { s -> s.target == "pictureMode" }) },
            line.powerSaving?.let { stringResource(R.string.tv_hub_power_saving, powerSavingModes().toMap()[it] ?: TvSettingCatalog.readable(it)) },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.settings_line_tv_parts) }
    }
    SettingsCategory.SOUNDBAR -> when (val line = SettingsMenu.barLine(state)) {
        SettingsMenu.BarLine.NoBar -> stringResource(R.string.settings_line_no_bar)
        is SettingsMenu.BarLine.Offline -> stringResource(R.string.devices_bar_status, linkLabel(line.link))
        is SettingsMenu.BarLine.Online -> listOfNotNull(
            state.devices.bar?.name?.takeIf { it.isNotBlank() },
            line.input?.let { barInputName(it, barValueLabel(TheatreFeature.INPUT, it), state.prefs.barInputNames) },
            pluralStringResource(R.plurals.settings_line_hidden_inputs, line.hidden, line.hidden).takeIf { line.hidden > 0 },
        ).joinToString(" · ")
    }
    SettingsCategory.REMOTE -> SettingsMenu.remoteLine(state).let { line ->
        listOf(
            if (line.pins == 0) stringResource(R.string.settings_line_no_pins) else pluralStringResource(R.plurals.settings_line_pins, line.pins, line.pins),
            if (line.longPresses == 0) {
                stringResource(R.string.settings_line_no_long_press)
            } else {
                pluralStringResource(R.plurals.settings_line_long_presses, line.longPresses, line.longPresses)
            },
        ).joinToString(" · ")
    }
    SettingsCategory.DISPLAY -> SettingsMenu.displayLine(state).let { line ->
        listOfNotNull(
            stringResource(
                when (line.theme) {
                    ThemeMode.SYSTEM -> R.string.settings_line_theme_system
                    ThemeMode.LIGHT -> R.string.settings_line_theme_light
                    ThemeMode.DARK -> R.string.settings_line_theme_dark
                },
            ),
            stringResource(R.string.settings_line_true_black).takeIf { line.trueBlack },
            stringResource(R.string.settings_line_dark_room).takeIf { line.darkRoom },
            stringResource(
                when (line.vibration) {
                    VibrationLevel.ALL -> R.string.settings_line_vibration_on
                    VibrationLevel.ESSENTIALS -> R.string.settings_line_vibration_essentials
                    VibrationLevel.OFF -> R.string.settings_line_vibration_off
                },
            ),
            stringResource(R.string.settings_line_key_sounds).takeIf { line.keySounds },
        ).joinToString(" · ")
    }
    SettingsCategory.ADVANCED -> stringResource(if (state.prefs.developerMode) R.string.settings_line_developer_on else R.string.settings_line_developer_off)
    SettingsCategory.ABOUT -> aboutLine()
}

/** About's line names a newer build while one waits, set aside or not (docs/design.md, "Updates"). */
@Composable
private fun aboutLine(): String = io.kud.tutti.ui.about.rememberUpdateCue().newer
    ?.let { stringResource(R.string.settings_line_update, it.toString()) }
    ?: stringResource(R.string.about_entry_detail)

/** Settings › Devices: the TV, the bar and the computers, adding one, setup again, the Sony account. */
@Composable
private fun DevicesPage(state: TuttiUiState, controller: TuttiController, onPair: () -> Unit, onAddBar: () -> Unit, hid: HidLink?) {
    var ipDialog by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf<Forget?>(null) }
    var adding by remember { mutableStateOf(false) }
    var tvBluetooth by remember { mutableStateOf(false) }
    val targets = rememberTargets()
    val target = targets?.state?.collectAsStateWithLifecycle()?.value
    // The phone can be a Bluetooth keyboard: the TV row offers it, and a computer can be added.
    val hidSupported = hid?.state?.collectAsStateWithLifecycle()?.value?.support == HidSupport.SUPPORTED
    if (hid != null) LaunchedEffect(hid) { hid.probe() }
    // Pairing from here holds the Bluetooth role; leaving Devices gives it back.
    if (targets != null) DisposableEffect(targets) { onDispose { targets.settingsLeft() } }

    val tv = state.devices.tv
    val bar = state.devices.bar
    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
        if (tv != null) {
            val ipRenew = !state.pskSet && state.ipCode == IpCodeStatus.RENEW
            SettingsRow(
                title = tv.name,
                icon = R.drawable.ic_tv,
                // Links in words (Erwann, 2026-10-25): "Google TV connected · IP control not set up".
                detail = listOfNotNull(
                    stringResource(R.string.devices_tv_status, linkLabel(state.links.tv), linkLabel(state.links.rest)),
                    stringResource(R.string.devices_bt_paired).takeIf { target?.list?.tv?.bluetooth != null },
                    stringResource(R.string.ipc_row_renew).takeIf { ipRenew },
                ).joinToString(" · "),
                footer = {
                    DeviceActions {
                        TextButton(onClick = onPair) { Text(stringResource(R.string.settings_pair_again)) }
                        // The code on the TV first, the PSK inside (Erwann, 2026-10-25).
                        TextButton(onClick = { ipDialog = true }) { Text(stringResource(if (ipRenew) R.string.ipc_renew_action else R.string.ipc_action)) }
                        // Setup only (Erwann, 2026-10-25): pairing and « Oublier », never a
                        // trackpad; the remote itself uses this link when the network drops.
                        if (hidSupported && targets != null) {
                            TextButton(onClick = { tvBluetooth = true }) { Text(stringResource(R.string.bt_backup_title)) }
                        }
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
                detail = stringResource(R.string.devices_bar_status, linkLabel(state.links.bar)),
                footer = {
                    DeviceActions {
                        TextButton(onClick = { forget = Forget.BAR }) { Text(stringResource(R.string.settings_forget)) }
                    }
                    ProblemNote(message = null, detail = state.linkNotes.bar)
                },
            )
        }
        if (targets != null && target != null) ComputerRows(targets, target, state.tv.inputs)
        // One way in for every device (Erwann, 2026-10-25: « Comment j'ajoute un appareil ? »):
        // it asks what first, offering the TV or the bar only when missing.
        if (targets != null) {
            AddDeviceRow(missing = tv == null || bar == null, onClick = { adding = true })
        } else if (tv == null) {
            SettingsRow(
                title = stringResource(R.string.settings_add_device),
                icon = R.drawable.ic_add,
                onClick = onPair,
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

    if (tvBluetooth && targets != null && hid != null) {
        TvBluetoothDialog(targets, hid, onDismiss = { tvBluetooth = false })
    }
    if (adding && targets != null) {
        AddDeviceFlow(
            targets = targets,
            hid = hid,
            inputs = state.tv.inputs,
            tvMissing = state.devices.tv == null,
            barMissing = state.devices.bar == null,
            onSetUpTv = onPair,
            onSetUpBar = onAddBar,
            onClose = { adding = false },
            onRefreshInputs = controller::refreshInputs,
        )
    }
    if (ipDialog) {
        // Renewing goes straight to the code: the row already says what it is for.
        IpControlDialog(state, controller, onDismiss = { ipDialog = false }, startWithCode = !state.pskSet && state.ipCode == IpCodeStatus.RENEW)
    }
    forget?.let { device ->
        // Named, so the dialog says which device goes (Iris, 2026-10-25).
        val name = if (device == Forget.TV) state.devices.tv?.name else state.devices.bar?.name
        AlertDialog(
            onDismissRequest = { forget = null },
            title = {
                Text(if (name.isNullOrBlank()) stringResource(R.string.settings_forget_title) else stringResource(R.string.settings_forget_named, name))
            },
            text = {
                Text(stringResource(if (device == Forget.TV) R.string.settings_forget_tv_detail else R.string.settings_forget_bar_detail))
            },
            confirmButton = {
                TextButton(onClick = {
                    if (device == Forget.TV) controller.forgetTv() else controller.forgetBar()
                    forget = null
                }) { Text(stringResource(R.string.settings_forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Settings › Remote & dock: the dock's layout, a long press per button, the apps shown, the volume guard, media controls. */
@Composable
private fun RemotePage(state: TuttiUiState, controller: TuttiController, onEditLayout: () -> Unit) {
    var picker by remember { mutableStateOf<SecondaryButton?>(null) }
    var appsDialog by remember { mutableStateOf(false) }

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
                    SecondaryButton.INPUT -> R.drawable.ic_input
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

    SettingsGroup(stringResource(R.string.settings_volume_title)) {
        VolumeGuardRows(state.prefs, onChange = controller::setVolumeGuard)
    }

    SettingsGroup(stringResource(R.string.settings_quick_access)) {
        QuickNotificationSetting(icon = R.drawable.ic_bolt)
    }

    SettingsGroup(stringResource(R.string.settings_layout_file)) {
        LayoutTransferRows(state, controller)
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
}

/** Settings › Display & feedback: the theme, true black, dark room, vibration and key sounds. */
@Composable
private fun DisplayPage(state: TuttiUiState, controller: TuttiController) {
    SettingsGroup(stringResource(R.string.settings_display)) {
        ThemeRow(state.prefs.theme, controller::setTheme)
        SwitchRow(
            label = stringResource(R.string.settings_true_black),
            supporting = stringResource(R.string.settings_true_black_detail),
            checked = state.prefs.trueBlack,
            onCheckedChange = controller::setTrueBlack,
            icon = R.drawable.ic_bedtime,
        )
        SwitchRow(
            label = stringResource(R.string.settings_evening_dim),
            supporting = stringResource(R.string.settings_evening_dim_detail),
            checked = state.prefs.eveningDim,
            onCheckedChange = controller::setEveningDim,
            icon = R.drawable.ic_visibility_off,
        )
    }

    SettingsGroup(stringResource(R.string.settings_feedback)) {
        FeedbackRows(state.prefs, onVibration = controller::setVibration, onKeySounds = controller::setKeySounds)
    }
}

/**
 * Settings › Advanced (Erwann, 2026-10-24: "une app de geek dans les options"): one switch, and
 * with it on, every technical screen in one place. The reverse map's readouts and hidden options
 * land here as rows, never on the everyday screens.
 */
@Composable
private fun AdvancedPage(state: TuttiUiState, controller: TuttiController, onOpenDiagnostic: () -> Unit, onOpenTechnical: () -> Unit) {
    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
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

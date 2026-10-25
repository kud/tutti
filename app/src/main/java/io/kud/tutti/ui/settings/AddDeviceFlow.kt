package io.kud.tutti.ui.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.controller.TargetController
import io.kud.tutti.platform.bluetooth.HidHost
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.bluetooth.HidPhase
import io.kud.tutti.platform.bluetooth.HidSupport
import io.kud.tutti.protocol.bravia.ExternalInput
import io.kud.tutti.protocol.devices.ComputerOs
import io.kud.tutti.protocol.devices.Targeting
import io.kud.tutti.ui.common.OpensChevron
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val INPUT_POLL_MS = 4_000L

private enum class AddStep { WHAT, OS, PAIR, INPUT, NAME, DONE }

/**
 * « Ajouter un appareil » (Iris, 2026-10-25, from Erwann's « Comment j'ajoute un appareil ? »).
 * Asks what first: a computer always; the TV or the bar only when missing, through their existing setup.
 * A computer then says its system (Erwann, 2026-10-25: « n'importe quel type d'ordi »), which sets
 * the pairing words and the default layout, and goes pair → input → name → done, each step saying
 * what to do next and every failure in words. Full screen, so Back steps back and the remote never shows through mid-flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddDeviceFlow(
    targets: TargetController,
    hid: HidLink?,
    inputs: List<ExternalInput>,
    tvMissing: Boolean,
    barMissing: Boolean,
    onSetUpTv: () -> Unit,
    onSetUpBar: () -> Unit,
    onClose: () -> Unit,
    onRefreshInputs: () -> Unit = {},
) {
    var step by remember { mutableStateOf(AddStep.WHAT) }
    // The inputs as they were when the flow opened: the one that comes on since is the computer's.
    val before = remember { inputs }
    var os by remember { mutableStateOf(ComputerOs.MACOS) }
    var picked by remember { mutableStateOf<HidHost?>(null) }
    var input by remember { mutableStateOf<String?>(null) }
    var inputTouched by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var addedId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // The TV reports an input's `connected` only when asked: ask again while the computer is being
    // plugged in or woken, so the input that comes on can be seen (and only then).
    if (step == AddStep.PAIR || step == AddStep.INPUT) {
        LaunchedEffect(Unit) {
            while (true) {
                onRefreshInputs()
                delay(INPUT_POLL_MS)
            }
        }
    }

    val back: () -> Unit = {
        when (step) {
            AddStep.WHAT, AddStep.DONE -> onClose()
            AddStep.OS -> step = AddStep.WHAT
            AddStep.PAIR -> step = AddStep.OS
            AddStep.INPUT -> step = AddStep.PAIR
            AddStep.NAME -> step = AddStep.INPUT
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = back)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_add_device)) },
                    navigationIcon = {
                        IconButton(onClick = back) {
                            Icon(
                                painterResource(if (step == AddStep.WHAT || step == AddStep.DONE) R.drawable.ic_close else R.drawable.ic_back),
                                contentDescription = stringResource(if (step == AddStep.WHAT || step == AddStep.DONE) R.string.devices_close else R.string.action_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            ) {
                when (step) {
                    AddStep.WHAT -> WhatStep(
                        hid = hid,
                        tvMissing = tvMissing,
                        barMissing = barMissing,
                        onComputer = { step = AddStep.OS },
                        onTv = {
                            onClose()
                            onSetUpTv()
                        },
                        onBar = {
                            onClose()
                            onSetUpBar()
                        },
                    )
                    AddStep.OS -> OsStep { chosen ->
                        os = chosen
                        step = AddStep.PAIR
                    }
                    AddStep.PAIR -> PairStep(targets, hid, os) { host ->
                        picked = host
                        name = host.name
                        step = AddStep.INPUT
                    }
                    AddStep.INPUT -> {
                        val newly = Targeting.newlyConnected(before, inputs)
                        // Preselect what came on, until the person picks by hand.
                        LaunchedEffect(newly) { if (!inputTouched && newly != null) input = newly }
                        InputStep(inputs, newly, input, airplay = os.airplay, onPick = {
                            inputTouched = true
                            input = it
                        }, onNext = { step = AddStep.NAME })
                    }
                    AddStep.NAME -> NameStep(name, failed, onName = { name = it }, onAdd = {
                        val host = picked ?: return@NameStep
                        failed = false
                        scope.launch {
                            runCatching { targets.addComputer(name.trim().ifEmpty { host.name }, host.address, os, input) }
                                .onSuccess { id ->
                                    addedId = id
                                    step = AddStep.DONE
                                }
                                .onFailure { failed = true }
                        }
                    })
                    AddStep.DONE -> {
                        val shownName = name.trim().ifEmpty { picked?.name.orEmpty() }
                        val sceneName = stringResource(R.string.devices_scene_name, shownName)
                        val id = addedId
                        val uri = input
                        DoneStep(
                            shownName,
                            input?.let { inputName(it, inputs) },
                            layoutName(os.defaultLayout),
                            onDone = onClose,
                            sceneName = if (id != null && uri != null) sceneName else null,
                            onAddScene = { if (id != null && uri != null) targets.addComputerScene(id, sceneName, uri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(number: Int?, title: String, body: String?) {
    Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (number != null) {
            Text(
                stringResource(R.string.devices_step, number, 3),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun WhatStep(hid: HidLink?, tvMissing: Boolean, barMissing: Boolean, onComputer: () -> Unit, onTv: () -> Unit, onBar: () -> Unit) {
    val support = hid?.state?.collectAsStateWithLifecycle()?.value?.support ?: HidSupport.UNSUPPORTED
    StepHeader(null, stringResource(R.string.devices_add_what), null)
    SettingsGroup(null) {
        // The TV first when it's missing: nothing else works without it.
        if (tvMissing) {
            SettingsRow(
                title = stringResource(R.string.devices_add_tv),
                detail = stringResource(R.string.devices_add_tv_detail),
                icon = R.drawable.ic_tv,
                onClick = onTv,
                trailing = { OpensChevron() },
            )
        }
        val computerPossible = support != HidSupport.UNSUPPORTED
        SettingsRow(
            title = stringResource(R.string.devices_add_computer),
            detail = stringResource(R.string.devices_add_computer_detail),
            notice = stringResource(R.string.devices_add_no_bt).takeUnless { computerPossible },
            icon = R.drawable.ic_laptop,
            enabled = computerPossible,
            onClick = onComputer.takeIf { computerPossible },
            trailing = if (computerPossible) ({ OpensChevron() }) else null,
        )
        if (!tvMissing && barMissing) {
            SettingsRow(
                title = stringResource(R.string.devices_add_bar),
                detail = stringResource(R.string.settings_add_bar_detail),
                icon = R.drawable.ic_speaker,
                onClick = onBar,
                trailing = { OpensChevron() },
            )
        }
    }
}

/** Right after « Ordinateur »: its system, which sets the pairing words and the default layout. */
@Composable
private fun OsStep(onPick: (ComputerOs) -> Unit) {
    StepHeader(null, stringResource(R.string.devices_os_title), null)
    SettingsGroup(null) {
        for (os in ComputerOs.entries) {
            SettingsRow(
                title = stringResource(os.label()),
                detail = stringResource(if (os == ComputerOs.MACOS) R.string.devices_os_macos_detail else R.string.devices_os_pc_detail),
                icon = R.drawable.ic_laptop,
                onClick = { onPick(os) },
                trailing = { OpensChevron() },
            )
        }
    }
}

private fun ComputerOs.label(): Int = when (this) {
    ComputerOs.MACOS -> R.string.devices_os_macos
    ComputerOs.WINDOWS -> R.string.devices_os_windows
    ComputerOs.LINUX -> R.string.devices_os_linux
}

/** Where each system keeps its Bluetooth settings, in words. */
private fun ComputerOs.pairSteps(): Int = when (this) {
    ComputerOs.MACOS -> R.string.devices_pair_steps_macos
    ComputerOs.WINDOWS -> R.string.devices_pair_steps_windows
    ComputerOs.LINUX -> R.string.devices_pair_steps_linux
}

/** Step 1: make the phone visible, pair from the computer, pick it among the bonded computers. */
@Composable
private fun PairStep(targets: TargetController, hid: HidLink?, os: ComputerOs, onPick: (HidHost) -> Unit) {
    // Holds the HID role while pairing, so the phone is registered as a keyboard and bonded hosts refresh.
    DisposableEffect(targets) {
        targets.settingsShown()
        onDispose { }
    }
    val hidState = hid?.state?.collectAsStateWithLifecycle()?.value
    val bonded by targets.bonded.collectAsStateWithLifecycle()
    val known by targets.state.collectAsStateWithLifecycle()
    val already = known.list.devices.mapNotNull { it.bluetooth?.address }.toSet()
    val choices = bonded.filter { it.address !in already }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { targets.settingsShown() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { targets.settingsShown() }
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    StepHeader(1, stringResource(R.string.devices_pair_title), stringResource(os.pairSteps()))

    // Where the link stands, with its one way forward; announced when it changes.
    val phase = hidState?.phase ?: HidPhase.STOPPED
    val (status, action) = when (phase) {
        HidPhase.NEEDS_PERMISSION -> stringResource(R.string.bt_status_permission) to (stringResource(R.string.bt_allow) to { hid?.let { permissions.launch(it.permissions()) }; Unit })
        HidPhase.BLUETOOTH_OFF -> stringResource(R.string.bt_status_off) to (stringResource(R.string.bt_turn_on) to { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) })
        HidPhase.BUSY -> stringResource(R.string.bt_status_busy) to (stringResource(R.string.bt_retry) to { targets.settingsLeft(); targets.settingsShown() })
        HidPhase.STOPPED, HidPhase.REGISTERING -> stringResource(R.string.bt_status_starting) to null
        HidPhase.READY, HidPhase.CONNECTING, HidPhase.CONNECTED -> null to (stringResource(R.string.bt_make_visible) to {
            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120))
        })
    }
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(status, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (action != null) {
            Button(onClick = action.second, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action.first) }
        }
    }

    StepHeader(null, stringResource(R.string.devices_computer_pick), stringResource(R.string.devices_pair_then))
    if (choices.isEmpty()) {
        Text(
            stringResource(R.string.devices_pair_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    } else {
        SettingsGroup(null) {
            for (host in choices) {
                SettingsRow(title = host.name, icon = R.drawable.ic_laptop, onClick = { onPick(host) }, trailing = { OpensChevron() })
            }
        }
    }
}

/** Step 2: the HDMI input, preselected when exactly one came on since the flow opened, and saying so. */
@Composable
private fun InputStep(inputs: List<ExternalInput>, newly: String?, selected: String?, airplay: Boolean, onPick: (String?) -> Unit, onNext: () -> Unit) {
    val explain = when {
        inputs.none { it.isHdmi } -> stringResource(R.string.devices_input_unknown)
        newly != null -> stringResource(R.string.devices_input_detected, inputName(newly, inputs))
        else -> stringResource(R.string.devices_input_not_detected)
    }
    StepHeader(2, stringResource(R.string.devices_computer_input), stringResource(R.string.devices_computer_input_hint))
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(explain, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        RadioList(InputOptions.of(inputs, selected, newly, airplay), selected, onPick)
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.devices_next)) }
    }
}

/** Step 3: the name the remote shows, prefilled with the computer's own Bluetooth name. */
@Composable
private fun NameStep(name: String, failed: Boolean, onName: (String) -> Unit, onAdd: () -> Unit) {
    StepHeader(3, stringResource(R.string.devices_name_title), stringResource(R.string.devices_name_hint))
    Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            label = { Text(stringResource(R.string.devices_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.devices_save_failed), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.devices_add_computer_button)) }
    }
}

@Composable
private fun DoneStep(name: String, input: String?, layout: String, onDone: () -> Unit, sceneName: String? = null, onAddScene: () -> Unit = {}) {
    var sceneAdded by remember { mutableStateOf(false) }
    StepHeader(
        null,
        stringResource(R.string.devices_done_title, name),
        if (input != null) stringResource(R.string.devices_done_input, input, name, layout) else stringResource(R.string.devices_done_no_input, layout),
    )
    Column(Modifier.padding(horizontal = 32.dp)) {
        // The starter scene « Mac sur la TV », offered once, never added behind his back (item 10).
        if (sceneName != null) {
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    onAddScene()
                    sceneAdded = true
                },
                enabled = !sceneAdded,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (sceneAdded) stringResource(R.string.devices_scene_added, sceneName) else stringResource(R.string.devices_scene_add, sceneName))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 8.dp))
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.devices_done)) }
    }
}

/** The Settings row that opens the flow; its detail says what can be added. */
@Composable
internal fun AddDeviceRow(missing: Boolean, onClick: () -> Unit) {
    SettingsRow(
        title = stringResource(R.string.settings_add_device),
        detail = stringResource(if (missing) R.string.devices_add_detail_missing else R.string.devices_add_detail),
        icon = R.drawable.ic_add,
        onClick = onClick,
        trailing = { OpensChevron() },
    )
}

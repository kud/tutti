package io.kud.tutti.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.kud.tutti.R
import io.kud.tutti.controller.PairingError
import io.kud.tutti.controller.PairingUi
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.protocol.net.LanAddress
import io.kud.tutti.ui.settings.linkLabel

/**
 * Add the TV (IP, then the code it shows), the bar (IP; "External control" must be on in
 * BRAVIA Connect) and, optionally, the TV's PSK for picture and sound settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    state: TuttiUiState,
    controller: TuttiController,
    discovery: TvDiscovery,
    onDone: () -> Unit,
) {
    val discovered by remember(discovery) { discovery.discover() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var tvHost by rememberSaveable { mutableStateOf(state.devices.tv?.host.orEmpty()) }
    var barHost by rememberSaveable { mutableStateOf(state.devices.bar?.host.orEmpty()) }
    var barError by remember { mutableStateOf(false) }
    var psk by remember { mutableStateOf("") }
    var pskSaved by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge)

            Step(title = stringResource(R.string.onboarding_tv_title), body = stringResource(R.string.onboarding_tv_body)) {
                if (discovered.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tv in discovered) {
                            AssistChip(onClick = { tvHost = tv.host }, label = { Text("${tv.name} · ${tv.host}") })
                        }
                    }
                }
                OutlinedTextField(
                    value = tvHost,
                    onValueChange = { tvHost = it.trim() },
                    label = { Text(stringResource(R.string.onboarding_ip)) },
                    singleLine = true,
                    isError = tvHost.isNotEmpty() && !LanAddress.isPrivate(tvHost),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = { controller.pairTv(tvHost) },
                    enabled = LanAddress.isPrivate(tvHost) && state.pairing !is PairingUi.Connecting && state.pairing !is PairingUi.AwaitingCode,
                ) { Text(stringResource(R.string.onboarding_pair)) }
                pairingStatus(state.pairing)?.let { (text, error) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                (state.pairing as? PairingUi.Failed)?.detail?.let { detail ->
                    // Selectable, so the cause can be copied into a report.
                    SelectionContainer {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Step(title = stringResource(R.string.onboarding_bar_title), body = stringResource(R.string.onboarding_bar_body)) {
                OutlinedTextField(
                    value = barHost,
                    onValueChange = {
                        barHost = it.trim()
                        barError = false
                    },
                    label = { Text(stringResource(R.string.onboarding_ip)) },
                    singleLine = true,
                    isError = barError,
                    supportingText = if (barError) {
                        { Text(stringResource(R.string.onboarding_lan_only)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(onClick = { barError = !controller.addBar(barHost) }, enabled = barHost.isNotBlank()) {
                    Text(stringResource(R.string.onboarding_add_bar))
                }
                if (state.devices.bar != null) {
                    Text(
                        linkLabel(state.links.bar),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Step(title = stringResource(R.string.onboarding_psk_title), body = stringResource(R.string.onboarding_psk_body)) {
                OutlinedTextField(
                    value = psk,
                    onValueChange = {
                        psk = it
                        pskSaved = false
                    },
                    label = { Text(stringResource(R.string.onboarding_psk_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = {
                        controller.setPsk(psk)
                        pskSaved = true
                    },
                    enabled = psk.isNotBlank() && !pskSaved,
                ) { Text(stringResource(if (pskSaved) R.string.onboarding_psk_saved else R.string.onboarding_save)) }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    controller.setOnboarded()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_done)) }
        }
    }

    val pairing = state.pairing
    if (pairing is PairingUi.AwaitingCode) {
        PairingCodeDialog(
            attempt = pairing.attempt,
            onSubmit = controller::submitPairingCode,
            onCancel = controller::resetPairing,
        )
    }
}

@Composable
private fun Step(title: String, body: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun pairingStatus(pairing: PairingUi): Pair<String, Boolean>? = when (pairing) {
    PairingUi.Idle -> null
    PairingUi.Connecting -> stringResource(R.string.pairing_connecting) to false
    is PairingUi.AwaitingCode -> stringResource(R.string.pairing_look_at_tv) to false
    PairingUi.Done -> stringResource(R.string.pairing_done) to false
    is PairingUi.Failed -> stringResource(
        when (pairing.reason) {
            PairingError.UNREACHABLE -> R.string.pairing_unreachable
            PairingError.BAD_CODE -> R.string.pairing_bad_code
            PairingError.REFUSED -> R.string.pairing_refused
        },
    ) to true
}

/** The 6-character code the TV shows. A typo is caught locally and asked again. */
@Composable
fun PairingCodeDialog(attempt: Int, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember(attempt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.pairing_code_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (attempt > 1) {
                    Text(stringResource(R.string.pairing_code_retry), color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.uppercase().filter { it.isLetterOrDigit() }.take(CODE_LENGTH) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.length == CODE_LENGTH) {
                Text(stringResource(R.string.pairing_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The TV's pre-shared key, for picture, sound output and inputs over its IP control. */
@Composable
fun PskDialog(onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var psk by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.onboarding_psk_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.onboarding_psk_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = psk,
                    onValueChange = { psk = it },
                    label = { Text(stringResource(R.string.onboarding_psk_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(psk.ifBlank { null }) }) { Text(stringResource(R.string.onboarding_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val CODE_LENGTH = 6

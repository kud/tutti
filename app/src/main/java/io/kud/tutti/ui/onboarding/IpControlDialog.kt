package io.kud.tutti.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.IpCodeStatus
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.TvCodeFailure
import io.kud.tutti.controller.TvCodeStep
import io.kud.tutti.protocol.bravia.IpRegistration
import io.kud.tutti.ui.common.BusyBar
import kotlinx.coroutines.launch

/** Where the « Contrôle IP » dialog stands. */
private sealed interface IpStage {
    data object Choose : IpStage
    data object Asking : IpStage
    data class Enter(val wrong: Boolean = false, val sending: Boolean = false) : IpStage
    data object Done : IpStage
    data class Failed(val why: TvCodeFailure) : IpStage
    data object Psk : IpStage
}

/**
 * « Contrôle IP » (Erwann, 2026-10-25: « Je suis friand des PIN affichés sur l'écran »): the code
 * shown on the TV comes first and is marked recommended, the PSK is the second choice, still whole
 * ([PskDialog]). The code flow: ask the TV to show its code, type the four digits, and say in words
 * whether it worked. Every place that used to open the PSK dialog opens this one. [startWithCode]
 * skips the choice and asks the TV at once, for buttons that already say "Show a code on the TV".
 */
@Composable
fun IpControlDialog(state: TuttiUiState, controller: TuttiController, onDismiss: () -> Unit, startWithCode: Boolean = false) {
    var stage by remember { mutableStateOf<IpStage>(IpStage.Choose) }
    var code by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val close = {
        controller.cancelTvCode()
        onDismiss()
    }

    fun after(step: TvCodeStep) {
        stage = when (step) {
            TvCodeStep.Shown -> IpStage.Enter()
            TvCodeStep.Registered -> IpStage.Done
            TvCodeStep.WrongCode -> IpStage.Enter(wrong = true)
            is TvCodeStep.Failed -> IpStage.Failed(step.why)
        }
    }

    fun ask() {
        code = ""
        stage = IpStage.Asking
        scope.launch { after(controller.requestTvCode()) }
    }

    fun send() {
        if (!IpRegistration.validCode(code)) return
        stage = IpStage.Enter(sending = true)
        scope.launch { after(controller.submitTvCode(code)) }
    }

    LaunchedEffect(Unit) { if (startWithCode) ask() }

    if (stage == IpStage.Psk) {
        PskDialog(hasKey = state.pskSet, onSave = {
            controller.setPsk(it)
            close()
        }, onDismiss = { stage = IpStage.Choose })
        return
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.ipc_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val now = stage) {
                    IpStage.Choose -> ChooseBody(state, onCode = ::ask, onPsk = { stage = IpStage.Psk })
                    IpStage.Asking -> Busy(stringResource(R.string.ipc_asking))
                    is IpStage.Enter -> {
                        Text(stringResource(R.string.ipc_enter_code), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                        OutlinedTextField(
                            value = code,
                            onValueChange = { typed -> code = typed.filter { it in '0'..'9' }.take(4) },
                            label = { Text(stringResource(R.string.ipc_code_field)) },
                            singleLine = true,
                            enabled = !now.sending,
                            isError = now.wrong,
                            supportingText = { Text(stringResource(if (now.wrong) R.string.ipc_wrong_code else R.string.ipc_code_rule)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { send() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (now.sending) Busy(stringResource(R.string.ipc_sending))
                        TextButton(onClick = ::ask, enabled = !now.sending) { Text(stringResource(R.string.ipc_another_code)) }
                    }
                    IpStage.Done -> Text(stringResource(R.string.ipc_done), style = MaterialTheme.typography.bodyMedium)
                    is IpStage.Failed -> {
                        Text(stringResource(failureText(now.why)), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { stage = IpStage.Psk }) { Text(stringResource(R.string.ipc_psk_choice)) }
                    }
                    IpStage.Psk -> Unit
                }
            }
        },
        confirmButton = {
            when (val now = stage) {
                is IpStage.Enter -> TextButton(onClick = ::send, enabled = !now.sending && IpRegistration.validCode(code)) { Text(stringResource(R.string.ipc_send)) }
                IpStage.Done -> TextButton(onClick = close) { Text(stringResource(R.string.ipc_close)) }
                is IpStage.Failed -> if (now.why != TvCodeFailure.NO_TV) TextButton(onClick = ::ask) { Text(stringResource(R.string.ipc_retry)) }
                else -> Unit
            }
        },
        dismissButton = {
            if (stage != IpStage.Done) TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The two ways in: the code first and recommended, the PSK as the plain second choice. */
@Composable
private fun ChooseBody(state: TuttiUiState, onCode: () -> Unit, onPsk: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Text(stringResource(R.string.ipc_gives), style = MaterialTheme.typography.bodyMedium)
    val current = when {
        !state.pskSet && state.ipCode == IpCodeStatus.RENEW -> R.string.ipc_now_renew
        state.ipCode == IpCodeStatus.SET -> R.string.ipc_now_code
        state.pskSet -> R.string.ipc_now_psk
        else -> null
    }
    if (current != null) Text(stringResource(current), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    // Recommended in words, never by colour alone (Erwann is colourblind).
    Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.ipc_recommended), style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
            Text(stringResource(R.string.ipc_code_choice), style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
            Text(stringResource(R.string.ipc_code_choice_detail), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
            Button(onClick = onCode, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.ipc_show_code)) }
        }
    }
    TextButton(onClick = onPsk) { Text(stringResource(R.string.ipc_psk_choice)) }
    Text(stringResource(R.string.ipc_needs), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

@Composable
private fun Busy(label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        BusyBar(Modifier.fillMaxWidth())
    }
}

private fun failureText(why: TvCodeFailure): Int = when (why) {
    TvCodeFailure.NO_TV -> R.string.ipc_failed_no_tv
    TvCodeFailure.UNREACHABLE -> R.string.ipc_failed_unreachable
    TvCodeFailure.NOT_ENABLED -> R.string.ipc_failed_not_enabled
    TvCodeFailure.SCREEN_OFF -> R.string.ipc_failed_screen_off
    TvCodeFailure.CERTIFICATE -> R.string.ipc_failed_certificate
    TvCodeFailure.OTHER -> R.string.ipc_failed_other
}

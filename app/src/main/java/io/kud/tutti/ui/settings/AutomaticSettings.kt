package io.kud.tutti.ui.settings

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.kud.tutti.R
import io.kud.tutti.auto.AutoPlayback
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.VolumeRules
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.protocol.auto.NightHours
import io.kud.tutti.ui.common.RevealRow
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.ui.common.SwitchRow
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings › Automatic (Erwann, 2026-11-12): two opt-ins, each off by default, in one group that
 * stands on its own (the Settings pages are being reorganised; this drops in wherever it lands).
 * The choices sync with the rest of the preferences; the phone permission is this phone's own, so a
 * synced "on" without it says so and offers the grant.
 */
@Composable
internal fun AutomaticGroup(prefs: AppPrefs, controller: TuttiController) {
    // Its own page (Settings › Automatic), whose title names it: no header of its own.
    SettingsGroup(null, Modifier.padding(top = 8.dp)) {
        CallPauseRow(prefs.pauseForCalls, controller::setPauseForCalls)
        NightHoursRows(prefs, controller)
    }
}

@Composable
private fun CallPauseRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(AutoPlayback.callPermission(context)) }
    var refused by remember { mutableStateOf(false) }
    var explaining by remember { mutableStateOf(false) }
    // A grant made in Android's settings while tutti was away shows on return.
    LifecycleResumeEffect(context) {
        granted = AutoPlayback.callPermission(context)
        if (granted) refused = false
        onPauseOrDispose { }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        refused = !ok
        if (ok) onChange(true)
    }
    SwitchRow(
        label = stringResource(R.string.auto_calls),
        supporting = stringResource(R.string.auto_calls_summary),
        checked = enabled,
        onCheckedChange = { on ->
            when {
                !on -> onChange(false)
                granted -> onChange(true)
                else -> explaining = true
            }
        },
        icon = R.drawable.ic_call,
        notice = when {
            refused -> stringResource(R.string.auto_calls_refused)
            enabled && !granted -> stringResource(R.string.auto_calls_missing)
            else -> null
        },
        footer = when {
            refused -> {
                { TextButton(onClick = { openAppSettings(context) }) { Text(stringResource(R.string.auto_open_settings)) } }
            }
            enabled && !granted -> {
                { TextButton(onClick = { explaining = true }) { Text(stringResource(R.string.auto_allow)) } }
            }
            else -> null
        },
    )
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text(stringResource(R.string.auto_calls_why_title)) },
            text = { Text(stringResource(R.string.auto_calls_why)) },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    ask.launch(Manifest.permission.READ_PHONE_STATE)
                }) { Text(stringResource(R.string.auto_calls_continue)) }
            },
            dismissButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.auto_not_now)) } },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.NightHoursRows(prefs: AppPrefs, controller: TuttiController) {
    val context = LocalContext.current
    val window = prefs.nightHours
    val formatter = remember { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    val from = minuteText(window.start, formatter)
    val until = minuteText(window.end, formatter)
    val dayLine = VolumeRules.threshold(prefs.volumeGuardLevel)
    SwitchRow(
        label = stringResource(R.string.auto_night),
        supporting = when {
            !window.enabled -> stringResource(R.string.auto_night_off_summary)
            prefs.volumeGuard -> stringResource(R.string.auto_night_summary, from, until, NightHours.guardLine(dayLine), dayLine)
            else -> stringResource(R.string.auto_night_summary_no_guard, from, until)
        },
        checked = window.enabled,
        onCheckedChange = { on -> controller.setNightHours { it.copy(enabled = on) } },
        icon = R.drawable.ic_bedtime,
        notice = if (window.enabled && window.start == window.end) stringResource(R.string.auto_night_same) else null,
    )
    RevealRow(visible = window.enabled) {
        androidx.compose.foundation.layout.Column {
            SettingsRow(
                title = stringResource(R.string.auto_night_from),
                detail = from,
                icon = R.drawable.ic_timer,
                onClick = { pickTime(context, window.start) { m -> controller.setNightHours { it.copy(start = m) } } },
            )
            SettingsRow(
                title = stringResource(R.string.auto_night_until),
                detail = until,
                icon = R.drawable.ic_timer,
                onClick = { pickTime(context, window.end) { m -> controller.setNightHours { it.copy(end = m) } } },
            )
        }
    }
}

private fun minuteText(minute: Int, formatter: DateTimeFormatter): String =
    LocalTime.of(minute / 60 % 24, minute % 60).format(formatter)

private fun pickTime(context: Context, minute: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(
        context,
        { _, hour, min -> onPicked(hour * 60 + min) },
        minute / 60 % 24,
        minute % 60,
        android.text.format.DateFormat.is24HourFormat(context),
    ).show()
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

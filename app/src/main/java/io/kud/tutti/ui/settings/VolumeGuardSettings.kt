package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.VolumeRules
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.ui.common.RevealRow
import io.kud.tutti.ui.common.SettingsRow
import io.kud.tutti.ui.common.SwitchRow
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.common.tabular

/**
 * Settings › Volume: the high-volume guard (Erwann, 2026-10-25), on by default at 30 on the bar's
 * 0–100 scale; a switch to turn it off and the threshold in steps of one, never a slider a stray
 * drag could move.
 */
@Composable
internal fun VolumeGuardRows(prefs: AppPrefs, onChange: (enabled: Boolean, level: Int) -> Unit) {
    val haptics = rememberHaptics()
    val level = VolumeRules.threshold(prefs.volumeGuardLevel)
    Column {
        SwitchRow(
            label = stringResource(R.string.settings_guard),
            supporting = if (prefs.volumeGuard) stringResource(R.string.settings_guard_summary, level) else stringResource(R.string.settings_guard_off_summary),
            checked = prefs.volumeGuard,
            onCheckedChange = { onChange(it, level) },
            icon = R.drawable.ic_volume_up,
        )
        RevealRow(visible = prefs.volumeGuard) {
            SettingsRow(
                title = stringResource(R.string.settings_guard_level),
                detail = stringResource(R.string.settings_guard_level_value, level),
                icon = R.drawable.ic_tune,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                haptics.tick()
                                onChange(true, level - 1)
                            },
                            enabled = level > VolumeRules.MIN_GUARD,
                        ) { Icon(painterResource(R.drawable.ic_remove), contentDescription = stringResource(R.string.settings_guard_down)) }
                        Text(
                            level.toString(),
                            style = MaterialTheme.typography.titleMedium.tabular(),
                            textAlign = TextAlign.Center,
                            // The row's detail already says it, with its scale.
                            modifier = Modifier.width(32.dp).clearAndSetSemantics { },
                        )
                        IconButton(
                            onClick = {
                                haptics.tick()
                                onChange(true, level + 1)
                            },
                            enabled = level < TuttiController.MAX_VOLUME - 1,
                        ) { Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.settings_guard_up)) }
                    }
                },
            )
        }
    }
}

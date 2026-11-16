package io.kud.tutti.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastSayAgain
import io.kud.tutti.ui.common.SettingsGroup
import io.kud.tutti.ui.common.SettingsRow

/**
 * How far Say again? goes back: one row of segments, 10, 15, 20 or 30 s (synced with the other
 * preferences). Self-contained so it can move with the Settings pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SayAgainSetting(seconds: Int, onChange: (Int) -> Unit) {
    val current = CastSayAgain.seconds(seconds)
    SettingsGroup(stringResource(R.string.say_again_settings_group)) {
        SettingsRow(
            title = stringResource(R.string.say_again_settings),
            detail = stringResource(R.string.say_again_settings_detail),
            icon = R.drawable.ic_replay,
            footer = {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    CastSayAgain.SECONDS.forEachIndexed { index, choice ->
                        val label = stringResource(R.string.say_again_seconds_label, choice)
                        SegmentedButton(
                            selected = choice == current,
                            onClick = { onChange(choice) },
                            shape = SegmentedButtonDefaults.itemShape(index, CastSayAgain.SECONDS.size),
                            modifier = Modifier.semantics { contentDescription = label },
                        ) {
                            Text(stringResource(R.string.say_again_seconds, choice), maxLines = 1)
                        }
                    }
                }
            },
        )
    }
}

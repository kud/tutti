package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.PowerTarget
import io.kud.tutti.ui.common.TuttiSheet

/** One row of the Power sheet: who it is for, and whether a tap turns that on ([on]) or off. */
data class PowerChoice(val target: PowerTarget, val on: Boolean)

/**
 * The long press on Power (Erwann, 2026-10-24): TV only, soundbar only, both, each stating what a
 * tap does from that device's own power. A device whose power is unknown (asleep and silent) is
 * offered "on", as the short press treats it. Both does exactly what the short press does, from
 * the combined [powered]. Only devices that are set up are offered; Both only with the two.
 */
fun powerChoices(hasTv: Boolean, tvPowered: Boolean?, hasBar: Boolean, barPowered: Boolean?, powered: Boolean?): List<PowerChoice> = buildList {
    if (hasTv) add(PowerChoice(PowerTarget.TV, on = tvPowered != true))
    if (hasBar) add(PowerChoice(PowerTarget.BAR, on = barPowered != true))
    if (hasTv && hasBar) add(PowerChoice(PowerTarget.BOTH, on = powered != true))
}

/**
 * Which device a Power is for, as a modal sheet (Erwann, 2026-10-24: « proposer une modale qui
 * propose quel device »): a sheet rather than a dialog because it is a choice among actions, like
 * every other picker here, and it lands under the thumb rather than mid-screen. One row per
 * [PowerChoice]: the device's glyph, its name, and the action in words ("Turn off"), never a
 * colour or a switch's position. A tap runs it and closes the sheet.
 */
@Composable
fun PowerSheet(
    choices: List<PowerChoice>,
    onChoose: (PowerChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.power_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp),
            )
            for (choice in choices) {
                val (icon, name) = when (choice.target) {
                    PowerTarget.TV -> R.drawable.ic_tv to stringResource(R.string.power_sheet_tv)
                    PowerTarget.BAR -> R.drawable.ic_speaker to stringResource(R.string.power_sheet_bar)
                    PowerTarget.BOTH -> R.drawable.ic_power to stringResource(R.string.power_sheet_both)
                }
                val action = stringResource(
                    when {
                        choice.target == PowerTarget.BOTH && choice.on -> R.string.power_sheet_turn_on_both
                        choice.target == PowerTarget.BOTH -> R.string.power_sheet_turn_off_both
                        choice.on -> R.string.power_sheet_turn_on
                        else -> R.string.power_sheet_turn_off
                    },
                )
                PowerRow(icon, name, action) { onChoose(choice) }
            }
        }
    }
}

@Composable
private fun PowerRow(@DrawableRes icon: Int, name: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

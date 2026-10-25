package io.kud.tutti.ui.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.TuttiMenu

/**
 * The remote's top bar, one layout at every width (Erwann, 2026-10-25: « les étoiles aussi dans le
 * menu », « le menu à côté du connecteur BRAVIA pour laisser de la place entre le bouton power »,
 * « les dots verticaux »): the system chip, the "⋮" right against it holding Scenes, Settings and
 * Gestures, then [POWER_GAP_DP] of nothing, then Power at the far edge. With one icon left between
 * chip and Power there is nothing to fold any more, so wide screens wear the same bar (Iris): a
 * second layout would only bring back an icon beside Power, which is what the gap is there to stop.
 *
 * Measured, not a device type: the bar's fixed parts are its padding (16 + 12 dp), the 4 dp gap
 * after the chip, the 48 dp menu button, the gap and the 52 dp power button.
 */
const val CHIP_MIN_DP = 192

/** Empty room between the menu's 48 dp target and Power's, so a thumb aimed at one can't land on the other. */
const val POWER_GAP_DP = 24
private const val BAR_FIXED_DP = 16 + 12 + 4 + 48 + POWER_GAP_DP + 52

/** The chip's width in dp on a bar [barWidthDp] wide. */
fun chipWidthDp(barWidthDp: Int): Int = barWidthDp - BAR_FIXED_DP

/**
 * The bar's "⋮": one icon button, heard as "More options", opening three text rows, Scenes, Settings
 * then Gestures, each with its icon and at least 48 dp tall. A tap runs the row and closes the menu.
 */
@Composable
fun TopBarOverflow(onScenes: () -> Unit, onSettings: () -> Unit, onGestures: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        TuttiMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_scenes)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_scene), contentDescription = null) },
                onClick = {
                    open = false
                    onScenes()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_settings)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                onClick = {
                    open = false
                    onSettings()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gestures_open)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_help), contentDescription = null) },
                onClick = {
                    open = false
                    onGestures()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

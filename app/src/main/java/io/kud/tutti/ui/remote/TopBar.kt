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
 * The remote's top bar on a narrow screen (Iris, chosen by Erwann, 2026-10-25: « menu ⋯ pour le ?
 * sur écran étroit »). Below [TOP_BAR_FOLD_DP] of width, Gestures and Settings fold into one "⋯"
 * overflow and the system chip takes the freed 48 dp; at or above it they stay separate icons.
 * The scenes and power buttons never fold.
 *
 * The threshold is measured, not a device type: the bar's fixed parts are its padding
 * (16 + 12 dp), the 4 dp gap after the chip, 48 dp per icon button and the 52 dp power button.
 * With three icons the chip keeps [CHIP_MIN_DP] (enough for "Living room · BRAVIA 8 + Bar 9"
 * before it ellipsises) only from 420 dp up; below that it folds.
 */
const val CHIP_MIN_DP = 192
private const val BAR_FIXED_DP = 16 + 12 + 4 + 52
private const val ICON_DP = 48

/** The chip's width in dp on a bar [barWidthDp] wide carrying [icons] icon buttons. */
fun chipWidthDp(barWidthDp: Int, icons: Int): Int = barWidthDp - BAR_FIXED_DP - icons * ICON_DP

/** The width below which the bar folds: where three icons would squeeze the chip under [CHIP_MIN_DP]. */
const val TOP_BAR_FOLD_DP = CHIP_MIN_DP + BAR_FIXED_DP + 3 * ICON_DP

/** True when Gestures and Settings fold into the overflow on a bar [barWidthDp] wide. */
fun topBarFolds(barWidthDp: Int): Boolean = chipWidthDp(barWidthDp, icons = 3) < CHIP_MIN_DP

/**
 * The folded bar's "⋯": one icon button, heard as "More options", opening two text rows, Settings
 * then Gestures, each with its icon and at least 48 dp tall. A tap runs the row and closes the menu.
 */
@Composable
fun TopBarOverflow(onSettings: () -> Unit, onGestures: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_horiz), contentDescription = stringResource(R.string.action_more))
        }
        TuttiMenu(expanded = open, onDismissRequest = { open = false }) {
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

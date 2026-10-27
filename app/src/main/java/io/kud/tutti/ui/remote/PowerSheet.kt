package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.PowerTarget
import io.kud.tutti.protocol.sleep.SleepFade
import io.kud.tutti.sleep.GentleOffRow
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiSheet

/** One row of the Power sheet: who it is for, and whether a tap turns that on ([on]) or off. */
data class PowerChoice(val target: PowerTarget, val on: Boolean)

/**
 * The long press on Power (Erwann, 2026-10-24): TV only, soundbar only, both, each stating what a
 * tap does from that device's own power. A device whose power is unknown (asleep and silent) is
 * offered "on", as the short press treats it. Both switches the TV and the bar together, from
 * the combined [powered]; the short press switches the TV alone ([shortPress]). Only devices that are set up are offered; Both only with the two.
 */
fun powerChoices(hasTv: Boolean, tvPowered: Boolean?, hasBar: Boolean, barPowered: Boolean?, powered: Boolean?): List<PowerChoice> = buildList {
    if (hasTv) add(PowerChoice(PowerTarget.TV, on = tvPowered != true))
    if (hasBar) add(PowerChoice(PowerTarget.BAR, on = barPowered != true))
    if (hasTv && hasBar) add(PowerChoice(PowerTarget.BOTH, on = powered != true))
}

/**
 * The TV's sleep timer row in the Power sheet: shown only when the TV lists an IRCC sleep key
 * (`Ircc.sleepTimerCode`, name unverified on the XR80), and [reason] says why a tap can't reach it
 * now (IP control down, the TV on standby). No state: Sony documents no getter for the timer, so
 * the TV's own screen shows the step each tap chose, never tutti.
 */
data class SleepTimerRow(val reason: String?)

/**
 * The sleep timer row, or null to hide it (no sleep key known). TV controls go down with their link
 * (design.md, State): this one rides IP control, so it is down unless REST answers, and a TV on
 * standby has no timer to set.
 */
fun sleepTimerRow(sleepCode: String?, restOnline: Boolean, tvPowered: Boolean?, downReason: String, standbyReason: String): SleepTimerRow? = when {
    sleepCode == null -> null
    !restOnline -> SleepTimerRow(downReason)
    tvPowered == false -> SleepTimerRow(standbyReason)
    else -> SleepTimerRow(null)
}

/**
 * "Screen off, sound on" in the Power sheet (Iris's top 10, item 4). [off] is true only when the TV
 * reports its power saving as `pictureOff`; unknown reads as lit, so the row offers to darken it,
 * and the way back is always one tap here or on a pin. Rides IP control like the sleep timer.
 */
data class ScreenOffRow(val off: Boolean, val reason: String?)

/** The screen-off row, or null when the TV doesn't list power saving (or IP control isn't set up). */
fun screenOffRow(ipControl: Boolean, supported: Boolean, loaded: Boolean, powerSaving: String?, restOnline: Boolean, tvPowered: Boolean?, downReason: String, standbyReason: String): ScreenOffRow? = when {
    !ipControl -> null
    loaded && !supported -> null
    !restOnline -> ScreenOffRow(off = false, reason = downReason)
    tvPowered == false -> ScreenOffRow(off = false, reason = standbyReason)
    else -> ScreenOffRow(off = powerSaving == "pictureOff", reason = null)
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
    sleep: SleepTimerRow? = null,
    onSleep: () -> Unit = {},
    screen: ScreenOffRow? = null,
    onScreen: (off: Boolean) -> Unit = {},
    onPinScreen: ((off: Boolean) -> Unit)? = null,
    gentle: GentleOffRow? = null,
    now: Long = 0L,
    exactAllowed: Boolean = true,
    onGentle: (minutes: Int) -> Unit = {},
    onGentleCancel: () -> Unit = {},
    onAllowExact: (() -> Unit)? = null,
) {
    // With the TV alone, "TV only" would imply a soundbar that isn't there.
    val solo = choices.none { it.target == PowerTarget.BAR }
    TuttiSheet(onDismissRequest = onDismiss) {
        val scroll = rememberScrollState()
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            modifier = Modifier.fillMaxWidth(),
            header = {
                Text(
                    stringResource(R.string.power_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            },
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (choice in choices) {
                    val (icon, name) = when (choice.target) {
                        PowerTarget.TV -> R.drawable.ic_tv to stringResource(if (solo) R.string.power_sheet_tv_solo else R.string.power_sheet_tv)
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
                // The sleep timer stays open after a tap: each press moves the TV to its next step,
                // and the TV's screen shows which (Iris's gesture audit, item 6).
                // Screen off stays open too: the next tap brings the picture back. A long press pins it.
                if (screen != null) {
                    val wantOff = !screen.off
                    val title = stringResource(if (wantOff) R.string.screen_off_title else R.string.screen_off_back)
                    val detail = screen.reason ?: stringResource(if (wantOff) R.string.screen_off_detail else R.string.screen_off_back_detail)
                    PowerRow(
                        if (wantOff) R.drawable.ic_visibility_off else R.drawable.ic_tv,
                        title,
                        detail,
                        enabled = screen.reason == null,
                        down = screen.reason != null,
                        onLongClickLabel = stringResource(R.string.screen_off_pin),
                        onLongClick = onPinScreen?.let { pin -> { pin(wantOff) } },
                        onClick = { onScreen(wantOff) },
                    )
                }
                if (sleep != null) {
                    val detail = sleep.reason ?: stringResource(R.string.power_sleep_detail)
                    PowerRow(R.drawable.ic_bedtime, stringResource(R.string.power_sleep), detail, enabled = sleep.reason == null, down = sleep.reason != null, onClick = onSleep)
                }
                if (gentle != null) GentleOff(gentle, now, exactAllowed, onGentle, onGentleCancel, onAllowExact)
            }
        }
    }
}

/**
 * The gentle switch-off timer (Iris's top 10, item 5): a row of durations, or, while one runs, its
 * end time and time left, a tap cancelling it. Without exact alarms it says, in words, that it may
 * switch off a few minutes late, and offers the system page that allows exact timing.
 */
@Composable
private fun GentleOff(
    row: GentleOffRow,
    now: Long,
    exactAllowed: Boolean,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onAllowExact: (() -> Unit)?,
) {
    val running = row.running
    if (running != null) {
        val at = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(running.endsAt))
        val left = stringResource(R.string.gentle_off_left, SleepFade.minutesLeft(now, running.endsAt))
        PowerRow(
            R.drawable.ic_bedtime,
            stringResource(R.string.gentle_off_at, at),
            if (running.exact) left else left + "\n" + stringResource(R.string.gentle_off_late),
            onClick = onCancel,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PowerRow(
            R.drawable.ic_bedtime,
            stringResource(R.string.gentle_off_title),
            row.reason ?: stringResource(R.string.gentle_off_detail),
            enabled = false,
            down = row.reason != null,
            onClick = {},
        )
        if (row.reason == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (minutes in SleepFade.DURATIONS_MIN) {
                    val label = stringResource(R.string.gentle_off_minutes_label, minutes)
                    FilledTonalButton(
                        onClick = { onStart(minutes) },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = label },
                    ) {
                        Text(stringResource(R.string.gentle_off_minutes, minutes), maxLines = 1, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (!exactAllowed) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.gentle_off_late),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    if (onAllowExact != null) TextButton(onClick = onAllowExact) { Text(stringResource(R.string.gentle_off_allow)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PowerRow(
    @DrawableRes icon: Int,
    name: String,
    action: String,
    enabled: Boolean = true,
    down: Boolean = false,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val dash = androidx.compose.runtime.remember { androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) }
    val outline = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(
                // Down wears the remote's one down dress: no fill, a dashed ring, the reason in words.
                if (down) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            outline,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx(), pathEffect = dash),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                        )
                    }
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                },
            )
            .then(
                // A row that only heads what follows (Gentle off's title over its durations) is
                // information, not a disabled button: no click semantics (Iris, 2026-10-27). A down
                // row keeps its disabled button, since it is a control that is unavailable.
                if (enabled || down) {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClickLabel = action,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).then(if (down) Modifier else Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (down) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(action, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

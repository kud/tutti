package io.kud.tutti.ui.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastRateSupport
import io.kud.tutti.protocol.cast.CastSpeed
import io.kud.tutti.protocol.cast.CastSpeedResult
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * The Speed chip and its picker (Erwann, 2026-11-12: « un bouton qui permet de lire en x1, x2 »).
 * Only Cast can do it: the Remote v2 has no speed key and the TV's REST API none for apps, so the
 * chip shows only while a Cast media session is reported, and not for an app whose status says it
 * takes no rate or that already refused one. The picker opens inline under the chips, never as a
 * second sheet over the Playback sheet.
 */
@Stable
internal class SpeedControl {
    var open by mutableStateOf(false)
    var busy by mutableStateOf(false)

    /** What the last request ended in, in words; null while there is nothing to say. */
    var outcome by mutableStateOf<CastSpeedResult?>(null)
}

@Composable
internal fun rememberSpeedControl(): SpeedControl = remember { SpeedControl() }

/** Whether the Speed chip belongs on [cast]: a reported session, no lack of support advertised, no refusal yet. */
internal fun speedOffered(cast: CastNowPlaying?, refused: Set<String>): Boolean =
    cast != null && cast.rateSupport != CastRateSupport.NOT_ADVERTISED && cast.appId !in refused

/** "1,5×" in French, "1.5×" in English. */
@Composable
internal fun rateLabel(rate: Double): String {
    val format = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 } }
    return stringResource(R.string.playback_speed_rate, format.format(rate))
}

@Composable
internal fun SpeedChip(cast: CastNowPlaying?, refused: Set<String>, downReason: String?, control: SpeedControl) {
    if (!speedOffered(cast, refused)) return
    val rate = cast?.playbackRate ?: 1.0
    // The state in words on the chip itself: "Speed" at normal, "Speed 1.5×" otherwise.
    val label = if (CastSpeed.same(rate, 1.0)) {
        stringResource(R.string.playback_speed)
    } else {
        stringResource(R.string.playback_speed_at, rateLabel(rate))
    }
    ActionChip(R.drawable.ic_speed, label, downReason) {
        control.open = !control.open
        control.outcome = null
    }
}

/**
 * Six rates in two rows of three. The current one wears a check glyph and bold type and an
 * outline, never colour alone. A refusal is said in words here, and stays said after the chip
 * has gone for that app.
 */
@Composable
internal fun SpeedPicker(cast: CastNowPlaying?, control: SpeedControl, onPick: suspend (Double) -> CastSpeedResult) {
    val scope = rememberCoroutineScope()
    val current = cast?.playbackRate?.let(CastSpeed::offered)
    AnimatedVisibility(visible = control.open || control.outcome != null, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (control.open && cast != null) {
                val title = stringResource(R.string.playback_speed_picker)
                Column(
                    Modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = title },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (row in CastSpeed.RATES.chunked(3)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (rate in row) {
                                SpeedSegment(
                                    label = rateLabel(rate),
                                    selected = current != null && CastSpeed.same(current, rate),
                                    enabled = !control.busy,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    control.busy = true
                                    control.outcome = null
                                    scope.launch {
                                        val result = try { onPick(rate) } finally { control.busy = false }
                                        // Accepted: the chip now says the rate, the picker folds away.
                                        // Anything else: the rates go, the reason stays in words.
                                        control.outcome = result.takeUnless { it is CastSpeedResult.Accepted }
                                        control.open = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val words = when {
                control.busy -> stringResource(R.string.playback_speed_asking)
                control.outcome is CastSpeedResult.NoSession -> stringResource(R.string.playback_speed_no_session)
                control.outcome != null -> stringResource(R.string.playback_speed_refused)
                else -> null
            }
            words?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun SpeedSegment(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh, shape)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, scheme.onSurface), shape) else Modifier)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.onSurface, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

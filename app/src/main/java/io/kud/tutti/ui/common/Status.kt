package io.kud.tutti.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.kud.tutti.R

/**
 * tutti's one status language (Erwann, 2026-11-06: « on a un souci de design system sur les
 * status »). Every place that says how something is doing (Connexions, Settings' tiles, What tutti
 * can do, Devices) says it with one of these six, each with its own glyph, so a state reads alike
 * everywhere and never by colour alone (Erwann is colourblind). docs/design.md › Status.
 */
enum class Status {
    /** Connected, on, done: a filled check circle. */
    OK,

    /** Connecting, checking, waiting a moment: circling arrows. */
    WORKING,

    /** Asleep, paused, on standby: a moon. Said, never a fault. */
    STANDBY,

    /** Not set up, not added, ready but unused, off: an empty ring. A calm offer. */
    NOT_YET,

    /** Something only he can fix: a warning triangle, and the one place with emphasis. */
    NEEDS_YOU,

    /** Out of his hands for now (an earlier step first, can't tell from here, coming): a padlock. */
    UNAVAILABLE,
}

/** How a status looks: its glyph, and whether it is the emphasised one ([emphasised] is NEEDS_YOU alone). */
data class StatusLook(@DrawableRes val glyph: Int, val emphasised: Boolean)

/** The pure mapping every screen draws from (`StatusTest`). */
fun statusLook(status: Status): StatusLook = when (status) {
    Status.OK -> StatusLook(R.drawable.ic_check_circle, emphasised = false)
    Status.WORKING -> StatusLook(R.drawable.ic_refresh, emphasised = false)
    Status.STANDBY -> StatusLook(R.drawable.ic_bedtime, emphasised = false)
    Status.NOT_YET -> StatusLook(R.drawable.ic_circle_outline, emphasised = false)
    Status.NEEDS_YOU -> StatusLook(R.drawable.ic_warning, emphasised = true)
    Status.UNAVAILABLE -> StatusLook(R.drawable.ic_lock, emphasised = false)
}

/** The status glyph, at the one size (18 dp). Decorative: the words beside it say the state. */
@Composable
fun StatusGlyph(status: Status, modifier: Modifier = Modifier) {
    val look = statusLook(status)
    Icon(
        painterResource(look.glyph),
        contentDescription = null,
        tint = if (look.emphasised) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(StatusGlyphSize),
    )
}

/** The one action a status offers, in the one button style: filled tonal, 48 dp tall, a verb. */
@Composable
fun StatusAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) { Text(label, maxLines = 2) }
}

/**
 * The emphasis a NEEDS_YOU status puts on its row or tile: a raised surface inside a 1.5 dp outline,
 * so it is set apart by a line as well as a tone. Nothing else wears it.
 */
@Composable
fun Modifier.needsYouBand(shape: Shape): Modifier = this
    .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
    .border(1.5.dp, MaterialTheme.colorScheme.outline, shape)

/**
 * One status, as one line: the glyph, then [words] ("Google TV · connected", or "Connected" on its
 * own under a name), then what it gives, the [capability] line, aligned with the words in the quiet
 * colour. TalkBack hears one sentence ([said]); the action, if any, after it at the end.
 */
@Composable
fun StatusLine(
    status: Status,
    words: String,
    modifier: Modifier = Modifier,
    capability: String? = null,
    said: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val emphasised = statusLook(status).emphasised
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (emphasised) Modifier.needsYouBand(StatusBandShape) else Modifier)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
                .semantics(mergeDescendants = true) { if (said != null) contentDescription = said },
            verticalAlignment = Alignment.Top,
        ) {
            StatusGlyph(status, Modifier.padding(top = 3.dp))
            Spacer(Modifier.width(StatusGap))
            StatusWords(status, words, capability)
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/** The words and capability line of a status, in the one type pair, for a layout of its own (a tile). */
@Composable
fun StatusWords(status: Status, words: String, capability: String? = null, wordsLines: Int = Int.MAX_VALUE) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            words,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (statusLook(status).emphasised) FontWeight.SemiBold else null,
            maxLines = wordsLines,
        )
        if (capability != null) {
            Text(capability, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

val StatusGlyphSize = 18.dp
val StatusGap = 10.dp
val StatusBandShape = RoundedCornerShape(12.dp)

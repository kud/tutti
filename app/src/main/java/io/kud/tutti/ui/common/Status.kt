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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
 * The emphasis a NEEDS_YOU status puts on its row or tile: the lowest surface inside a 1.5 dp
 * outline, so it is set apart by a line as well as a tone, and the tonal action button stands
 * clear of it in light and dark (a raised band left it pale on pale in light). Nothing else wears it.
 */
@Composable
fun Modifier.needsYouBand(shape: Shape): Modifier = this
    .background(NeedsYouSurface(), shape)
    .border(1.5.dp, MaterialTheme.colorScheme.outline, shape)

/**
 * One status, as one line: the glyph, then [words] ("Google TV · connected", or "Connected" on its
 * own under a name), then what it gives, the [capability] line, aligned with the words in the quiet
 * colour. TalkBack hears one sentence ([said]); the action, if any, after it.
 *
 * The action sits at the trailing end while the words keep at least [MIN_WORDS_BESIDE] of the
 * width and fit in two lines beside it; otherwise it moves under the words, aligned with them
 * ([actionBeside], Erwann, 2026-11-06: French link lines wrapped onto four lines beside "Configurer").
 */
@Composable
fun StatusLine(
    status: Status,
    words: String,
    modifier: Modifier = Modifier,
    capability: String? = null,
    said: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val emphasised = statusLook(status).emphasised
    val hasAction = actionLabel != null && onAction != null
    val box = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .then(if (emphasised) Modifier.needsYouBand(StatusBandShape) else Modifier)
        .padding(start = 12.dp, end = 8.dp)
    // Only a line with an action measures itself, so a line without one still answers an
    // intrinsic-height parent (a BoxWithConstraints can't).
    if (!hasAction) {
        Box(box, contentAlignment = Alignment.CenterStart) { StatusLineBody(status, words, capability, said, null, null, beside = true) }
        return
    }
    BoxWithConstraints(box, contentAlignment = Alignment.CenterStart) {
        StatusLineBody(status, words, capability, said, actionLabel, onAction, beside = actionBeside(status, words, actionLabel!!, maxWidth))
    }
}

@Composable
private fun StatusLineBody(
    status: Status,
    words: String,
    capability: String?,
    said: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    beside: Boolean,
) {
    run {
        val hasAction = actionLabel != null && onAction != null
        val wordsBlock: @Composable (Modifier) -> Unit = { m ->
            Row(
                m
                    .padding(vertical = 8.dp)
                    .semantics(mergeDescendants = true) { if (said != null) contentDescription = said },
                verticalAlignment = Alignment.Top,
            ) {
                StatusGlyph(status, Modifier.padding(top = 3.dp))
                Spacer(Modifier.width(StatusGap))
                StatusWords(status, words, capability)
            }
        }
        if (!hasAction || beside) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                wordsBlock(Modifier.weight(1f))
                if (hasAction) {
                    Spacer(Modifier.width(8.dp))
                    StatusAction(actionLabel!!, onAction!!)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                wordsBlock(Modifier.fillMaxWidth())
                StatusAction(actionLabel!!, onAction!!, Modifier.padding(start = StatusGlyphSize + StatusGap, bottom = 8.dp))
            }
        }
    }
}

/**
 * Whether a status's action fits beside its words in [width]: the button at its own width (label
 * plus M3's 24 dp each side), and the words, in their own weight, in at most two lines in what is
 * left, never under [MIN_WORDS_BESIDE].
 */
@Composable
fun actionBeside(status: Status, words: String, actionLabel: String, width: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val buttonPx = measurer.measure(actionLabel, typography.labelLarge, maxLines = 1).size.width + with(density) { 48.dp.roundToPx() }
    val leftPx = with(density) { (width - StatusGlyphSize - StatusGap - 8.dp).roundToPx() } - buttonPx
    if (leftPx < with(density) { MIN_WORDS_BESIDE.roundToPx() }) return false
    val style = typography.bodyLarge.copy(fontWeight = if (statusLook(status).emphasised) FontWeight.SemiBold else null)
    return measurer.measure(words, style, constraints = Constraints(maxWidth = leftPx)).lineCount <= 2
}

/** The narrowest the words may get beside an action before it moves under them. */
val MIN_WORDS_BESIDE = 160.dp

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

/** The Needs-you band's surface ([needsYouBand]). */
@Composable
fun NeedsYouSurface() = MaterialTheme.colorScheme.surfaceContainerLowest

val StatusGlyphSize = 18.dp
val StatusGap = 10.dp
val StatusBandShape = RoundedCornerShape(12.dp)

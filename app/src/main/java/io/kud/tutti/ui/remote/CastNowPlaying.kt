package io.kud.tutti.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * The full card's Cast line (Iris, 2026-10-25, design.md "Now-showing card: what Cast reports
 * playing"): the title in place of the detail line, a 2 dp read-only track and "12:03 / 41:10"
 * beside it, or under it when the column is under 200 dp. Paused and loading say so in words;
 * live content has no track and reads "Live". The time steps once a second with no animated fill.
 * TalkBack hears [castSpoken], which leaves the time out while playing so the tick never re-reads.
 */
@Composable
internal fun CastLine(cast: CastNowPlaying, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis(), cast) {
        while (true) {
            value = System.currentTimeMillis()
            if (cast.state != CastPlayerState.PLAYING) break
            delay(1_000L - value % 1_000L)
        }
    }
    val spoken = castSpoken(cast, now) { id, args -> context.getString(id, *args) }
    val fold = rememberTitleFold(cast.title)
    val foldLabel = stringResource(if (fold.open) R.string.cast_title_less else R.string.cast_title_more)
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = spoken
            // The card's own node takes this action (TalkBack's actions menu); its tap still opens playback.
            if (fold.offered) customActions = listOf(CustomAccessibilityAction(foldLabel) { fold.toggle(); true })
        },
    ) {
        // Only the title's own width takes the tap, and only when it is cut or open: the rest of
        // the card, the chevron included, still opens playback.
        Text(
            cast.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (fold.open) CARD_TITLE_OPEN_LINES else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = fold::measured,
            modifier = if (fold.offered) Modifier.clickable(onClickLabel = foldLabel, onClick = fold::toggle) else Modifier,
        )
        // Open, the title takes the track's room: the card keeps its height.
        if (fold.open) return@Column
        Spacer(Modifier.height(4.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val progress = cast.progressAt(now)
            val status = @Composable { CastStatus(cast, now) }
            if (progress == null) {
                status()
            } else if (maxWidth < 200.dp) {
                Column {
                    CastTrack(progress, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    status()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CastTrack(progress, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    status()
                }
            }
        }
    }
}

@Composable
private fun CastTrack(progress: Float, modifier: Modifier) {
    val shape = RoundedCornerShape(1.dp)
    Row(modifier.height(2.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)) {
        if (progress > 0f) {
            Spacer(Modifier.weight(progress).height(2.dp).clip(shape).background(MaterialTheme.colorScheme.primary, shape))
        }
        if (progress < 1f) Spacer(Modifier.weight(1f - progress))
    }
}

@Composable
private fun CastStatus(cast: CastNowPlaying, now: Long) {
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val word = when (cast.state) {
            CastPlayerState.PAUSED -> {
                Icon(painterResource(R.drawable.ic_pause), contentDescription = null, tint = colour, modifier = Modifier.size(14.dp))
                R.string.cast_paused
            }
            CastPlayerState.BUFFERING -> R.string.cast_loading
            else -> null
        }
        val time = castTime(cast, now)
        val words = listOfNotNull(
            word?.let { stringResource(it) },
            time ?: if (cast.durationSec == null) stringResource(R.string.cast_live) else null,
        )
        Text(words.joinToString(" · "), style = style, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "12:03 / 41:10", or null for live content or an unknown position. */
internal fun castTime(cast: CastNowPlaying, nowMillis: Long): String? {
    val duration = cast.durationSec?.takeIf { it > 0 } ?: return null
    val at = cast.positionAt(nowMillis) ?: return null
    return "${clockOf(at)} / ${clockOf(duration)}"
}

/** "12:03", or "1:02:03" from an hour; whole seconds, rounded down. */
internal fun clockOf(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * What TalkBack hears: "<title>, 12 minutes of 41, paused". While playing only the title (or
 * "<title>, live"), so the description holds still between reports.
 */
internal fun castSpoken(cast: CastNowPlaying, nowMillis: Long, string: (Int, Array<Any>) -> String): String {
    val live = cast.durationSec?.takeIf { it > 0 } == null
    return when (cast.state) {
        CastPlayerState.PLAYING -> if (live) string(R.string.cast_spoken_live, arrayOf(cast.title)) else cast.title
        CastPlayerState.BUFFERING -> string(R.string.cast_spoken_loading, arrayOf(cast.title))
        else -> {
            val at = cast.positionAt(nowMillis)
            val duration = cast.durationSec
            if (live || at == null || duration == null) {
                string(R.string.cast_spoken_paused, arrayOf(cast.title))
            } else {
                string(R.string.cast_spoken_paused_at, arrayOf(cast.title, minutesOf(at), minutesOf(duration)))
            }
        }
    }
}

private fun minutesOf(seconds: Double): Long = (seconds / 60.0).roundToLong()

/** Lines a Cast title may take open on the 104 dp card: overline, app name and three lines fill it. */
private const val CARD_TITLE_OPEN_LINES = 3

/**
 * A title cut to one line that a tap shows whole and a second tap folds back (Erwann, 2026-10-26:
 * « cliquer sur le titre pour avoir la vue entière »). The tap is offered only while the title is
 * cut or open, so a title that fits leaves the tap to whatever is under it. A new title starts folded.
 */
@Stable
internal class TitleFold {
    var open by mutableStateOf(false)
        private set
    private var cut by mutableStateOf(false)

    val offered: Boolean get() = open || cut

    fun toggle() {
        open = !open
    }

    /** Folded, the layout says whether the title is cut; open, it keeps what folded said. */
    fun measured(layout: TextLayoutResult) {
        if (!open) cut = layout.hasVisualOverflow
    }
}

@Composable
internal fun rememberTitleFold(title: String): TitleFold = remember(title) { TitleFold() }

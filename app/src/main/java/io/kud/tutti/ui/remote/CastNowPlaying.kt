package io.kud.tutti.ui.remote

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastPlayerState
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/**
 * The full card's Cast line (Iris, 2026-10-25, design.md "Now-showing card: what Cast reports
 * playing"): the title in place of the detail line (two lines, then an ellipsis), the channel or
 * artist on one line under it whenever Cast reports one, a 2 dp read-only track and "12:03 / 41:10"
 * beside it, or under it when the column is under 200 dp. Paused and loading say so in words;
 * live content has no track and reads "Live". The time steps once a second with no animated fill.
 * TalkBack hears [castSpoken], which leaves the time out while playing so the tick never re-reads.
 */
@Composable
internal fun CastLine(cast: CastNowPlaying, modifier: Modifier = Modifier, roomy: Boolean = true) {
    val resources = LocalResources.current
    val now by produceState(System.currentTimeMillis(), cast) {
        while (true) {
            value = System.currentTimeMillis()
            if (cast.state != CastPlayerState.PLAYING) break
            delay(1_000L - value % 1_000L)
        }
    }
    // The channel or artist joins the title for TalkBack: "<title>, <channel>, paused".
    val secondary = cast.secondary?.takeIf { it.isNotBlank() }
    val spoken = castSpoken(if (secondary == null) cast else cast.copy(title = "${cast.title}, $secondary"), now) { id, args -> resources.getString(id, *args) }
    // Two lines, then an ellipsis, with no tap (Erwann, 2026-10-26): the playback sheet shows it whole.
    var wrapped by remember(cast.title) { mutableStateOf(false) }
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Text(
            cast.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // At 104 dp the channel takes the title's second line, so the card keeps its height.
            maxLines = if (secondary == null || roomy) CARD_TITLE_LINES else 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { wrapped = it.lineCount > 1 },
        )
        // The channel or artist, always, when Cast reports one (Erwann, 2026-10-27: « J'aimerais
        // toujours afficher la chaîne sur la première photo »): one quiet line under the title's two.
        secondary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A second line takes most of the gap, so the card keeps its 104 dp.
        Spacer(Modifier.height(if (wrapped) 2.dp else 4.dp))
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

/** Lines a Cast title takes on the card before its ellipsis. */
private const val CARD_TITLE_LINES = 2

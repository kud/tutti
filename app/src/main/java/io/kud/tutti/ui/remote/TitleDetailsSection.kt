package io.kud.tutti.ui.remote

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.platform.net.CastArtworkLoader
import io.kud.tutti.platform.net.CinemetaClient
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.details.Cinemeta
import io.kud.tutti.protocol.details.TitleDetails
import io.kud.tutti.protocol.privacy.NonFreeFeature

/**
 * The playback sheet's "Details" (Erwann, 2026-11-12: « le plus de métadata quand je lis un film »),
 * from Cinemeta, under everything else: the sheet is the remote first, and this is reading.
 * Shown only once there is something to show: no spinner, no empty frame, nothing when the id has
 * no evidence (`Cinemeta.requestFor`) or the lookup fails. No card line: the full card is already
 * at its room budget (design.md), so the details live in the sheet alone.
 */
@Composable
internal fun TitleDetailsSection(cast: CastNowPlaying) {
    val request = Cinemeta.requestFor(cast) ?: return
    val details by produceState(CinemetaClient.cached(request), request) {
        value = CinemetaClient.load(request)
    }
    val shown = details?.takeUnless { it.isEmpty } ?: return
    TitleDetailsBody(shown)
}

/**
 * Where Details would help but is off (Erwann, 2026-11-12: « je veux vraiment être friendly avec les
 * utilisateurs sur ce sujet »): one quiet line, only for a title Cinemeta could answer, saying what
 * is sent and to whom, with Enable and No thanks of equal weight. Enable turns on Details alone;
 * No thanks is for good. Never shown under "Only free services" (`FreeServices.offers`).
 */
@Composable
internal fun TitleDetailsOffer(cast: CastNowPlaying, onEnable: () -> Unit, onDecline: () -> Unit) {
    Cinemeta.requestFor(cast) ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.details_offer), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDecline, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.details_offer_decline)) }
            OutlinedButton(onClick = onEnable, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.details_offer_enable)) }
        }
    }
}

@Composable
private fun TitleDetailsBody(details: TitleDetails) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.details_label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            modifier = Modifier.semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            details.poster?.let { Poster(it) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                details.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                factsLine(details)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                details.imdbRating?.let {
                    Text(stringResource(R.string.details_imdb_rating, it), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
                if (details.director.isNotEmpty()) {
                    Text(
                        stringResource(R.string.details_director, details.director.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                }
            }
        }
        details.episode?.let { episode ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val heading = stringResource(R.string.details_episode, episode.season, episode.episode)
                Text(
                    listOfNotNull(heading, episode.title).joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                )
                episode.overview?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        details.synopsis?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (details.cast.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.details_cast), style = MaterialTheme.typography.titleSmall)
                // Names only, as plain labels: they open nothing, so they wear no chip outline.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (name in details.cast) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** "2001 · 112 min · Drama, Mystery": what is known, in that order. */
private fun factsLine(details: TitleDetails): String? =
    listOfNotNull(details.year, details.runtime, details.genres.takeIf { it.isNotEmpty() }?.joinToString(", "))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/** The poster at 2:3, 72 dp wide; decorative, since the title is read beside it. Absent until loaded. */
@Composable
private fun Poster(url: String) {
    val widthPx = with(LocalDensity.current) { 72.dp.roundToPx() }
    val art by produceState(CastArtworkLoader.cached(url, NonFreeFeature.TITLE_DETAILS), url, widthPx) {
        value = CastArtworkLoader.load(url, widthPx, NonFreeFeature.TITLE_DETAILS)
    }
    val bitmap = art ?: return
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(72.dp, 108.dp).clip(RoundedCornerShape(8.dp)),
    )
}

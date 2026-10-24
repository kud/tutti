package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.motion.Motion

/**
 * What the TV is showing, in the room between the scene pills and the pad (Iris's option A,
 * chosen by Erwann, 2026-10-24): an icon, the name large and a small detail line. [fit] comes from
 * [placePad]: full (104 to 140 dp, the icon and name growing with it), one 56 dp line, or hidden.
 * Every state is told by its icon and its words, never colour alone, and it is never empty: an
 * app, a channel or an input, the bar on its own, standby, the home screen, or the TV out of
 * reach. A tap on the card does nothing for now; the bar on its own carries one button, "Turn on
 * the TV" ([onWakeTv]). State changes fade through by opacity only, which is also the
 * reduced-motion form.
 */
@Composable
internal fun NowShowingCard(
    showing: CardShowing,
    fit: CardFit,
    tvName: String,
    appIcons: Map<String, String>,
    modifier: Modifier = Modifier,
    onWakeTv: () -> Unit = {},
    waking: Boolean = false,
) {
    if (fit.size == CardSize.HIDDEN) return
    Box(
        modifier
            .fillMaxWidth()
            .height(fit.height.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(CARD_CORNER))
            .semantics(mergeDescendants = true) {},
    ) {
        // Fade through, not a cross-fade: the old words are gone before the new ones arrive, so two
        // titles never sit on top of each other mid-change ("Reconnecting…" under "TV out of reach").
        AnimatedContent(
            targetState = showing,
            transitionSpec = {
                fadeIn(tween(Motion.MEDIUM, delayMillis = Motion.FADE_OUT)) togetherWith fadeOut(tween(Motion.FADE_OUT))
            },
            label = "now-showing",
            modifier = Modifier.fillMaxSize(),
        ) { now ->
            val words = cardWords(now, tvName)
            // The bar on its own offers the one thing it can't do by itself: waking the TV.
            val wake: (@Composable () -> Unit)? = if (now is CardShowing.OnTheBar) {
                { WakeTvButton(onWakeTv, waking, compact = fit.size != CardSize.FULL) }
            } else {
                null
            }
            if (fit.size == CardSize.FULL) {
                Full(now, words, fit.height, appIcons, wake)
            } else {
                Line(now, words, appIcons, wake)
            }
        }
    }
}

private data class CardWords(val title: String, val detail: String?)

@Composable
private fun cardWords(showing: CardShowing, tvName: String): CardWords = when (showing) {
    is CardShowing.Showing -> when (val now = showing.now) {
        is NowShowing.App -> CardWords(now.name, tvName)
        is NowShowing.Input -> CardWords(now.title, stringResource(R.string.card_input))
        is NowShowing.Channel -> {
            val head = listOfNotNull(now.number, now.name).joinToString(" · ").ifEmpty { null }
            CardWords(head ?: stringResource(R.string.system_tv_channel), now.programme ?: if (head != null) stringResource(R.string.system_tv_channel) else tvName)
        }
    }
    // The source large; under it what the TV is doing: asleep, or today's out-of-reach words.
    is CardShowing.OnTheBar -> CardWords(
        barValueLabel(TheatreFeature.INPUT, showing.bar.input),
        stringResource(if (showing.bar.tvAsleep) R.string.card_standby else R.string.card_offline),
    )
    is CardShowing.Standby -> CardWords(stringResource(R.string.card_standby), if (showing.barOn) stringResource(R.string.card_bar_stays_on) else null)
    CardShowing.Home -> CardWords(stringResource(R.string.card_home), stringResource(R.string.card_home_detail))
    CardShowing.Reconnecting -> CardWords(stringResource(R.string.card_reconnecting), stringResource(R.string.card_reconnecting_detail))
    CardShowing.Offline -> CardWords(stringResource(R.string.card_offline), stringResource(R.string.card_offline_detail))
}

@DrawableRes
private fun CardShowing.icon(): Int = when (this) {
    is CardShowing.Showing -> when (now) {
        is NowShowing.App -> R.drawable.ic_apps
        is NowShowing.Input -> R.drawable.ic_input
        is NowShowing.Channel -> R.drawable.ic_tv
    }
    is CardShowing.OnTheBar -> barSourceIcon(bar.input)
    is CardShowing.Standby -> R.drawable.ic_power
    CardShowing.Home -> R.drawable.ic_home
    // Quiet: the turning arrow, not the warning triangle, which is kept for out of reach.
    CardShowing.Reconnecting -> R.drawable.ic_refresh
    CardShowing.Offline -> R.drawable.ic_warning
}

/** The bar's own source as a glyph: Spotify's own mark, the Bluetooth and AirPlay symbols. */
@DrawableRes
internal fun barSourceIcon(input: String): Int = when (input) {
    "spotify" -> R.drawable.brand_spotify
    "bluetooth" -> R.drawable.ic_bluetooth
    "airplay2" -> R.drawable.ic_airplay
    else -> R.drawable.ic_speaker
}

/** The app's own icon when there is one, else the state's glyph on a tile of the same size. */
@Composable
private fun CardIcon(showing: CardShowing, size: Dp, appIcons: Map<String, String>) {
    val app = (showing as? CardShowing.Showing)?.now as? NowShowing.App
    if (app != null) {
        AppIcon(name = app.name, iconPath = appIcons[app.packageName], packageName = app.packageName, size = size, corner = size / 4)
        return
    }
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(showing.icon()),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun Full(showing: CardShowing, words: CardWords, height: Float, appIcons: Map<String, String>, action: (@Composable () -> Unit)?) {
    // 104 dp gives a 56 dp icon and a large title; the room above that grows both, up to 140 dp.
    val grow = ((height - CARD_FULL) / (CARD_MAX - CARD_FULL)).coerceIn(0f, 1f)
    val icon = (56f + 16f * grow).dp
    val title = if (grow >= 0.5f) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
    Row(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardIcon(showing, icon, appIcons)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(if (showing is CardShowing.OnTheBar) R.string.card_on_the_bar else R.string.card_on_the_tv).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(words.title, style = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            words.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    }
}

@Composable
private fun Line(showing: CardShowing, words: CardWords, appIcons: Map<String, String>, action: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardIcon(showing, 32.dp, appIcons)
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                words.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            words.detail?.let {
                Text(
                    " · $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            action()
        }
    }
}

/**
 * "Turn on the TV": wakes the TV alone and leaves the bar as it is (Erwann, 2026-10-24). The power
 * glyph and the words together; on the 56 dp line the words shorten to "Turn on", the TV implied
 * by "TV on standby" beside it, and TalkBack still hears the full label. Disabled while a wake is
 * under way, which the Power key's ring already shows.
 */
@Composable
private fun WakeTvButton(onClick: () -> Unit, waking: Boolean, compact: Boolean) {
    val full = stringResource(R.string.card_wake_tv)
    FilledTonalButton(
        onClick = onClick,
        enabled = !waking,
        contentPadding = PaddingValues(horizontal = if (compact) 12.dp else 16.dp),
        modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = full },
    ) {
        Icon(painterResource(R.drawable.ic_power), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (compact) stringResource(R.string.card_wake_tv_short) else full, maxLines = 1)
    }
}

private val CARD_CORNER = 24.dp

package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
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
import io.kud.tutti.ui.common.brandColour
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.motion.Motion

/**
 * What the TV is showing, in the room between the scene pills and the pad (Iris's option A,
 * chosen by Erwann, 2026-10-24): an icon, the name large and a small detail line. [fit] comes from
 * [placePad]: full (104 to 140 dp, the icon and name growing with it), one 56 dp line, or hidden.
 * Every state is told by its icon and its words, never colour alone, and it is never empty: an
 * app, a channel or an input, the bar on its own, standby, the home screen, or the TV out of
 * reach. While the TV shows something a tap opens the playback sheet ([onOpen]) and, on an app, a
 * long press its actions ([onActions]); a quiet chevron at the end says the card opens (Erwann,
 * 2026-10-25). The bar on its own carries one button, "Turn on the TV" ([onWakeTv]). State changes fade through by opacity only, which is also the
 * reduced-motion form.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowShowingCard(
    showing: CardShowing,
    fit: CardFit,
    tvName: String,
    appIcons: Map<String, String>,
    modifier: Modifier = Modifier,
    onWakeTv: () -> Unit = {},
    waking: Boolean = false,
    onOpen: (() -> Unit)? = null,
    /** The tap points the remote at this computer ([CardTap.CONTROL_COMPUTER]): « Contrôler <nom> » instead of playback. */
    controls: String? = null,
    onActions: (() -> Unit)? = null,
    /** A running switch-off timer, in words ("Off at 23:40 · 12 min left"), added to the card's detail line. */
    timer: String? = null,
    /** What Cast reports playing: the full card's title line, track and time; never on the compact card. */
    cast: io.kud.tutti.protocol.cast.CastNowPlaying? = null,
) {
    if (fit.size == CardSize.HIDDEN) return
    val shape = RoundedCornerShape(CARD_CORNER)
    val openLabel = if (controls != null) stringResource(R.string.card_control_computer, controls) else stringResource(R.string.card_playback_open)
    val actionsLabel = stringResource(R.string.card_app_actions_open)
    Box(
        modifier
            .fillMaxWidth()
            .height(fit.height.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .then(
                if (onOpen != null) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClickLabel = openLabel,
                        onLongClickLabel = onActions?.let { actionsLabel },
                        onLongClick = onActions,
                        onClick = onOpen,
                    )
                } else {
                    Modifier
                },
            )
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
            val words = cardWords(now, tvName).let { words ->
                if (timer == null) words else words.copy(detail = listOfNotNull(words.detail, timer).joinToString(" · "))
            }
            // The bar on its own offers the one thing it can't do by itself: waking the TV.
            val wake: (@Composable () -> Unit)? = when {
                now is CardShowing.OnTheBar -> {
                    { WakeTvButton(onWakeTv, waking, compact = fit.size != CardSize.FULL) }
                }
                onOpen != null && cardOpensPlayback(now) -> {
                    { OpensChevron() }
                }
                else -> null
            }
            if (fit.size == CardSize.FULL) {
                Full(now, words, fit.height, appIcons, wake, cast?.takeIf { castFits(now) })
            } else {
                Line(now, words, appIcons, wake)
            }
        }
    }
}

internal data class CardWords(val title: String, val detail: String?)

@Composable
internal fun cardWords(showing: CardShowing, tvName: String): CardWords = when (showing) {
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
internal fun CardIcon(showing: CardShowing, size: Dp, appIcons: Map<String, String>) {
    val app = (showing as? CardShowing.Showing)?.now as? NowShowing.App
    if (app != null) {
        AppIcon(name = app.name, iconPath = appIcons[app.packageName], packageName = app.packageName, size = size, corner = size / 4)
        return
    }
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = showing.icon()
        Icon(
            painterResource(glyph),
            contentDescription = null,
            // A brand logo (the bar's Spotify) keeps its own colour; state glyphs stay quiet.
            tint = brandColour(glyph)?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun Full(
    showing: CardShowing,
    words: CardWords,
    height: Float,
    appIcons: Map<String, String>,
    action: (@Composable () -> Unit)?,
    cast: io.kud.tutti.protocol.cast.CastNowPlaying?,
) {
    // 104 dp gives a 56 dp icon and a large title; the room above that grows both, up to 140 dp.
    val grow = ((height - CARD_FULL) / (CARD_MAX - CARD_FULL)).coerceIn(0f, 1f)
    val icon = (56f + 16f * grow).dp
    val title = if (grow >= 0.5f) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
    Row(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CastLead(showing, icon, appIcons, cast)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(if (showing is CardShowing.OnTheBar) R.string.card_on_the_bar else R.string.card_on_the_tv).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(words.title, style = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (cast != null) {
                // Iris: the Cast title takes the detail line's place, the channel always under it
                // (Erwann, 2026-10-27); at 104 dp the title gives up its second line for it.
                CastLine(cast, roomy = grow >= 0.5f)
            } else words.detail?.let {
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

/**
 * The card opens: a 20 dp chevron pointing up, where the sheet will rise from, in
 * onSurfaceVariant. Quiet on purpose: the card stays a readout first; TalkBack hears the action.
 */
@Composable
private fun OpensChevron() {
    Icon(
        painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp).rotate(-90f),
    )
}

private val CARD_CORNER = 24.dp

/** Cast speaks for the TV's own screen only: an app, a channel or the home screen, never the bar alone, standby or a lost link. */
internal fun castFits(showing: CardShowing): Boolean = showing is CardShowing.Showing || showing == CardShowing.Home

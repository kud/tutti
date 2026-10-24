package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.motion.Motion

/**
 * What the TV is showing, in the room between the scene pills and the pad's tab (Iris's option A,
 * chosen by Erwann, 2026-10-24): an icon, the name large and a small detail line. [fit] comes from
 * [placePad]: full (104 to 140 dp, the icon and name growing with it), one 56 dp line, or hidden.
 * Every state is told by its icon and its words, never colour alone, and it is never empty: an
 * app, a channel or an input, standby, the home screen, or the TV out of reach. A tap does nothing
 * for now. State changes cross-fade by opacity only, which is also the reduced-motion form.
 */
@Composable
internal fun NowShowingCard(
    showing: CardShowing,
    fit: CardFit,
    tvName: String,
    appIcons: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    if (fit.size == CardSize.HIDDEN) return
    Box(
        modifier
            .fillMaxWidth()
            .height(fit.height.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(CARD_CORNER))
            .semantics(mergeDescendants = true) {},
    ) {
        Crossfade(
            targetState = showing,
            animationSpec = tween(Motion.MEDIUM),
            label = "now-showing",
            modifier = Modifier.fillMaxSize(),
        ) { now ->
            val words = cardWords(now, tvName)
            if (fit.size == CardSize.FULL) {
                Full(now, words, fit.height, appIcons)
            } else {
                Line(now, words, appIcons)
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
    is CardShowing.Standby -> CardWords(stringResource(R.string.card_standby), if (showing.barOn) stringResource(R.string.card_bar_stays_on) else null)
    CardShowing.Home -> CardWords(stringResource(R.string.card_home), stringResource(R.string.card_home_detail))
    CardShowing.Offline -> CardWords(stringResource(R.string.card_offline), stringResource(R.string.card_offline_detail))
}

@DrawableRes
private fun CardShowing.icon(): Int = when (this) {
    is CardShowing.Showing -> when (now) {
        is NowShowing.App -> R.drawable.ic_apps
        is NowShowing.Input -> R.drawable.ic_input
        is NowShowing.Channel -> R.drawable.ic_tv
    }
    is CardShowing.Standby -> R.drawable.ic_power
    CardShowing.Home -> R.drawable.ic_home
    CardShowing.Offline -> R.drawable.ic_warning
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
private fun Full(showing: CardShowing, words: CardWords, height: Float, appIcons: Map<String, String>) {
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
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(R.string.card_on_the_tv).uppercase(),
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
    }
}

@Composable
private fun Line(showing: CardShowing, words: CardWords, appIcons: Map<String, String>) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardIcon(showing, 32.dp, appIcons)
        Spacer(Modifier.width(12.dp))
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
}

private val CARD_CORNER = 24.dp

package io.kud.tutti.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.remote.ConnLink
import io.kud.tutti.ui.common.StatusAction
import io.kud.tutti.ui.common.StatusGap
import io.kud.tutti.ui.common.StatusGlyph
import io.kud.tutti.ui.common.StatusWords
import io.kud.tutti.ui.remote.stateWords

/**
 * The status tiles above Settings' list ([dashboard]): a tile that needs him spans the width, first,
 * with its one action as a button; the others sit two by two in a fixed order (TV, soundbar, sync,
 * what tutti can do), so the eye learns where each one lives, and an odd one out spans the width
 * rather than sitting alone in half of it ([dashRows]). Every state is a glyph and words, never a
 * colour alone (Erwann is colourblind); a tile's tap opens its section.
 */
@Composable
internal fun Dashboard(items: List<DashItem>, onTile: (DashTile) -> Unit, onAction: (DashAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in dashRows(items)) {
            if (row.size == 1) {
                Tile(row.single(), onTile, onAction, Modifier.fillMaxWidth(), wide = true)
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in row) Tile(item, onTile, onAction, Modifier.weight(1f).fillMaxHeight(), wide = false)
                }
            }
        }
    }
}

/**
 * The tiles in rows: each one that needs him alone, then the rest in pairs, the last spanning the
 * width when they don't pair up (Erwann, 2026-11-06: with a problem tile on top, "Features" sat
 * alone in half the width, its words squeezed onto four lines).
 */
internal fun dashRows(items: List<DashItem>): List<List<DashItem>> =
    items.filter { it.needsYou }.map { listOf(it) } + items.filterNot { it.needsYou }.chunked(2)

@Composable
private fun Tile(item: DashItem, onTile: (DashTile) -> Unit, onAction: (DashAction) -> Unit, modifier: Modifier, wide: Boolean) {
    // One status language (design.md › Status): the glyph and words of [Status], and only a tile
    // that needs him wears the emphasis, a raised surface inside an outline, with its one action.
    val needs = item.needsYou
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = { onTile(item.tile) },
        modifier = modifier.heightIn(min = 72.dp),
        shape = TileShape,
        color = if (needs) colors.surfaceContainerHighest else colors.surfaceContainer,
        border = if (needs) BorderStroke(1.5.dp, colors.outline) else null,
    ) {
        // Half a phone is too narrow for "Synchronisation" beside an icon (Erwann, 2026-11-06: no
        // cut tile names), so a half tile wears its icon on top and gives the name its whole width;
        // a name that still can't fit, at a large font size, breaks with a hyphen, never an ellipsis.
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = if (needs) Alignment.CenterVertically else Alignment.Top) {
            if (wide) {
                Icon(painterResource(tileIcon(item.tile)), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (!wide) {
                    Icon(painterResource(tileIcon(item.tile)), contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    tileName(item.tile),
                    style = MaterialTheme.typography.titleSmall.copy(hyphens = Hyphens.Auto),
                    maxLines = 2,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    StatusGlyph(item.mark, Modifier.padding(top = 3.dp))
                    Spacer(Modifier.width(StatusGap))
                    StatusWords(
                        item.mark,
                        tileWords(item.words),
                        capability = (item.words as? DashWords.Progress)?.next?.let { stringResource(R.string.dash_next, stringResource(unlockTitle(it))) },
                        wordsLines = if (needs) 3 else 2,
                    )
                }
            }
            val action = item.action
            if (needs && action != null) {
                Spacer(Modifier.width(12.dp))
                StatusAction(actionLabel(action), onClick = { onAction(action) })
            }
        }
    }
}

private val TileShape = RoundedCornerShape(16.dp)

private fun tileIcon(tile: DashTile): Int = when (tile) {
    DashTile.TV -> R.drawable.ic_tv
    DashTile.SOUNDBAR -> R.drawable.ic_speaker
    DashTile.SYNC -> R.drawable.ic_refresh
    DashTile.UNLOCKS -> R.drawable.ic_unlock
}

@Composable
private fun tileName(tile: DashTile): String = stringResource(
    when (tile) {
        DashTile.TV -> R.string.settings_tv
        DashTile.SOUNDBAR -> R.string.settings_bar
        DashTile.SYNC -> R.string.sync_title
        DashTile.UNLOCKS -> R.string.dash_unlocks
    },
)

@Composable
private fun tileWords(words: DashWords): String = when (words) {
    is DashWords.Device -> {
        val said = stateWords(words.words).replaceFirstChar { it.uppercase() }
        // Only IP control speaks for itself on the TV's tile; the Google TV link is the remote.
        if (words.link == ConnLink.SONY_IP) stringResource(R.string.dash_link_words, stringResource(R.string.conn_link_sony_ip), stateWords(words.words)) else said
    }
    is DashWords.Sync -> listOfNotNull(
        stringResource(
            when (words.words) {
                SyncWords.OFF -> R.string.sync_line_off
                SyncWords.ON -> R.string.sync_line_on
                SyncWords.PAUSED -> R.string.sync_line_paused
                SyncWords.WAITING -> R.string.dash_sync_waiting
                SyncWords.PROBLEM -> R.string.sync_line_problem
            },
        ),
        words.age?.let { ageWords(it) },
    ).joinToString(" · ")
    is DashWords.Progress ->
        if (words.next == null) stringResource(R.string.unlock_entry_all) else stringResource(R.string.dash_progress, words.done, words.total)
}

/** "just now", "5 min ago", "2 h ago", "3 days ago": never DateUtils' "0 minutes ago". */
@Composable
internal fun ageWords(age: SyncAge): String = when (age) {
    SyncAge.JustNow -> stringResource(R.string.sync_age_now)
    is SyncAge.Minutes -> pluralStringResource(R.plurals.sync_age_minutes, age.n, age.n)
    is SyncAge.Hours -> pluralStringResource(R.plurals.sync_age_hours, age.n, age.n)
    is SyncAge.Days -> pluralStringResource(R.plurals.sync_age_days, age.n, age.n)
}

@Composable
private fun actionLabel(action: DashAction): String = stringResource(
    when (action) {
        DashAction.PAIR_TV -> R.string.conn_action_pair
        DashAction.SET_UP_IP -> R.string.conn_action_set_up
        DashAction.ADD_BAR -> R.string.conn_action_add
        DashAction.OPEN_DEVICES -> R.string.conn_action_devices
        DashAction.CHECK_SYNC -> R.string.dash_action_check
    },
)

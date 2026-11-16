package io.kud.tutti.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.cast.CastSayAgain

/**
 * Say again? (Erwann, 2026-11-12, « Hein ? »): one tap jumps the Cast app back [seconds] and turns
 * captions and the bar's voice enhancement on for the replayed span ([CastSayAgain]). Shown beside
 * Speed while a Cast item with a trusted position is shown, unless the app says it takes no SEEK
 * or refused one this run, the same rule as the Speed chip. Nothing to pick: the seconds are a setting.
 */
@Composable
internal fun SayAgainChip(cast: CastNowPlaying?, refused: Set<String>, seconds: Int, downReason: String?, onSayAgain: (Int) -> Unit) {
    if (!CastSayAgain.offered(cast, refused, System.currentTimeMillis())) return
    ActionChip(R.drawable.ic_replay, stringResource(R.string.say_again), downReason) { onSayAgain(seconds) }
}

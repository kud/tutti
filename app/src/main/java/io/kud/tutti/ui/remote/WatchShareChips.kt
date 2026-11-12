package io.kud.tutti.ui.remote

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.kud.tutti.R
import io.kud.tutti.protocol.cast.CastArtwork
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.share.ShareLanguage
import io.kud.tutti.protocol.share.TitleLink
import io.kud.tutti.protocol.share.TitleLinks
import io.kud.tutti.protocol.share.TraktStep
import io.kud.tutti.protocol.share.WatchShare
import io.kud.tutti.protocol.share.Watching
import java.util.Locale

/**
 * What the card shows, as [Watching]: the Cast item when Cast names one (with the app in front's
 * name and the id its artwork carries: a YouTube video id, or Cinemeta's IMDb id), else the
 * tuner's channel, else the app alone. An input, the home screen or standby say nothing worth sharing: null.
 */
internal fun watchingOf(showing: CardShowing, cast: CastNowPlaying?, nowMillis: Long): Watching? {
    val now = (showing as? CardShowing.Showing)?.now
    if (cast != null && castFits(showing)) {
        return Watching.Media(
            title = cast.title,
            secondary = cast.secondary,
            app = (now as? NowShowing.App)?.name,
            positionSec = cast.positionAt(nowMillis)?.toLong(),
            durationSec = cast.durationSec?.toLong(),
            videoId = WatchShare.youTubeVideoId(cast.imageUrl),
            imdbId = CastArtwork.imdbId(cast.imageUrl),
            metadataType = cast.metadataType,
            appPackage = (now as? NowShowing.App)?.packageName,
        )
    }
    return when (now) {
        is NowShowing.Channel -> Watching.Channel(now.number, now.name, now.programme)
        is NowShowing.App -> Watching.App(now.name, now.packageName)
        else -> null
    }
}

/**
 * The playback sheet's last row (Erwann, 2026-11-11): Share, always, and the Trakt, JustWatch and
 * IMDb buttons when their switches are on and a film or series plays ([TitleLinks.offered],
 * [TitleLinks.isFilmOrSeries]: never in YouTube or a music app): JustWatch searches a known title;
 * IMDb opens its page, or searches it; Trakt opens on it, or searches it, or, with only an app in
 * front, opens Trakt itself ([io.kud.tutti.protocol.share.TraktTarget]). All of them act
 * on the phone, so they stay live with the TV's link down. Nothing leaves the phone but through
 * the share sheet or the link he taps, and nothing here is logged: titles are viewing data.
 */
@Composable
internal fun WatchShareChips(watchingAt: (nowMillis: Long) -> Watching?, trakt: Boolean, justWatch: Boolean, imdb: Boolean) {
    val watching = watchingAt(System.currentTimeMillis()) ?: return
    val context = LocalContext.current
    // The UI's own language, as resolved from values/ and values-fr/, not the phone's.
    val language = ShareLanguage.of(stringResource(R.string.watch_share_language))
    val text = WatchShare.text(watching, language)
    val title = TitleLinks.searchTitle(watching)
    val links = TitleLinks.offered(watching, trakt, justWatch, imdb)
    if (text == null && links.isEmpty()) return
    val chooserTitle = stringResource(R.string.watch_share_title)
    PlaybackChipRow(Modifier.fillMaxWidth()) {
        if (text != null) {
            ActionChip(R.drawable.ic_share, stringResource(R.string.watch_share), null) {
                // Read again at the tap, so the sentence says where it is now, not when the sheet opened.
                val fresh = watchingAt(System.currentTimeMillis())?.let { WatchShare.text(it, language) } ?: text
                shareText(context, fresh, chooserTitle)
            }
        }
        val traktTo = TitleLinks.traktTarget(watching)
        if (traktTo != null && TitleLink.TRAKT in links) {
            ActionChip(R.drawable.ic_open_in_new, stringResource(R.string.watch_trakt), null) {
                openTrakt(context, TitleLinks.traktSteps(traktTo))
            }
        }
        if (title != null && TitleLink.JUSTWATCH in links) {
            ActionChip(R.drawable.ic_open_in_new, stringResource(R.string.watch_justwatch), null) {
                val phone = Locale.getDefault()
                openLink(context, TitleLinks.justWatch(title, phone.country, phone.language))
            }
        }
        val imdbUrl = TitleLinks.imdb(watching)
        if (imdbUrl != null && TitleLink.IMDB in links) {
            ActionChip(R.drawable.ic_open_in_new, stringResource(R.string.watch_imdb), null) {
                openInApp(context, imdbUrl, TitleLinks.IMDB_PACKAGE)
            }
        }
    }
}

private fun shareText(context: Context, text: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No share target at all: nothing to do, and nothing to log.
    }
}

/**
 * Trakt, by [TitleLinks.traktSteps]: the first step the phone can take. The app's package is
 * visible through the manifest's launcher `<queries>`, so the app link and the app search are
 * resolved before they are sent, and one the installed version does not handle falls through
 * rather than reaching a browser or another app.
 */
private fun openTrakt(context: Context, steps: List<TraktStep>) {
    val pm = context.packageManager
    for (step in steps) {
        val intent = when (step) {
            is TraktStep.AppLink -> Intent(Intent.ACTION_VIEW, step.url.toUri())
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(TitleLinks.TRAKT_PACKAGE)
                .takeIf { it.resolveActivity(pm) != null }
            is TraktStep.AppSearch -> Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, step.query)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .setPackage(TitleLinks.TRAKT_PACKAGE)
                .takeIf { it.resolveActivity(pm) != null }
            TraktStep.LaunchApp -> pm.getLaunchIntentForPackage(TitleLinks.TRAKT_PACKAGE)
            is TraktStep.Web -> Intent(Intent.ACTION_VIEW, step.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        } ?: continue
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next way; with none left, there is nothing to open.
        }
    }
}

/**
 * [url] in [pkg] when that app is installed and takes the link (resolved first, through the
 * launcher `<queries>`), else a plain VIEW, as [openLink].
 */
private fun openInApp(context: Context, url: String, pkg: String) {
    val inApp = Intent(Intent.ACTION_VIEW, url.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setPackage(pkg)
        .takeIf { it.resolveActivity(context.packageManager) != null }
    if (inApp != null) {
        try {
            context.startActivity(inApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the browser.
        }
    }
    openLink(context, url)
}

/** A plain VIEW, not a Custom Tab, so an app that verified the link's host can take it. */
private fun openLink(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, url.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        // No browser on the phone: nothing to open.
    }
}

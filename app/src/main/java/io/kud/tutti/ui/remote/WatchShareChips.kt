package io.kud.tutti.ui.remote

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.kud.tutti.R
import io.kud.tutti.platform.net.CastArtworkLoader
import io.kud.tutti.protocol.cast.CastArtwork
import io.kud.tutti.protocol.cast.CastNowPlaying
import io.kud.tutti.protocol.share.ShareArtwork
import io.kud.tutti.protocol.share.ShareLanguage
import io.kud.tutti.protocol.share.TitleLink
import io.kud.tutti.protocol.share.TitleLinks
import io.kud.tutti.protocol.share.TraktStep
import io.kud.tutti.protocol.share.WatchShare
import io.kud.tutti.protocol.share.Watching
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * Share sends the card's artwork with the sentence when the card may show it ([artworkAt], the
 * same gate and hosts as the card: [ShareArtwork]), else the sentence alone, as before.
 */
@Composable
internal fun WatchShareChips(
    watchingAt: (nowMillis: Long) -> Watching?,
    artworkAt: () -> String?,
    trakt: Boolean,
    justWatch: Boolean,
    imdb: Boolean,
) {
    val watching = watchingAt(System.currentTimeMillis()) ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                val candidates = ShareArtwork.candidates(artworkAt())
                scope.launch {
                    val image = runCatching { artworkFile(context, scope, candidates) }.getOrNull()
                    share(context, fresh, chooserTitle, image)
                }
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

/**
 * The artwork for a share, written to `cache/share/` and offered through the share FileProvider,
 * or null (no candidate, the gate shut, nothing decoded in time, any failure). The folder is
 * emptied first, every time, so the last share's picture never outlives the next tap; it sits in
 * the cache, which backup never takes. Nothing here logs: the picture is viewing data.
 */
private suspend fun artworkFile(context: Context, scope: CoroutineScope, candidates: List<String>): Uri? {
    val dir = File(context.cacheDir, ShareArtwork.DIR)
    withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
    if (candidates.isEmpty()) return null
    // Started on the screen's scope, not this one, so a load the wait gives up on still finishes
    // and its picture is in memory for the next share.
    val loads = candidates.map { url -> scope.async { CastArtworkLoader.load(url, ShareArtwork.WIDTH_PX) } }
    val bitmap = ShareArtwork.pick(loads) ?: return null
    val file = withContext(Dispatchers.IO) {
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext null
        val out = File(dir, ShareArtwork.FILE_NAME)
        val written = out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        out.takeIf { written }
    } ?: return null
    return FileProvider.getUriForFile(context, "${context.packageName}$SHARE_AUTHORITY_SUFFIX", file)
}

/** Matches the `<provider>` in the manifest (`${applicationId}.share`). */
private const val SHARE_AUTHORITY_SUFFIX = ".share"

/**
 * The sentence, with the artwork when there is one: `image/jpeg`, the picture as EXTRA_STREAM and
 * as ClipData (so the chooser previews it, Android 10 and up, and the grant reaches the target),
 * the sentence as EXTRA_TEXT. Without one, plain text, as before.
 */
private fun share(context: Context, text: String, chooserTitle: String, image: Uri?) {
    val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
    if (image != null) {
        send.setType(ShareArtwork.MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, image)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(context.contentResolver, chooserTitle, image)
    } else {
        send.setType("text/plain")
    }
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

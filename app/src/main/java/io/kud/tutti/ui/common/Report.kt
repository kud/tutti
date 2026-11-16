package io.kud.tutti.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.kud.tutti.R
import io.kud.tutti.platform.diagnostic.AppLog

/*
 * "Report on GitHub", keyless (Erwann, 2026-11-11: « sans clé c'est pas mal aussi »): a
 * new-issue link filled in from the failure and the recent events, opened in the browser, where
 * the person reads it and submits it with their own account. tutti sends nothing and holds no
 * GitHub token; the link is the whole feature. Developer mode only, beside Copy details.
 */

/** Opens the filled-in new-issue page for [what], with [detail] and the event log's newest lines. */
fun reportOnGitHub(context: Context, what: String, detail: String?) {
    openInBrowser(context, AppLog.issueLink(context, what, detail))
}

/** The Android share sheet with [text] whole, for what a link can't carry. */
fun shareText(context: Context, text: String, subject: String = context.getString(R.string.event_log_share_subject)) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The "Report on GitHub" button; the caller shows it only in developer mode. */
@Composable
fun ReportButton(what: String, detail: String?) {
    val context = LocalContext.current
    TextButton(onClick = { reportOnGitHub(context, what, detail) }) {
        Text(stringResource(R.string.report_github))
    }
}

/** A Custom Tab when a browser offers one, any browser otherwise, nothing if there is none. */
private fun openInBrowser(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No browser on the phone: Copy details still works.
        }
    }
}

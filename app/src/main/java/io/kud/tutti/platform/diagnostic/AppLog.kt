package io.kud.tutti.platform.diagnostic

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.kud.tutti.BuildConfig
import io.kud.tutti.protocol.diagnostic.EventLog
import io.kud.tutti.protocol.diagnostic.IssueLink

/**
 * The process's one [EventLog]: the controller writes to it, developer mode reads it (the log
 * screen, "Report on GitHub"). A plain object rather than an injected one because the crash dialog
 * and the problem notes reach it outside the graph. In memory only; it dies with the process.
 */
object AppLog {
    val events = EventLog()

    /** The TV's model family ("K-xxXR80") once the TV has said its model; never the full model. */
    @Volatile var tvFamily: String? = null

    /**
     * The phone's own name ("Erwann's Pixel"), scrubbed from every line by value: a name the
     * person gave their phone is theirs, not a fact for a report.
     */
    fun phoneNames(context: Context): List<String> = listOfNotNull(
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
    )

    /** The context lines of a report: what build, what Android, what TV family. No identifiers. */
    fun facts(context: Context): List<Pair<String, String>> = listOfNotNull(
        "app" to "${AppVersion.of(context)}, ${BuildConfig.FLAVOR}",
        "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        tvFamily?.let { "tv" to it },
    )

    /** The "Report on GitHub" link for [what] failing, with [detail] and the recent events. */
    fun issueLink(context: Context, what: String, detail: String?): String =
        IssueLink.build(title = what, facts = facts(context), detail = detail, log = events.render())
}

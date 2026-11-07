package io.kud.tutti.ui.about

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import io.kud.tutti.BuildConfig
import io.kud.tutti.R
import io.kud.tutti.protocol.update.AppVersion
import io.kud.tutti.ui.common.LocalMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** The intent action that opens What's new over the remote (the after-update notification's tap). */
const val ACTION_WHATS_NEW = "io.kud.tutti.action.WHATS_NEW"

/** The notes this build carries, as this build tells them (without the updater's changes in F-Droid's). */
fun installedNotes(): Release? =
    ReleaseNotes.forInstalled(AppVersion.parse(BuildConfig.VERSION_NAME))?.forBuild(CARRIES_UPDATER)

/**
 * Which notes this install has read, on this phone only: never synced (each phone updates on its
 * own) and apart from the settings blob, so no stored setting changes shape. A plain string under a
 * key of its own; a missing key reads as "never stored", which [WhatsNewRule] tells apart.
 */
class WhatsNewMemory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val seen: AppVersion? get() = prefs.getString(KEY_SEEN, null)?.let(AppVersion::parse)

    fun markSeen(version: AppVersion) {
        val current = seen
        if (current == null || current < version) prefs.edit().putString(KEY_SEEN, version.toString()).apply()
    }

    private companion object {
        const val PREFS = "whats_new"
        const val KEY_SEEN = "seen_notes"
    }
}

/**
 * After an update, one message on the plain remote: "Updated to version x." with **What's new**
 * (docs/design.md, "What's new"). It waits for the launch to settle and for the same quiet moment
 * the update notice waits for ([updateNoticeAllowed]): no sheet, menu or editor, no other message.
 * It is marked read once said, so it never repeats; a session that never reaches a quiet moment
 * leaves it for the next. Nothing moves on the remote: the message host draws it as any other.
 */
@Composable
fun WhatsNewHintEffect(
    ready: Boolean,
    established: Boolean,
    onRemote: () -> Boolean,
    editing: () -> Boolean,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val messages = LocalMessages.current ?: return
    val open by rememberUpdatedState(onOpen)
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        val notes = installedNotes() ?: return@LaunchedEffect
        val memory = WhatsNewMemory(context)
        when (WhatsNewRule.hint(notes.version, memory.seen, established)) {
            WhatsNewRule.Hint.NONE -> return@LaunchedEffect
            WhatsNewRule.Hint.RECORD -> {
                memory.markSeen(notes.version)
                return@LaunchedEffect
            }
            WhatsNewRule.Hint.SHOW -> Unit
        }
        // Past the launch intro and the first connection's own words.
        delay(SETTLE_MS)
        snapshotFlow {
            updateNoticeAllowed(onRemote(), editing(), covers = messages.covers, messageShowing = messages.current != null)
        }.first { it }
        // Read again: opening the page from the notification meanwhile has already said it.
        if (WhatsNewRule.hint(notes.version, memory.seen, established = true) != WhatsNewRule.Hint.SHOW) return@LaunchedEffect
        messages.show(
            resources.getString(R.string.whats_new_message, notes.version.toString()),
            actionLabel = resources.getString(R.string.whats_new_action),
            onAction = { open() },
            long = true,
        )
        memory.markSeen(notes.version)
    }
}

private const val SETTLE_MS = 2_500L

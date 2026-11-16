package io.kud.tutti.ui.about

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.kud.tutti.R
import io.kud.tutti.protocol.update.AppVersion

/**
 * What changed in a release, in the words of the person holding the remote (docs/design.md,
 * "What's new"). The strings are the one source: the What's new page, the after-update message and
 * notification read them here, and `docs/releases/<version>.md` repeats the English for GitHub and
 * the blog (ReleaseNotesDocTest keeps the two in step).
 *
 * [updaterOnly] marks a change only the build with the in-app updater has (test builds, the
 * after-update notification): the F-Droid build leaves it out rather than describe what it lacks.
 */
data class ReleaseNote(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    val updaterOnly: Boolean = false,
)

data class Release(
    /** Major, minor and patch only: a test build of this version carries the same notes. */
    val version: AppVersion,
    /** One sentence: the notification's line and the page's lead. */
    @param:StringRes val headline: Int,
    val new: List<ReleaseNote>,
    val better: List<ReleaseNote>,
    val fixed: List<ReleaseNote>,
) {
    /** The same release as this build tells it: without the updater's own changes when it has none. */
    fun forBuild(carriesUpdater: Boolean): Release =
        if (carriesUpdater) {
            this
        } else {
            copy(new = new.filterNot { it.updaterOnly }, better = better.filterNot { it.updaterOnly }, fixed = fixed.filterNot { it.updaterOnly })
        }
}

object ReleaseNotes {
    private val v0_3_1 = Release(
        version = AppVersion(0, 3, 1, null),
        headline = R.string.whats_new_0_3_1_headline,
        new = listOf(
            ReleaseNote(R.drawable.ic_subtitles, R.string.whats_new_0_3_1_say_again, R.string.whats_new_0_3_1_say_again_detail),
            ReleaseNote(R.drawable.ic_bedtime, R.string.whats_new_0_3_1_episode, R.string.whats_new_0_3_1_episode_detail),
            ReleaseNote(R.drawable.ic_call, R.string.whats_new_0_3_1_calls, R.string.whats_new_0_3_1_calls_detail),
            ReleaseNote(R.drawable.ic_volume_up, R.string.whats_new_0_3_1_night, R.string.whats_new_0_3_1_night_detail),
            ReleaseNote(R.drawable.ic_contrast, R.string.whats_new_0_3_1_picture, R.string.whats_new_0_3_1_picture_detail),
            ReleaseNote(R.drawable.ic_share, R.string.whats_new_0_3_1_share, R.string.whats_new_0_3_1_share_detail),
            ReleaseNote(R.drawable.ic_article, R.string.whats_new_0_3_1_details, R.string.whats_new_0_3_1_details_detail),
            ReleaseNote(R.drawable.ic_link, R.string.whats_new_0_3_1_destinations, R.string.whats_new_0_3_1_destinations_detail),
            ReleaseNote(R.drawable.ic_folder, R.string.whats_new_0_3_1_backup, R.string.whats_new_0_3_1_backup_detail),
        ),
        better = listOf(
            ReleaseNote(R.drawable.ic_settings, R.string.whats_new_0_3_1_settings, R.string.whats_new_0_3_1_settings_detail),
            ReleaseNote(R.drawable.ic_visibility_off, R.string.whats_new_0_3_1_privacy, R.string.whats_new_0_3_1_privacy_detail),
            ReleaseNote(R.drawable.ic_movie, R.string.whats_new_0_3_1_artwork, R.string.whats_new_0_3_1_artwork_detail),
            ReleaseNote(R.drawable.ic_info, R.string.whats_new_0_3_1_hold, R.string.whats_new_0_3_1_hold_detail),
            ReleaseNote(R.drawable.ic_lock, R.string.whats_new_0_3_1_trust, R.string.whats_new_0_3_1_trust_detail),
        ),
        fixed = listOf(
            ReleaseNote(R.drawable.ic_dpad, R.string.whats_new_0_3_1_dpad, R.string.whats_new_0_3_1_dpad_detail),
            ReleaseNote(R.drawable.ic_keyboard, R.string.whats_new_0_3_1_typing, R.string.whats_new_0_3_1_typing_detail),
            ReleaseNote(R.drawable.ic_refresh, R.string.whats_new_0_3_1_wifi, R.string.whats_new_0_3_1_wifi_detail),
            ReleaseNote(R.drawable.ic_input, R.string.whats_new_0_3_1_inputs, R.string.whats_new_0_3_1_inputs_detail),
        ),
    )

    private val v0_3_0 = Release(
        version = AppVersion(0, 3, 0, null),
        headline = R.string.whats_new_0_3_0_headline,
        new = listOf(
            ReleaseNote(R.drawable.ic_search, R.string.whats_new_0_3_0_search, R.string.whats_new_0_3_0_search_detail),
            ReleaseNote(R.drawable.ic_lock, R.string.whats_new_0_3_0_lockscreen, R.string.whats_new_0_3_0_lockscreen_detail),
            ReleaseNote(R.drawable.ic_settings, R.string.whats_new_0_3_0_tiles, R.string.whats_new_0_3_0_tiles_detail),
            ReleaseNote(R.drawable.ic_edit, R.string.whats_new_0_3_0_dock, R.string.whats_new_0_3_0_dock_detail),
            ReleaseNote(R.drawable.ic_apps, R.string.whats_new_0_3_0_icons, R.string.whats_new_0_3_0_icons_detail),
            ReleaseNote(R.drawable.ic_download, R.string.whats_new_0_3_0_channel, R.string.whats_new_0_3_0_channel_detail, updaterOnly = true),
        ),
        better = listOf(
            ReleaseNote(R.drawable.ic_tv, R.string.whats_new_0_3_0_link, R.string.whats_new_0_3_0_link_detail),
            ReleaseNote(R.drawable.ic_info, R.string.whats_new_0_3_0_status, R.string.whats_new_0_3_0_status_detail),
            ReleaseNote(R.drawable.ic_notifications, R.string.whats_new_0_3_0_back, R.string.whats_new_0_3_0_back_detail, updaterOnly = true),
            ReleaseNote(R.drawable.ic_fast_forward, R.string.whats_new_0_3_0_jog, R.string.whats_new_0_3_0_jog_detail),
        ),
        fixed = listOf(
            ReleaseNote(R.drawable.ic_dpad, R.string.whats_new_0_3_0_keys, R.string.whats_new_0_3_0_keys_detail),
            ReleaseNote(R.drawable.ic_play_pause, R.string.whats_new_0_3_0_crash, R.string.whats_new_0_3_0_crash_detail),
            ReleaseNote(R.drawable.ic_movie, R.string.whats_new_0_3_0_names, R.string.whats_new_0_3_0_names_detail),
        ),
    )

    /** Newest first. A release joins the top of the list, with its `docs/releases` file. */
    val all: List<Release> = listOf(v0_3_1, v0_3_0)

    val latest: Release get() = all.first()

    /** The newest notes this [installed] build carries: its own, or the last release before it. */
    fun forInstalled(installed: AppVersion?): Release? =
        installed?.let { version -> all.firstOrNull { it.version <= WhatsNewRule.core(version) } }
}

/**
 * When the remote says it was updated (docs/design.md, "What's new"): once per release, after an
 * upgrade, never on a fresh install. Pure, so it is tested without Android.
 */
internal object WhatsNewRule {
    enum class Hint {
        /** Offer the notes now: say so once, on the plain remote. */
        SHOW,

        /** A fresh install: these notes describe nothing it lived through, so mark them read quietly. */
        RECORD,

        /** Nothing to say. */
        NONE,
    }

    /** A test build of 0.3.0 is 0.3.0 for the notes: they describe the release it leads to. */
    fun core(version: AppVersion): AppVersion = version.copy(dev = null)

    /**
     * [seen] is the last notes version marked read (null when this install never stored one: a
     * fresh install, or one from before What's new existed); [established] is an install already
     * set up (a device or the onboarding done), which tells the second from the first.
     */
    fun hint(notes: AppVersion?, seen: AppVersion?, established: Boolean): Hint = when {
        notes == null -> Hint.NONE
        seen == null -> if (established) Hint.SHOW else Hint.RECORD
        core(seen) < notes -> Hint.SHOW
        else -> Hint.NONE
    }
}

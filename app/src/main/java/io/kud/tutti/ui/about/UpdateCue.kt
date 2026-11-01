package io.kud.tutti.ui.about

import io.kud.tutti.protocol.update.AppVersion

/**
 * Everything the rest of the app knows of the updater, in one place (Iris, 2026-10-28): the GitHub
 * updater is meant to be temporary (final releases will likely go to F-Droid), so a build without it
 * needs only these to return nothing:
 * the github and fdroid source sets each implement `rememberUpdateCue`, `UpdateCheckEffect`,
 * `UpdateNowSheet` and `UpdateRow`. [newer] is a newer build, set aside or not; [notice] is
 * true until "Later".
 */
data class UpdateCueState(val newer: AppVersion?, val notice: Boolean)

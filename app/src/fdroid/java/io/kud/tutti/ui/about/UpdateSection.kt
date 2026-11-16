package io.kud.tutti.ui.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.BuildConfig
import io.kud.tutti.R

/*
 * The fdroid build's side of the updater seam (the github build's UpdateSection.kt is the other):
 * F-Droid updates this build, so it carries no updater and no install permission
 * (DistributionGuardsTest, checkDistribution). The same four entry points, each returning nothing (2026-10-28:
 * "a build without it needs only these to return nothing").
 */

@Composable
fun rememberUpdateCue(): UpdateCueState = UpdateCueState(newer = null, notice = false)

@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdateCheckEffect(onRemote: () -> Boolean, editing: () -> Boolean, onInstall: () -> Unit) = Unit

/** Never opened here (no update is ever found); closes at once if it were. */
@Composable
fun UpdateNowSheet(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { onDismiss() }
}

/** About › Version: the installed version, and where updates come from. */
@Composable
internal fun UpdateRow() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            stringResource(R.string.about_version_detail, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.update_from_fdroid),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

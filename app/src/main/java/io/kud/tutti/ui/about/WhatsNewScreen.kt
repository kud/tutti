package io.kud.tutti.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.ui.common.SectionTitle

/**
 * What's new (docs/design.md, "What's new"): the release's lead sentence, then what is new, what got
 * better and what was fixed, one row each, a title and one plain line under it. A still page, as
 * About is: nothing moves, so reduced motion has nothing to take away. The icons only echo the
 * words beside them (decorative to TalkBack, never colour alone); each row reads as one item, and
 * the version, the lead and each group are headings to jump between.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(onBack: () -> Unit, release: Release = installedNotes() ?: ReleaseNotes.latest.forBuild(CARRIES_UPDATER)) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whats_new_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                ReleaseBody(release)
            }
        }
    }
}

@Composable
private fun ReleaseBody(release: Release) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.whats_new_version, release.version.toString()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(release.headline), style = MaterialTheme.typography.headlineSmall)
    }
    Group(R.string.whats_new_new, release.new)
    Group(R.string.whats_new_better, release.better)
    Group(R.string.whats_new_fixed, release.fixed)
}

@Composable
private fun Group(title: Int, notes: List<ReleaseNote>) {
    if (notes.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    SectionTitle(stringResource(title), Modifier.semantics { heading() })
    notes.forEach { NoteRow(it) }
}

@Composable
private fun NoteRow(note: ReleaseNote) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(note.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f).padding(top = 1.dp)) {
            Text(stringResource(note.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(note.detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

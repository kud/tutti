package io.kud.tutti.ui.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.PinnedHeader
import io.kud.tutti.ui.common.TuttiSheet
import io.kud.tutti.ui.common.featureLabel

/**
 * A labelled group of the Son sheet, each with one "?" that explains it (Iris, 2026-10-24, from
 * Erwann: "un moyen d'être explicatif sur chacune des options"). One help per section, never one
 * per tile: the sheet stays calm, and the answer is one tap away.
 */
sealed interface SoundSection {
    data object Input : SoundSection
    data object Effects : SoundSection
    data class Choice(val feature: TheatreFeature) : SoundSection
    data object Levels : SoundSection
}

/** The settings a section explains, in the sheet's order; Voice Zoom 3 only when the layout shows it. */
fun sectionFeatures(section: SoundSection, layout: SoundSheetLayout): List<TheatreFeature> = when (section) {
    SoundSection.Input -> listOf(TheatreFeature.INPUT)
    SoundSection.Effects -> layout.tiles
    is SoundSection.Choice -> listOf(section.feature)
    SoundSection.Levels -> layout.levels
}

/** Every section the sheet shows, top to bottom. */
fun soundSections(layout: SoundSheetLayout): List<SoundSection> =
    listOf(SoundSection.Input, SoundSection.Effects) + layout.choices.map { SoundSection.Choice(it) } + SoundSection.Levels

/**
 * What a setting does, in one or two plain sentences: the section sheet's text and the control's
 * TalkBack hint, so the two never drift. Kept to what Sony documents. Null for a setting the Son
 * sheet doesn't show.
 */
@StringRes
fun explanation(feature: TheatreFeature): Int? = when (feature) {
    TheatreFeature.INPUT -> R.string.explain_input
    TheatreFeature.SOUND_FIELD -> R.string.explain_sound_field
    TheatreFeature.VOICE -> R.string.explain_voice
    TheatreFeature.NIGHT -> R.string.explain_night
    TheatreFeature.AUTO_VOLUME -> R.string.explain_auto_volume
    TheatreFeature.SPATIAL_360 -> R.string.explain_360
    TheatreFeature.VOICE_ZOOM -> R.string.explain_voice_zoom
    TheatreFeature.DRC -> R.string.explain_drc
    TheatreFeature.IMAX -> R.string.explain_imax
    TheatreFeature.BASS -> R.string.explain_bass
    TheatreFeature.REAR_LEVEL -> R.string.explain_rear
    TheatreFeature.VOICE_ZOOM_LEVEL -> R.string.explain_voice_zoom_level
    else -> null
}

/** "Name. What it does." for TalkBack: the name first, the hint after it, as Android reads a hint. */
fun withHint(name: String, hint: String?): String = if (hint.isNullOrBlank()) name else "$name. $hint"

@StringRes
fun sectionTitle(section: SoundSection): Int = when (section) {
    SoundSection.Input -> R.string.bar_input
    SoundSection.Effects -> R.string.sound_section_effects
    is SoundSection.Choice -> featureLabel(section.feature)
    SoundSection.Levels -> R.string.sound_section_levels
}

/** The quiet "?" at the end of a section label: a 48 dp target, the icon in `onSurfaceVariant`. */
@Composable
fun SectionHelp(title: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            painterResource(R.drawable.ic_help),
            contentDescription = stringResource(R.string.explain_open, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A section label with its "?" at the end; [trailing] sits between them (the input's current value). */
@Composable
fun SectionLabel(title: String, onHelp: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        trailing()
        SectionHelp(title, onHelp)
    }
}

/**
 * What a "?" opens: a sheet titled with the section, one short paragraph per setting, each under
 * its name when the section holds more than one. Nothing to do here but read; the scrim or a drag
 * closes it.
 */
@Composable
fun ExplainSheet(section: SoundSection, layout: SoundSheetLayout, onDismiss: () -> Unit) {
    val features = sectionFeatures(section, layout)
    val scroll = rememberScrollState()
    TuttiSheet(onDismissRequest = onDismiss) {
        PinnedHeader(
            scrolled = scroll.canScrollBackward,
            header = {
                Text(
                    stringResource(sectionTitle(section)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
                )
            },
        ) {
            Column(Modifier.verticalScroll(scroll).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 24.dp)) {
                features.forEachIndexed { index, feature ->
                    val text = explanation(feature) ?: return@forEachIndexed
                    if (index > 0) Spacer(Modifier.height(16.dp))
                    if (features.size > 1) {
                        Text(stringResource(featureLabel(feature)), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        stringResource(text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

package io.kud.tutti.ui.remote

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.protocol.bravia.TvSetting
import io.kud.tutti.ui.common.tvSoundOutputIcon
import io.kud.tutti.ui.common.tvSoundOutputLabel
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.apps.AppLibrary
import io.kud.tutti.protocol.atv.KeyGroup
import io.kud.tutti.protocol.atv.NamedKeys
import io.kud.tutti.protocol.bravia.Ircc
import io.kud.tutti.protocol.theatre.BarShortcuts
import io.kud.tutti.protocol.theatre.FeatureKind
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.ui.common.AppIcon
import io.kud.tutti.ui.common.TuttiSheet
import io.kud.tutti.ui.common.barIcon
import io.kud.tutti.ui.common.barValueLabel
import io.kud.tutti.ui.common.featureLabel
import io.kud.tutti.ui.common.glyphRes
import io.kud.tutti.ui.common.keyGroupLabel
import io.kud.tutti.ui.common.keyIcon
import io.kud.tutti.ui.common.keyLabel
import java.text.Normalizer

/** The picker's sections, in the order the list shows them; each is also a filter chip. */
private enum class Category(val label: Int) {
    KEYS(R.string.picker_keys),
    APPS(R.string.picker_apps),
    INPUTS(R.string.picker_inputs),
    BAR(R.string.picker_bar),
    TV(R.string.picker_tv),
    SCENES(R.string.picker_scenes),
}

/** Marks "toggle" among a bar toggle's values: it keeps the plain ToggleBar action. */
private const val TOGGLE = "\u0000toggle"

/**
 * Picks what a pin or a long press does, full height over the remote: search, a chip per
 * section, and only what the TV and the bar announced. What is missing is counted or explained
 * in one line, never shown greyed out (the rule Settings follows).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionPickerSheet(
    title: String,
    state: TuttiUiState,
    allowRemove: Boolean,
    onPick: (DockAction?) -> Unit,
    onLoadTv: () -> Unit,
    onDismiss: () -> Unit,
) {
    TuttiSheet(onDismissRequest = onDismiss) {
        ActionPickerContent(title, state, allowRemove, onPick, onLoadTv, Modifier.fillMaxHeight())
    }
}

@Composable
fun ActionPickerContent(
    title: String,
    state: TuttiUiState,
    allowRemove: Boolean,
    onPick: (DockAction?) -> Unit,
    onLoadTv: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Picture modes and the TV's own keys come from the settings load: ask once IP control answers.
    LaunchedEffect(state.ipControl) {
        if (state.ipControl && !state.tv.settings.loaded && !state.tv.settings.loading) onLoadTv()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<Category?>(null) }
    var allKeys by rememberSaveable { mutableStateOf(false) }
    val held = remember { mutableStateMapOf<Int, Boolean>() }
    val barValues = remember { mutableStateMapOf<TheatreFeature, String>() }
    val needle = fold(query.trim())
    val searching = needle.isNotEmpty()
    fun matches(label: String) = !searching || needle in fold(label)
    fun shows(category: Category) = filter == null || filter == category

    // Labels resolve here, in composition, so search can read them.
    val keyLabels = NamedKeys.all.associate { it.code to keyLabel(it.code) }
    val apps = state.apps.ifEmpty { AppLibrary.all(emptyList()) }
    val visibleApps = apps.filterNot { it.packageName in state.prefs.hiddenApps }
    val hiddenApps = apps.size - visibleApps.size
    val inputs = state.tv.inputs.map { it.label.ifBlank { it.title } to it.uri }
    val barOffer = BarShortcuts.offer(state.bar)
    val barLabels = barOffer.offered.associateWith { stringResource(featureLabel(it)) }
    val barOnline = state.links.bar == Link.ONLINE
    val pictureModes = state.tv.settings.picture.firstOrNull { it.target == "pictureMode" }
        ?.candidates.orEmpty().filter { it.isAvailable }.map { (it.title ?: it.value) to it.value }
    val ircc = state.tv.settings.ircc
    // Sound output: offered when the TV lists setSoundSettings, readable or write-only.
    val soundOutputs = if (state.tv.settings.outputWriteOnly || state.tv.settings.sound.any { it.target == TvSetting.OUTPUT_TERMINAL }) {
        listOf(TvSetting.TERMINAL_AUDIO_SYSTEM, TvSetting.TERMINAL_SPEAKER)
    } else {
        emptyList()
    }
    val soundLabels = soundOutputs.associateWith { stringResource(tvSoundOutputLabel(it)) }
    val sceneRows = state.scenes.map { it.scene }

    val keyRows: List<Pair<KeyGroup?, List<Int>>> = when {
        searching -> listOf(null to NamedKeys.all.map { it.code }.filter { matches(keyLabels.getValue(it)) })
        allKeys || filter == Category.KEYS -> KeyGroup.entries.map { group -> group to NamedKeys.all.filter { it.group == group }.map { it.code } }
        else -> listOf(null to NamedKeys.common)
    }
    val appRows = visibleApps.filter { matches(it.name) }
    val inputRows = inputs.filter { matches(it.first) }
    val barRows = barOffer.offered.filter { matches(barLabels.getValue(it)) }
    val pictureRows = pictureModes.filter { matches(it.first) }
    val irccRows = ircc.filter { matches(Ircc.displayName(it.name)) }
    val soundRows = soundOutputs.filter { matches(soundLabels.getValue(it)) }
    val scenesShown = sceneRows.filter { matches(it.name) }
    val nothing = searching && keyRows.all { it.second.isEmpty() } && appRows.isEmpty() && inputRows.isEmpty() &&
        barRows.isEmpty() && pictureRows.isEmpty() && irccRows.isEmpty() && soundRows.isEmpty() && scenesShown.isEmpty()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (allowRemove) TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.picker_remove)) }
            }
        }
        item(key = "search") {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.picker_search), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        item(key = "chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Category.entries, key = { it.name }) { category ->
                    FilterChip(
                        selected = filter == category,
                        onClick = { filter = if (filter == category) null else category },
                        label = { Text(stringResource(category.label)) },
                    )
                }
            }
        }

        if (nothing) {
            item(key = "none") { Note(stringResource(R.string.picker_no_match, query.trim())) }
        }

        if (shows(Category.KEYS)) {
            for ((group, codes) in keyRows) {
                if (codes.isEmpty()) continue
                header("keys-${group?.name}", R.string.picker_keys, group?.let(::keyGroupLabel))
                items(codes, key = { "key-$it" }) { code ->
                    val long = held[code] == true
                    KeyRow(
                        label = keyLabels.getValue(code),
                        icon = keyIcon(code),
                        long = long,
                        onMode = { held[code] = it },
                        onPick = { onPick(DockAction.Key(code, long)) },
                    )
                }
            }
            if (!searching && filter == null) {
                item(key = "keys-more") {
                    Text(
                        if (allKeys) stringResource(R.string.picker_fewer_keys) else stringResource(R.string.picker_all_keys, NamedKeys.all.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { allKeys = !allKeys }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                }
            }
        }

        if (shows(Category.APPS) && (appRows.isNotEmpty() || !searching)) {
            header("apps", R.string.picker_apps)
            items(appRows, key = { "app-${it.packageName}" }) { app ->
                PickRow(app.name, R.drawable.ic_apps, app = app.packageName, state = state) {
                    onPick(DockAction.LaunchApp(app.packageName, app.name))
                }
            }
            if (hiddenApps > 0 && !searching) item(key = "apps-hidden") { Note(stringResource(R.string.picker_apps_hidden, hiddenApps)) }
        }

        if (shows(Category.INPUTS) && (inputRows.isNotEmpty() || !searching)) {
            header("inputs", R.string.picker_inputs)
            items(inputRows, key = { "input-${it.second}" }) { (label, uri) ->
                PickRow(label, R.drawable.ic_input) { onPick(DockAction.Input(uri, label)) }
            }
            if (inputs.isEmpty() && !state.ipControl && !searching) item(key = "inputs-why") { Note(stringResource(R.string.picker_inputs_no_ip)) }
        }

        if (shows(Category.BAR) && (barRows.isNotEmpty() || !searching)) {
            header("bar", R.string.picker_bar)
            items(barRows, key = { "bar-${it.key}" }) { feature ->
                val values = (if (feature.kind is FeatureKind.Toggle) listOf(TOGGLE) else emptyList()) + BarShortcuts.values(feature)
                val value = barValues[feature] ?: if (feature.kind is FeatureKind.Toggle) TOGGLE else BarShortcuts.initial(feature, state.bar)
                BarRow(
                    label = barLabels.getValue(feature),
                    icon = barIcon(feature.key),
                    value = if (value == TOGGLE) stringResource(R.string.picker_toggle) else barValueLabel(feature, value),
                    onStep = { delta ->
                        val at = values.indexOf(value).coerceAtLeast(0)
                        barValues[feature] = values[(at + delta).mod(values.size)]
                    },
                    onPick = { onPick(if (value == TOGGLE) DockAction.ToggleBar(feature.key) else DockAction.SetBar(feature.key, value)) },
                )
            }
            if (!searching) {
                when {
                    !barOnline -> item(key = "bar-why") { Note(stringResource(R.string.picker_bar_offline)) }
                    barOffer.hidden > 0 -> item(key = "bar-hidden") { Note(stringResource(R.string.picker_bar_hidden, barOffer.hidden)) }
                }
            }
        }

        if (shows(Category.TV) && (pictureRows.isNotEmpty() || irccRows.isNotEmpty() || soundRows.isNotEmpty() || !searching)) {
            header("tv", R.string.picker_tv)
            if (!state.ipControl) {
                if (!searching) item(key = "tv-why") { Note(stringResource(R.string.picker_no_ip)) }
            } else if (!state.tv.settings.loaded) {
                if (!searching) item(key = "tv-loading") { Note(stringResource(R.string.picker_tv_loading)) }
            } else {
                if (pictureRows.isNotEmpty()) {
                    header("tv-picture", R.string.picker_picture_mode, sub = true)
                    items(pictureRows, key = { "picture-${it.second}" }) { (label, mode) ->
                        PickRow(label, R.drawable.ic_tune) { onPick(DockAction.PictureMode(mode, label)) }
                    }
                }
                if (soundRows.isNotEmpty()) {
                    header("tv-sound", R.string.picker_tv_sound, sub = true)
                    items(soundRows, key = { "sound-$it" }) { terminal ->
                        PickRow(soundLabels.getValue(terminal), tvSoundOutputIcon(terminal)) { onPick(DockAction.TvSoundOutput(terminal)) }
                    }
                }
                if (irccRows.isNotEmpty()) {
                    header("tv-keys", R.string.picker_tv_keys, sub = true)
                    items(irccRows, key = { "ircc-${it.name}" }) { code ->
                        PickRow(Ircc.displayName(code.name), R.drawable.ic_tv) { onPick(DockAction.Ircc(code.name, code.value)) }
                    }
                }
                if (pictureModes.isEmpty() && ircc.isEmpty() && soundOutputs.isEmpty() && !searching) item(key = "tv-none") { Note(stringResource(R.string.picker_tv_none)) }
            }
        }

        if (shows(Category.SCENES) && scenesShown.isNotEmpty()) {
            header("scenes", R.string.picker_scenes)
            items(scenesShown, key = { "scene-${it.id}" }) { scene ->
                PickRow(scene.name, glyphRes(scene.glyph)) { onPick(DockAction.RunScene(scene.id)) }
            }
        }
    }
}

/** Case- and accent-blind, so "entree" finds « Entrée ». */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private fun LazyListScope.header(key: String, @StringRes text: Int, @StringRes detail: Int? = null, sub: Boolean = false) {
    item(key = "h-$key") {
        val full = stringResource(text) + (detail?.let { " · " + stringResource(it) } ?: "")
        Text(
            full.uppercase(),
            style = if (sub) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            color = if (sub) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = if (sub) 4.dp else 12.dp, start = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun RowCard(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun RowLabel(@DrawableRes icon: Int, label: String, modifier: Modifier, app: String? = null, state: TuttiUiState? = null) {
    if (app != null && state != null) {
        AppIcon(label, state.appIcons[app], size = 24.dp, corner = 6.dp, packageName = app)
    } else {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
    Spacer(Modifier.width(16.dp))
    Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
private fun PickRow(label: String, @DrawableRes icon: Int, app: String? = null, state: TuttiUiState? = null, onPick: () -> Unit) {
    RowCard(onPick) { RowLabel(icon, label, Modifier.weight(1f).padding(end = 8.dp), app, state) }
}

/** A key: the row picks it, the pills on the right choose a short press or a held one. */
@Composable
private fun KeyRow(label: String, @DrawableRes icon: Int, long: Boolean, onMode: (Boolean) -> Unit, onPick: () -> Unit) {
    RowCard(onPick) {
        RowLabel(icon, label, Modifier.weight(1f).padding(end = 8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Pill(stringResource(R.string.picker_press_short), selected = !long) { onMode(false) }
            Pill(stringResource(R.string.picker_press_long), selected = long) { onMode(true) }
        }
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A bar setting: the row picks it at the value the chevrons show (or "toggle" for a switch). */
@Composable
private fun BarRow(label: String, @DrawableRes icon: Int, value: String, onStep: (Int) -> Unit, onPick: () -> Unit) {
    val previous = stringResource(R.string.picker_value_previous)
    val next = stringResource(R.string.picker_value_next)
    RowCard(onPick) {
        RowLabel(icon, label, Modifier.weight(1f).padding(end = 8.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { stateDescription = value },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chevron(R.drawable.ic_chevron_left, previous) { onStep(-1) }
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.widthIn(min = 72.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Chevron(R.drawable.ic_chevron_right, next) { onStep(1) }
        }
    }
}

@Composable
private fun Chevron(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

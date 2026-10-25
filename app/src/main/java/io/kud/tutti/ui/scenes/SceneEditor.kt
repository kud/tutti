package io.kud.tutti.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.protocol.atv.AppCatalog
import io.kud.tutti.protocol.scenes.Scene
import io.kud.tutti.protocol.scenes.SceneKey
import io.kud.tutti.protocol.scenes.SceneValue
import io.kud.tutti.ui.common.ChoiceRow
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.SceneGlyphs
import io.kud.tutti.ui.common.SectionTitle
import io.kud.tutti.ui.common.TuttiMenu
import io.kud.tutti.ui.common.glyphRes
import io.kud.tutti.ui.common.pinGlyphLabel
import io.kud.tutti.ui.common.rememberHaptics
import io.kud.tutti.ui.remote.contentOn
import io.kud.tutti.ui.remote.signed
import kotlin.math.roundToInt

/** Scene colours, each with the name TalkBack reads (Scene.color stores only the value). */
private val Palette: List<Pair<Long, Int>> = listOf(
    0xFF8E5CF2 to R.string.scene_colour_violet,
    0xFF5C7CF2 to R.string.scene_colour_blue,
    0xFF3B4A6B to R.string.scene_colour_navy,
    0xFFE0609A to R.string.scene_colour_pink,
    0xFF3FAE6A to R.string.scene_colour_green,
    0xFFF2994A to R.string.scene_colour_orange,
    0xFF9AA5B1 to R.string.scene_colour_grey,
    0xFFE05C5C to R.string.scene_colour_red,
)

/**
 * Scene editor. Main path: "Capturer l'état actuel", every captured value becomes a removable
 * chip; the blank form ("Ajouter un réglage") is the fallback. Name, colour, glyph, final app,
 * per-app profile and dock slot.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SceneEditorScreen(
    sceneId: String?,
    state: TuttiUiState,
    controller: TuttiController,
    onDone: () -> Unit,
) {
    val haptics = rememberHaptics()
    val context = LocalContext.current
    val messages = LocalMessages.current
    val original = remember(sceneId) { state.scenes.firstOrNull { it.scene.id == sceneId }?.scene }
    val id = remember(sceneId) { original?.id ?: controller.newSceneId() }
    val savedProfile = remember(id) { state.profiles.firstOrNull { it.sceneId == id }?.appPackage }
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var color by remember { mutableLongStateOf(original?.color ?: Palette.first().first) }
    var glyph by remember { mutableStateOf(original?.glyph ?: "auto_awesome") }
    var settings by remember { mutableStateOf(original?.settings ?: emptyMap()) }
    var launchApp by remember { mutableStateOf(original?.launchApp) }
    var profile by remember { mutableStateOf(savedProfile) }
    var editing by remember { mutableStateOf<SceneKey?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Picture mode candidates come from the TV itself (the starter values are unverified).
    LaunchedEffect(state.links.rest) {
        if (state.links.rest == Link.ONLINE && state.tv.settings.picture.isEmpty()) controller.loadTvSettings()
    }

    fun build() = Scene(id = id, name = name.trim(), color = color, glyph = glyph, settings = settings, launchApp = launchApp)

    fun save() {
        controller.saveScene(build())
        if (profile != savedProfile) {
            savedProfile?.let { controller.setProfile(it, null) }
            profile?.let { controller.setProfile(it, id) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (original == null) R.string.editor_new else R.string.editor_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (original != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.editor_delete))
                        }
                    }
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            haptics.select()
                            save()
                            onDone()
                        },
                    ) { Text(stringResource(R.string.editor_save)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            SectionTitle(stringResource(R.string.editor_look))
            // Colour and symbol are two radio groups: each option is a 48 dp target with a name, and the
            // chosen one says so in shape (a ring and a tick, a filled disc), never by colour alone.
            FlowRow(Modifier.padding(horizontal = 12.dp).selectableGroup()) {
                for ((swatch, nameRes) in Palette) {
                    val selected = swatch == color
                    val colourName = stringResource(nameRes)
                    Box(
                        Modifier
                            .size(48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                haptics.tick()
                                color = swatch
                            }
                            .semantics { contentDescription = colourName },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = contentOn(Color(swatch)), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.padding(horizontal = 12.dp).selectableGroup()) {
                for (option in SceneGlyphs) {
                    val selected = option == glyph
                    val glyphName = stringResource(pinGlyphLabel(option))
                    Box(
                        Modifier
                            .size(48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                haptics.tick()
                                glyph = option
                            }
                            .semantics { contentDescription = glyphName },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color(color) else MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(glyphRes(option)),
                                contentDescription = null,
                                tint = if (selected) contentOn(Color(color)) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.editor_settings))
            FilledTonalButton(
                onClick = {
                    val captured = controller.captureCurrent()
                    if (captured.isEmpty()) {
                        messages?.show(context.getString(R.string.editor_capture_empty))
                    } else {
                        haptics.doublePulse()
                        settings = settings + captured
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(painterResource(R.drawable.ic_scene), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.editor_capture))
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, value) in settings.entries.sortedBy { it.key.ordinal }) {
                    InputChip(
                        selected = false,
                        onClick = { editing = key },
                        label = { Text(settingChip(key, value, state)) },
                        trailingIcon = {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.editor_remove_setting),
                                modifier = Modifier.size(18.dp).clickable {
                                    haptics.tick()
                                    settings = settings - key
                                },
                            )
                        },
                    )
                }
            }
            val missing = SceneKey.entries.filterNot { settings.containsKey(it) }
            if (missing.isNotEmpty()) {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { adding = true }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.editor_add_setting))
                    }
                    TuttiMenu(expanded = adding, onDismissRequest = { adding = false }) {
                        for (key in missing) {
                            DropdownMenuItem(
                                text = { Text(keyName(key)) },
                                onClick = {
                                    adding = false
                                    settings = settings + (key to defaultValue(key, state))
                                    editing = key
                                },
                            )
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.editor_then))
            val apps: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to stringResource(R.string.editor_none)) +
                AppCatalog.apps.map { it.packageName to it.name }
            ChoiceRow(
                label = stringResource(R.string.editor_launch_app),
                valueLabel = launchApp?.let(::appName) ?: stringResource(R.string.editor_none),
                options = apps,
                onSelect = { launchApp = it },
            )
            ChoiceRow(
                label = stringResource(R.string.editor_profile),
                valueLabel = profile?.let { stringResource(R.string.editor_profile_value, appName(it)) } ?: stringResource(R.string.editor_none),
                options = apps,
                onSelect = { profile = it },
            )

            SectionTitle(stringResource(R.string.editor_dock))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (index in 0 until DockConfig.SIZE) {
                    val holds = (state.dock.slots.getOrNull(index)?.primary as? DockAction.RunScene)?.sceneId == id
                    FilledTonalButton(
                        enabled = name.isNotBlank() && !holds,
                        onClick = {
                            haptics.select()
                            save()
                            controller.assignDock(index, DockAction.RunScene(id), state.dock.slots.getOrNull(index)?.secondary)
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text((index + 1).toString()) }
                }
            }
        }
    }

    editing?.let { key ->
        val value = settings[key]
        if (value != null) {
            SettingDialog(
                key = key,
                value = value,
                state = state,
                onChange = { settings = settings + (key to it) },
                onDismiss = { editing = null },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.editor_delete_title, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    controller.deleteScene(id)
                    onDone()
                }) { Text(stringResource(R.string.editor_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun defaultValue(key: SceneKey, state: TuttiUiState): SceneValue = when (key) {
    SceneKey.TV_INPUT -> SceneValue.Text(state.tv.input ?: state.tv.inputs.firstOrNull()?.uri ?: "extInput:hdmi?port=1")
    SceneKey.TV_APP -> SceneValue.Text(state.remote.currentApp ?: AppCatalog.apps.first().packageName)
    SceneKey.PICTURE_MODE -> SceneValue.Text(state.tv.pictureMode ?: "standard")
    SceneKey.SOUND_FIELD, SceneKey.VOICE, SceneKey.NIGHT -> SceneValue.Flag(true)
    SceneKey.BASS -> SceneValue.Level(0)
    SceneKey.VOLUME -> SceneValue.Level(state.volume ?: 20)
    SceneKey.VOLUME_CAP -> SceneValue.Level(20)
}

/** Edits one scene value with the control its type calls for; changes apply live to the draft. */
@Composable
private fun SettingDialog(
    key: SceneKey,
    value: SceneValue,
    state: TuttiUiState,
    onChange: (SceneValue) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(keyName(key)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_done)) } },
        text = {
            when (value) {
                is SceneValue.Flag -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settingChip(key, value, state),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(checked = value.on, onCheckedChange = { onChange(SceneValue.Flag(it)) })
                }
                is SceneValue.Level -> {
                    val range = if (key == SceneKey.BASS) -10..10 else 0..100
                    Column {
                        Text(
                            if (key == SceneKey.BASS) signed(value.value) else value.value.toString(),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Slider(
                            value = value.value.toFloat(),
                            onValueChange = { onChange(SceneValue.Level(it.roundToInt())) },
                            valueRange = range.first.toFloat()..range.last.toFloat(),
                            steps = if (key == SceneKey.BASS) 19 else 0,
                        )
                    }
                }
                is SceneValue.Text -> {
                    val options: List<Pair<String, String>> = when (key) {
                        SceneKey.TV_INPUT -> state.tv.inputs.map { it.uri to it.label.ifBlank { it.title } }
                        SceneKey.TV_APP -> AppCatalog.apps.map { it.packageName to it.name }
                        SceneKey.PICTURE_MODE -> state.tv.settings.picture
                            .firstOrNull { it.target == "pictureMode" }
                            ?.candidates?.map { it.value to (it.title ?: it.value) }
                            .orEmpty()
                        else -> emptyList()
                    }
                    if (options.isEmpty()) {
                        OutlinedTextField(
                            value = value.value,
                            onValueChange = { onChange(SceneValue.Text(it)) },
                            singleLine = true,
                            supportingText = { Text(stringResource(R.string.editor_free_value)) },
                        )
                    } else {
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(options) { (optionValue, label) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { onChange(SceneValue.Text(optionValue)) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = optionValue == value.value, onClick = { onChange(SceneValue.Text(optionValue)) })
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

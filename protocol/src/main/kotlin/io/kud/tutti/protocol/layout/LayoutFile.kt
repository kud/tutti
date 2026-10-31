package io.kud.tutti.protocol.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The remote's layout as a file (Erwann, 2026-10-31: « Juste l'export et import de la disposition »):
 * the dock's twelve places and spare tray, the row keys' long presses, the hand and the pins' dress.
 * Nothing else: no scene, no device, no secret, no identifier of hardware or of the home. The file is
 * a contract of its own, apart from how `:app` stores its prefs, so either can change without the other.
 *
 * Scenes stay out (Iris, 2026-10-31); a pin that runs one names the scene ([FileAction.Scene]) and is
 * matched by name on import, or skipped with a reason.
 */
data class LayoutFile(
    /** "left" or "right"; null leaves the phone's own. */
    val hand: String? = null,
    /** "icon", "both" or "name"; null leaves the phone's own. */
    val dockLabels: String? = null,
    /** Whether the scene strip shows; null leaves the phone's own. */
    val sceneStrip: Boolean? = null,
    /** Exactly [PLACES] places, null where a place is empty. */
    val pins: List<FilePin?> = List(PLACES) { null },
    val spare: List<FilePin> = emptyList(),
    /** Row key ([LONG_PRESS_KEYS]) to what its long press runs. */
    val longPress: Map<String, FileAction> = emptyMap(),
) {
    companion object {
        const val FORMAT = "tutti-layout"
        const val VERSION = 1
        const val PLACES = 12
        const val MAX_SPARE = 24
        val LONG_PRESS_KEYS = listOf("home", "back", "play", "input")
    }
}

data class FilePin(
    val tap: FileAction? = null,
    val hold: FileAction? = null,
    val label: String? = null,
    val icon: String? = null,
    val letters: String? = null,
) {
    val isSet: Boolean get() = tap != null || hold != null
}

/**
 * What a pin runs, in words that hold on any phone and any BRAVIA: apps by package, inputs by
 * `extInput:` URI, bar settings by feature key, the TV's IRCC keys by their code, scenes by name.
 */
sealed interface FileAction {
    data class App(val packageName: String, val label: String) : FileAction
    data class Input(val uri: String, val label: String) : FileAction
    data class ToggleBar(val featureKey: String) : FileAction
    data class SetBar(val featureKey: String, val value: String) : FileAction
    data class Scene(val name: String) : FileAction
    data class Key(val keyCode: Int, val long: Boolean) : FileAction
    data class Ircc(val name: String, val code: String) : FileAction
    data class PictureMode(val mode: String, val label: String) : FileAction
    data class TvSound(val terminal: String) : FileAction
    data class PictureOff(val on: Boolean) : FileAction
    data class Macro(val name: String, val steps: List<FileStep>) : FileAction
    data object OpenApps : FileAction
    data object OpenKeys : FileAction
    /** One of the remote's own keys, by name ([REMOTE_KEYS]). */
    data class Remote(val key: String) : FileAction

    companion object {
        val REMOTE_KEYS = setOf("back", "home", "play", "voice", "keyboard", "info", "captions", "input", "guide", "tv_settings")
    }
}

sealed interface FileStep {
    data class Run(val action: FileAction) : FileStep
    data class Wait(val millis: Long) : FileStep
}

/** Why a part of a file was left out on reading it: said in words by the app. */
sealed interface Skipped {
    /** Place [place] (0-based), or a spare when [place] is null, held something this version can't read. */
    data class Pin(val place: Int?, val label: String?) : Skipped

    /** The long press of row key [key] couldn't be read. */
    data class LongPress(val key: String) : Skipped
}

sealed interface LayoutRead {
    data class Ok(val file: LayoutFile, val skipped: List<Skipped>) : LayoutRead

    /** Not JSON, not ours, or broken past use. Nothing is replaced. */
    data object NotALayout : LayoutRead

    /** Bigger than any layout can be ([LayoutCodec.MAX_BYTES]). */
    data object TooLarge : LayoutRead

    /** Written by a newer tutti; this one can't be sure what it means. */
    data class FromTheFuture(val version: Int) : LayoutRead
}

/**
 * Reads and writes [LayoutFile] as JSON. Reading is strict about shape and lenient about content:
 * an unknown field is ignored, a value that fails its check drops only the pin or long press that
 * held it (reported in [LayoutRead.Ok.skipped]), and a file that isn't one at all is refused whole.
 * Every string is length-capped and charset-checked, because a file is input from anywhere.
 */
object LayoutCodec {
    /** A full dock with long labels is about 6 KiB; 64 KiB leaves room and refuses anything silly. */
    const val MAX_BYTES = 64 * 1024
    private const val MAX_STEPS = 32
    private const val MAX_WAIT_MS = 60_000L

    private val json = Json { prettyPrint = true }
    private val strict = Json

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val INPUT_URI = Regex("extInput:[a-z]+(\\?[A-Za-z0-9=&._-]{0,64})?")
    private val FEATURE_KEY = Regex("[a-z][a-z0-9._]{0,63}")
    private val WORD = Regex("[A-Za-z0-9_.-]{1,64}")
    private val IRCC_CODE = Regex("[A-Za-z0-9+/]{1,64}={0,2}")
    private val ICON = Regex("[a-z0-9_]{1,48}")

    fun encode(file: LayoutFile): String = json.encodeToString(JsonElement.serializer(), toJson(file))

    fun decode(text: String): LayoutRead {
        if (text.length > MAX_BYTES) return LayoutRead.TooLarge
        val root = runCatching { strict.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return LayoutRead.NotALayout
        if (root.str("format") != LayoutFile.FORMAT) return LayoutRead.NotALayout
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return LayoutRead.NotALayout
        if (version > LayoutFile.VERSION) return LayoutRead.FromTheFuture(version)
        if (version < 1) return LayoutRead.NotALayout

        val skipped = mutableListOf<Skipped>()
        val pinsJson = root["pins"] as? JsonArray ?: return LayoutRead.NotALayout
        if (pinsJson.size > LayoutFile.PLACES) return LayoutRead.NotALayout
        val pins = List(LayoutFile.PLACES) { place ->
            val element = pinsJson.getOrNull(place)
            if (element == null || element is JsonNull) null else readPin(element).also { if (it == null) skipped += Skipped.Pin(place, labelOf(element)) }
        }
        val spareJson = when (val s = root["spare"]) {
            null, JsonNull -> JsonArray(emptyList())
            is JsonArray -> s
            else -> return LayoutRead.NotALayout
        }
        if (spareJson.size > LayoutFile.MAX_SPARE) return LayoutRead.NotALayout
        val spare = spareJson.mapNotNull { element -> readPin(element).also { if (it == null) skipped += Skipped.Pin(null, labelOf(element)) } }.filter { it.isSet }
        val longPressJson = when (val l = root["longPress"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject -> l
            else -> return LayoutRead.NotALayout
        }
        val longPress = buildMap {
            for ((key, value) in longPressJson) {
                if (key !in LayoutFile.LONG_PRESS_KEYS) continue
                val action = readAction(value, nested = false)
                if (action == null || !action.fitsLongPress) skipped += Skipped.LongPress(key) else put(key, action)
            }
        }
        return LayoutRead.Ok(
            LayoutFile(
                hand = root.str("hand")?.takeIf { it in setOf("left", "right") },
                dockLabels = root.str("dockLabels")?.takeIf { it in setOf("icon", "both", "name") },
                sceneStrip = (root["sceneStrip"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
                pins = pins.map { it?.takeIf(FilePin::isSet) },
                spare = spare,
                longPress = longPress,
            ),
            skipped,
        )
    }

    /** Screen-only actions (a sheet, a remote key) never go behind a long press, as in the app's pickers. */
    private val FileAction.fitsLongPress: Boolean
        get() = this !is FileAction.OpenApps && this !is FileAction.OpenKeys && this !is FileAction.Remote

    private fun labelOf(element: JsonElement): String? = (element as? JsonObject)?.str("label")?.take(40)

    private fun readPin(element: JsonElement): FilePin? {
        val o = element as? JsonObject ?: return null
        val tap = o["tap"]?.takeIf { it !is JsonNull }?.let { readAction(it, nested = false) ?: return null }
        val hold = o["hold"]?.takeIf { it !is JsonNull }?.let { readAction(it, nested = false) ?: return null }
        if (hold != null && !hold.fitsLongPress) return null
        val label = o.optText("label", 40) ?: return null
        val icon = o.optMatch("icon", ICON) ?: return null
        val letters = o.optText("letters", 2) ?: return null
        return FilePin(tap, hold, label.value, icon.value, letters.value)
    }

    private fun readAction(element: JsonElement, nested: Boolean): FileAction? {
        val o = element as? JsonObject ?: return null
        return when (o.str("type")) {
            "app" -> FileAction.App(o.match("package", PACKAGE, 255) ?: return null, o.text("label", 60) ?: return null)
            "input" -> FileAction.Input(o.match("uri", INPUT_URI, 96) ?: return null, o.text("label", 60) ?: return null)
            "toggle" -> FileAction.ToggleBar(o.match("feature", FEATURE_KEY, 64) ?: return null)
            "bar.set" -> FileAction.SetBar(o.match("feature", FEATURE_KEY, 64) ?: return null, o.match("value", WORD, 64) ?: return null)
            "scene" -> FileAction.Scene(o.text("name", 60)?.takeIf { it.isNotBlank() } ?: return null)
            "key" -> FileAction.Key(o.int("code")?.takeIf { it in 0..1000 } ?: return null, o.bool("long") ?: false)
            "ircc" -> FileAction.Ircc(o.match("name", WORD, 64) ?: return null, o.match("code", IRCC_CODE, 68) ?: return null)
            "picture" -> FileAction.PictureMode(o.match("mode", WORD, 64) ?: return null, o.text("label", 60) ?: return null)
            "tv.sound" -> FileAction.TvSound(o.match("terminal", WORD, 32) ?: return null)
            "tv.pictureOff" -> FileAction.PictureOff(o.bool("on") ?: true)
            "open.apps" -> FileAction.OpenApps
            "open.keys" -> FileAction.OpenKeys
            "remote" -> FileAction.Remote(o.str("key")?.takeIf { it in FileAction.REMOTE_KEYS } ?: return null)
            "macro" -> {
                if (nested) return null
                val name = o.text("name", 60) ?: return null
                val steps = (o["steps"] as? JsonArray)?.takeIf { it.size in 1..MAX_STEPS } ?: return null
                FileAction.Macro(name, steps.map { readStep(it) ?: return null })
            }
            else -> null
        }
    }

    private fun readStep(element: JsonElement): FileStep? {
        val o = element as? JsonObject ?: return null
        return when (o.str("type")) {
            "wait" -> FileStep.Wait((o["millis"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..MAX_WAIT_MS } ?: return null)
            "run" -> {
                val action = readAction(o["action"] ?: return null, nested = true) ?: return null
                if (!action.fitsLongPress) return null
                FileStep.Run(action)
            }
            else -> null
        }
    }

    private fun toJson(file: LayoutFile): JsonObject = buildJsonObject {
        put("format", LayoutFile.FORMAT)
        put("version", LayoutFile.VERSION)
        file.hand?.let { put("hand", it) }
        file.dockLabels?.let { put("dockLabels", it) }
        file.sceneStrip?.let { put("sceneStrip", it) }
        put("pins", buildJsonArray { List(LayoutFile.PLACES) { file.pins.getOrNull(it) }.forEach { add(it?.let(::pinJson) ?: JsonNull) } })
        put("spare", JsonArray(file.spare.filter { it.isSet }.take(LayoutFile.MAX_SPARE).map(::pinJson)))
        put("longPress", JsonObject(file.longPress.filterKeys { it in LayoutFile.LONG_PRESS_KEYS }.mapValues { actionJson(it.value) }))
    }

    private fun pinJson(pin: FilePin): JsonObject = buildJsonObject {
        pin.tap?.let { put("tap", actionJson(it)) }
        pin.hold?.let { put("hold", actionJson(it)) }
        pin.label?.let { put("label", it) }
        pin.icon?.let { put("icon", it) }
        pin.letters?.let { put("letters", it) }
    }

    private fun actionJson(action: FileAction): JsonObject = buildJsonObject {
        when (action) {
            is FileAction.App -> { put("type", "app"); put("package", action.packageName); put("label", action.label) }
            is FileAction.Input -> { put("type", "input"); put("uri", action.uri); put("label", action.label) }
            is FileAction.ToggleBar -> { put("type", "toggle"); put("feature", action.featureKey) }
            is FileAction.SetBar -> { put("type", "bar.set"); put("feature", action.featureKey); put("value", action.value) }
            is FileAction.Scene -> { put("type", "scene"); put("name", action.name) }
            is FileAction.Key -> { put("type", "key"); put("code", action.keyCode); put("long", action.long) }
            is FileAction.Ircc -> { put("type", "ircc"); put("name", action.name); put("code", action.code) }
            is FileAction.PictureMode -> { put("type", "picture"); put("mode", action.mode); put("label", action.label) }
            is FileAction.TvSound -> { put("type", "tv.sound"); put("terminal", action.terminal) }
            is FileAction.PictureOff -> { put("type", "tv.pictureOff"); put("on", action.on) }
            FileAction.OpenApps -> put("type", "open.apps")
            FileAction.OpenKeys -> put("type", "open.keys")
            is FileAction.Remote -> { put("type", "remote"); put("key", action.key) }
            is FileAction.Macro -> {
                put("type", "macro")
                put("name", action.name)
                put("steps", JsonArray(action.steps.map { step ->
                    when (step) {
                        is FileStep.Run -> buildJsonObject { put("type", "run"); put("action", actionJson(step.action)) }
                        is FileStep.Wait -> buildJsonObject { put("type", "wait"); put("millis", step.millis) }
                    }
                }))
            }
        }
    }

    /** An optional field: [Opt] with null when absent, null itself when present but wrong. */
    private class Opt(val value: String?)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** Free text he or the TV wrote: capped, no control characters. */
    private fun JsonObject.text(key: String, max: Int): String? = str(key)?.takeIf { s -> s.length <= max && s.none { it.isISOControl() } }
    private fun JsonObject.match(key: String, pattern: Regex, max: Int): String? = str(key)?.takeIf { it.length <= max && pattern.matches(it) }

    private fun JsonObject.optText(key: String, max: Int): Opt? = optional(key) { text(key, max) }
    private fun JsonObject.optMatch(key: String, pattern: Regex): Opt? = optional(key) { match(key, pattern, 64) }
    private inline fun JsonObject.optional(key: String, read: () -> String?): Opt? {
        val raw = this[key]
        if (raw == null || raw is JsonNull) return Opt(null)
        return read()?.let(::Opt)
    }
}

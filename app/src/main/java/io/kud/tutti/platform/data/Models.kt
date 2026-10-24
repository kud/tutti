package io.kud.tutti.platform.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TvDevice(
    val host: String,
    val name: String = "BRAVIA 8",
    /** Learnt from the TV over IP control, for Wake-on-LAN. Not a secret, but a device identifier: never log it. */
    val mac: String? = null,
)

@Serializable
data class BarDevice(val host: String, val name: String = "Bar 9")

@Serializable
data class Devices(val tv: TvDevice? = null, val bar: BarDevice? = null) {
    val isEmpty: Boolean get() = tv == null && bar == null
}

@Serializable
enum class PadMode { TOUCHPAD, DPAD }

/** Light or dark: follow the phone, or force one. */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How much tutti vibrates. Android's own touch vibration switch and intensity still apply on top.
 * [ESSENTIALS] drops the per-key click and per-step tick, keeping state changes, walls and outcomes.
 */
@Serializable
enum class VibrationLevel { OFF, ESSENTIALS, ALL }

@Serializable
data class AppPrefs(
    /** The single switch before the Feedback group (2026-10-23); read only to migrate, see [vibrationLevel]. */
    val haptics: Boolean = true,
    /** Null until he picks one in the Feedback group; [vibrationLevel] then falls back to [haptics]. */
    val vibration: VibrationLevel? = null,
    /** The platform click on single key presses. Off by default: the TV is the sound in this room. */
    val keySounds: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val trueBlack: Boolean = false,
    /** Apps left out of the Apps sheet and pickers, by package name. */
    val hiddenApps: Set<String> = emptySet(),
    val padMode: PadMode = PadMode.TOUCHPAD,
    val onboarded: Boolean = false,
    /** What the remote screen shows and in what order; the default is the screen as it always was. */
    val layout: RemoteLayout = RemoteLayout(),
    /** Technical details and "Copy details" under errors, and the diagnostics. Off by default, in every build. */
    val developerMode: Boolean = false,
) {
    /** The old switch on reads as [VibrationLevel.ALL], off as [VibrationLevel.OFF]. */
    val vibrationLevel: VibrationLevel
        get() = vibration ?: if (haptics) VibrationLevel.ALL else VibrationLevel.OFF
}

/**
 * The round buttons the row under the pad can hold. [MUTE] stays only so stored rows still decode:
 * mute lives on the volume rail's foot now (Erwann, 2026-10-24), so the row never draws or offers it.
 */
@Serializable
enum class RowButton { BACK, HOME, PLAY, VOICE, KEYBOARD, MUTE, INFO, CAPTIONS, INPUT, GUIDE, TV_SETTINGS }

/** Three steps, not a slider: the pad as it shipped is [NORMAL]. */
@Serializable
enum class PadSize { COMPACT, NORMAL, LARGE }

/**
 * The parts of the remote screen he may arrange. Power, the volume rail, the pad's place and the
 * Son sheet are anchors and have no field here, so no stored layout can move or hide them.
 * Every field has a default, and the defaults draw today's screen exactly, so prefs stored
 * before layouts existed read as the screen they always showed.
 */
@Serializable
data class RemoteLayout(
    val row: List<RowButton> = DEFAULT_ROW,
    val scenes: Boolean = true,
    val padSize: PadSize = PadSize.NORMAL,
) {
    /** The row as drawn: no button twice, never mute (the rail's foot has it), never more than [MAX_ROW]. */
    val shownRow: List<RowButton> get() = row.distinct().filter { it != RowButton.MUTE }.take(MAX_ROW)

    /** What could still go in the row, in the enum's order. */
    val addable: List<RowButton> get() = RowButton.entries.filter { it !in shownRow && it != RowButton.MUTE }

    fun withAdded(button: RowButton): RemoteLayout =
        if (button in shownRow || shownRow.size >= MAX_ROW) this else copy(row = shownRow + button)

    fun withRemoved(button: RowButton): RemoteLayout = copy(row = shownRow - button)

    /** The button at [from] moved to [to], both clamped to the row. */
    fun withMoved(from: Int, to: Int): RemoteLayout = copy(row = shownRow.moved(from, to))

    companion object {
        const val MAX_ROW = 6
        val DEFAULT_ROW = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD)
    }
}

/** This list with the item at [from] taken out and put back at [to]; out-of-range is a no-op. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices) return this
    val target = to.coerceIn(indices)
    if (target == from) return this
    return toMutableList().apply { add(target, removeAt(from)) }
}

/** What a dock slot or a secondary (long-press) button does. */
@Serializable
sealed interface DockAction {
    @Serializable
    @SerialName("app")
    data class LaunchApp(val packageName: String, val label: String) : DockAction

    @Serializable
    @SerialName("input")
    data class Input(val uri: String, val label: String) : DockAction

    /** Toggles one bar setting, by `TheatreFeature.key`. */
    @Serializable
    @SerialName("toggle")
    data class ToggleBar(val featureKey: String) : DockAction

    @Serializable
    @SerialName("scene")
    data class RunScene(val sceneId: String) : DockAction

    /** Sets one bar setting to a wire value, by `TheatreFeature.key` (a level travels as its number). */
    @Serializable
    @SerialName("bar.set")
    data class SetBar(val featureKey: String, val value: String) : DockAction

    /** A remote v2 key; [long] holds it (START_LONG / END_LONG) instead of a short press. */
    @Serializable
    @SerialName("key")
    data class Key(val keyCode: Int, val long: Boolean = false) : DockAction

    /** One of the TV's own IRCC keys, as getRemoteControllerInfo listed it. Needs IP control. */
    @Serializable
    @SerialName("ircc")
    data class Ircc(val name: String, val code: String) : DockAction

    /** The TV's picture mode (videoSettings pictureMode); [label] is the TV's own title for it. Needs IP control. */
    @Serializable
    @SerialName("picture")
    data class PictureMode(val mode: String, val label: String) : DockAction

    /**
     * The TV's sound output (setSoundSettings `outputTerminal`), one of `TvSetting.OUTPUT_TERMINALS`:
     * `audioSystem` sends sound to the bar, `speaker` to the TV's own speakers. Needs IP control.
     */
    @Serializable
    @SerialName("tv.sound")
    data class TvSoundOutput(val terminal: String) : DockAction

    /** Ordered steps with optional waits. Nested macros are flattened out at edit time. */
    @Serializable
    @SerialName("macro")
    data class Macro(val name: String, val steps: List<MacroStep>) : DockAction
}

/** A macro's steps with any nested macro spliced in, so a pin never holds a macro inside a macro. */
fun List<MacroStep>.flattened(): List<MacroStep> = flatMap { step ->
    val action = (step as? MacroStep.Run)?.action
    if (action is DockAction.Macro) action.steps.flattened() else listOf(step)
}

/** What a tap runs, as editable steps: a lone action is one step, a macro its own steps. */
fun DockAction?.asSteps(): List<MacroStep> = when (this) {
    null -> emptyList()
    is DockAction.Macro -> steps.flattened()
    else -> listOf(MacroStep.Run(this))
}

/** The steps back as one action: none, the lone action itself, or a macro named [name]. */
fun List<MacroStep>.asAction(name: String): DockAction? {
    val steps = flattened()
    val runs = steps.filterIsInstance<MacroStep.Run>()
    return when {
        runs.isEmpty() -> null
        steps.size == 1 -> runs.single().action
        else -> DockAction.Macro(name, steps)
    }
}

@Serializable
sealed interface MacroStep {
    @Serializable
    @SerialName("run")
    data class Run(val action: DockAction) : MacroStep

    @Serializable
    @SerialName("wait")
    data class Wait(val millis: Long) : MacroStep
}

/**
 * A pin: what it does, plus the name and icon he gave it. [label] and [icon] are null until he
 * sets them, and then the pin names itself after its action. Every field has a default, so a
 * dock stored before they existed reads unchanged.
 */
@Serializable
data class DockSlot(
    val primary: DockAction? = null,
    val secondary: DockAction? = null,
    val label: String? = null,
    /** A name from `PinGlyphs`; null follows the action. */
    val icon: String? = null,
    /** One or two letters drawn instead of an icon ("Aa · lettres"); wins over [icon] when set. */
    val letters: String? = null,
)

/** Secondary buttons that accept a long-press action. */
@Serializable
enum class SecondaryButton { HOME, BACK, PLAY }

/**
 * The pins live in one flat [slots] list, [PAGE_SIZE] to a page: page n is slots 4n..4n+3. A dock
 * stored before pages existed holds four slots, which is page 1 exactly, so nothing new is stored
 * and nothing old is read differently. A short list is padded out to whole pages when read.
 */
@Serializable
data class DockConfig(
    val slots: List<DockSlot> = DEFAULT_SLOTS,
    val longPress: Map<SecondaryButton, DockAction> = emptyMap(),
) {
    /** Whole pages, at least one and at most [MAX_PAGES]. */
    val pages: List<List<DockSlot>>
        get() {
            val count = ((slots.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceIn(1, MAX_PAGES)
            return List(count) { page -> List(PAGE_SIZE) { slots.getOrNull(page * PAGE_SIZE + it) ?: DockSlot() } }
        }

    fun slot(index: Int): DockSlot = slots.getOrNull(index) ?: DockSlot()

    /** Pin [index] replaced, padding the pages up to it. */
    fun withSlot(index: Int, slot: DockSlot): DockConfig {
        val padded = pages.flatten().toMutableList()
        while (padded.size <= index) padded += DockSlot()
        padded[index] = slot
        return copy(slots = padded.take(MAX_PAGES * PAGE_SIZE))
    }

    /** Pin [from] moved to [to], counted across pages; the pins between shift along. */
    fun withSlotMoved(from: Int, to: Int): DockConfig {
        val all = pages.flatten()
        return copy(slots = all.moved(from, to))
    }

    /** True when page [page] holds no pin at all, so removing it loses nothing. */
    fun pageIsEmpty(page: Int): Boolean = pages.getOrNull(page)?.all { it.primary == null && it.secondary == null } ?: true

    /** One more empty page at the end, while there is room. */
    fun withPageAdded(): DockConfig =
        if (pages.size >= MAX_PAGES) this else copy(slots = pages.flatten() + List(PAGE_SIZE) { DockSlot() })

    /** Page [page] and its pins gone; the last page left is never removed. */
    fun withPageRemoved(page: Int): DockConfig {
        val all = pages
        if (all.size <= 1 || page !in all.indices) return this
        return copy(slots = all.filterIndexed { i, _ -> i != page }.flatten())
    }

    companion object {
        const val PAGE_SIZE = 4
        const val MAX_PAGES = 3

        /** Pins per page; kept for the callers that only know page 1. */
        const val SIZE = PAGE_SIZE

        /**
         * A fresh install pins the two bar toggles the design asks for one tap away; apps live
         * behind the Apps tile, and the other slots wait for his own pins. A dock already
         * stored (any earlier assignment wrote all four slots) is read as it is: nothing in it
         * is dropped or rewritten.
         */
        val DEFAULT_SLOTS = listOf(
            DockSlot(DockAction.ToggleBar("audio.nightmode")),
            DockSlot(DockAction.ToggleBar("audio.voiceenhancer")),
            DockSlot(),
            DockSlot(),
        )
    }
}

internal val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

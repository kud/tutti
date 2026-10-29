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
enum class PadMode {
    TOUCHPAD,
    DPAD,

    /** The phone as an air mouse: only for a Bluetooth target, and only with a gyroscope (Iris, 2026-10-25). */
    GYRO,
}

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
    /**
     * The bar's last reported volume, kept across sessions: when the bar doesn't say its level at
     * connect, a stale one still lets + and − work (Erwann, 2026-10-24: "même s'il est faux").
     */
    val lastBarVolume: Int? = null,
    /** The one-time gesture hints already shown, and the day of the last one. */
    val hints: HintMemory = HintMemory(),
    /** His names for the bar's inputs, by `main.input` value; the segment shows them. */
    val barInputNames: Map<String, String> = emptyMap(),
    /** Bar inputs left out of the Son sheet's row, by value; the current input always shows. */
    val hiddenBarInputs: Set<String> = emptySet(),
    /**
     * The high-volume guard (Erwann, 2026-10-25): a change from the app that would take the bar past
     * [volumeGuardLevel] stops there and asks first; the quick surfaces stop there and say so.
     */
    val volumeGuard: Boolean = true,
    /** On the bar's own 0–100 scale (`main.volumestep`). */
    val volumeGuardLevel: Int = DEFAULT_VOLUME_GUARD,
    /** The volume sheet's saved levels: three slots, null when empty. */
    val volumePresets: List<Int?> = emptyList(),
    /** "Cap for tonight": a ceiling on the bar's volume until a time, set from the volume sheet. */
    val tonightCap: TonightCap? = null,
    /** "Salle obscure": tutti's window dims in the evening while the TV is on (`EveningDim`). Off by default. */
    val eveningDim: Boolean = false,
) {
    /** The old switch on reads as [VibrationLevel.ALL], off as [VibrationLevel.OFF]. */
    val vibrationLevel: VibrationLevel
        get() = vibration ?: if (haptics) VibrationLevel.ALL else VibrationLevel.OFF
}

/**
 * A ceiling on the bar's volume until [until] (wall-clock epoch ms), enforced like a scene's cap;
 * the lower of the two wins. Cleared once the time has passed.
 */
@Serializable
data class TonightCap(val level: Int, val until: Long)

/**
 * 30 on the bar's 0–100 scale (Erwann, 2026-10-25: « 30 ou 40, il faudrait étudier, mais 30 c'est
 * pas mal »): about a third of the travel, already loud for a Bar 9 in a living room at night.
 */
const val DEFAULT_VOLUME_GUARD = 30

/**
 * The remote's own keys, once the round row under the pad; since 2026-10-29 they are dock pins
 * ([DockAction.Remote]) and the row survives only to be joined into the dock once. [MUTE] stays only so stored rows still decode:
 * mute lives on the volume rail's foot now (Erwann, 2026-10-24), so the row never draws or offers it.
 */
@Serializable
enum class RowButton { BACK, HOME, PLAY, VOICE, KEYBOARD, MUTE, INFO, CAPTIONS, INPUT, GUIDE, TV_SETTINGS }

/** Three steps, not a slider: the pad as it shipped is [NORMAL]. */
@Serializable
enum class PadSize { COMPACT, NORMAL, LARGE }

/**
 * The hand that holds the phone: it puts the pad under that thumb and the volume rail on the other
 * side (Erwann, 2026-10-24). [RIGHT] is the default, also for layouts stored before it existed;
 * [LEFT] is the layout as it first shipped, pad on the left.
 */
@Serializable
enum class Hand { LEFT, RIGHT }

/**
 * What a dock pin wears (Erwann, 2026-10-29: « savoir si l'utilisateur veut afficher label ou icône
 * ou les deux »): its glyph and its name ([BOTH], the dock as it shipped and the default, also for
 * layouts stored before this existed), the glyph alone, larger ([ICON]), or the name alone, on up to
 * two lines ([NAME]). TalkBack hears the pin's name in every one ([PinDress]).
 */
@Serializable
enum class DockLabels { ICON, BOTH, NAME }

/**
 * The parts of the remote screen he may arrange. Power, the volume rail and the Son sheet are
 * anchors and have no field here, so no stored layout can move or hide them; [hand] only picks
 * which side the pad and the rail stand on, never a dragged place. Every field has a default, so
 * prefs stored before a field existed still read: the scenes and pad size as they always were,
 * the hand as [Hand.RIGHT], and the row as today's default unless he arranged it ([rowChosen]).
 */
@Serializable
data class RemoteLayout(
    val row: List<RowButton> = DEFAULT_ROW,
    val scenes: Boolean = true,
    val padSize: PadSize = PadSize.NORMAL,
    val hand: Hand = Hand.RIGHT,
    /**
     * True once he has added, removed or moved a row button. Until then the row follows
     * [DEFAULT_ROW] as it stands in this version, so a new default reaches a row he never touched
     * (Input joined it on 2026-10-26). Prefs are stored with every default written out, and
     * layouts stored before this field have none, so for them an untouched row is one that still
     * equals a default that shipped ([SHIPPED_ROWS]); any other row is his and is kept as it is.
     */
    val rowChosen: Boolean = false,
    /** What the dock's pins wear; [DockLabels.BOTH] until he picks, so nothing changes on upgrade. */
    val dockLabels: DockLabels = DockLabels.BOTH,
) {
    /** The row he arranged, or today's default while he hasn't ([rowChosen]). */
    private val effectiveRow: List<RowButton> get() = if (rowChosen || row !in SHIPPED_ROWS) row else DEFAULT_ROW

    /** The row as drawn: no button twice, never mute (the rail's foot has it), never more than [MAX_ROW]. */
    val shownRow: List<RowButton> get() = effectiveRow.distinct().filter { it != RowButton.MUTE }.take(MAX_ROW)

    /** What could still go in the row, in the enum's order. */
    val addable: List<RowButton> get() = RowButton.entries.filter { it !in shownRow && it != RowButton.MUTE }

    fun withAdded(button: RowButton): RemoteLayout =
        if (button in shownRow || shownRow.size >= MAX_ROW) this else copy(row = shownRow + button, rowChosen = true)

    fun withRemoved(button: RowButton): RemoteLayout = copy(row = shownRow - button, rowChosen = true)

    /** The button at [from] moved to [to], both clamped to the row. */
    fun withMoved(from: Int, to: Int): RemoteLayout {
        val moved = shownRow.moved(from, to)
        return if (moved == shownRow) this else copy(row = moved, rowChosen = true)
    }

    /**
     * The row left to right on screen. [row] is kept from the most used outward (Back first), and
     * the right hand reads it mirrored so Back and Home sit under the thumb (Erwann, 2026-10-24).
     */
    val rowOnScreen: List<RowButton> get() = if (hand == Hand.RIGHT) shownRow.asReversed() else shownRow

    /** [withMoved] with positions as drawn ([rowOnScreen]). */
    fun withMovedOnScreen(from: Int, to: Int): RemoteLayout {
        if (hand != Hand.RIGHT) return withMoved(from, to)
        val last = shownRow.size - 1
        return withMoved(last - from, last - to)
    }

    companion object {
        const val MAX_ROW = 6

        /** Input joined on 2026-10-26 (Erwann): a tap opens tutti's input chooser, a long press the TV's own menu. */
        val DEFAULT_ROW = listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD, RowButton.INPUT)

        /**
         * Every default row that shipped, oldest first: with mute (2026-10-23), without it
         * (2026-10-24), with Input (2026-10-26). A stored row equal to one of them, with no
         * [rowChosen] mark, was never arranged and follows today's [DEFAULT_ROW]. Append here
         * whenever [DEFAULT_ROW] changes; never remove an entry.
         */
        internal val SHIPPED_ROWS = listOf(
            listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD, RowButton.MUTE),
            listOf(RowButton.BACK, RowButton.HOME, RowButton.PLAY, RowButton.VOICE, RowButton.KEYBOARD),
            DEFAULT_ROW,
        )
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

    /** Screen off with the sound on ([on]), or the picture back: power saving `pictureOff` / `off`. Needs IP control. */
    @Serializable
    @SerialName("tv.pictureOff")
    data class PictureOff(val on: Boolean = true) : DockAction

    /** Ordered steps with optional waits. Nested macros are flattened out at edit time. */
    @Serializable
    @SerialName("macro")
    data class Macro(val name: String, val steps: List<MacroStep>) : DockAction

    /**
     * Opens the Apps sheet. Apps and Keys were fixed doors at the dock's left until 2026-10-28; now
     * they are pins like any other, which he may move or drop (Iris). The remote screen runs them,
     * never the controller, so they are offered only for a dock pin, never in a scene, a macro step
     * or a row button's long press.
     */
    @Serializable
    @SerialName("open.apps")
    data object OpenApps : DockAction

    /** Opens the Keys sheet, the rest of the remote; see [OpenApps]. */
    @Serializable
    @SerialName("open.keys")
    data object OpenKeys : DockAction

    /**
     * One of the remote's own keys, the round row under the pad until 2026-10-29 (Erwann: « un
     * dock de 2 lignes au final et basta »). It behaves exactly as the row's key did, its long
     * press included: [DockConfig.longPress] for Back, Home, Play/Pause and Input (or their
     * defaults), the paste on Keyboard, hold to talk on the mic. Screen-only, like [OpenApps]:
     * offered for a pin's tap, never in a scene, a macro step or a long press.
     */
    @Serializable
    @SerialName("remote")
    data class Remote(val button: RowButton) : DockAction
}

/** True for the two pins that open a sheet of the remote rather than send anything. */
val DockAction?.opensSheet: Boolean get() = this == DockAction.OpenApps || this == DockAction.OpenKeys

/** A tap that is the whole pin: no steps, no wait, no "Try" (the remote's sheets and its own keys). */
val DockAction?.wholePin: Boolean get() = opensSheet || this is DockAction.Remote

/** A slot that does something, on tap or on long press. */
val DockSlot.isSet: Boolean get() = primary != null || secondary != null

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

/** Secondary buttons that accept a long-press action. [INPUT] joined on 2026-10-26; stored maps without it read unchanged. */
@Serializable
enum class SecondaryButton { HOME, BACK, PLAY, INPUT }

/**
 * The dock: one grid of two rows of [ROW], twelve places, no pages (Erwann, 2026-10-29: « un dock
 * de 2 lignes au final et basta »). The remote's own keys (the round row until then) are pins like
 * the rest ([DockAction.Remote]), as are Apps and Keys ([DockAction.OpenApps], [DockAction.OpenKeys]).
 *
 * Stored under the key `pins`. Two upgrades run once, when read ([normalised]):
 * - a dock from before 2026-10-28 has only `slots` ([legacySlots]), four to a page behind two
 *   fixed doors: it becomes Apps, Keys, then every pin in its old order, empty slots closed up;
 * - a dock from before 2026-10-29 has no `one` mark ([merged]): the row he saw on screen joins it
 *   ([joinRow]). Its keys take the first row in the order they stood, his pins follow from the
 *   second row in order; whatever passes twelve goes to [spare], never thrown away, and the editor
 *   offers to put it back or let it go. A dock never stored is the default row plus [DEFAULT_SLOTS].
 */
@Serializable
data class DockConfig(
    @SerialName("pins")
    val slots: List<DockSlot> = DEFAULT_SLOTS,
    val longPress: Map<SecondaryButton, DockAction> = emptyMap(),
    /** The pre-2026-10-28 dock, read only to migrate it; null once [normalised]. */
    @SerialName("slots")
    val legacySlots: List<DockSlot>? = null,
    /** True once the round row has joined the dock; false on every dock stored before 2026-10-29. */
    @SerialName("one")
    val merged: Boolean = false,
    /** Pins that didn't fit the twelve when the row joined, in order: kept until he puts them back or lets them go. */
    val spare: List<DockSlot> = emptyList(),
) {
    /** The dock in today's model, the row of [layout] joined in if it hasn't been; anything else as it is. */
    fun normalised(layout: RemoteLayout = RemoteLayout()): DockConfig {
        val current = legacySlots?.let { copy(slots = fromLegacy(it), legacySlots = null) } ?: this
        if (current.merged) return current
        val laid = joinRow(layout.rowOnScreen, current.slots)
        return current.copy(
            slots = laid.take(PAGE_SIZE),
            spare = current.spare + laid.drop(PAGE_SIZE).filter { it.isSet },
            merged = true,
        )
    }

    /** The one page of twelve. A list of pages so "Pin to dock" and the pickers read as they did. */
    val pages: List<List<DockSlot>>
        get() = listOf(List(PAGE_SIZE) { slots.getOrNull(it) ?: DockSlot() })

    fun slot(index: Int): DockSlot = slots.getOrNull(index) ?: DockSlot()

    /** True when some pin runs [action], on tap or on long press. */
    fun holds(action: DockAction): Boolean = slots.any { it.primary == action || it.secondary == action }

    /** True when every place holds a pin. */
    val full: Boolean get() = pages.single().all { it.isSet }

    /** Pin [index] replaced. */
    fun withSlot(index: Int, slot: DockSlot): DockConfig {
        if (index !in 0 until PAGE_SIZE) return this
        val all = pages.single().toMutableList()
        all[index] = slot
        return copy(slots = all)
    }

    /** Pin [from] moved to [to]; the pins between shift along. */
    fun withSlotMoved(from: Int, to: Int): DockConfig = copy(slots = pages.single().moved(from, to))

    /** Spare [index] back in the first free place; nothing changes while the dock is full. */
    fun withSpareRestored(index: Int): DockConfig {
        val pin = spare.getOrNull(index) ?: return this
        val all = pages.single().toMutableList()
        val free = all.indexOfFirst { !it.isSet }
        if (free < 0) return this
        all[free] = pin
        return copy(slots = all, spare = spare.filterIndexed { i, _ -> i != index })
    }

    /** Spare [index] let go, on his word. */
    fun withSpareDropped(index: Int): DockConfig = copy(spare = spare.filterIndexed { i, _ -> i != index })

    companion object {
        /** Pins per row: six round keys filled the row edge to edge, so a tile keeps the width his thumb knows. */
        const val ROW = 6
        const val ROWS = 2
        const val PAGE_SIZE = ROW * ROWS

        /** A fresh dock's pins; the default row joins in front of them ([normalised]). */
        val DEFAULT_SLOTS = listOf(
            DockSlot(DockAction.OpenApps),
            DockSlot(DockAction.OpenKeys),
            DockSlot(DockAction.ToggleBar("audio.nightmode")),
            DockSlot(DockAction.ToggleBar("audio.voiceenhancer")),
        )

        /** A pre-2026-10-28 dock (four to a page, behind the doors) as a list of pins; see [DockConfig]. */
        internal fun fromLegacy(legacy: List<DockSlot>): List<DockSlot> =
            listOf(DockSlot(DockAction.OpenApps), DockSlot(DockAction.OpenKeys)) + legacy.filter { it.isSet }

        /**
         * The row as it stood on screen ([row], at most six) followed by his pins, empty places
         * closed up. While his pins fit the second row they start there, so the keys keep a row
         * of their own; past six they follow the keys directly, so as few as possible go spare.
         */
        internal fun joinRow(row: List<RowButton>, slots: List<DockSlot>): List<DockSlot> {
            val keys = row.take(ROW).map { DockSlot(DockAction.Remote(it)) }
            val pins = slots.filter { it.isSet }
            return if (pins.size <= ROW) keys + List(ROW - keys.size) { DockSlot() } + pins else keys + pins
        }
    }
}

internal val StoreJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * What the one-time hints remember (discoverability rule 3): every hint already shown, and the
 * day the last one showed. Shown counts as spent: a hint shows once, ever, so closing it is
 * permanent by construction.
 */
@Serializable
data class HintMemory(
    val shown: Set<String> = emptySet(),
    /** Days since the epoch, local time; null before the first hint. */
    val lastDay: Long? = null,
)

package io.kud.tutti.platform.data

import io.kud.tutti.protocol.layout.FileAction
import io.kud.tutti.protocol.layout.FilePin
import io.kud.tutti.protocol.layout.FileStep
import io.kud.tutti.protocol.layout.LayoutFile
import io.kud.tutti.protocol.layout.Skipped
import io.kud.tutti.protocol.scenes.Scene

/**
 * Between the stored layout ([RemoteLayout], [DockConfig]) and the file ([LayoutFile], in `:protocol`),
 * both ways (Erwann, 2026-10-31: « Juste l'export et import de la disposition »). What goes in: the
 * dock's twelve places and spare tray, the row keys' long presses, the hand, the pins' dress and the
 * scene strip's switch. Scenes stay on the phone (Iris, 2026-10-31): a pin that runs one names it, and
 * the import finds the scene of that name here or skips the pin, saying so. Only the layout and the
 * dock reach this code, so a device, a host or a secret has no way into the file.
 */
object LayoutTransfer {
    /** The file's cap on a pin's name; longer names are cut, never refused. */
    private const val MAX_LABEL = 40

    /** The file, and how many pins couldn't go in it (an input that isn't an `extInput:`, a scene gone). */
    data class Export(val file: LayoutFile, val leftOut: Int)

    fun export(layout: RemoteLayout, dock: DockConfig, scenes: List<Scene>): Export {
        val names = scenes.associate { it.id to it.name }
        var leftOut = 0
        fun pin(slot: DockSlot): FilePin? {
            if (!slot.isSet) return null
            val tap = slot.primary?.let { toFile(it, names) ?: return null.also { leftOut++ } }
            // A remote key's long press is the row's own ([DockConfig.longPress]), never the pin's (Iris).
            val hold = slot.secondary?.takeIf { tap !is FileAction.Remote }?.let { toFile(it, names) ?: return null.also { leftOut++ } }
            return FilePin(tap, hold, slot.label?.take(MAX_LABEL), slot.icon, slot.letters).takeIf { it.isSet }
        }
        val pins = dock.pages.single().map(::pin)
        val spare = dock.spare.mapNotNull(::pin)
        val longPress = dock.longPress.mapNotNull { (button, action) ->
            toFile(action, names)?.let { button.name.lowercase() to it } ?: null.also { leftOut++ }
        }.toMap()
        return Export(
            LayoutFile(
                hand = layout.hand.name.lowercase(),
                dockLabels = layout.dockLabels.name.lowercase(),
                sceneStrip = layout.scenes,
                pins = pins,
                spare = spare,
                longPress = longPress,
            ),
            leftOut,
        )
    }

    /** Why a part of the file didn't come in, said in words by the import dialog. */
    sealed interface Skip {
        /** Pin [label] runs scene [scene], and no scene here has that name. */
        data class SceneMissing(val label: String) : Skip

        /** Place [place] (1-based), or a set-aside pin when null, holds something this version can't read. */
        data class Unreadable(val place: Int?, val label: String?) : Skip

        data class LongPress(val button: SecondaryButton) : Skip
    }

    /** What the import would write, and what it left out. Nothing is written until he says Replace. */
    data class Import(val layout: RemoteLayout, val dock: DockConfig, val skipped: List<Skip>) {
        val pins: Int get() = dock.slots.count { it.isSet }
    }

    fun import(file: LayoutFile, unread: List<Skipped>, current: RemoteLayout, scenes: List<Scene>): Import {
        val ids = scenes.associate { it.name.trim() to it.id }
        val skipped = unread.map {
            when (it) {
                is Skipped.Pin -> Skip.Unreadable(it.place?.plus(1), it.label)
                is Skipped.LongPress -> Skip.LongPress(SecondaryButton.valueOf(it.key.uppercase()))
            }
        }.toMutableList()

        fun slot(pin: FilePin?): DockSlot? {
            if (pin == null) return null
            val missing = pin.scenes().firstOrNull { it.trim() !in ids }
            if (missing != null) {
                skipped += Skip.SceneMissing(pin.label ?: missing)
                return null
            }
            val tap = pin.tap?.let { fromFile(it, ids) }
            val hold = pin.hold?.takeIf { tap !is DockAction.Remote }?.let { fromFile(it, ids) }
            return DockSlot(tap, hold, pin.label, pin.icon, pin.letters).takeIf { it.isSet }
        }

        val places = List(DockConfig.PAGE_SIZE) { slot(file.pins.getOrNull(it)) ?: DockSlot() }
        val spare = file.spare.mapNotNull(::slot)
        val longPress = buildMap {
            for ((key, action) in file.longPress) {
                val button = SecondaryButton.valueOf(key.uppercase())
                val missing = action.scenes().any { it.trim() !in ids }
                if (missing) skipped += Skip.LongPress(button) else fromFile(action, ids)?.let { put(button, it) }
            }
        }
        val layout = current.copy(
            hand = file.hand?.let { Hand.valueOf(it.uppercase()) } ?: current.hand,
            dockLabels = file.dockLabels?.let { DockLabels.valueOf(it.uppercase()) } ?: current.dockLabels,
            scenes = file.sceneStrip ?: current.scenes,
        )
        return Import(layout, DockConfig(slots = places, longPress = longPress, merged = true, spare = spare), skipped)
    }

    private fun FilePin.scenes(): List<String> = listOfNotNull(tap, hold).flatMap { it.scenes() }

    private fun FileAction.scenes(): List<String> = when (this) {
        is FileAction.Scene -> listOf(name)
        is FileAction.Macro -> steps.filterIsInstance<FileStep.Run>().flatMap { it.action.scenes() }
        else -> emptyList()
    }

    private fun toFile(action: DockAction, scenes: Map<String, String>): FileAction? = when (action) {
        is DockAction.LaunchApp -> FileAction.App(action.packageName, action.label)
        // Only the TV's own inputs travel; any other URI (a channel) is this TV's and stays here.
        is DockAction.Input -> if (action.uri.startsWith("extInput:")) FileAction.Input(action.uri, action.label) else null
        is DockAction.ToggleBar -> FileAction.ToggleBar(action.featureKey)
        is DockAction.SetBar -> FileAction.SetBar(action.featureKey, action.value)
        is DockAction.RunScene -> scenes[action.sceneId]?.let { FileAction.Scene(it) }
        is DockAction.Key -> FileAction.Key(action.keyCode, action.long)
        is DockAction.Ircc -> FileAction.Ircc(action.name, action.code)
        is DockAction.PictureMode -> FileAction.PictureMode(action.mode, action.label)
        is DockAction.TvSoundOutput -> FileAction.TvSound(action.terminal)
        is DockAction.PictureOff -> FileAction.PictureOff(action.on)
        is DockAction.Macro -> {
            val steps = action.steps.flattened().map { step ->
                when (step) {
                    is MacroStep.Wait -> FileStep.Wait(step.millis)
                    is MacroStep.Run -> FileStep.Run(toFile(step.action, scenes) ?: return null)
                }
            }
            FileAction.Macro(action.name, steps)
        }
        DockAction.OpenApps -> FileAction.OpenApps
        DockAction.OpenKeys -> FileAction.OpenKeys
        is DockAction.Remote -> if (action.button == RowButton.MUTE) null else FileAction.Remote(action.button.name.lowercase())
    }

    private fun fromFile(action: FileAction, scenes: Map<String, String>): DockAction? = when (action) {
        is FileAction.App -> DockAction.LaunchApp(action.packageName, action.label)
        is FileAction.Input -> DockAction.Input(action.uri, action.label)
        is FileAction.ToggleBar -> DockAction.ToggleBar(action.featureKey)
        is FileAction.SetBar -> DockAction.SetBar(action.featureKey, action.value)
        is FileAction.Scene -> scenes[action.name.trim()]?.let { DockAction.RunScene(it) }
        is FileAction.Key -> DockAction.Key(action.keyCode, action.long)
        is FileAction.Ircc -> DockAction.Ircc(action.name, action.code)
        is FileAction.PictureMode -> DockAction.PictureMode(action.mode, action.label)
        is FileAction.TvSound -> DockAction.TvSoundOutput(action.terminal)
        is FileAction.PictureOff -> DockAction.PictureOff(action.on)
        is FileAction.Macro -> DockAction.Macro(
            action.name,
            action.steps.map { step ->
                when (step) {
                    is FileStep.Wait -> MacroStep.Wait(step.millis)
                    is FileStep.Run -> MacroStep.Run(fromFile(step.action, scenes) ?: return null)
                }
            },
        )
        FileAction.OpenApps -> DockAction.OpenApps
        FileAction.OpenKeys -> DockAction.OpenKeys
        is FileAction.Remote -> RowButton.entries.firstOrNull { it.name.lowercase() == action.key && it != RowButton.MUTE }?.let { DockAction.Remote(it) }
    }
}

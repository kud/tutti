package io.kud.tutti.platform.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TvDevice(val host: String, val name: String = "BRAVIA 8")

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

@Serializable
data class AppPrefs(
    val haptics: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val trueBlack: Boolean = false,
    /** Apps left out of the Apps sheet and pickers, by package name. */
    val hiddenApps: Set<String> = emptySet(),
    val padMode: PadMode = PadMode.TOUCHPAD,
    val onboarded: Boolean = false,
)

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

    /** Ordered steps with optional waits. Nested macros are flattened out at edit time. */
    @Serializable
    @SerialName("macro")
    data class Macro(val name: String, val steps: List<MacroStep>) : DockAction
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
)

/** Secondary buttons that accept a long-press action. */
@Serializable
enum class SecondaryButton { HOME, BACK, PLAY }

@Serializable
data class DockConfig(
    val slots: List<DockSlot> = DEFAULT_SLOTS,
    val longPress: Map<SecondaryButton, DockAction> = emptyMap(),
) {
    companion object {
        const val SIZE = 4

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

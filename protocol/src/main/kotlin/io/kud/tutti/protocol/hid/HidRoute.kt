package io.kud.tutti.protocol.hid

import io.kud.tutti.protocol.atv.V2Key

/**
 * What the remote is pointed at over Bluetooth (Erwann, 2026-10-25: the TV, or the Mac when it is
 * shown on the TV; no other host). Each target remembers its own Bluetooth address and keyboard
 * layout; [layouts] is the choice offered for it, the first being the default.
 */
enum class HidTarget(val layouts: List<KeyLayout>) {
    TV(listOf(KeyLayout.US, KeyLayout.FR_AZERTY)),
    MAC(listOf(KeyLayout.MAC_FR, KeyLayout.MAC_US)),
    ;

    val defaultLayout: KeyLayout get() = layouts.first()

    /** [stored] if it belongs to this target, else the default: a TV layout never types to the Mac. */
    fun layoutOr(stored: KeyLayout?): KeyLayout = stored?.takeIf { it in layouts } ?: defaultLayout
}

/** One remote control as Bluetooth sends it: a keyboard key, or a consumer-page usage. */
sealed interface HidAction {
    data class Key(val stroke: KeyStroke) : HidAction

    data class Consumer(val usage: ConsumerUsage) : HidAction
}

/** Which link a control goes over. One meaning each, so the remote can say which one in words. */
enum class RemoteLink { LAN, BLUETOOTH, NONE }

/**
 * The remote is one interface whatever the link (Erwann, 2026-10-25: « la même interface,
 * qu'importe si c'est Bluetooth ou Google TV »). This maps its keys (Remote v2 key codes) to what
 * Bluetooth HID can send, and picks the link a control uses.
 */
object HidRoute {
    /**
     * The TV prefers the LAN (Remote v2 reaches keys HID can't: power, inputs, settings) and falls
     * back to Bluetooth when it is down. The Mac has no LAN link: Bluetooth or nothing.
     */
    fun link(target: HidTarget, lanUp: Boolean, hidConnected: Boolean): RemoteLink = when {
        target == HidTarget.TV && lanUp -> RemoteLink.LAN
        hidConnected -> RemoteLink.BLUETOOTH
        else -> RemoteLink.NONE
    }

    /**
     * The target that follows the TV's input (Erwann, 2026-10-25: tutti is used as much on macOS
     * shown on the TV as on Google TV): the Mac while the TV shows the input marked as the Mac's,
     * the TV otherwise. Null [macInput] (nothing marked) always gives the TV.
     */
    fun targetFor(currentInput: String?, macInput: String?): HidTarget =
        if (macInput != null && currentInput == macInput) HidTarget.MAC else HidTarget.TV

    /** Whether Bluetooth must be registered while the remote is in front: never in the background. */
    fun needsHid(target: HidTarget, lanUp: Boolean): Boolean = target == HidTarget.MAC || !lanUp

    /**
     * The pad's gyro pointer mode is offered only where it can work (Erwann, 2026-10-25: « seulement
     * quand c'est possible »): a cursor needs the Bluetooth link up, and the phone needs a gyroscope.
     */
    fun gyroPossible(link: RemoteLink, hasGyroscope: Boolean): Boolean = link == RemoteLink.BLUETOOTH && hasGyroscope

    /**
     * The HID equivalent of a Remote v2 key for [target], or null when Bluetooth has none (power,
     * inputs, colour keys, settings: the remote shows those as unavailable rather than guessing).
     * Volume is the bar's and the TV's, never the Mac's: the Mac's sound goes out over HDMI.
     */
    fun action(v2Key: Int, target: HidTarget): HidAction? {
        common(v2Key)?.let { return it }
        return when (target) {
            HidTarget.TV -> when (v2Key) {
                V2Key.BACK -> HidAction.Consumer(ConsumerUsage.BACK)
                V2Key.HOME -> HidAction.Consumer(ConsumerUsage.HOME)
                V2Key.VOLUME_UP -> HidAction.Consumer(ConsumerUsage.VOLUME_UP)
                V2Key.VOLUME_DOWN -> HidAction.Consumer(ConsumerUsage.VOLUME_DOWN)
                V2Key.VOLUME_MUTE -> HidAction.Consumer(ConsumerUsage.MUTE)
                else -> null
            }
            // macOS ignores AC Back and AC Home; Esc is what "back out" means there.
            HidTarget.MAC -> when (v2Key) {
                V2Key.BACK -> key(KeyUsage.ESCAPE)
                else -> null
            }
        }
    }

    private fun common(v2Key: Int): HidAction? = when (v2Key) {
        V2Key.DPAD_UP -> key(KeyUsage.UP)
        V2Key.DPAD_DOWN -> key(KeyUsage.DOWN)
        V2Key.DPAD_LEFT -> key(KeyUsage.LEFT)
        V2Key.DPAD_RIGHT -> key(KeyUsage.RIGHT)
        V2Key.DPAD_CENTER, V2Key.ENTER, V2Key.NUMPAD_ENTER -> key(KeyUsage.ENTER)
        V2Key.ESCAPE -> key(KeyUsage.ESCAPE)
        V2Key.DEL -> key(KeyUsage.BACKSPACE)
        V2Key.FORWARD_DEL -> key(KeyUsage.DELETE)
        V2Key.TAB -> key(KeyUsage.TAB)
        V2Key.SPACE -> key(KeyUsage.SPACE)
        in V2Key.NUM_1..V2Key.NUM_9 -> key(0x1E + (v2Key - V2Key.NUM_1))
        V2Key.NUM_0 -> key(0x27)
        V2Key.MEDIA_PLAY_PAUSE, V2Key.MEDIA_PLAY, V2Key.MEDIA_PAUSE -> HidAction.Consumer(ConsumerUsage.PLAY_PAUSE)
        V2Key.MEDIA_NEXT -> HidAction.Consumer(ConsumerUsage.NEXT)
        V2Key.MEDIA_PREVIOUS -> HidAction.Consumer(ConsumerUsage.PREVIOUS)
        else -> null
    }

    private fun key(usage: Int) = HidAction.Key(KeyStroke(usage))
}

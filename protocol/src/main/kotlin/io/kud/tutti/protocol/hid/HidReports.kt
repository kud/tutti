package io.kud.tutti.protocol.hid

/** Report payloads for [HidDescriptor], without the report id. Pure and total. */
object HidReports {
    /** Usage 0x01: more than six keys held at once. */
    private const val ERROR_ROLL_OVER = 0x01

    /** Keyboard: [modifiers] is a [Modifier] bit set; up to six [keys] usages, more is roll-over. */
    fun keyboard(modifiers: Int = 0, keys: List<Int> = emptyList()): ByteArray {
        val out = ByteArray(HidDescriptor.KEYBOARD_SIZE)
        out[0] = (modifiers and 0xFF).toByte()
        val held = keys.filter { it in 1..0x65 }
        if (held.size > 6) {
            for (i in 2 until 8) out[i] = ERROR_ROLL_OVER.toByte()
        } else {
            held.forEachIndexed { i, usage -> out[2 + i] = usage.toByte() }
        }
        return out
    }

    /** Everything up: sent after every keystroke, so a repeated letter reads as two presses. */
    fun keyboardReleased(): ByteArray = keyboard()

    /** Mouse: [buttons] a [MouseButton] bit set; [dx], [dy], [wheel] clamped to int8. */
    fun mouse(buttons: Int = 0, dx: Int = 0, dy: Int = 0, wheel: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(),
        clampByte(dx),
        clampByte(dy),
        clampByte(wheel),
    )

    /** Consumer control: one usage held ([ConsumerUsage]), 0 to release. Little-endian. */
    fun consumer(usage: Int): ByteArray = byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0x03).toByte())

    internal fun clampByte(value: Int): Byte = value.coerceIn(-127, 127).toByte()
}

/** Modifier bits of the keyboard report's first byte. */
object Modifier {
    const val LEFT_CTRL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
    const val RIGHT_CTRL = 0x10
    const val RIGHT_SHIFT = 0x20

    /** AltGr on European layouts. */
    const val RIGHT_ALT = 0x40
    const val RIGHT_GUI = 0x80
}

object MouseButton {
    const val PRIMARY = 0x01
    const val SECONDARY = 0x02
    const val MIDDLE = 0x04
}

/**
 * Consumer page usages Android maps to keys (`frameworks/base/data/keyboards/Generic.kl`, the
 * `usage 0x0c` lines): Home and Back are AC Home / AC Back.
 */
enum class ConsumerUsage(val usage: Int) {
    PLAY_PAUSE(0xCD),
    NEXT(0xB5),
    PREVIOUS(0xB6),
    MUTE(0xE2),
    VOLUME_UP(0xE9),
    VOLUME_DOWN(0xEA),
    HOME(0x223),
    BACK(0x224),
}

/** Keyboard page usages for the keys tutti sends by name. Letters and digits come from [KeyLayout]. */
object KeyUsage {
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val DELETE = 0x4C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
}

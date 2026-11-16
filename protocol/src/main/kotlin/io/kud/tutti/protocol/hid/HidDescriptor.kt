package io.kud.tutti.protocol.hid

/**
 * The HID report descriptor tutti registers with Android's `BluetoothHidDevice` (Bluetooth mode:
 * the phone as a keyboard and mouse paired straight to the TV, no LAN).
 *
 * One composite device, three reports. The report id is not part of the payload: Android's
 * `sendReport(device, id, data)` prefixes it.
 *
 * | id | report   | payload                                                        |
 * | -- | -------- | -------------------------------------------------------------- |
 * | 1  | keyboard | 8 bytes: modifiers, reserved, six key usages (boot layout)     |
 * | 2  | mouse    | 4 bytes: buttons (3 bits), x, y, wheel (each int8, relative)   |
 * | 3  | consumer | 2 bytes: one consumer usage, little-endian (0 = released)      |
 */
object HidDescriptor {
    const val KEYBOARD_ID = 1
    const val MOUSE_ID = 2
    const val CONSUMER_ID = 3

    const val KEYBOARD_SIZE = 8
    const val MOUSE_SIZE = 4
    const val CONSUMER_SIZE = 2

    /** SDP strings and the subclass Android advertises. Combo keyboard + pointing device. */
    const val NAME = "tutti"
    const val DESCRIPTION = "Keyboard and mouse"
    const val PROVIDER = "tutti"
    const val SUBCLASS_COMBO: Byte = 0xC0.toByte()

    private val words = intArrayOf(
        // Keyboard
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1, 0x01, // Collection (Application)
        0x85, KEYBOARD_ID, //   Report ID
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0, //   Usage Minimum (Left Control)
        0x29, 0xE7, //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x08, //   Report Count (8)
        0x81, 0x02, //   Input (Data, Var, Abs): modifiers
        0x95, 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81, 0x01, //   Input (Const): reserved
        0x95, 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x65, //   Logical Maximum (101)
        0x05, 0x07, //   Usage Page (Keyboard/Keypad)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0x65, //   Usage Maximum (101)
        0x81, 0x00, //   Input (Data, Array): keys
        0xC0, // End Collection

        // Mouse
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x02, // Usage (Mouse)
        0xA1, 0x01, // Collection (Application)
        0x85, MOUSE_ID, //   Report ID
        0x09, 0x01, //   Usage (Pointer)
        0xA1, 0x00, //   Collection (Physical)
        0x05, 0x09, //     Usage Page (Button)
        0x19, 0x01, //     Usage Minimum (1)
        0x29, 0x03, //     Usage Maximum (3)
        0x15, 0x00, //     Logical Minimum (0)
        0x25, 0x01, //     Logical Maximum (1)
        0x95, 0x03, //     Report Count (3)
        0x75, 0x01, //     Report Size (1)
        0x81, 0x02, //     Input (Data, Var, Abs): buttons
        0x95, 0x01, //     Report Count (1)
        0x75, 0x05, //     Report Size (5)
        0x81, 0x01, //     Input (Const): padding
        0x05, 0x01, //     Usage Page (Generic Desktop)
        0x09, 0x30, //     Usage (X)
        0x09, 0x31, //     Usage (Y)
        0x09, 0x38, //     Usage (Wheel)
        0x15, 0x81, //     Logical Minimum (-127)
        0x25, 0x7F, //     Logical Maximum (127)
        0x75, 0x08, //     Report Size (8)
        0x95, 0x03, //     Report Count (3)
        0x81, 0x06, //     Input (Data, Var, Rel)
        0xC0, //   End Collection
        0xC0, // End Collection

        // Consumer control (media, volume, Home, Back)
        0x05, 0x0C, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xA1, 0x01, // Collection (Application)
        0x85, CONSUMER_ID, //   Report ID
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF, 0x03, //   Logical Maximum (1023)
        0x19, 0x00, //   Usage Minimum (0)
        0x2A, 0xFF, 0x03, //   Usage Maximum (1023)
        0x75, 0x10, //   Report Size (16)
        0x95, 0x01, //   Report Count (1)
        0x81, 0x00, //   Input (Data, Array)
        0xC0, // End Collection
    )

    /** A fresh copy each call: callers may hand it to the platform, which keeps the array. */
    fun bytes(): ByteArray = ByteArray(words.size) { words[it].toByte() }
}

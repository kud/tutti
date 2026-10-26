package io.kud.tutti.protocol.atv

/**
 * What kind of field the TV opened, read from [ImeProbe.int5] *as if* it were Android's
 * `EditorInfo.inputType` (class in the low four bits, variation in the next eight). That reading
 * is inferred, not documented: the diagnostic reports it beside the raw number so a report from
 * the XR80 can confirm or refute it (Maya's top 10, item 10, groundwork). Masking a password
 * field waits on that proof. Never derived from, nor shown with, the field's value.
 */
object ImeFieldKind {
    private const val CLASS_MASK = 0x0000000F
    private const val VARIATION_MASK = 0x00000FF0

    /** "text/password", "number/plain", "unknown class 7"…: a label for the report, never for the UI. */
    fun of(int5: Int): String {
        val variation = int5 and VARIATION_MASK
        return when (int5 and CLASS_MASK) {
            0 -> if (int5 == 0) "none" else "unknown class 0"
            1 -> "text/" + when (variation) {
                0x00 -> "plain"
                0x10 -> "uri"
                0x20 -> "email"
                0x30 -> "email subject"
                0x40 -> "short message"
                0x50 -> "long message"
                0x60 -> "person name"
                0x70 -> "postal address"
                0x80 -> "password"
                0x90 -> "visible password"
                0xA0 -> "web edit"
                0xB0 -> "filter"
                0xC0 -> "phonetic"
                0xD0 -> "web email"
                0xE0 -> "web password"
                else -> "variation 0x%x".format(variation)
            }
            2 -> "number/" + when (variation) {
                0x00 -> "plain"
                0x10 -> "password"
                else -> "variation 0x%x".format(variation)
            }
            3 -> "phone"
            4 -> "datetime"
            else -> "unknown class ${int5 and CLASS_MASK}"
        }
    }

    /** Whether the inferred kind is a password field: what masking would key on, once the reading is proven. */
    fun isPassword(int5: Int): Boolean = of(int5).let { it.endsWith("password") && !it.startsWith("text/visible") }
}

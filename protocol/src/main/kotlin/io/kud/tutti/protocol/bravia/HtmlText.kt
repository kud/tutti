package io.kud.tutti.protocol.bravia

/**
 * The TV escapes some of its strings for HTML even inside JSON: an app listed as
 * "Live TV &amp; Catch Up" by `getApplicationList`. [decode] turns the common named entities and
 * every numeric one back into text, once (so `&amp;amp;` reads `&amp;`, as the TV meant). Anything
 * it does not know, or a number that is no character, stays exactly as written.
 */
object HtmlText {
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,6});")

    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
    )

    fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { match ->
            val body = match.groupValues[1]
            val code = when {
                body.startsWith("#x") || body.startsWith("#X") -> body.drop(2).toIntOrNull(16)
                body.startsWith("#") -> body.drop(1).toIntOrNull()
                else -> return@replace NAMED[body.lowercase()] ?: match.value
            }
            if (code == null || code == 0 || code > Character.MAX_CODE_POINT || code in 0xD800..0xDFFF) {
                match.value
            } else {
                String(Character.toChars(code))
            }
        }
    }
}

package io.kud.tutti.protocol.bravia

/**
 * What is behind one of the TV's inputs, as far as tutti can tell, so the Input sheet can give
 * each row its glyph (Erwann, 2026-11-12, on dev.13: « Bizarre sans icône »). Decorative only:
 * the row's name says what it is, so a wrong guess costs a picture, never a meaning.
 */
enum class InputKind { COMPUTER, SOUNDBAR, GAME, PORT }

/**
 * The kind of [this] input, first match wins:
 * 1. a computer set up in tutti as shown on this input ([computerInputs], Sony uris);
 * 2. the TV's own `icon` for it (`getCurrentExternalInputsStatus` v1.1: `meta:pc`, `meta:game`,
 *    `meta:audiosystem`, `meta:avamp`, `meta:hometheater`);
 * 3. the words of its label (the plugged-in device's CEC name, or a name set on the TV), then its
 *    title: a computer ("PC", "macOS", "Windows", "Linux"…), a console ("PS5", "Xbox", "Nintendo
 *    Switch"…), a soundbar ("HT-A9000", "Soundbar", "Theatre Bar"…);
 * 4. the eARC/ARC port with something on it, which is where the bar sits: Sony's bars ask for it;
 * 5. else a plain port.
 */
fun ExternalInput.kind(computerInputs: Set<String> = emptySet()): InputKind {
    if (uri in computerInputs) return InputKind.COMPUTER
    when (icon?.trim()?.lowercase()?.removePrefix("meta:")) {
        "pc" -> return InputKind.COMPUTER
        "game" -> return InputKind.GAME
        "audiosystem", "avamp", "hometheater" -> return InputKind.SOUNDBAR
    }
    for (words in listOf(label, title)) wordsKind(words)?.let { return it }
    if (connected && ARC in tokens(title)) return InputKind.SOUNDBAR
    return InputKind.PORT
}

private const val ARC = "arc"

private val COMPUTER_WORDS = setOf(
    "pc", "computer", "mac", "macos", "macbook", "imac", "windows", "linux", "ubuntu", "laptop", "desktop", "chromebook",
)
private val GAME_WORDS = setOf("ps3", "ps4", "ps5", "playstation", "xbox", "nintendo", "nintendoswitch", "console", "game", "steamdeck")
private val SOUNDBAR_WORDS = setOf("soundbar", "theatre", "theater", "hometheater", "hometheatre", "avr", "receiver")

/** Sony's soundbars and home theatres name themselves by model over CEC: HT-A9000, HT-S2000, HT-A7000… */
private val SONY_HT = Regex("""\bht-?[a-z]\d""", RegexOption.IGNORE_CASE)

private fun tokens(text: String): List<String> = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

private fun wordsKind(text: String): InputKind? {
    if (text.isBlank()) return null
    val t = tokens(text)
    val joined = t.joinToString(" ")
    return when {
        t.any { it in COMPUTER_WORDS } -> InputKind.COMPUTER
        t.any { it in GAME_WORDS } || "playstation" in joined || "xbox" in joined -> InputKind.GAME
        t.any { it in SOUNDBAR_WORDS } || "sound bar" in joined || SONY_HT.containsMatchIn(text) -> InputKind.SOUNDBAR
        else -> null
    }
}

package io.kud.tutti.ui.onboarding

/**
 * Whether [key] could be a BRAVIA pre-shared key: 8 to 32 printable ASCII characters, as the TV's
 * IP control screen accepts. A local stand-in until the protocol layer owns the rule (PskRules).
 */
fun pskLooksValid(key: String): Boolean =
    key.length in 8..32 && key.all { it in ' '..'~' }

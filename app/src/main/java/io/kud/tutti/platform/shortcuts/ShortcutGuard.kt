package io.kud.tutti.platform.shortcuts

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Who may run a scene through [MainActivity][io.kud.tutti.MainActivity]. That activity is exported
 * (it is the launcher's), so any installed app can send it [ACTION_SCENE]; a scene switches the TV
 * off or sets the volume, so without a check every app on the phone is a remote of the living room.
 *
 * The launcher shortcuts tutti publishes carry a per-install random token in [EXTRA_TOKEN]; an
 * [ACTION_SCENE] intent runs only when it carries that exact token. The token is a secret: it is
 * kept encrypted in the secure store beside the PSK, excluded from backup, and never logged or
 * echoed (a rejected intent says nothing about why). Pure, so the rule is unit-tested.
 */
object ShortcutGuard {
    /** 32 random bytes, URL-safe Base64 without padding (43 characters). */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Whether [presented] is this install's token. False when either side is missing or blank: an
     * install whose token could not be read runs nothing from outside. Constant-time comparison.
     */
    fun accepts(expected: String?, presented: String?): Boolean {
        if (expected.isNullOrBlank() || presented.isNullOrBlank()) return false
        return MessageDigest.isEqual(expected.encodeToByteArray(), presented.encodeToByteArray())
    }

    /**
     * What a launch intent may do: the scene id to apply for an [ACTION_SCENE] carrying the right
     * token and a non-blank id, else null. Other actions are not this rule's concern.
     */
    fun sceneToRun(action: String?, sceneId: String?, presented: String?, expected: String?): String? {
        if (action != ACTION_SCENE) return null
        if (sceneId.isNullOrBlank()) return null
        return sceneId.takeIf { accepts(expected, presented) }
    }

    private const val TOKEN_BYTES = 32
}

const val EXTRA_TOKEN = "io.kud.tutti.extra.TOKEN"

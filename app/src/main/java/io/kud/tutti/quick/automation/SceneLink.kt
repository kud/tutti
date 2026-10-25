package io.kud.tutti.quick.automation

/**
 * `tutti://scene/<id>`: what an NFC tag, Tasker or MacroDroid sends to apply a scene without
 * opening the app. Only a well-formed scene id gets through; anything else is ignored.
 */
object SceneLink {
    const val SCHEME = "tutti"
    const val HOST = "scene"

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun of(sceneId: String): String = "$SCHEME://$HOST/$sceneId"

    /** The scene id in [scheme]://[host]/[path], else null. */
    fun parse(scheme: String?, host: String?, path: String?): String? {
        if (!scheme.equals(SCHEME, ignoreCase = true) || !host.equals(HOST, ignoreCase = true)) return null
        val id = path?.removePrefix("/")?.removeSuffix("/") ?: return null
        return id.takeIf { ID.matches(it) }
    }
}

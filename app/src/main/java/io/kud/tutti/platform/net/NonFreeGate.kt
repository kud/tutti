package io.kud.tutti.platform.net

import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.protocol.privacy.NonFreeFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The network edge's copy of `AppPrefs.allows` (Erwann, 2026-11-12: "Only free services"): every
 * client that reaches a third-party service ([CastArtworkLoader], [CinemetaClient]) asks it before
 * any request, so a screen or a notification that forgets the setting still sends nothing. Empty
 * until the stored settings are read: nothing is allowed before then.
 */
internal object NonFreeGate {
    private val state = MutableStateFlow<Set<NonFreeFeature>>(emptySet())

    val allowed: StateFlow<Set<NonFreeFeature>> = state.asStateFlow()

    fun update(prefs: AppPrefs) {
        state.value = NonFreeFeature.entries.filterTo(mutableSetOf()) { prefs.allows(it) }
    }

    fun allows(feature: NonFreeFeature): Boolean = feature in state.value
}

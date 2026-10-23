package io.kud.tutti.platform.net.sim

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal const val HOST = "127.0.0.1"

/** The simulator listens on loopback, which the app's LAN check rightly refuses; this lets only it through. */
internal fun loopbackOnly(host: String) {
    require(host == HOST) { "simulator tests talk to $HOST only" }
}

/** The first value matching [predicate], or a failure naming what was awaited. */
internal suspend fun <T> Flow<T>.await(what: String, millis: Long = 3_000, predicate: (T) -> Boolean): T =
    try {
        withTimeout(millis) { first(predicate) }
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("timed out after $millis ms waiting for $what")
    }

internal inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }

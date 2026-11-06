package io.kud.tutti.protocol.net

/**
 * How long a session loop waits before its next connect. A link that drops after a healthy
 * session is retried at once: the drop is news, and the old fixed 1 s pause was a third of what
 * a blip cost. A session that ends sooner than [HEALTHY_MS], or an attempt that never got up,
 * waits its backoff, so a TV that accepts and drops at once never turns this into a tight loop.
 */
object ReconnectPace {
    const val HEALTHY_MS = 5_000L

    /** [sessionMs]: how long the link was up before it dropped; null when the attempt failed. */
    fun pause(sessionMs: Long?, backoffMs: Long): Long =
        if (sessionMs != null && sessionMs >= HEALTHY_MS) 0L else backoffMs
}

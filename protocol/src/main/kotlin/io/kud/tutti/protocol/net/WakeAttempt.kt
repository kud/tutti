package io.kud.tutti.protocol.net

/**
 * What one Power-from-standby attempt saw: whether each device came back, whether tutti knew
 * the TV's MAC and how many magic packets went out, and how the remote's last connect failed.
 * Pure, so the cause shown to the user and the diagnostic line are tested here.
 */
data class WakeAttempt(
    val tv: Reach,
    val bar: Reach,
    val macKnown: Boolean,
    val packetsSent: Int,
    val timeoutMs: Long,
    /** `ErrorClass` of the remote's last failed connect (`timeout`, `connection_refused`, …), if any. */
    val tvLastConnect: String? = null,
) {
    enum class Reach(val word: String) {
        ANSWERED("answered"),
        SILENT("did not answer"),
        NOT_CONFIGURED("not configured"),
    }

    enum class Cause {
        /** No MAC learnt, so no magic packet: only the TV's own Remote start could have woken it. */
        TV_NO_MAC,

        /** Packets went out and nothing answered at the address at all (timeout, no route). */
        TV_NOT_FOUND,

        /** Packets went out and the host answered something, or how it failed is unknown. */
        TV_ASLEEP,
        BAR_SILENT,
    }

    val failed: Boolean get() = tv == Reach.SILENT || bar == Reach.SILENT

    /** Why it failed, TV first since the bar usually follows it over HDMI-CEC; null on success. */
    val cause: Cause?
        get() = when {
            tv == Reach.SILENT && !macKnown -> Cause.TV_NO_MAC
            tv == Reach.SILENT && tvLastConnect in HOST_ABSENT -> Cause.TV_NOT_FOUND
            tv == Reach.SILENT -> Cause.TV_ASLEEP
            bar == Reach.SILENT -> Cause.BAR_SILENT
            else -> null
        }

    /** The diagnostic's outcome line. Says whether the MAC was known, never the MAC itself. */
    fun outcome(): String = buildString {
        append("tv ${tv.word}, bar ${bar.word} within $timeoutMs ms")
        if (tv != Reach.NOT_CONFIGURED) {
            append("; wake-on-lan: mac ${if (macKnown) "known" else "not learnt (needs ip control with psk)"}, packets sent $packetsSent")
            tvLastConnect?.let { append("; remote last connect: $it (${meaning(it)})") }
        }
    }

    private fun meaning(errorClass: String): String = when (errorClass) {
        in HOST_ABSENT -> "nothing answered at the address"
        "connection_refused" -> "host up, port closed"
        else -> "other"
    }

    private companion object {
        val HOST_ABSENT = setOf("timeout", "no_route")
    }
}

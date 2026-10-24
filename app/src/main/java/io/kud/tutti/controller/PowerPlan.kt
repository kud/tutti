package io.kud.tutti.controller

/** Which device a Power command is for: the short press is [BOTH]; the long press offers all three. */
enum class PowerTarget { TV, BAR, BOTH }

/**
 * What one Power command does to each device (Erwann, 2026-10-24: « Appui long sur Power »).
 * A device in reach gets the command at once ([sendTv], [sendBar]); turning on one out of reach
 * wakes it instead ([wakeTv], [wakeBar]); turning off one out of reach does nothing, since it is
 * asleep already. A device the target leaves out is never touched, so a wake that fails reports
 * only the device it tried. [overridePowered] is the combined "powered" the remote shows at once,
 * or null to leave it to the reports: switching one device off says nothing about the other.
 */
data class PowerPlan(
    val sendTv: Boolean,
    val sendBar: Boolean,
    val wakeTv: Boolean,
    val wakeBar: Boolean,
    val overridePowered: Boolean?,
) {
    val waking: Boolean get() = wakeTv || wakeBar
    val nothing: Boolean get() = !sendTv && !sendBar && !waking
}

fun powerPlan(
    target: PowerTarget,
    on: Boolean,
    tvConfigured: Boolean,
    tvReachable: Boolean,
    barConfigured: Boolean,
    barReachable: Boolean,
): PowerPlan {
    val tv = tvConfigured && target != PowerTarget.BAR
    val bar = barConfigured && target != PowerTarget.TV
    val sendTv = tv && tvReachable
    val sendBar = bar && barReachable
    val sent = sendTv || sendBar
    return PowerPlan(
        sendTv = sendTv,
        sendBar = sendBar,
        wakeTv = on && tv && !tvReachable,
        wakeBar = on && bar && !barReachable,
        overridePowered = when {
            !sent -> null
            target == PowerTarget.BOTH -> on
            // One device on means the room is on; one device off leaves the other's report to say.
            on -> true
            else -> null
        },
    )
}

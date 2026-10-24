package io.kud.tutti.ui.remote

import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState

/**
 * What a pin whose action toggles a bar setting says about that setting. Never "off" by default:
 * a value the bar has not reported is [Unknown], and a bar that is not there is [Unreachable] or
 * [NoBar], even if an old value is still remembered (the reducer keeps `confirmed` across a drop).
 */
sealed interface PinToggle {
    /** On or off, [pending] while the bar has not yet confirmed what was just asked (optimistic). */
    data class Known(val on: Boolean, val pending: Boolean) : PinToggle

    /** The bar is connected but has not reported this setting, or reported a value that is neither on nor off. */
    data object Unknown : PinToggle

    /** A bar is added but not connected right now. */
    data object Unreachable : PinToggle

    /** No bar added at all. */
    data object NoBar : PinToggle
}

/** The toggle state [action] stands for, or null when it is not a bar toggle (a pin with no state). */
fun pinToggle(action: DockAction?, bar: TheatreState, barAdded: Boolean): PinToggle? {
    val feature = (action as? DockAction.ToggleBar)?.featureKey?.let(TheatreFeature::fromKey) ?: return null
    return when {
        !barAdded -> PinToggle.NoBar
        !bar.connected -> PinToggle.Unreachable
        else -> bar.isOn(feature)?.let { PinToggle.Known(it, bar.isPending(feature)) } ?: PinToggle.Unknown
    }
}

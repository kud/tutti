package io.kud.tutti.protocol.theatre

/**
 * The pairing sequence bravia-quad-homeassistant sends over this port (its "Bluetooth pairing"
 * button, `button.py`): switch the input to Bluetooth, then `bluetooth.mode` `Off`, a 500 ms
 * pause, then `RX`. Observed on the Theatre Quad; unverified on the Bar 9. The values are sent
 * exactly as observed, capitals included.
 */
object BluetoothPairing {
    data class Step(val feature: TheatreFeature, val value: String, val pauseAfterMillis: Long = 0)

    const val MODE_OFF = "Off"
    const val MODE_RECEIVE = "RX"
    const val PAUSE_MILLIS = 500L

    fun steps(currentInput: String?): List<Step> = buildList {
        if (currentInput != "bluetooth") add(Step(TheatreFeature.INPUT, "bluetooth"))
        add(Step(TheatreFeature.BLUETOOTH_MODE, MODE_OFF, pauseAfterMillis = PAUSE_MILLIS))
        add(Step(TheatreFeature.BLUETOOTH_MODE, MODE_RECEIVE))
    }
}

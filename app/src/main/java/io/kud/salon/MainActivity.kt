package io.kud.salon

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.kud.salon.controller.SalonController
import io.kud.salon.platform.discovery.TvDiscovery
import io.kud.salon.ui.SalonApp
import io.kud.salon.ui.common.LocalHapticsEnabled
import io.kud.salon.ui.common.LocalReducedMotion
import io.kud.salon.ui.common.rememberReducedMotion
import io.kud.salon.ui.theme.SalonTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: SalonController

    @Inject lateinit var discovery: TvDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val reducedMotion = rememberReducedMotion()
            SalonTheme(trueBlack = state.prefs.trueBlack) {
                CompositionLocalProvider(
                    LocalHapticsEnabled provides state.prefs.haptics,
                    LocalReducedMotion provides reducedMotion,
                ) {
                    SalonApp(controller, discovery, state)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.retain()
    }

    override fun onStop() {
        controller.release()
        super.onStop()
    }

    /**
     * While the remote is in front: the phone's volume buttons drive the bar, and a hardware
     * keyboard drives the TV (arrows → D-pad, Enter → OK, Esc → Back, text → IME).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controller.remoteInFront) {
            val code = event.keyCode
            val volumeKey = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
            if (volumeKey && controller.state.value.devices.bar != null) {
                if (event.action == KeyEvent.ACTION_DOWN) controller.volumeStep(if (code == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
                return true
            }
            if (!volumeKey && event.action == KeyEvent.ACTION_DOWN && isHardwareKeyboard(event)) {
                if (controller.hardwareKey(code, controller.typing)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isHardwareKeyboard(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        return !device.isVirtual && (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
    }
}

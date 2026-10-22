package io.kud.tutti

import android.annotation.SuppressLint
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
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.platform.diagnostic.Diagnostics
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.ui.TuttiApp
import io.kud.tutti.ui.common.LocalHapticsEnabled
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.rememberReducedMotion
import io.kud.tutti.ui.theme.TuttiTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: TuttiController

    @Inject lateinit var discovery: TvDiscovery

    @Inject lateinit var diagnostics: Diagnostics

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val reducedMotion = rememberReducedMotion()
            TuttiTheme(trueBlack = state.prefs.trueBlack) {
                CompositionLocalProvider(
                    LocalHapticsEnabled provides state.prefs.haptics,
                    LocalReducedMotion provides reducedMotion,
                ) {
                    TuttiApp(controller, discovery, diagnostics, state)
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
     *
     * RestrictedApi is a false positive here: this overrides the public
     * android.app.Activity.dispatchKeyEvent, which androidx.core's ComponentActivity re-declares
     * under @RestrictTo. Nothing restricted is called. onKeyDown would not do: it only sees keys
     * that Compose's focus system left unhandled, and arrows/Enter must reach the TV first.
     */
    @SuppressLint("RestrictedApi")
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

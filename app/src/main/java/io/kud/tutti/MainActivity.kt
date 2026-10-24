package io.kud.tutti

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.kud.tutti.ui.common.ChipNudge
import io.kud.tutti.ui.common.FeedbackPrefs
import io.kud.tutti.ui.common.LocalChipNudge
import io.kud.tutti.ui.common.LocalFeedbackPrefs
import io.kud.tutti.ui.common.rememberTuttiIndication
import android.annotation.SuppressLint
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.platform.data.ThemeMode
import io.kud.tutti.platform.diagnostic.CrashLog
import io.kud.tutti.platform.diagnostic.Diagnostics
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.ui.TuttiApp
import io.kud.tutti.ui.common.CrashDialog
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.LocalReducedMotion
import io.kud.tutti.ui.common.MessageHost
import io.kud.tutti.ui.common.Messages
import io.kud.tutti.ui.common.rememberReducedMotion
import io.kud.tutti.ui.intro.IntroAnchors
import io.kud.tutti.ui.intro.LaunchIntro
import io.kud.tutti.ui.intro.LaunchIntroOverlay
import io.kud.tutti.ui.intro.LocalIntroAnchors
import io.kud.tutti.ui.theme.TuttiTheme
import io.kud.tutti.ui.theme.WindowBackground
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var controller: TuttiController

    @Inject lateinit var discovery: TvDiscovery

    @Inject lateinit var diagnostics: Diagnostics

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Before Compose's first frame: the background the theme last drew, so a dark choice on a
        // light phone (or the reverse) never flashes the other one.
        WindowBackground.restore(this)
        // The system splash is only a still bridge; the intro takes over at the same place and
        // plays on the app's own frame clock over the already-drawn remote: in full on a cold
        // start, short on a warm one, not at all for a recreated activity.
        val intro = LaunchIntro(LaunchIntro.kindFor(restored = savedInstanceState != null))
        splash.setOnExitAnimationListener(intro::takeOver)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val reducedMotion = rememberReducedMotion()
            val theme = state.prefs.theme
            val dark = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            TuttiTheme(darkTheme = dark, trueBlack = state.prefs.trueBlack) {
                WindowBackground.Follow(this@MainActivity)
                CompositionLocalProvider(
                    LocalMessages provides remember { Messages() },
                    LocalFeedbackPrefs provides FeedbackPrefs(state.prefs.vibrationLevel, state.prefs.keySounds),
                    // Rows and every plain clickable: Material ripple plus the inset focus ring.
                    LocalIndication provides rememberTuttiIndication(RoundedCornerShape(12.dp), ripple = true, ringInset = true),
                    LocalChipNudge provides remember { ChipNudge() },
                    LocalReducedMotion provides reducedMotion,
                    LocalIntroAnchors provides remember { IntroAnchors() },
                ) {
                    TuttiApp(controller, discovery, diagnostics, state)
                    LaunchIntroOverlay(
                        intro,
                        firstLaunch = if (state.loaded) state.devices.isEmpty && !state.prefs.onboarded else null,
                    )
                    var crash by remember { mutableStateOf(CrashLog.read(this@MainActivity)) }
                    crash?.let { report ->
                        CrashDialog(report) {
                            CrashLog.clear(this@MainActivity)
                            crash = null
                        }
                    }
                    // Last, so a message draws above the remote, its intro and every screen.
                    MessageHost()
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

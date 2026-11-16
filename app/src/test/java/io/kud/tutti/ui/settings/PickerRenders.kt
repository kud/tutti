package io.kud.tutti.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.Links
import io.kud.tutti.controller.TargetController
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.TvShown
import io.kud.tutti.controller.TvState
import io.kud.tutti.di.preferencesStore
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.data.AppPrefs
import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.DockAction
import io.kud.tutti.platform.data.DockConfig
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.platform.data.VibrationLevel
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.platform.net.LocalNetworkPermission
import io.kud.tutti.platform.security.ClientIdentityStore
import io.kud.tutti.platform.security.SecretCipher
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.atv.RemoteState
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.cast.CastConnector
import io.kud.tutti.protocol.grpc.UnavailableSonyCloudPlane
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnector
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState
import io.kud.tutti.ui.theme.TuttiTheme
import java.io.File
import java.io.IOException
import java.time.Duration
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings' pickers (Display & feedback, Soundbar) in both locales and themes, to judge the
 * segmented rows and the choice sheet. Invented fixtures. Skipped unless `TUTTI_RENDERS` names an
 * output folder:
 *
 *     TUTTI_RENDERS=<dir> ./gradlew --offline :app:testGithubDebugUnitTest --tests '*PickerRenders*'
 *
 * Writes `<dir>/<locale>/<shot>-<light|dark>.png` at 1080x2400.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class PickerRenders {
    private val out: File? = System.getenv("TUTTI_RENDERS")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test fun display() = shoot("settings-display", SettingsCategory.DISPLAY, Fixture.essentials)

    @Test fun soundbar() = shoot("settings-soundbar", SettingsCategory.SOUNDBAR, Fixture.essentials)

    private fun shoot(name: String, category: SettingsCategory, state: TuttiUiState) {
        val dir = out
        assumeTrue("TUTTI_RENDERS not set", dir != null)
        val (controller, _) = graph()
        for (locale in listOf("fr", "en")) {
            for (dark in listOf(false, true)) {
                RuntimeEnvironment.setQualifiers("$locale-w360dp-h800dp-${if (dark) "night" else "notnight"}-xxhdpi")
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
                activity.setContent {
                    TuttiTheme(darkTheme = dark, dynamicColor = false) {
                        SettingsPageScreen(category, state, controller, onBack = {}, onOpenDiagnostic = {}, onPair = {}, onAddBar = {}, onOpenTechnical = {})
                    }
                }
                repeat(16) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(125)) }
                val view = activity.window.decorView
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val file = File(dir!!, "$locale/$name-${if (dark) "dark" else "light"}.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "$file not written" } }
                activity.finish()
            }
        }
    }

    /** The real controller over empty stores and connectors that refuse: it never reaches a device. */
    private fun graph(): Pair<TuttiController, TargetController> {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "renders").apply { mkdirs() }
        val store = TuttiStore(preferencesStore { File(dir, "tutti.preferences_pb") }, context)
        val secure = SecureStore(preferencesStore { File(dir, "secure.preferences_pb") }, SecretCipher())
        val lan = LocalNetworkPermission(context)
        val controller = TuttiController(
            context, store, secure, ClientIdentityStore(context, SecretCipher()),
            object : TlsConnector {
                override suspend fun connect(host: String, port: Int, identity: io.kud.tutti.protocol.net.ClientIdentity, expectedPin: io.kud.tutti.protocol.net.CertificatePin?) = throw IOException("render")
            },
            object : TcpConnector {
                override suspend fun connect(host: String, port: Int) = throw IOException("render")
            },
            UnavailableSonyCloudPlane(),
            object : CastConnector {
                override suspend fun connect(host: String, port: Int) = throw IOException("render")
            },
            TvDiscovery(context, lan),
            lan,
        )
        return controller to TargetController(context, controller, store, secure, HidLink(context))
    }

    private object Fixture {
        private val bar = TheatreState(
            connected = true,
            confirmed = mapOf(
                TheatreFeature.POWER.key to "on",
                TheatreFeature.INPUT.key to "tv",
                TheatreFeature.DRC.key to "auto",
                TheatreFeature.IMAX.key to "off",
                TheatreFeature.HDMI_ARC.key to "earc",
                TheatreFeature.HDMI_PASSTHROUGH.key to "auto",
            ),
        )

        val essentials = TuttiUiState(
            loaded = true,
            devices = Devices(TvDevice(host = "192.168.1.40"), BarDevice(host = "192.168.1.41")),
            links = Links(tv = Link.ONLINE, rest = Link.ONLINE, bar = Link.ONLINE, tvShown = TvShown(Link.ONLINE)),
            bar = bar,
            prefs = AppPrefs(vibration = VibrationLevel.ESSENTIALS),
        )
    }
}

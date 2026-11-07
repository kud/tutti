package io.kud.tutti.ui.about

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.Links
import io.kud.tutti.controller.TargetController
import io.kud.tutti.controller.TuttiController
import io.kud.tutti.controller.TuttiUiState
import io.kud.tutti.controller.TvShown
import io.kud.tutti.controller.TvState
import io.kud.tutti.di.preferencesStore
import io.kud.tutti.platform.bluetooth.HidLink
import io.kud.tutti.platform.data.BarDevice
import io.kud.tutti.platform.data.Devices
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.data.TvDevice
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
import io.kud.tutti.ui.common.LocalMessages
import io.kud.tutti.ui.common.MessageHost
import io.kud.tutti.ui.common.Messages
import io.kud.tutti.ui.remote.RemoteScreen
import io.kud.tutti.ui.theme.TuttiTheme
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.time.Duration

/**
 * What's new as it is drawn: the page (the github build's notes) and the once-after-an-update
 * message over the remote, from invented fixtures. Skipped unless `TUTTI_RENDERS` names a folder:
 *
 *     TUTTI_RENDERS=<dir> ./gradlew :app:testGithubDebugUnitTest --tests '*WhatsNewRenders*'
 *
 * Writes `<dir>/<locale>/whats-new-<shot>-<light|dark>.png` at 1080x2400.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class WhatsNewRenders {
    private val out: File? = System.getenv("TUTTI_RENDERS")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test fun page() = shoot("whats-new-page") { _, _ -> WhatsNewScreen(onBack = {}, release = ReleaseNotes.latest) }

    @Test fun pageScrolled() = shoot("whats-new-page-end") { _, _ ->
        // The same page with only its last group, to show the foot of a long list.
        WhatsNewScreen(onBack = {}, release = ReleaseNotes.latest.copy(new = emptyList()))
    }

    @Test fun message() = shoot("whats-new-message") { controller, target ->
        val messages = remember { Messages() }
        val context = LocalContext.current
        CompositionLocalProvider(LocalMessages provides messages) {
            Box(Modifier.fillMaxSize()) {
                RemoteScreen(Fixture.home, controller, target, onOpenScenes = {}, onOpenSettings = {}, onEditScene = {})
                MessageHost()
            }
        }
        LaunchedEffect(Unit) {
            messages.show(
                context.getString(R.string.whats_new_message, ReleaseNotes.latest.version.toString()),
                actionLabel = context.getString(R.string.whats_new_action),
                onAction = {},
                long = true,
            )
        }
    }

    private fun shoot(name: String, screen: @Composable (TuttiController, TargetController) -> Unit) {
        val dir = out
        assumeTrue("TUTTI_RENDERS not set", dir != null)
        val (controller, target) = graph()
        for (locale in listOf("en", "fr")) {
            for (dark in listOf(false, true)) {
                RuntimeEnvironment.setQualifiers("$locale-w360dp-h800dp-${if (dark) "night" else "notnight"}-xxhdpi")
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
                activity.setContent { TuttiTheme(darkTheme = dark, dynamicColor = false) { screen(controller, target) } }
                // A second and a half: the message has come in and not yet gone.
                repeat(12) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(125)) }
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
        val dir = File(context.filesDir, "whats-new-renders").apply { mkdirs() }
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

    /** Invented: private-range addresses, the devices' default names. */
    private object Fixture {
        val home = TuttiUiState(
            loaded = true,
            devices = Devices(TvDevice(host = "192.168.1.40"), BarDevice(host = "192.168.1.41")),
            links = Links(tv = Link.ONLINE, rest = Link.ONLINE, bar = Link.ONLINE, tvShown = TvShown(Link.ONLINE)),
            tv = TvState(power = PowerStatus.ACTIVE),
            remote = RemoteState(ready = true, powered = true),
            bar = TheatreState(
                connected = true,
                confirmed = mapOf(
                    TheatreFeature.POWER.key to "on",
                    TheatreFeature.INPUT.key to "tv",
                    TheatreFeature.VOLUME.key to "12",
                    TheatreFeature.MUTE.key to "off",
                ),
            ),
            volume = 12,
            powered = true,
        )
    }
}

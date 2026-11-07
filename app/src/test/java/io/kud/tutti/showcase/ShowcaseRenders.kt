package io.kud.tutti.showcase

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.kud.tutti.R
import io.kud.tutti.controller.Link
import io.kud.tutti.controller.Links
import io.kud.tutti.controller.SceneUi
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
import io.kud.tutti.platform.data.DockLabels
import io.kud.tutti.platform.data.DockSlot
import io.kud.tutti.platform.data.PadMode
import io.kud.tutti.platform.data.RemoteLayout
import io.kud.tutti.platform.data.TuttiStore
import io.kud.tutti.platform.data.TvDevice
import io.kud.tutti.platform.discovery.TvDiscovery
import io.kud.tutti.platform.net.LocalNetworkPermission
import io.kud.tutti.platform.security.ClientIdentityStore
import io.kud.tutti.platform.security.SecretCipher
import io.kud.tutti.platform.security.SecureStore
import io.kud.tutti.protocol.apps.KnownApp
import io.kud.tutti.protocol.atv.ImeField
import io.kud.tutti.protocol.atv.RemoteState
import io.kud.tutti.protocol.bravia.PowerStatus
import io.kud.tutti.protocol.cast.CastConnector
import io.kud.tutti.protocol.grpc.UnavailableSonyCloudPlane
import io.kud.tutti.protocol.net.TcpConnector
import io.kud.tutti.protocol.net.TlsConnector
import io.kud.tutti.protocol.scenes.StarterScenes
import io.kud.tutti.protocol.theatre.TheatreFeature
import io.kud.tutti.protocol.theatre.TheatreState
import io.kud.tutti.ui.remote.AppsSheetContent
import io.kud.tutti.ui.remote.RemoteScreen
import io.kud.tutti.ui.remote.SoundSheet
import io.kud.tutti.ui.scenes.ScenesScreen
import io.kud.tutti.ui.settings.SettingsCategory
import io.kud.tutti.ui.settings.SettingsPageScreen
import io.kud.tutti.ui.settings.SettingsScreen
import io.kud.tutti.ui.theme.TuttiTheme
import java.io.File
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDisplay

/**
 * The showcase screenshots (README, F-Droid listing, landing page), drawn from invented fixtures:
 * made-up device names, private-range addresses, an invented app list. Nothing here is a capture
 * of a real device. Skipped unless `TUTTI_SHOWCASE` names an output folder:
 *
 *     TUTTI_SHOWCASE=<dir> ./gradlew --offline :app:testGithubDebugUnitTest --tests '*Showcase*'
 *
 * Writes `<dir>/<locale>/<shot>-<light|dark>.png` for `en` and `fr`, at a Pixel 10's size: 1080x2424
 * px at 420 dpi (density 2.625, about 411x923 dp; Google lists the panel at 1080x2424, 422 ppi), so
 * the width-based layout (the pad, the card room) spreads its space as it does on that phone. The
 * window is edge to edge, as in [io.kud.tutti.MainActivity], under a status bar and a gesture
 * navigation bar given as insets ([STATUS_BAR], [NAVIGATION_BAR]); the bars themselves are not
 * drawn, so their space shows the app's own background.
 *
 * The screens take the real [TuttiController], built here from offline stand-ins (no connector ever
 * reaches the network: nothing is configured in its stores), while what they draw comes from the
 * [TuttiUiState] passed in, which is the fixture.
 */
/**
 * A Pixel 10 at its default display size: 1080x2424 px at 420 dpi. 411x923 dp is the nearest whole-dp
 * window the qualifiers can give; the display is then set to the panel's exact pixels.
 */
private const val SCREEN = "w411dp-h923dp"
private const val WIDTH_PX = 1080
private const val HEIGHT_PX = 2424

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "$SCREEN-420dpi")
class ShowcaseRenders {
    private val out: File? = System.getenv("TUTTI_SHOWCASE")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test fun remote() = shoot("1-remote") { c, t, _ -> RemoteScreen(Fixture.home, c, t, onOpenScenes = {}, onOpenSettings = {}, onEditScene = {}) }

    @Test fun apps() = shoot("2-apps") { _, _, _ ->
        Sheet { AppsSheetContent(Fixture.home, onLaunch = { null }, onLaunched = {}, onAllApps = {}, onPin = { _, _ -> }, recents = Fixture.recents) }
    }

    @Test fun sound() = shoot("3-sound") { c, _, _ -> Sheet { SoundSheet(Fixture.home, c) } }

    @Test fun typing() = shoot("4-typing") { c, t, locale -> RemoteScreen(Fixture.typing(locale), c, t, onOpenScenes = {}, onOpenSettings = {}, onEditScene = {}) }

    @Test fun scenes() = shoot("5-scenes") { c, _, _ -> ScenesScreen(Fixture.home, c, onBack = {}, onEdit = {}) }

    @Test fun sync() = shoot("6-sync") { c, _, _ ->
        SettingsPageScreen(SettingsCategory.SYNC, Fixture.home, c, onBack = {}, onOpenDiagnostic = {}, onEditLayout = {}, onPair = {}, onAddBar = {}, onOpenTechnical = {})
    }

    @Test fun settings() = shoot("7-settings") { _, _, _ -> SettingsScreen(Fixture.settled, onBack = {}, onOpen = {}) }

    @Test fun settingsNeedsAction() = shoot("8-settings-needs-action") { _, _, _ -> SettingsScreen(Fixture.ipLost, onBack = {}, onOpen = {}) }

    @Test fun remoteNoBar() = shoot("9-remote-no-bar") { c, t, _ -> RemoteScreen(Fixture.noBar, c, t, onOpenScenes = {}, onOpenSettings = {}, onEditScene = {}) }

    /** A sheet's content on its own, under the status bar, as it reads once pulled up. */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.statusBarsPadding()) { content() }
        }
    }

    private fun shoot(name: String, screen: @Composable (TuttiController, TargetController, String) -> Unit) {
        val dir = out
        assumeTrue("TUTTI_SHOWCASE not set", dir != null)
        dir!!.mkdirs()
        val (controller, target) = Graph.build()
        for (locale in listOf("en", "fr")) {
            for (dark in listOf(false, true)) {
                RuntimeEnvironment.setQualifiers("$locale-$SCREEN-${if (dark) "night" else "notnight"}-420dpi")
                // Whole dp can't make 1080x2424 at 2.625 (411 dp is 1078.9 px): set the panel's pixels.
                shadowOf(ShadowDisplay.getDefaultDisplay()).apply {
                    setWidth(WIDTH_PX)
                    setHeight(HEIGHT_PX)
                    setRealWidth(WIDTH_PX)
                    setRealHeight(HEIGHT_PX)
                }
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
                val redispatch = withSystemBars(activity)
                var seen = "compose insets never read"
                var compositions = 0
                // Compose's own frame clock outlives the test that first made it: it stays bound to the
                // first test's Choreographer, so from the second test on nothing recomposes after the
                // first composition (no insets catching up, no entrance settling). Each shot gets a
                // recomposer on a clock driven here, frame by frame, on the main looper.
                val clock = BroadcastFrameClock()
                val scope = CoroutineScope(Handler(Looper.getMainLooper()).asCoroutineDispatcher() + clock + Job())
                val recomposer = Recomposer(scope.coroutineContext)
                scope.launch { recomposer.runRecomposeAndApplyChanges() }
                val frame = {
                    Snapshot.sendApplyNotifications()
                    shadowOf(Looper.getMainLooper()).idle()
                    clock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
                }
                activity.setContent(recomposer) {
                    val px = LocalDensity.current
                    val status = WindowInsets.statusBars.getTop(px)
                    val navigation = WindowInsets.navigationBars.getBottom(px)
                    SideEffect {
                        compositions++
                        seen = "compose insets: status $status px, navigation $navigation px"
                    }
                    TuttiTheme(darkTheme = dark, dynamicColor = false) { screen(controller, target, locale) }
                }
                // The bars went out before the ComposeView existed: hand them over again now its
                // insets holder listens, then let two seconds of frames recompose and settle.
                frame()
                redispatch()
                repeat(SETTLE_MS / FRAME_MS.toInt()) { frame() }
                // App icons decode off the main thread: give that real time to land, then draw it.
                repeat(ICON_WAITS) { Thread.sleep(ICON_WAIT_MS); frame() }
                val view = activity.window.decorView
                val log = File(dir!!, "window.txt")
                val line = "$name $locale ${if (dark) "dark" else "light"}: window ${view.width}x${view.height}, screen ${activity.resources.configuration.screenWidthDp}x${activity.resources.configuration.screenHeightDp} dp, $seen, $compositions composition(s)"
                // Both flavours run this test into the same folder: one line per shot.
                if (!log.exists() || line !in log.readLines()) log.appendText("$line\n")
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                check(bitmap.width == WIDTH_PX && bitmap.height == HEIGHT_PX) { "$name rendered at ${bitmap.width}x${bitmap.height}" }
                val file = File(dir!!, "$locale/$name-${if (dark) "dark" else "light"}.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "$file not written" } }
                activity.finish()
                recomposer.cancel()
                scope.cancel()
            }
        }
    }

    /**
     * Robolectric draws no system bars and hands the window zero insets. Every dispatch through the
     * content frame is replaced by a phone's: a status bar on top and a gesture bar at the bottom,
     * so `statusBarsPadding`, `navigationBarsPadding` and the sheet's peek read what they read on
     * the phone. The same insets are dispatched once by hand in case nothing else asks; the returned
     * function dispatches them again, once the content is there to take them.
     */
    private fun withSystemBars(activity: ComponentActivity): () -> Unit {
        val density = activity.resources.displayMetrics.density
        val status = Insets.of(0, Math.round(STATUS_BAR * density), 0, 0)
        val navigation = Insets.of(0, 0, 0, Math.round(NAVIGATION_BAR * density))
        val bars = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), status)
            .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
            .setInsets(WindowInsetsCompat.Type.systemGestures(), navigation)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .build()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, _ -> bars }
        ViewCompat.dispatchApplyWindowInsets(content, bars)
        return { ViewCompat.dispatchApplyWindowInsets(content, bars) }
    }

    /** The real controller over empty stores and connectors that refuse: it never reaches a device. */
    private object Graph {
        fun build(): Pair<TuttiController, TargetController> {
            val context = RuntimeEnvironment.getApplication()
            val dir = File(context.filesDir, "showcase").apply { mkdirs() }
            val store = TuttiStore(preferencesStore { File(dir, "tutti.preferences_pb") }, context)
            val secure = SecureStore(preferencesStore { File(dir, "secure.preferences_pb") }, SecretCipher())
            val lan = LocalNetworkPermission(context)
            val controller = TuttiController(
                context, store, secure, ClientIdentityStore(context, SecretCipher()),
                object : TlsConnector {
                    override suspend fun connect(host: String, port: Int, identity: io.kud.tutti.protocol.net.ClientIdentity, expectedPin: io.kud.tutti.protocol.net.CertificatePin?) = throw IOException("showcase")
                },
                object : TcpConnector {
                    override suspend fun connect(host: String, port: Int) = throw IOException("showcase")
                },
                UnavailableSonyCloudPlane(),
                object : CastConnector {
                    override suspend fun connect(host: String, port: Int) = throw IOException("showcase")
                },
                TvDiscovery(context, lan),
                lan,
            )
            return controller to TargetController(context, controller, store, secure, HidLink(context))
        }
    }

    private companion object {
        /** Two seconds of 16 ms frames: every entrance has settled. */
        const val SETTLE_MS = 2000
        const val FRAME_MS = 16L
        const val ICON_WAITS = 10
        const val ICON_WAIT_MS = 30L


        /**
         * The bars in dp, read off a screenshot of Erwann's Pixel 10 (dark remote, 2026-11-07): the
         * top bar's chip starts 193 px down (8 dp of row padding under the status bar), the Sound
         * sheet's edge 284 px above the bottom (72 dp of peek over the gesture strip), and the
         * gesture handle sits centred in that 95 px strip.
         */
        const val STATUS_BAR = 65f
        const val NAVIGATION_BAR = 36f
    }
}

/**
 * Invented: the addresses (private range), the apps and their icons; the devices keep the app's
 * default names. Set up as on Erwann's phone: the D-pad, an icons-only dock of two rows (the round
 * row joined in, then his pins), and the starter scenes named in the phone's language.
 */
private object Fixture {
    private fun app(id: String, name: String) = KnownApp("app.invented.$id", name)

    val apps = listOf(
        app("lumen", "Lumen"),
        app("reelclub", "Reel Club"),
        app("harbourfm", "Harbour FM"),
        app("pebble", "Pebble Kids"),
        app("atlas", "Atlas Docs"),
        app("tempo", "Tempo"),
        app("cosy", "Cosy Kitchen"),
        app("orbit", "Orbit Sport"),
        app("lantern", "Lantern"),
    )

    val recents = listOf("app.invented.lumen", "app.invented.tempo", "app.invented.reelclub")

    /**
     * Launcher art for the invented apps, standing where the TV's own icons are cached: a flat
     * colour and one plain white shape each, drawn here, so no real brand appears.
     */
    private val icons: Map<String, String> by lazy {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "showcase-icons").apply { mkdirs() }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val stroke = Paint(ink).apply { style = Paint.Style.STROKE; strokeWidth = 18f; strokeCap = Paint.Cap.ROUND }
        val faces: Map<String, Pair<Long, (Canvas) -> Unit>> = mapOf(
            "lumen" to (0xFFE59A2F to { c: Canvas -> c.drawCircle(96f, 96f, 40f, ink) }),
            "reelclub" to (0xFFC0394B to { c: Canvas ->
                c.drawPath(Path().apply { moveTo(74f, 56f); lineTo(140f, 96f); lineTo(74f, 136f); close() }, ink)
            }),
            "harbourfm" to (0xFF1E8582 to { c: Canvas ->
                c.drawArc(RectF(46f, 46f, 146f, 146f), 200f, 140f, false, stroke)
                c.drawArc(RectF(72f, 72f, 120f, 120f), 200f, 140f, false, stroke)
                c.drawCircle(96f, 120f, 12f, ink)
            }),
            "pebble" to (0xFF3E9E5F to { c: Canvas -> c.drawOval(RectF(50f, 66f, 142f, 130f), ink) }),
            "atlas" to (0xFF3F51A8 to { c: Canvas ->
                for (y in listOf(66f, 96f, 126f)) c.drawLine(58f, y, if (y == 126f) 108f else 134f, y, stroke)
            }),
            "tempo" to (0xFFC2448A to { c: Canvas ->
                for ((x, h) in listOf(66f to 30f, 96f to 52f, 126f to 40f)) c.drawLine(x, 96f - h / 2 - 8f, x, 96f + h / 2 + 8f, stroke)
            }),
            "cosy" to (0xFFD8683A to { c: Canvas -> c.drawArc(RectF(52f, 52f, 140f, 140f), 0f, 180f, true, ink) }),
            "orbit" to (0xFF2C78CC to { c: Canvas ->
                c.drawCircle(96f, 96f, 44f, Paint(stroke).apply { strokeWidth = 14f })
                c.drawCircle(130f, 66f, 14f, ink)
            }),
            "lantern" to (0xFF2D3955 to { c: Canvas -> c.drawRoundRect(RectF(70f, 56f, 122f, 136f), 18f, 18f, Paint(ink).apply { color = 0xFFF2C14E.toInt() }) }),
        )
        faces.map { (id, face) ->
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(face.first.toInt()); face.second(this) }
            val file = File(dir, "$id.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            "app.invented.$id" to file.path
        }.toMap()
    }

    /** The starters read in the phone's language, as `TuttiStore` shows a scene he never renamed. */
    private val starterNames = mapOf(
        "starter-cinema" to R.string.scene_starter_cinema,
        "starter-serie" to R.string.scene_starter_serie,
        "starter-nuit" to R.string.scene_starter_nuit,
        "starter-musique" to R.string.scene_starter_musique,
        "starter-sport" to R.string.scene_starter_sport,
        "starter-jeu" to R.string.scene_starter_jeu,
        "starter-info" to R.string.scene_starter_info,
    )

    private fun scenes() = StarterScenes.all.map { scene ->
        val name = starterNames[scene.id]?.let { RuntimeEnvironment.getApplication().getString(it) } ?: scene.name
        SceneUi(scene.copy(name = name))
    }

    private val layout = RemoteLayout(dockLabels = DockLabels.ICON)

    private val bar = TheatreState(
        connected = true,
        confirmed = mapOf(
            TheatreFeature.POWER.key to "on",
            TheatreFeature.INPUT.key to "tv",
            TheatreFeature.VOLUME.key to "18",
            TheatreFeature.MUTE.key to "off",
            TheatreFeature.SOUND_FIELD.key to "on",
            TheatreFeature.VOICE.key to "upon",
            TheatreFeature.NIGHT.key to "off",
            TheatreFeature.DRC.key to "auto",
            TheatreFeature.AUTO_VOLUME.key to "off",
            TheatreFeature.SPATIAL_360.key to "on",
            TheatreFeature.IMAX.key to "auto",
            TheatreFeature.BASS.key to "2",
            TheatreFeature.REAR_LEVEL.key to "0",
        ),
    )

    /** Read per shot: the scene names follow the locale the shot is drawn in. */
    val home: TuttiUiState get() = TuttiUiState(
        loaded = true,
        devices = Devices(TvDevice(host = "192.168.1.40"), BarDevice(host = "192.168.1.41")),
        prefs = AppPrefs(padMode = PadMode.DPAD, layout = layout),
        dock = DockConfig(
            slots = listOf(
                DockSlot(DockAction.OpenApps),
                DockSlot(DockAction.OpenKeys),
                DockSlot(DockAction.LaunchApp("app.invented.lumen", "Lumen")),
                DockSlot(DockAction.LaunchApp("app.invented.reelclub", "Reel Club")),
                DockSlot(DockAction.LaunchApp("app.invented.tempo", "Tempo")),
                DockSlot(DockAction.ToggleBar("audio.nightmode")),
            ),
        ).normalised(layout),
        links = Links(tv = Link.ONLINE, rest = Link.ONLINE, bar = Link.ONLINE, tvShown = TvShown(Link.ONLINE)),
        tv = TvState(power = PowerStatus.ACTIVE),
        remote = RemoteState(ready = true, powered = true, currentApp = "app.invented.lumen"),
        bar = bar,
        scenes = scenes(),
        volume = 18,
        powered = true,
        apps = apps,
        appIcons = icons,
    )

    /** IP control set up with a key, so Settings reads a room with everything in place. */
    val settled: TuttiUiState get() = home.copy(pskSet = true)

    /** IP control lost its pairing: the TV's tile asks first. */
    val ipLost: TuttiUiState get() = settled.copy(links = home.links.copy(rest = Link.NEEDS_PAIRING))

    /** A TV and no soundbar: the remote with no Sound sheet. */
    val noBar: TuttiUiState get() = home.copy(devices = Devices(TvDevice(host = "192.168.1.40")), links = home.links.copy(bar = Link.ABSENT), bar = TheatreState())

    /** The TV's own search field, labelled in the phone's language as the TV would. */
    fun typing(locale: String) = home.copy(
        remote = home.remote.copy(
            ime = ImeField(text = "night garden", label = if (locale == "fr") "Rechercher" else "Search", selectionStart = 12, selectionEnd = 12),
            imeShows = 1,
        ),
    )
}

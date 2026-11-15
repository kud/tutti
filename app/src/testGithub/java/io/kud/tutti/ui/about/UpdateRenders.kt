package io.kud.tutti.ui.about

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.kud.tutti.platform.update.UpdateProblem
import io.kud.tutti.platform.update.UpdateState
import io.kud.tutti.platform.update.Updater
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
import java.time.Duration

/**
 * The Updates sheet's content after a failed check, with the way out to the releases page
 * (2026-11-15). The github build only: fdroid has no updater. Skipped unless `TUTTI_RENDERS`
 * names a folder:
 *
 *     TUTTI_RENDERS=<dir> ./gradlew :app:testGithubDebugUnitTest --tests '*UpdateRenders*'
 *
 * Writes `<dir>/<locale>/update-<shot>-<light|dark>.png`. Nothing reaches the network: the state
 * is handed to the sheet, and the updater is only there for the buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
class UpdateRenders {
    private val out: File? = System.getenv("TUTTI_RENDERS")?.takeIf { it.isNotBlank() }?.let(::File)

    @Test fun failedNetwork() = shoot("update-failed-network", UpdateState.Failed(UpdateProblem.NETWORK, null))

    @Test fun failedRateLimited() = shoot("update-failed-rate-limited", UpdateState.Failed(UpdateProblem.RATE_LIMITED, null))

    private fun shoot(name: String, state: UpdateState) {
        val dir = out
        assumeTrue("TUTTI_RENDERS not set", dir != null)
        val updater = Updater(RuntimeEnvironment.getApplication())
        for (locale in listOf("en", "fr")) {
            for (dark in listOf(false, true)) {
                RuntimeEnvironment.setQualifiers("$locale-w360dp-h800dp-${if (dark) "night" else "notnight"}-xxhdpi")
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
                activity.setContent {
                    TuttiTheme(darkTheme = dark, dynamicColor = false) {
                        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            UpdateSheetBody(updater, state, onDismiss = {})
                        }
                    }
                }
                repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(125)) }
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
}

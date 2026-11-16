package io.kud.tutti.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.kud.tutti.controller.TargetController
import io.kud.tutti.protocol.bravia.ExternalInput

/**
 * Settings reaches [TargetController] through the graph rather than a parameter threaded from
 * MainActivity, so the Devices screens stay inside ui/settings while the remote is wired to it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DevicesEntryPoint {
    fun targets(): TargetController
}

internal fun Context.targetController(): TargetController =
    EntryPointAccessors.fromApplication(applicationContext, DevicesEntryPoint::class.java).targets()

/** The singleton [TargetController], or null in a preview with no graph. */
@Composable
internal fun rememberTargets(): TargetController? {
    val context = LocalContext.current
    return remember(context) { runCatching { context.targetController() }.getOrNull() }
}

/** An HDMI input in words: the TV's own label if set, else its title, else "HDMI n" from the uri. */
internal fun ExternalInput.spoken(): String =
    label.takeIf { it.isNotBlank() }
        ?: title.takeIf { it.isNotBlank() }
        ?: uri.substringAfter("port=", "").takeIf { it.isNotBlank() }?.let { "HDMI $it" }
        ?: uri

internal val ExternalInput.isHdmi: Boolean get() = uri.startsWith("extInput:hdmi")

/** The name of [uri] among [inputs], or "HDMI n" when the TV hasn't listed it (IP control off). */
internal fun inputName(uri: String, inputs: List<ExternalInput>): String =
    inputs.firstOrNull { it.uri == uri }?.spoken() ?: ExternalInput(uri, "", "", false).spoken()

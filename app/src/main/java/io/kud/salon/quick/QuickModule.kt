package io.kud.salon.quick

import android.content.Context
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Optional

/**
 * Binds [QuickControls] to the app controller. Optional on the consumer side, so a
 * surface entered before the graph is ready still renders "Déconnecté" rather than crash.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuickModule {
    @BindsOptionalOf
    abstract fun optionalQuickControls(): QuickControls

    @Binds
    abstract fun quickControls(impl: ControllerQuickControls): QuickControls
}

/**
 * Tile, widget, receivers and the overlay are entered by the system, not through
 * the UI graph. An entry point keeps them independent of @AndroidEntryPoint support
 * for each component type (GlanceAppWidgetReceiver, TileService).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickEntryPoint {
    fun quickControls(): Optional<QuickControls>
}

/** The bound controller, or null while none is bound. */
internal fun Context.quickControls(): QuickControls? =
    EntryPointAccessors.fromApplication(applicationContext, QuickEntryPoint::class.java)
        .quickControls()
        .orElse(null)

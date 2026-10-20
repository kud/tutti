package io.kud.salon.quick

import android.content.Context
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Optional

/**
 * Declares [QuickControls] as optional so the quick surfaces compile and run
 * (showing "Déconnecté") before the controller is wired in, without binding a fake.
 *
 * TODO(merge): bind the real controller, once, in this module:
 *
 *     @Binds
 *     abstract fun quickControls(impl: SalonController): QuickControls
 *
 * with `SalonController : QuickControls` (or a small adapter class mapping its
 * combined state onto [QuickState]). @BindsOptionalOf then resolves to present.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuickModule {
    @BindsOptionalOf
    abstract fun optionalQuickControls(): QuickControls
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

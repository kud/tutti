package io.kud.tutti.quick.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.kud.tutti.R
import io.kud.tutti.quick.QuickRuntime
import io.kud.tutti.quick.QuickState
import io.kud.tutti.quick.overlay.QuickOverlayActivity
import io.kud.tutti.quick.quickControls
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tile. Tap opens the compact remote overlay (design.md § Quick
 * access); long-press opens the app through the QS_TILE_PREFERENCES alias in the
 * manifest. The tile lights while the room is on, connected and audible; the
 * icon and subtitle carry mute and connection.
 *
 * Not an "active" tile: the system binds it when the shade opens, which is the
 * only time it is visible, so no background push is needed.
 */
class QuickTileService : TileService() {
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        QuickRuntime.start(this)
        val controls = quickControls()
        if (controls == null) {
            render(QuickState())
            return
        }
        listening?.cancel()
        listening = QuickRuntime.scope.launch { controls.state.collect { render(it) } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Volume is harmless, but the overlay also has Power and scenes: ask for the unlock first.
        if (isLocked) unlockAndRun { openOverlay() } else openOverlay()
    }

    private fun openOverlay() {
        val intent = Intent(this, QuickOverlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            startLegacy(intent)
        }
    }

    /** The Intent overload throws on API 34+ for apps targeting 34+; only reached below 34. */
    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun startLegacy(intent: Intent) {
        startActivityAndCollapse(intent)
    }

    private fun render(state: QuickState) {
        val tile = qsTile ?: return
        val live = state.connected && state.power
        tile.state = if (live && !state.muted) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.quick_tile_label)
        tile.subtitle = when {
            !state.connected -> getString(R.string.quick_status_disconnected)
            !state.power -> getString(R.string.quick_status_off)
            state.muted -> getString(R.string.quick_status_muted)
            else -> getString(R.string.quick_volume_value, state.volume)
        }
        tile.icon = Icon.createWithResource(
            this,
            if (state.muted) R.drawable.quick_ic_volume_off else R.drawable.quick_ic_volume_up,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = tile.subtitle
        }
        tile.updateTile()
    }
}

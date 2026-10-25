package io.kud.tutti.quick.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.kud.tutti.MainActivity
import io.kud.tutti.platform.shortcuts.ACTION_KEYBOARD

/**
 * The Keyboard tile (Iris's benchmark, idea 6, background half): opens tutti on the remote with its
 * keyboard view, the launcher shortcut's own path ([ACTION_KEYBOARD]). It reads nothing and holds
 * nothing: with tutti in the background no session is kept alive to hear that the TV opened a
 * field (no foreground service, design.md § Notification), so the tile is the way in, not a signal.
 */
class KeyboardTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Typing reaches the TV: unlocked first, like the remote tile.
        if (isLocked) unlockAndRun { openKeyboard() } else openKeyboard()
    }

    private fun openKeyboard() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_KEYBOARD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
}

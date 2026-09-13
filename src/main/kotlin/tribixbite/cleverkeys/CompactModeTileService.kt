package tribixbite.cleverkeys

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.content.SharedPreferences

/**
 * Quick Settings tile toggling Compact mode (T2): the IME window is narrowed
 * to a share of the screen width and anchored to one edge, so the keyboard
 * sits within thumb reach on tablets. The width/edge themselves are configured
 * in Settings → Appearance (compact_width / compact_side_right); this tile only
 * flips the master switch [Defaults.COMPACT_MODE], the same key the settings
 * UI and Config.refresh read.
 *
 * The write lands in the same DirectBootAware SharedPreferences store the IME
 * service listens to, so the change applies on the next Config refresh — no
 * explicit service messaging needed.
 */
class CompactModeTileService : TileService() {

    private fun prefs(): SharedPreferences =
        DirectBootAwarePreferences.get_shared_preferences(this)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = prefs()
        // getBoolean (not safeGet): the only writers are this tile and the
        // settings UI, both typed booleans; a hand-corrupted store falls back
        // to the default, matching every other read site.
        val newValue = !prefs.getBoolean("compact_mode", Defaults.COMPACT_MODE)
        prefs.edit().putBoolean("compact_mode", newValue).apply()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (prefs().getBoolean("compact_mode", Defaults.COMPACT_MODE))
            Tile.STATE_ACTIVE
        else
            Tile.STATE_INACTIVE
        tile.updateTile()
    }
}

package ai.nexus.app.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ai.nexus.app.NexusApp

/**
 * "NEXUS" tile in the quick settings (swipe down from the top). One tap = start listening.
 * It never touches the phone's volume or any hardware button.
 */
class ListenTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = "NEXUS"; updateTile() }
    }

    override fun onClick() {
        NexusApp.get(this).quickListen()
    }
}

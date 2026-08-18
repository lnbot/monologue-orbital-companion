package com.overstuffed.monologue_orbital_companion

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that mirrors and toggles the master sync gate
 * ([SyncCoordinator.masterSyncEnabled] / [SyncCoordinator.setMasterSyncEnabled]).
 *
 * The tile is `STATE_ACTIVE` while master sync is enabled and `STATE_INACTIVE` while it is
 * disabled; tapping it flips the gate (which unregisters the alarm/calendar/timer listeners and
 * stops the foreground [SyncService] when turned off, and restores the persisted per-channel
 * settings when turned back on).
 *
 * **State freshness.** While the tile is visible ([onStartListening]) we collect the reactive
 * [SyncCoordinator.masterSyncEnabled] flow, so toggling master sync from the app's configuration
 * screen updates the tile immediately. The tile also refreshes on [onTileAdded] and after each
 * [onClick].
 *
 * The process-level [MonologueApplication] initializes [SyncCoordinator] when the tile's process
 * starts, so the tile always has a fully-initialized coordinator to read and toggle.
 */
class MasterSyncTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectionJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Apply the current state immediately, then keep it in sync with the coordinator.
        refreshTile()
        collectionJob = scope.launch {
            SyncCoordinator.masterSyncEnabled.collect { refreshTile() }
        }
    }

    override fun onStopListening() {
        collectionJob?.cancel()
        collectionJob = null
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val enable = !SyncCoordinator.isMasterSyncEnabled()
        Log.i(TAG, "QS tile tapped: toggling master sync -> $enable")
        SyncCoordinator.setMasterSyncEnabled(enable)
        refreshTile()
    }

    /**
     * Pushes the current master-sync state onto the bound tile.
     *
     * `qsTile` (and therefore [Tile.updateTile]'s visual effect) is only available while the
     * system has bound a tile to this service; before that the call is a safe no-op.
     */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val enabled = SyncCoordinator.isMasterSyncEnabled()
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_quick_settings_sync)
        tile.label = getString(R.string.tile_master_sync_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Subtitle support was added in API 29; older devices simply show the label.
            tile.subtitle = getString(
                if (enabled) R.string.tile_master_sync_on else R.string.tile_master_sync_off,
            )
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = PebbleMessageKeys.LOG_TAG
    }
}
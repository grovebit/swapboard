package com.clipsync.android.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clipsync.android.R
import com.clipsync.android.data.SetupPrefs
import com.clipsync.android.net.ConnState
import com.clipsync.android.syncController
import com.clipsync.android.ui.SendClipboardActivity

/** Quick Settings tile "Send to Mac": opens the translucent activity that reads the clipboard. */
class SendTileService : TileService() {
    // The setup screens and the main screen show whether the tile is in place.
    override fun onTileAdded() {
        super.onTileAdded()
        SetupPrefs(this).tileAdded = true
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        SetupPrefs(this).tileAdded = false
    }

    override fun onStartListening() {
        super.onStartListening()
        SetupPrefs(this).tileAdded = true // tiles added before this was tracked
        val tile = qsTile ?: return
        val state = syncController.state.value
        tile.state = if (state is ConnState.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = when (state) {
            is ConnState.Connected -> state.macName
            is ConnState.RePairNeeded -> getString(R.string.tile_repair)
            ConnState.NotPaired -> getString(R.string.tile_not_paired)
            else -> getString(R.string.tile_offline)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // the Intent form only runs below Android 14
    override fun onClick() {
        super.onClick()
        val intent = SendClipboardActivity.intent(this, sensitive = false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // API 34+: startActivityAndCollapse(Intent) throws; the PendingIntent form is required.
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val launch = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION") // the only option before Android 14
                startActivityAndCollapse(intent)
            }
        }
        if (isLocked) unlockAndRun { launch() } else launch()
    }
}

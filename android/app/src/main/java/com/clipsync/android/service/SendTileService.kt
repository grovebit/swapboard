package com.clipsync.android.service

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clipsync.android.R
import com.clipsync.android.net.ConnState
import com.clipsync.android.syncController
import com.clipsync.android.ui.SendClipboardActivity

/** Quick Settings tile "Send to Mac": opens the translucent activity that reads the clipboard. */
class SendTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
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
        if (isLocked) unlockAndRun { startActivityAndCollapse(pi) } else startActivityAndCollapse(pi)
    }
}

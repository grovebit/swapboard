package app.swapboard.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.swapboard.android.syncController

/** Starts the sync service after boot and after an app update (both allowed for connectedDevice). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (context.syncController.pairingStore.load() != null) SwapboardService.start(context)
            }
        }
    }
}

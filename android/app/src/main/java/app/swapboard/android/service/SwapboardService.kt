package app.swapboard.android.service

import android.app.Notification
import androidx.core.content.ContextCompat
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log
import app.swapboard.android.R
import app.swapboard.android.SyncController
import app.swapboard.android.net.ConnState
import app.swapboard.android.net.NetworkMonitor
import app.swapboard.android.syncController
import app.swapboard.android.ui.MainActivity
import app.swapboard.android.ui.SendClipboardActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the connection to the Mac alive so
 * Mac → phone clips arrive in the background. Its notification carries the one-tap send actions.
 */
class SwapboardService : Service() {
    private lateinit var controller: SyncController
    private var stateJob: Job? = null
    private var networkMonitor: NetworkMonitor? = null
    private var receiverRegistered = false

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.connection.kick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = syncController
        startForeground(
            Notifications.STATUS_ID,
            Notifications.status(this, controller.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        controller.connection.start()
        controller.autoSync.start()
        val nm = getSystemService(NotificationManager::class.java)
        stateJob = controller.scope.launch {
            controller.state.collect { state ->
                // Re-posting also restores the notification if the user swiped it away (Android 14+).
                nm.notify(Notifications.STATUS_ID, Notifications.status(this@SwapboardService, state))
                runCatching {
                    TileService.requestListeningState(
                        this@SwapboardService,
                        ComponentName(this@SwapboardService, SendTileService::class.java),
                    )
                }
            }
        }
        networkMonitor = NetworkMonitor(this) { controller.connection.kick() }.also { it.start() }
        ContextCompat.registerReceiver(
            this,
            wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (controller.pairingStore.load() == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        controller.connection.start()
        controller.connection.kick()
        return START_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        networkMonitor?.stop()
        if (receiverRegistered) runCatching { unregisterReceiver(wakeReceiver) }
        controller.autoSync.stop()
        controller.connection.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, SwapboardService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(SyncController.TAG, "could not start sync service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SwapboardService::class.java))
        }
    }
}

object Notifications {
    const val STATUS_ID = 1
    private const val CHANNEL_STATUS = "status"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
            },
        )
    }

    fun status(context: Context, state: ConnState): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(describe(context, state))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(sendAction(context, sensitive = false))
            .addAction(sendAction(context, sensitive = true))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Points straight at the activity: notification trampolines through services are blocked. */
    private fun sendAction(context: Context, sensitive: Boolean): Notification.Action {
        val intent = SendClipboardActivity.intent(context, sensitive).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context,
            if (sensitive) 2 else 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = context.getString(if (sensitive) R.string.action_send_sensitive else R.string.action_send)
        return Notification.Action.Builder(null, label, pi).build()
    }

    fun describe(context: Context, state: ConnState): String = when (state) {
        ConnState.Stopped -> context.getString(R.string.state_stopped)
        ConnState.NotPaired -> context.getString(R.string.state_not_paired)
        is ConnState.Connecting -> context.getString(R.string.state_connecting, state.host)
        is ConnState.Connected -> context.getString(R.string.state_connected, state.macName, state.host)
        is ConnState.Waiting -> context.getString(R.string.state_waiting)
        is ConnState.RePairNeeded -> context.getString(R.string.state_repair)
    }
}

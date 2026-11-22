package app.swapboard.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import app.swapboard.android.R
import app.swapboard.android.data.SetupPrefs
import app.swapboard.android.net.TailscaleApp
import app.swapboard.android.service.SwapboardService
import app.swapboard.android.service.SendTileService
import app.swapboard.android.syncController

/** What Swapboard needs to keep working while the phone is idle, and whether the tile is in place. */
data class BackgroundAccess(
    val notifications: Boolean,
    val swapboardBattery: Boolean,
    val tailscaleInstalled: Boolean,
    val tailscaleBattery: Boolean,
    val tile: Boolean,
) {
    /** Everything syncing depends on; the tile is only a shortcut. */
    val essentialsDone: Boolean
        get() = notifications && swapboardBattery && (!tailscaleInstalled || tailscaleBattery)
}

fun readBackgroundAccess(context: Context): BackgroundAccess = BackgroundAccess(
    notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    swapboardBattery = context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true,
    tailscaleInstalled = TailscaleApp.isInstalled(context),
    tailscaleBattery = TailscaleApp.isBatteryUnrestricted(context),
    tile = SetupPrefs(context).tileAdded,
)

/** One row per item. With [onlyMissing], rows that are already done are hidden. */
@Composable
fun BackgroundAccessRows(access: BackgroundAccess, onRefresh: () -> Unit, showTile: Boolean, onlyMissing: Boolean) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onRefresh()
        // Re-post the status notification now that it's allowed.
        if (context.syncController.pairingStore.load() != null) SwapboardService.start(context)
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!onlyMissing || !access.notifications) {
            ChecklistRow(
                done = access.notifications,
                title = "Notifications",
                detail = "Shows that Swapboard is connected, with quick “Send to Mac” buttons.",
                action = "Allow",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openNotificationSettings(context)
                }
            }
        }
        if (!onlyMissing || !access.swapboardBattery) {
            ChecklistRow(
                done = access.swapboardBattery,
                title = "Let Swapboard run in the background",
                detail = "Keeps the connection to your Mac while your phone is idle.",
                action = "Allow",
            ) { requestBatteryExemption(context) }
        }
        if (access.tailscaleInstalled && (!onlyMissing || !access.tailscaleBattery)) {
            ChecklistRow(
                done = access.tailscaleBattery,
                title = "Let Tailscale run in the background",
                detail = "Tap “Battery” (or “App battery usage”), then choose “Unrestricted”.",
                action = "Open",
            ) { TailscaleApp.openAppInfo(context) }
        }
        if (showTile && (!onlyMissing || !access.tile)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ChecklistRow(
                    done = access.tile,
                    title = "“Send to Mac” button",
                    detail = "Adds it to your Quick Settings panel, the quickest way to send.",
                    action = "Add",
                ) { requestAddTile(context) { onRefresh() } }
            } else {
                ChecklistRow(
                    done = access.tile,
                    title = "“Send to Mac” button",
                    detail = "Swipe down from the top twice, tap the pencil (edit) icon, and drag “Send to Mac” in.",
                    action = null,
                ) {}
            }
        }
    }
}

@Composable
fun ChecklistRow(done: Boolean, title: String, detail: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text((if (done) "✓ " else "• ") + title, style = MaterialTheme.typography.bodyLarge)
            if (!done) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!done && action != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

@SuppressLint("BatteryLife") // sideloaded app whose core job needs a long-lived connection
fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
}

/** Android 13+: asks to add the tile; [onResult] gets whether it's now in Quick Settings. */
fun requestAddTile(context: Context, onResult: (Boolean) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onResult(false)
    requestAddTileApi33(context, onResult)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestAddTileApi33(context: Context, onResult: (Boolean) -> Unit) {
    val statusBar = context.getSystemService(StatusBarManager::class.java)
    statusBar.requestAddTileService(
        ComponentName(context, SendTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { result ->
        val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
        if (added) SetupPrefs(context).tileAdded = true
        onResult(added)
    }
}

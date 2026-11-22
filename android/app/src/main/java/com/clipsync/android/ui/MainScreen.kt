package com.clipsync.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipsync.android.R
import com.clipsync.android.clip.ClipboardReader
import com.clipsync.android.core.Pairing
import com.clipsync.android.core.TailnetPolicy
import com.clipsync.android.net.ConnState
import com.clipsync.android.service.ClipSyncService
import com.clipsync.android.service.Notifications
import com.clipsync.android.service.SendTileService
import com.clipsync.android.shizuku.AutoSync
import com.clipsync.android.syncController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(refreshKey: Int, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val controller = context.syncController
    val state by controller.state.collectAsStateWithLifecycle()
    val pairing by controller.pairingStore.pairing.collectAsStateWithLifecycle()
    var confirmUnpair by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("ClipSync") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state, pairing)
            AutoSyncCard(refreshKey, paired = pairing != null)
            SetupCard(refreshKey, onRefresh, paired = pairing != null)
            SamsungCard()
            PairingCard(pairing, onUnpair = { confirmUnpair = true })
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Unpair from ${pairing?.name ?: "Mac"}?") },
            text = { Text("The pairing key is deleted from this phone. You'll need to scan a new QR code to sync again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    controller.pairingStore.clear()
                    controller.onPairingChanged()
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(state: ConnState, pairing: Pairing?) {
    val context = LocalContext.current
    val controller = context.syncController
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (pairing != null) "Paired with “${pairing.name}”" else "Not paired",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(Notifications.describe(context, state))
            if (state is ConnState.Waiting && state.lastError != null) {
                Text("Last error: ${state.lastError}", style = MaterialTheme.typography.bodySmall)
            }
            if (pairing != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        // This activity has window focus, so it may read the clipboard directly.
                        val clip = ClipboardReader.read(context)
                        if (clip == null) {
                            controller.toast(R.string.toast_clipboard_empty)
                        } else {
                            controller.sendFromUser(clip.text, clip.sensitive, skipIfEcho = false)
                        }
                    }) { Text("Send clipboard now") }
                    OutlinedButton(onClick = {
                        controller.connection.restart()
                        ClipSyncService.start(context)
                    }) { Text("Reconnect") }
                }
            }
        }
    }
}

@Composable
private fun AutoSyncCard(refreshKey: Int, paired: Boolean) {
    val autoSync = LocalContext.current.syncController.autoSync
    val enabled by autoSync.enabled.collectAsStateWithLifecycle()
    val status by autoSync.status.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { autoSync.refresh() } // e.g. after starting Shizuku and coming back

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic phone → Mac", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Send every copy on this phone to the Mac without tapping. Android only lets " +
                            "privileged apps read the clipboard in the background, so this uses Shizuku. " +
                            "Items marked sensitive (passwords) are skipped.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { autoSync.setEnabled(it) })
            }
            if (enabled) {
                Text(
                    when (val s = status) {
                        AutoSync.Status.Off -> if (paired) "Starting…" else "Pair with a Mac first."
                        AutoSync.Status.ShizukuNotInstalled -> "Shizuku isn't installed. Install and start it, then come back."
                        AutoSync.Status.ShizukuNotRunning -> "Shizuku isn't running. Open Shizuku and start it (needed after each reboot)."
                        AutoSync.Status.PermissionNeeded -> "ClipSync needs permission in Shizuku."
                        AutoSync.Status.Starting -> "Starting the Shizuku helper…"
                        is AutoSync.Status.Active -> "✓ On: ${s.detail}"
                        is AutoSync.Status.Failed -> "Error: ${s.message}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status == AutoSync.Status.PermissionNeeded) {
                    OutlinedButton(onClick = { autoSync.requestPermission() }) { Text("Grant Shizuku access") }
                }
            }
        }
    }
}

@Composable
private fun SetupCard(refreshKey: Int, onRefresh: () -> Unit, paired: Boolean) {
    val context = LocalContext.current
    val notificationsGranted = remember(refreshKey) { isGranted(context, Manifest.permission.POST_NOTIFICATIONS) }
    val batteryUnrestricted = remember(refreshKey) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onRefresh()
        if (paired) ClipSyncService.start(context)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            ChecklistRow(
                done = notificationsGranted,
                title = "Notifications",
                detail = "Shows the status notification with its “Send clipboard” buttons.",
                action = "Allow",
            ) { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            ChecklistRow(
                done = batteryUnrestricted,
                title = "Unrestricted battery",
                detail = "Keeps the connection alive while the phone is idle.",
                action = "Allow",
            ) { requestBatteryExemption(context) }
            ChecklistRow(
                done = false,
                title = "Quick Settings tile",
                detail = "Adds “Send to Mac” to your Quick Settings panel.",
                action = "Add tile",
            ) { requestAddTile(context) }
        }
    }
}

@Composable
private fun ChecklistRow(done: Boolean, title: String, detail: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text((if (done) "✓ " else "• ") + title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (!done) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SamsungCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Samsung battery settings", style = MaterialTheme.typography.titleMedium)
            Text("Samsung can put background apps to sleep. For both ClipSync and Tailscale:")
            Text("1. Settings → Apps → (app) → Battery → Unrestricted")
            Text("2. Settings → Battery → Background usage limits → Never sleeping apps → add the app")
            Text("3. Turn off “Put unused apps to sleep” in the same screen, or keep both apps in the never-sleeping list.")
        }
    }
}

@Composable
private fun PairingCard(pairing: Pairing?, onUnpair: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pairing", style = MaterialTheme.typography.titleMedium)
            if (pairing != null) {
                val tailscale = TailnetPolicy.allowedHosts(pairing.hosts)
                Text(
                    if (tailscale.isEmpty()) "No Tailscale address in this pairing. Re-pair with Tailscale on."
                    else "Mac's Tailscale address: ${tailscale.joinToString(", ")} (port ${pairing.port}). " +
                        "ClipSync only connects through Tailscale, never over Wi-Fi/LAN.",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { context.startActivity(Intent(context, PairActivity::class.java)) }) {
                    Text(if (pairing == null) "Pair with a Mac" else "Re-pair")
                }
                if (pairing != null) OutlinedButton(onClick = onUnpair) { Text("Unpair") }
            }
        }
    }
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife") // sideloaded app whose core job needs a long-lived connection
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

private fun requestAddTile(context: Context) {
    val statusBar = context.getSystemService(StatusBarManager::class.java)
    statusBar.requestAddTileService(
        ComponentName(context, SendTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor,
    ) { }
}

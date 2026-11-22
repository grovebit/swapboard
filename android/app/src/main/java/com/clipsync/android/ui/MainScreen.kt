package com.clipsync.android.ui

import android.content.Intent
import android.os.Build
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipsync.android.BuildConfig
import com.clipsync.android.R
import com.clipsync.android.clip.ClipboardReader
import com.clipsync.android.core.Pairing
import com.clipsync.android.core.TailnetPolicy
import com.clipsync.android.net.ConnState
import com.clipsync.android.net.TailscaleApp
import com.clipsync.android.service.ClipSyncService
import com.clipsync.android.service.Notifications
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
    val access = remember(refreshKey) { readBackgroundAccess(context) }
    val autoSyncOn by controller.autoSync.enabled.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("ClipSync") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(state, pairing, refreshKey)
            if (pairing != null && !(access.essentialsDone && access.tile)) {
                FinishSetupCard(access, onRefresh)
            }
            if (pairing != null) HowToSendCard()
            if (autoSyncOn) AutoSyncCard(refreshKey, paired = pairing != null)
            PairingCard(pairing, onUnpair = { confirmUnpair = true })
            AdvancedSection(refreshKey, paired = pairing != null, autoSyncOn = autoSyncOn)
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
private fun StatusCard(state: ConnState, pairing: Pairing?, refreshKey: Int) {
    val context = LocalContext.current
    val controller = context.syncController
    // Re-checked whenever the connection state changes or the app comes back to the front.
    val tailscaleUp = remember(state, refreshKey) { TailscaleApp.isConnected(context) }
    val tailscaleInstalled = remember(state, refreshKey) { TailscaleApp.isInstalled(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (pairing != null) "Paired with “${pairing.name}”" else "Not paired",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(Notifications.describe(context, state))
            if (pairing != null && state !is ConnState.Connected && !tailscaleUp) {
                Text(
                    if (tailscaleInstalled) "Tailscale is off on this phone. ClipSync needs it to reach your Mac."
                    else "Tailscale isn't installed on this phone. ClipSync needs it to reach your Mac.",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { TailscaleApp.open(context) }) {
                    Text(if (tailscaleInstalled) "Open Tailscale" else "Install Tailscale")
                }
            } else if (state is ConnState.Waiting && state.lastError != null) {
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
private fun FinishSetupCard(access: BackgroundAccess, onRefresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
            BackgroundAccessRows(access, onRefresh, showTile = true, onlyMissing = true)
        }
    }
}

@Composable
private fun HowToSendCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Send from this phone", style = MaterialTheme.typography.titleMedium)
            Text("Copy the text, then:", style = MaterialTheme.typography.bodyMedium)
            Bullet("tap “Send to Mac” in Quick Settings,")
            Bullet("or select text and tap “Send to Mac” in the menu,")
            Bullet("or tap Share → “Send to Mac”.")
            Text(
                "What you copy on your Mac arrives here by itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rarely needed options, folded away so the main screen stays simple. */
@Composable
private fun AdvancedSection(refreshKey: Int, paired: Boolean, autoSyncOn: Boolean) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide advanced options" else "Advanced options")
    }
    if (expanded) {
        if (!autoSyncOn) AutoSyncCard(refreshKey, paired)
        BatteryCard()
    }
    if (BuildConfig.PROJECT_URL.isNotEmpty()) {
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, (BuildConfig.PROJECT_URL + "#readme").toUri()))
            }
        }) { Text("Help: open the ClipSync guide") }
    }
}

/** Phone makers add their own background limits; Samsung gets exact steps, others a pointer. */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val maker = Build.MANUFACTURER.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Keep it running in the background", style = MaterialTheme.typography.titleMedium)
            if (maker.equals("samsung", ignoreCase = true)) {
                Text("Samsung can put background apps to sleep. For both ClipSync and Tailscale:", style = MaterialTheme.typography.bodyMedium)
                Text("1. Settings → Apps → (app) → Battery → Unrestricted", style = MaterialTheme.typography.bodyMedium)
                Text("2. Settings → Battery → Background usage limits → Never sleeping apps → add the app", style = MaterialTheme.typography.bodyMedium)
                Text("3. Turn off “Put unused apps to sleep” in the same screen, or keep both apps in the never-sleeping list.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Some phones stop background apps to save battery. Allow unrestricted battery use for " +
                        "both ClipSync and Tailscale. Don't Kill My App has the exact steps for your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = {
                    val page = maker.lowercase().replace(Regex("[^a-z0-9]"), "").ifEmpty { "" }
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://dontkillmyapp.com/$page".toUri()))
                    }
                }) { Text("Open dontkillmyapp.com") }
            }
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
                    else "ClipSync only connects through Tailscale, never over Wi-Fi. " +
                        "Your Mac's Tailscale address: ${tailscale.joinToString(", ")} (port ${pairing.port}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

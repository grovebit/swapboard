package com.clipsync.android.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.clipsync.android.R
import com.clipsync.android.data.SetupPrefs
import com.clipsync.android.net.TailscaleApp
import com.clipsync.android.syncController
import kotlinx.coroutines.delay

/** First-run setup: welcome → Tailscale → pair → background access → tile → done. */
@Composable
fun SetupScreen(refreshKey: Int, prefs: SetupPrefs, onRefresh: () -> Unit, onFinished: () -> Unit) {
    val context = LocalContext.current
    val pairing by context.syncController.pairingStore.pairing.collectAsStateWithLifecycle()
    var started by remember { mutableStateOf(prefs.started) }
    var permissionsReviewed by remember { mutableStateOf(prefs.permissionsReviewed) }
    var tileReviewed by remember { mutableStateOf(prefs.tileReviewed) }
    var tailscaleInstalled by remember { mutableStateOf(TailscaleApp.isInstalled(context)) }
    var tailscaleConnected by remember { mutableStateOf(TailscaleApp.isConnected(context)) }

    // Notice Tailscale being installed or connected while the user is in that app and comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tailscaleInstalled = TailscaleApp.isInstalled(context)
                tailscaleConnected = TailscaleApp.isConnected(context)
                delay(1_500)
            }
        }
    }

    val step = currentSetupStep(
        SetupProgress(
            started = started,
            tailscaleConnected = tailscaleConnected,
            paired = pairing != null,
            permissionsReviewed = permissionsReviewed,
            tileReviewed = tileReviewed,
        ),
    )

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                SetupStep.WELCOME -> WelcomeStep {
                    prefs.started = true
                    started = true
                }
                SetupStep.TAILSCALE -> TailscaleStep(installed = tailscaleInstalled)
                SetupStep.PAIR -> PairStep()
                SetupStep.PERMISSIONS -> PermissionsStep(refreshKey, onRefresh) {
                    prefs.permissionsReviewed = true
                    permissionsReviewed = true
                }
                SetupStep.TILE -> TileStep {
                    prefs.tileReviewed = true
                    tileReviewed = true
                }
                SetupStep.DONE -> DoneStep(onFinished)
            }
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Icon(
        painterResource(R.drawable.ic_tile),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(64.dp),
    )
    Text("Welcome to ClipSync", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Copy on your Mac, paste on your phone, and the other way around.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("Setup takes about 3 minutes. You'll need:", style = MaterialTheme.typography.bodyLarge)
    Bullet("your Mac, with the ClipSync app open")
    Bullet("the free Tailscale app on this phone (the next screen helps you get it)")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
}

@Composable
private fun TailscaleStep(installed: Boolean) {
    val context = LocalContext.current
    StepTitle(1, "Connect to Tailscale")
    Text(
        "ClipSync reaches your Mac through Tailscale, a free app that connects your own devices privately.",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (!installed) {
        Numbered(1, "Install Tailscale from Google Play.")
        Button(onClick = { TailscaleApp.openStore(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Install Tailscale")
        }
        Numbered(2, "Open Tailscale and sign in with the same account as on your Mac.")
        Numbered(3, "If your phone asks whether Tailscale may set up a VPN connection, tap OK.")
    } else {
        Numbered(1, "Open Tailscale and sign in with the same account as on your Mac.")
        Numbered(2, "Make sure Tailscale is switched on. If your phone asks about a VPN connection, tap OK.")
        Button(onClick = { TailscaleApp.open(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open Tailscale")
        }
    }
    Text(
        "Then come back here: this screen moves on by itself once Tailscale is connected.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairStep() {
    val context = LocalContext.current
    StepTitle(2, "Scan the code on your Mac")
    Text(
        "On your Mac, open the ClipSync app. Its window shows a code.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "Closed the window already? Click the ClipSync icon at the top of your Mac's screen and choose “Set Up a Phone”.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { context.startActivity(PairActivity.scanIntent(context)) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Scan code") }
    TextButton(onClick = { context.startActivity(Intent(context, PairActivity::class.java)) }) {
        Text("Can't scan? Paste a link instead")
    }
}

@Composable
private fun PermissionsStep(refreshKey: Int, onRefresh: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val access = remember(refreshKey) { readBackgroundAccess(context) }
    StepTitle(3, "Keep ClipSync running")
    Text(
        "So that what you copy on your Mac arrives even when you're not using this app:",
        style = MaterialTheme.typography.bodyLarge,
    )
    BackgroundAccessRows(access, onRefresh, showTile = false, onlyMissing = false)
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        Text(
            "Samsung tip: also add ClipSync and Tailscale to Settings → Battery → Background usage limits → Never sleeping apps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
        Text(if (access.essentialsDone) "Continue" else "Skip for now")
    }
}

@Composable
private fun TileStep(onContinue: () -> Unit) {
    val context = LocalContext.current
    StepTitle(4, "Add the “Send to Mac” button")
    Text(
        "The quickest way to send from this phone: copy something, swipe down from the top of the screen, and tap “Send to Mac”.",
        style = MaterialTheme.typography.bodyLarge,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Button(
            onClick = { requestAddTile(context) { added -> if (added) onContinue() } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add the button") }
        TextButton(onClick = onContinue) { Text("Skip") }
    } else {
        Numbered(1, "Swipe down from the top of the screen twice.")
        Numbered(2, "Tap the pencil (edit) icon.")
        Numbered(3, "Drag “Send to Mac” into your buttons.")
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text("You're all set!", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Copy anything on your Mac, and a moment later you can paste it here.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("To send from this phone, copy the text, then:", style = MaterialTheme.typography.bodyLarge)
    Bullet("tap “Send to Mac” in Quick Settings,")
    Bullet("or select text and tap “Send to Mac” in the menu,")
    Bullet("or tap Share → “Send to Mac”.")
    Spacer(Modifier.height(8.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Start using ClipSync") }
}

@Composable
private fun StepTitle(number: Int, title: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        "Step $number of 4",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(title, style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Numbered(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

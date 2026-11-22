package com.clipsync.android.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clipsync.android.R
import com.clipsync.android.core.Pairing
import com.clipsync.android.core.TailnetPolicy
import com.clipsync.android.core.PairingUri
import com.clipsync.android.syncController
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private sealed interface PairState {
    data object Entry : PairState
    data class Confirm(val pairing: Pairing) : PairState
    data class Error(val message: String) : PairState
}

/**
 * Pairs with a Mac from a `clipsync://pair` link: opened by a scanned QR code (VIEW intent),
 * the in-app scanner, or a pasted link. Always asks for confirmation, because any web page
 * can fire a clipsync:// link. FLAG_SECURE keeps the key-bearing screen out of screenshots.
 */
class PairActivity : ComponentActivity() {
    private var state by mutableStateOf<PairState>(PairState.Entry)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            ClipSyncTheme {
                PairScreen(
                    state = state,
                    existing = syncController.pairingStore.load(),
                    onScan = ::scan,
                    onPaste = { state = parse(it) },
                    onConfirm = ::confirm,
                    onCancel = { finish() },
                    onRetry = { state = PairState.Entry },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        state = parse(data)
        setIntent(Intent(this, PairActivity::class.java)) // don't keep the key in the intent
    }

    private fun parse(text: String): PairState = try {
        PairState.Confirm(PairingUri.parse(text))
    } catch (e: IllegalArgumentException) {
        PairState.Error(e.message ?: "Invalid pairing link")
    }

    private fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let { state = parse(it) } }
            .addOnFailureListener { e ->
                state = PairState.Error("Scanner unavailable (${e.message ?: "unknown error"}). Try again in a moment, or paste the link.")
            }
    }

    private fun confirm(pairing: Pairing) {
        syncController.pairingStore.save(pairing)
        syncController.onPairingChanged()
        Toast.makeText(this, getString(R.string.toast_paired, pairing.name), Toast.LENGTH_SHORT).show()
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairScreen(
    state: PairState,
    existing: Pairing?,
    onScan: () -> Unit,
    onPaste: (String) -> Unit,
    onConfirm: (Pairing) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Pair with a Mac") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                PairState.Entry -> EntryContent(onScan, onPaste)
                is PairState.Confirm -> ConfirmContent(state.pairing, existing, onConfirm, onCancel)
                is PairState.Error -> {
                    Text("Couldn't use that pairing link", style = MaterialTheme.typography.titleMedium)
                    Text(state.message)
                    Button(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun EntryContent(onScan: () -> Unit, onPaste: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    Text("On your Mac, open Alfred, type “clip” and choose “Pair phone”. A QR code appears.")
    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan QR code") }
    Text("Or paste the pairing link:", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        label = { Text("clipsync://pair?…") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { onPaste(link) }, enabled = link.isNotBlank()) { Text("Continue") }
}

@Composable
private fun ConfirmContent(pairing: Pairing, existing: Pairing?, onConfirm: (Pairing) -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pair with “${pairing.name}”?", style = MaterialTheme.typography.titleLarge)
            val tailscale = TailnetPolicy.allowedHosts(pairing.hosts)
            if (tailscale.isEmpty()) {
                Text(
                    "This code has no Tailscale address. ClipSync only syncs over Tailscale: turn on " +
                        "Tailscale on the Mac, then show a new QR code.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text("Tailscale address: ${tailscale.joinToString(", ")}")
            }
            Text("Port: ${pairing.port}")
            Text(
                "Only continue if you just showed this code on your own Mac. Clips will be sent to and from this Mac.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (existing != null && existing.pid != pairing.pid) {
                Text(
                    "This replaces your current pairing with “${existing.name}”.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { onConfirm(pairing) }, enabled = TailnetPolicy.allowedHosts(pairing.hosts).isNotEmpty()) {
            Text("Pair")
        }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

package com.clipsync.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.clipsync.android.service.ClipSyncService
import com.clipsync.android.syncController

class MainActivity : ComponentActivity() {
    /** Bumped on resume so permission/battery states are re-read after visiting Settings. */
    private var refreshKey by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (syncController.pairingStore.load() != null) ClipSyncService.start(this)
        setContent {
            ClipSyncTheme {
                MainScreen(refreshKey = refreshKey, onRefresh = { refreshKey++ })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshKey++
    }
}

@Composable
fun ClipSyncTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

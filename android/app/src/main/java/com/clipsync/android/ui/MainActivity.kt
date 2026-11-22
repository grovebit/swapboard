package com.clipsync.android.ui

import android.os.Bundle
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import android.os.Build
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.clipsync.android.data.SetupPrefs
import com.clipsync.android.service.ClipSyncService
import com.clipsync.android.syncController

class MainActivity : ComponentActivity() {
    /** Bumped on resume so permission/battery states are re-read after visiting Settings. */
    private var refreshKey by mutableIntStateOf(0)
    private var setupDone by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val paired = syncController.pairingStore.load() != null
        val setup = SetupPrefs(this)
        setup.decideOnce(alreadyPaired = paired)
        setupDone = setup.done
        if (paired) ClipSyncService.start(this)
        setContent {
            ClipSyncTheme {
                if (setupDone) {
                    MainScreen(refreshKey = refreshKey, onRefresh = { refreshKey++ })
                } else {
                    SetupScreen(
                        refreshKey = refreshKey,
                        prefs = setup,
                        onRefresh = { refreshKey++ },
                        onFinished = {
                            setup.done = true
                            setupDone = true
                        },
                    )
                }
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
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

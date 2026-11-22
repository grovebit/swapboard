package com.clipsync.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.clipsync.android.R
import com.clipsync.android.clip.ClipboardReader
import com.clipsync.android.syncController

/**
 * Translucent, invisible activity used by the tile, the notification actions and the launcher
 * shortcut. Android only lets an app read the clipboard while one of its windows has focus, so
 * it reads in [onWindowFocusChanged], hands the text to the sync controller and finishes.
 */
class SendClipboardActivity : Activity() {
    private var handled = false
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        if (!handled) {
            handled = true
            syncController.toast(R.string.toast_cannot_read)
            finishQuietly()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handler.postDelayed(giveUp, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        handler.removeCallbacks(giveUp)
        val clip = ClipboardReader.read(this)
        if (clip == null) {
            syncController.toast(R.string.toast_clipboard_empty)
        } else {
            val sensitive = clip.sensitive || intent.getBooleanExtra(EXTRA_SENSITIVE, false)
            syncController.sendFromUser(clip.text, sensitive, skipIfEcho = true)
        }
        finishQuietly()
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private fun finishQuietly() {
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        const val EXTRA_SENSITIVE = "com.clipsync.android.extra.SENSITIVE"
        private const val FOCUS_TIMEOUT_MS = 2_000L

        fun intent(context: Context, sensitive: Boolean): Intent =
            Intent(context, SendClipboardActivity::class.java)
                .setAction(if (sensitive) "com.clipsync.android.SEND_SENSITIVE" else "com.clipsync.android.SEND")
                .putExtra(EXTRA_SENSITIVE, sensitive)
    }
}

/**
 * "Send to Mac" in the share sheet (ACTION_SEND text/plain) and the text-selection menu
 * (ACTION_PROCESS_TEXT). The `.ui.ShareToMacSensitive` alias sends with sensitive=true.
 * These are explicit choices, so the echo check is skipped.
 */
class ShareToMacActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sensitive = intent.component?.className?.endsWith("Sensitive") == true
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (text.isNullOrEmpty()) {
            syncController.toast(R.string.toast_nothing_to_send)
        } else {
            syncController.sendFromUser(text, sensitive, skipIfEcho = false)
        }
        setResult(RESULT_CANCELED) // never replace the selected text
        finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}

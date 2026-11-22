package app.swapboard.android.clip

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Writes clips received from the Mac. Allowed from the background (only reads are restricted). */
class ClipboardWriter(private val context: Context) {
    /** Must be called on the main thread. */
    fun write(text: String, sensitive: Boolean) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(LABEL, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    companion object {
        const val LABEL = "Swapboard"
    }
}

/** Reads the clipboard. Only works while one of our activities has window focus (Android 10+). */
object ClipboardReader {
    data class Result(val text: String, val sensitive: Boolean)

    fun read(context: Context): Result? {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        if (text.isNullOrEmpty()) return null
        val sensitive = clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        return Result(text, sensitive)
    }
}

package com.clipsync.android.shizuku

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.IOnPrimaryClipChangedListener
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's user-service process as the shell user (uid 2000), never in the app.
 * The shell package holds READ_CLIPBOARD_IN_BACKGROUND, so this process may watch and read the
 * clipboard while ClipSync is in the background. It talks to the system clipboard service through
 * its hidden binder interface (parameters differ between Android versions, so calls are built by
 * reflection) and reports every change to the app through [IClipCallback].
 */
class ClipWatcherService() : IClipWatcher.Stub() {
    /** Shizuku may construct user services with a Context. */
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val executor = Executors.newSingleThreadScheduledExecutor()

    // Fields below are only touched on [executor].
    private var callback: IClipCallback? = null
    private var listenerRegistered = false
    private var pending: ScheduledFuture<*>? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var lastFingerprint: Long? = null

    private val clipboard: Any by lazy {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "clipboard") as IBinder
        Class.forName("android.content.IClipboard\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
    }

    private val listener = object : IOnPrimaryClipChangedListener.Stub() {
        override fun dispatchPrimaryClipChanged() {
            executor.execute {
                // Coalesce bursts: some apps set the clipboard several times for one copy.
                pending?.cancel(false)
                pending = executor.schedule({ readAndReport() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun start(callback: IClipCallback) {
        executor.execute {
            this.callback = callback
            if (Process.myUid() != SHELL_UID && Process.myUid() != ROOT_UID) {
                report("helper is not running as shell (uid ${Process.myUid()})")
                return@execute
            }
            // Only report future copies, not whatever is on the clipboard right now.
            lastFingerprint = runCatching { fingerprint(readClip()) }.getOrNull()
            if (!listenerRegistered) {
                listenerRegistered = runCatching { call("addPrimaryClipChangedListener", listener) }
                    .onFailure { report("clipboard listener unavailable (${rootCause(it)}); polling instead") }
                    .isSuccess
            }
            if (!listenerRegistered && pollTask == null) {
                pollTask = executor.scheduleWithFixedDelay({ readAndReport() }, 1, 1, TimeUnit.SECONDS)
            }
            report(if (listenerRegistered) "watching the clipboard" else "checking the clipboard every second")
        }
    }

    override fun stop() {
        executor.execute { stopWatching() }
    }

    override fun destroy() {
        executor.execute {
            stopWatching()
            exitProcess(0)
        }
    }

    private fun stopWatching() {
        callback = null
        pending?.cancel(false)
        pollTask?.cancel(false)
        pollTask = null
        if (listenerRegistered) {
            runCatching { call("removePrimaryClipChangedListener", listener) }
            listenerRegistered = false
        }
    }

    private fun readAndReport() {
        val cb = callback ?: return
        val clip = try {
            readClip()
        } catch (e: Exception) {
            report("could not read the clipboard: ${rootCause(e)}")
            return
        } ?: return
        val fingerprint = fingerprint(clip)
        if (fingerprint == lastFingerprint) return
        lastFingerprint = fingerprint
        val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text.isNullOrEmpty()) return // text only; images and files are ignored
        val description = clip.description
        val sensitive = description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
        try {
            cb.onClip(text, sensitive, description?.label?.toString())
        } catch (e: RemoteException) {
            callback = null // the app went away; Shizuku will tear this process down
        }
    }

    private fun readClip(): ClipData? = call("getPrimaryClip") as ClipData?

    private fun fingerprint(clip: ClipData?): Long? {
        if (clip == null || clip.itemCount == 0) return null
        val text = clip.getItemAt(0).text?.toString() ?: return null
        return (clip.description?.timestamp ?: 0L) * 31 + text.hashCode()
    }

    /**
     * Calls an IClipboard method, filling in the version-specific parameters: calling package
     * (shell), attribution tag (null), user id and device id (both 0 = owner / default device).
     */
    private fun call(name: String, listenerArg: Any? = null): Any? {
        val method = clipboard.javaClass.methods
            .filter { it.name == name }
            .maxByOrNull { it.parameterTypes.size }
            ?: throw NoSuchMethodException("IClipboard.$name")
        var strings = 0
        val args = method.parameterTypes.map { type ->
            when {
                listenerArg != null && type.isInstance(listenerArg) -> listenerArg
                type == String::class.java -> if (strings++ == 0) SHELL_PACKAGE else null
                type == Int::class.javaPrimitiveType -> 0
                type == Long::class.javaPrimitiveType -> 0L
                type == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }
        return method.invoke(clipboard, *args.toTypedArray())
    }

    private fun report(message: String) {
        Log.i(TAG, "clip watcher: $message")
        runCatching { callback?.onStatus(message) }
    }

    private fun rootCause(t: Throwable): String {
        var cause = t
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private companion object {
        const val TAG = "ClipSync"
        const val SHELL_PACKAGE = "com.android.shell"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val DEBOUNCE_MS = 150L
    }
}

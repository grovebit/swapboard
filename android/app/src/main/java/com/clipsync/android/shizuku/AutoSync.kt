package com.clipsync.android.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.clipsync.android.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Optional automatic phone → Mac sync. Android doesn't let normal apps read the clipboard in the
 * background, so this runs [ClipWatcherService] as the shell user through Shizuku.
 * **Off by default**: nothing happens (and Shizuku is never asked for permission) until the user
 * turns the switch on. Main-thread only, except [onClip] which arrives on a binder thread.
 */
class AutoSync(
    private val context: Context,
    private val onClip: (text: String, sensitive: Boolean, label: String?) -> Unit,
    private val log: (String) -> Unit,
) {
    sealed interface Status {
        data object Off : Status
        data object ShizukuNotInstalled : Status
        data object ShizukuNotRunning : Status
        data object PermissionNeeded : Status
        data object Starting : Status
        data class Active(val detail: String) : Status
        data class Failed(val message: String) : Status
    }

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** True while the sync service is running (i.e. paired); the helper only runs then. */
    private var running = false
    private var listenersAdded = false
    private var bound = false
    @Volatile private var watcher: IClipWatcher? = null

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ClipWatcherService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("clipwatcher")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val callback = object : IClipCallback.Stub() {
        override fun onClip(text: String?, sensitive: Boolean, label: String?) {
            if (!text.isNullOrEmpty()) this@AutoSync.onClip(text, sensitive, label)
        }

        override fun onStatus(message: String?) {
            log("auto-sync: $message")
            if (watcher != null && message != null) _status.value = Status.Active(message)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.takeIf { it.pingBinder() }?.let { IClipWatcher.Stub.asInterface(it) }
            if (service == null) {
                _status.value = Status.Failed("the Shizuku helper did not start")
                return
            }
            watcher = service
            runCatching { service.start(callback) }
                .onSuccess { _status.value = Status.Active("starting the clipboard watcher") }
                .onFailure { _status.value = Status.Failed("helper error: ${it.message}") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            watcher = null
            bound = false
            log("auto-sync: helper disconnected")
            refresh()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        watcher = null
        bound = false
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST) refresh()
    }

    /** Called when the sync service starts. */
    fun start() {
        running = true
        addListeners()
        refresh()
    }

    /** Called when the sync service stops. */
    fun stop() {
        running = false
        unbind()
        refresh()
    }

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        addListeners()
        if (on && shizukuAlive() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            requestPermission()
        }
        refresh()
    }

    fun requestPermission() {
        if (shizukuAlive()) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    /** Re-reads Shizuku's state and starts or stops the helper to match the switch. */
    fun refresh() {
        val blocked: Status? = when {
            !isShizukuInstalled() -> Status.ShizukuNotInstalled
            !shizukuAlive() -> Status.ShizukuNotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.PermissionNeeded
            !_enabled.value || !running -> Status.Off
            else -> null
        }
        if (blocked != null) {
            unbind()
            _status.value = blocked
            return
        }
        if (!bound) {
            _status.value = Status.Starting
            runCatching {
                Shizuku.bindUserService(serviceArgs, connection)
                bound = true
            }.onFailure { _status.value = Status.Failed("could not start the Shizuku helper: ${it.message}") }
        }
    }

    private fun unbind() {
        runCatching { watcher?.stop() }
        watcher = null
        if (bound) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
            bound = false
        }
    }

    private fun addListeners() {
        if (listenersAdded) return
        listenersAdded = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    private fun shizukuAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private companion object {
        const val KEY_ENABLED = "auto_send_from_phone"
        const val PERMISSION_REQUEST = 4242
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}

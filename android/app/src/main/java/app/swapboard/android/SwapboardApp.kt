package app.swapboard.android

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.swapboard.android.clip.AutoSendFilter
import app.swapboard.android.clip.ClipboardWriter
import app.swapboard.android.clip.EchoGuard
import app.swapboard.android.core.Protocol
import app.swapboard.android.data.PairingStore
import app.swapboard.android.net.ConnState
import app.swapboard.android.net.ConnectionManager
import app.swapboard.android.net.OkHttpWsTransport
import app.swapboard.android.net.TailscaleNetwork
import app.swapboard.android.net.SendOutcome
import app.swapboard.android.service.SwapboardService
import app.swapboard.android.service.Notifications
import app.swapboard.android.shizuku.AutoSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class SwapboardApp : Application() {
    lateinit var controller: SyncController
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        controller = SyncController(this)
    }
}

val Context.syncController: SyncController
    get() = (applicationContext as SwapboardApp).controller

/** App-wide wiring: pairing store, connection, echo guard, and user-facing send. */
class SyncController(private val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pairingStore = PairingStore(app)
    private val echoGuard = EchoGuard()
    private val writer = ClipboardWriter(app)
    private val autoFilter = AutoSendFilter(echoGuard)

    val connection = ConnectionManager(
        scope = scope,
        transport = OkHttpWsTransport(socketFactoryFor = TailscaleNetwork(app)::socketFactoryFor),
        pairingProvider = { pairingStore.load() },
        deviceName = { deviceName(app) },
        onIncomingClip = { clip ->
            echoGuard.recordIncoming(clip.text)
            withContext(Dispatchers.Main) { writer.write(clip.text, clip.sensitive) }
        },
        log = { Log.i(TAG, it) },
    )

    val state: StateFlow<ConnState> get() = connection.state

    /** Optional automatic phone → Mac sync through Shizuku (off unless the user enables it). */
    val autoSync = AutoSync(
        context = app,
        onClip = { text, sensitive, label -> sendAutomatic(text, sensitive, label) },
        log = { Log.i(TAG, it) },
    )

    /** A clipboard change seen by automatic sync. Quiet: no toasts. */
    fun sendAutomatic(text: String, sensitive: Boolean, label: String?) {
        if (pairingStore.load() == null) return
        when (val decision = autoFilter.check(text, sensitive, label)) {
            AutoSendFilter.Decision.SEND -> connection.send(text, sensitive = false)
            else -> Log.i(TAG, "auto-sync skipped a copy (${decision.name.lowercase()})")
        }
    }

    /** Called after pairing or unpairing. */
    fun onPairingChanged() {
        if (pairingStore.load() == null) {
            SwapboardService.stop(app)
            connection.stop()
        } else {
            connection.restart()
            SwapboardService.start(app)
        }
    }

    /**
     * Sends text chosen by the user. [skipIfEcho] is set for one-tap clipboard sends, so text
     * that just arrived from the Mac isn't sent straight back.
     */
    fun sendFromUser(text: String, sensitive: Boolean, skipIfEcho: Boolean) {
        when {
            pairingStore.load() == null -> return toast(R.string.toast_not_paired)
            text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT_BYTES -> return toast(R.string.toast_too_large)
            skipIfEcho && echoGuard.isEcho(text) -> return toast(R.string.toast_already_on_mac)
        }
        SwapboardService.start(app)
        val id = UUID.randomUUID().toString()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val outcome = withTimeoutOrNull(ACK_WAIT_MS) { connection.outcomes.first { it.id == id } }
            toast(
                when (outcome) {
                    is SendOutcome.Delivered -> R.string.toast_sent
                    is SendOutcome.Expired -> R.string.toast_expired
                    null -> R.string.toast_queued
                },
            )
        }
        connection.send(text, sensitive, id)
    }

    fun toast(resId: Int) {
        scope.launch(Dispatchers.Main) { Toast.makeText(app, resId, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val TAG = "Swapboard"
        private const val ACK_WAIT_MS = 3_000L

        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
                ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}

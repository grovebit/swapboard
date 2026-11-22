package app.swapboard.android.net

import app.swapboard.android.core.Ack
import app.swapboard.android.core.Auth
import app.swapboard.android.core.ClientSession
import app.swapboard.android.core.Clip
import app.swapboard.android.core.Message
import app.swapboard.android.core.Pairing
import app.swapboard.android.core.Ping
import app.swapboard.android.core.Pong
import app.swapboard.android.core.Protocol
import app.swapboard.android.core.ProtocolException
import app.swapboard.android.core.TailnetPolicy
import app.swapboard.android.core.Welcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Connection status, shown in the notification, tile and main screen. */
sealed interface ConnState {
    data object Stopped : ConnState
    data object NotPaired : ConnState
    data class Connecting(val host: String) : ConnState
    data class Connected(val host: String, val macName: String) : ConnState
    data class Waiting(val retryAtMs: Long, val lastError: String?) : ConnState
    data class RePairNeeded(val reason: String) : ConnState
}

/** What happened to a clip queued with [ConnectionManager.send]. */
sealed interface SendOutcome {
    val id: String

    data class Delivered(override val id: String) : SendOutcome
    data class Expired(override val id: String) : SendOutcome
}

/**
 * Keeps one authenticated WebSocket session to the paired Mac (PROTOCOL.md).
 * Tries the pairing's Tailscale addresses in order (never LAN addresses), backs off 1 s → 60 s with jitter, retries at once on [kick],
 * pings every 30 s and drops the connection after 75 s of silence. Holds only the latest
 * outgoing clip (5-minute TTL) and resends it after reconnecting until it is acked.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val transport: WsTransport,
    private val pairingProvider: () -> Pairing?,
    private val deviceName: () -> String,
    private val onIncomingClip: suspend (Clip) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Stopped)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _outcomes = MutableSharedFlow<SendOutcome>(extraBufferCapacity = 32)
    val outcomes: SharedFlow<SendOutcome> = _outcomes.asSharedFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    private val lock = Any()
    private var live: Live? = null // guarded by lock
    private var pending: PendingClip? = null // guarded by lock
    private val seenIds = ArrayDeque<String>() // guarded by lock

    private class Live(val session: ClientSession, val connection: WsConnection)
    private class PendingClip(val clip: Clip, val createdAt: Long)

    private sealed interface SessionResult {
        val description: String

        data class ConnectFailed(override val description: String) : SessionResult
        data class AuthFailed(override val description: String) : SessionResult
        data class Ended(val code: Int, val wasEstablished: Boolean, override val description: String) : SessionResult
    }

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(lock) {
            live?.connection?.close(Protocol.CLOSE_NORMAL, "stopped")
            live = null
        }
        _state.value = ConnState.Stopped
    }

    fun restart() {
        stop()
        start()
    }

    /** Retry now (network change, screen on). If connected, a ping checks the link is still alive. */
    fun kick() {
        if (!sendMessage(Ping)) wake.trySend(Unit)
    }

    /** True while [id] is still waiting for an ack. */
    fun isPending(id: String): Boolean = synchronized(lock) { pending?.clip?.id == id }

    /**
     * Queues [text] for the Mac, replacing any older unacknowledged clip, and sends it now if
     * connected. Watch [outcomes] for [SendOutcome.Delivered] with the same [id].
     */
    fun send(text: String, sensitive: Boolean, id: String = UUID.randomUUID().toString()): String {
        val clip = Clip(id = id, text = text, sensitive = sensitive, ts = clock())
        val connected = synchronized(lock) {
            pending = PendingClip(clip, clock())
            live?.let { transmitLocked(it, clip) } ?: false
        }
        if (!connected) wake.trySend(Unit)
        return id
    }

    private suspend fun runLoop() {
        var backoffMs = MIN_BACKOFF_MS
        while (coroutineContext.isActive) {
            val pairing = pairingProvider()
            if (pairing == null) {
                _state.value = ConnState.NotPaired
                wake.receive()
                continue
            }
            // Tailscale only: LAN addresses in older pairing links are never tried.
            val hosts = TailnetPolicy.allowedHosts(pairing.hosts)
            if (hosts.isEmpty()) {
                log("pairing has no Tailscale address; not connecting over other networks")
                _state.value = ConnState.RePairNeeded("This pairing has no Tailscale address. Turn on Tailscale on the Mac and pair again.")
                return
            }
            var result: SessionResult = SessionResult.ConnectFailed("no hosts")
            for (host in hosts) {
                _state.value = ConnState.Connecting(host)
                result = runSession(pairing, host)
                if (result !is SessionResult.ConnectFailed) break
                log("connect to $host failed: ${result.description}")
            }
            when (val r = result) {
                is SessionResult.AuthFailed -> {
                    log("authentication failed: ${r.description}")
                    _state.value = ConnState.RePairNeeded(r.description)
                    return // wait for restart() after re-pairing
                }
                is SessionResult.Ended -> {
                    log("session ended (${r.code}): ${r.description}")
                    if (r.wasEstablished) backoffMs = MIN_BACKOFF_MS
                    if (r.code == Protocol.CLOSE_REPLACED) backoffMs = maxOf(backoffMs, REPLACED_BACKOFF_MS)
                }
                is SessionResult.ConnectFailed -> Unit
            }
            val delayMs = (backoffMs * (0.8 + Random.nextDouble() * 0.4)).toLong()
            _state.value = ConnState.Waiting(clock() + delayMs, result.description)
            val woken = withTimeoutOrNull(delayMs) { wake.receive() } != null
            backoffMs = if (woken) MIN_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun runSession(pairing: Pairing, host: String): SessionResult = coroutineScope {
        val connection = transport.open("ws://${formatHost(host)}:${pairing.port}/")
        val session = ClientSession(pairing, deviceName())
        var established = false
        var finished = false
        val lastInbound = AtomicLong(clock())
        val lastPing = AtomicLong(clock())
        var watchdog: Job? = null
        try {
            val first = withTimeoutOrNull(OPEN_TIMEOUT_MS) { connection.events.receiveCatching().getOrNull() }
            if (first != WsEvent.Opened) {
                finished = first != null
                connection.cancel()
                return@coroutineScope SessionResult.ConnectFailed(describe(first) ?: "timed out")
            }
            connection.sendText(session.start())
            val openedAt = clock()
            watchdog = launch {
                while (isActive) {
                    delay(WATCHDOG_TICK_MS)
                    val now = clock()
                    if (!session.isEstablished) {
                        if (now - openedAt > HANDSHAKE_TIMEOUT_MS) {
                            log("handshake timed out")
                            connection.cancel()
                            break
                        }
                    } else {
                        if (now - lastInbound.get() > Protocol.DEAD_AFTER_MS) {
                            log("no traffic for ${Protocol.DEAD_AFTER_MS / 1000}s; dropping connection")
                            connection.cancel()
                            break
                        }
                        if (now - lastPing.get() >= Protocol.PING_INTERVAL_MS) {
                            lastPing.set(now)
                            sendMessage(Ping)
                        }
                    }
                }
            }
            for (event in connection.events) {
                when (event) {
                    WsEvent.Opened -> Unit
                    is WsEvent.Text -> {
                        lastInbound.set(clock())
                        connection.sendBinary(session.onText(event.text))
                    }
                    is WsEvent.Binary -> {
                        lastInbound.set(clock())
                        val message = session.onBinary(event.bytes)
                        if (!established && session.isEstablished) {
                            established = true
                            onEstablished(session, connection, host, pairing)
                        } else if (message != null) {
                            handle(message)
                        }
                    }
                    is WsEvent.Closed -> {
                        finished = true
                        return@coroutineScope if (event.code == Protocol.CLOSE_AUTH && !established) {
                            SessionResult.AuthFailed("Mac rejected this phone (${event.reason.ifBlank { "4001" }})")
                        } else {
                            SessionResult.Ended(event.code, established, "closed by Mac: ${event.code} ${event.reason}")
                        }
                    }
                    is WsEvent.Failed -> {
                        finished = true
                        return@coroutineScope SessionResult.Ended(-1, established, describe(event) ?: "connection failed")
                    }
                }
            }
            finished = true
            SessionResult.Ended(-1, established, "connection ended")
        } catch (e: ProtocolException) {
            finished = true
            connection.close(e.closeCode, e.message ?: "protocol error")
            if (e.closeCode == Protocol.CLOSE_AUTH && !established) {
                SessionResult.AuthFailed(e.message ?: "authentication failed")
            } else {
                SessionResult.Ended(e.closeCode, established, e.message ?: "protocol error")
            }
        } finally {
            watchdog?.cancel()
            synchronized(lock) { if (live?.connection === connection) live = null }
            if (!finished) connection.close(Protocol.CLOSE_NORMAL, "bye")
            connection.events.cancel()
        }
    }

    private fun onEstablished(session: ClientSession, connection: WsConnection, host: String, pairing: Pairing) {
        var expired: String? = null
        synchronized(lock) {
            val l = Live(session, connection)
            live = l
            val p = pending
            if (p != null) {
                if (clock() - p.createdAt <= Protocol.PENDING_TTL_MS) {
                    transmitLocked(l, p.clip)
                } else {
                    pending = null
                    expired = p.clip.id
                }
            }
        }
        expired?.let { _outcomes.tryEmit(SendOutcome.Expired(it)) }
        _state.value = ConnState.Connected(host, session.macName ?: pairing.name)
        log("connected to ${session.macName} via $host")
    }

    private suspend fun handle(message: Message) {
        when (message) {
            is Clip -> {
                val duplicate = synchronized(lock) {
                    if (message.id in seenIds) {
                        true
                    } else {
                        seenIds.addLast(message.id)
                        while (seenIds.size > Protocol.DEDUPE_IDS) seenIds.removeFirst()
                        false
                    }
                }
                if (!duplicate && message.text.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_TEXT_BYTES) {
                    runCatching { onIncomingClip(message) }.onFailure { log("applying clip failed: $it") }
                }
                sendMessage(Ack(message.id))
            }
            is Ack -> {
                val delivered = synchronized(lock) {
                    val p = pending
                    if (p != null && p.clip.id == message.id) {
                        pending = null
                        true
                    } else {
                        false
                    }
                }
                if (delivered) _outcomes.tryEmit(SendOutcome.Delivered(message.id))
            }
            Ping -> sendMessage(Pong)
            Pong, is Welcome, is Auth -> Unit
        }
    }

    /** Seals and sends under [lock] so frame counters go out in order. */
    private fun sendMessage(message: Message): Boolean = synchronized(lock) {
        val l = live ?: return false
        runCatching { l.connection.sendBinary(l.session.seal(message)) }.getOrDefault(false)
    }

    private fun transmitLocked(l: Live, clip: Clip): Boolean =
        runCatching { l.connection.sendBinary(l.session.seal(clip)) }.getOrDefault(false)

    private fun describe(event: WsEvent?): String? = when (event) {
        null -> null
        is WsEvent.Failed -> event.error.message ?: event.error.javaClass.simpleName
        is WsEvent.Closed -> "closed ${event.code} ${event.reason}"
        else -> event.toString()
    }

    private fun formatHost(host: String): String =
        if (':' in host && !host.startsWith("[")) "[$host]" else host

    companion object {
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val REPLACED_BACKOFF_MS = 10_000L
        const val OPEN_TIMEOUT_MS = 8_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val WATCHDOG_TICK_MS = 5_000L
    }
}

package app.swapboard.android.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** Events from one WebSocket connection. The channel closes after [Closed] or [Failed]. */
sealed interface WsEvent {
    data object Opened : WsEvent
    data class Text(val text: String) : WsEvent
    class Binary(val bytes: ByteArray) : WsEvent
    data class Closed(val code: Int, val reason: String) : WsEvent
    data class Failed(val error: Throwable) : WsEvent
}

/** One WebSocket connection attempt; sends made before it opens are queued. */
interface WsConnection {
    val events: ReceiveChannel<WsEvent>
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    fun close(code: Int, reason: String)
    fun cancel()
}

fun interface WsTransport {
    fun open(url: String): WsConnection
}

/**
 * OkHttp-backed transport. OkHttp's own pings are off; the protocol has app-level ping/pong.
 * [socketFactoryFor] returns a factory whose sockets are bound to the Tailscale VPN network (or
 * null when Tailscale is off, in which case nothing is attempted over Wi-Fi or mobile data).
 */
class OkHttpWsTransport(
    connectTimeoutMs: Long = 4_000,
    private val socketFactoryFor: (host: String) -> SocketFactory?,
) : WsTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    override fun open(url: String): WsConnection {
        val channel = Channel<WsEvent>(Channel.UNLIMITED)
        val host = runCatching { URI(url).host }.getOrNull()
        val socketFactory = host?.let(socketFactoryFor)
        if (socketFactory == null) {
            channel.trySend(WsEvent.Failed(IOException("Tailscale isn't connected on this phone")))
            channel.close()
            return object : WsConnection {
                override val events: ReceiveChannel<WsEvent> = channel
                override fun sendText(text: String) = false
                override fun sendBinary(bytes: ByteArray) = false
                override fun close(code: Int, reason: String) = Unit
                override fun cancel() = Unit
            }
        }
        val webSocket = client.newBuilder().socketFactory(socketFactory).build().newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    channel.trySend(WsEvent.Opened)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    channel.trySend(WsEvent.Text(text))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    channel.trySend(WsEvent.Binary(bytes.toByteArray()))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runCatching { webSocket.close(code, null) }
                    channel.trySend(WsEvent.Closed(code, reason))
                    channel.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    channel.trySend(WsEvent.Closed(code, reason))
                    channel.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.trySend(WsEvent.Failed(t))
                    channel.close()
                }
            },
        )
        return object : WsConnection {
            override val events: ReceiveChannel<WsEvent> = channel
            override fun sendText(text: String): Boolean = webSocket.send(text)
            override fun sendBinary(bytes: ByteArray): Boolean = webSocket.send(bytes.toByteString())
            override fun close(code: Int, reason: String) {
                runCatching { webSocket.close(code, reason.take(100)) }
            }
            override fun cancel() = webSocket.cancel()
        }
    }
}

package app.swapboard.android.net

import app.swapboard.android.core.Ack
import app.swapboard.android.core.Clip
import app.swapboard.android.core.Pairing
import app.swapboard.android.core.Ping
import app.swapboard.android.core.Pong
import app.swapboard.android.core.Quiet
import app.swapboard.android.core.TestServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {
    private val pairing = Pairing("pid1", "Mac", ByteArray(32) { it.toByte() }, listOf("100.64.0.1", "192.168.1.20", "mac.local", "100.64.0.2"), 48613)

    private class FakeConnection(val url: String) : WsConnection {
        val incoming = Channel<WsEvent>(Channel.UNLIMITED)
        val sentText = Channel<String>(Channel.UNLIMITED)
        val sentBinary = Channel<ByteArray>(Channel.UNLIMITED)
        override val events: ReceiveChannel<WsEvent> = incoming
        override fun sendText(text: String): Boolean = sentText.trySend(text).isSuccess
        override fun sendBinary(bytes: ByteArray): Boolean = sentBinary.trySend(bytes).isSuccess
        override fun close(code: Int, reason: String) {
            incoming.trySend(WsEvent.Closed(code, reason))
            incoming.close()
        }
        override fun cancel() {
            incoming.trySend(WsEvent.Failed(IOException("cancelled")))
            incoming.close()
        }
    }

    private class Harness(scope: TestScope, pairing: Pairing) {
        val connections = Channel<FakeConnection>(Channel.UNLIMITED)
        val received = mutableListOf<Clip>()
        val manager = ConnectionManager(
            scope = scope.backgroundScope,
            transport = WsTransport { url -> FakeConnection(url).also { connections.trySend(it) } },
            pairingProvider = { pairing },
            deviceName = { "Phone" },
            onIncomingClip = { received += it },
            clock = { scope.testScheduler.currentTime },
        )

        /** Accepts the next connection and completes the handshake as the Mac. */
        suspend fun connect(server: TestServer): FakeConnection {
            val conn = connections.receive()
            conn.incoming.send(WsEvent.Opened)
            conn.incoming.send(WsEvent.Text(server.onHello(conn.sentText.receive())))
            conn.incoming.send(WsEvent.Binary(server.onAuth(conn.sentBinary.receive())))
            manager.state.first { it is ConnState.Connected }
            return conn
        }
    }

    @Test
    fun exchangesClipsAcksAndPings() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        assertEquals("ws://100.64.0.1:48613/", conn.url)
        assertEquals("Phone", server.deviceName)

        // Mac → phone: applied once, acked every time.
        conn.incoming.send(WsEvent.Binary(server.seal(Clip("c1", "hello", false, 1))))
        assertEquals(Ack("c1"), server.open(conn.sentBinary.receive()))
        conn.incoming.send(WsEvent.Binary(server.seal(Clip("c1", "hello", false, 1))))
        assertEquals(Ack("c1"), server.open(conn.sentBinary.receive()))
        assertEquals(listOf("hello"), h.received.map { it.text })

        // Phone → Mac, delivered once the Mac acks.
        val outcome = async(start = CoroutineStart.UNDISPATCHED) { h.manager.outcomes.first() }
        val id = h.manager.send("to mac", sensitive = true)
        val sent = server.open(conn.sentBinary.receive()) as Clip
        assertEquals(id, sent.id)
        assertEquals("to mac", sent.text)
        assertTrue(sent.sensitive)
        assertTrue(h.manager.isPending(id))
        conn.incoming.send(WsEvent.Binary(server.seal(Ack(id))))
        assertEquals(SendOutcome.Delivered(id), outcome.await())
        assertFalse(h.manager.isPending(id))

        // The Mac pings, the phone answers.
        conn.incoming.send(WsEvent.Binary(server.seal(Ping)))
        assertEquals(Pong, server.open(conn.sentBinary.receive()))
        h.manager.stop()
    }

    @Test
    fun clipQueuedWhileOfflineIsSentAfterHandshake() = runTest {
        val h = Harness(this, pairing)
        val id = h.manager.send("queued", sensitive = false)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        val clip = server.open(conn.sentBinary.receive()) as Clip
        assertEquals(id, clip.id)
        assertEquals("queued", clip.text)
        h.manager.stop()
    }

    @Test
    fun triesNextHostWhenFirstFails() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val first = h.connections.receive()
        assertEquals("ws://100.64.0.1:48613/", first.url)
        first.incoming.send(WsEvent.Failed(IOException("connection refused")))
        first.incoming.close()
        val second = h.connections.receive()
        assertEquals("ws://100.64.0.2:48613/", second.url)
        h.manager.stop()
    }

    @Test
    fun rejectionBeforeWelcomeMeansRePairNeeded() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val conn = h.connections.receive()
        conn.incoming.send(WsEvent.Opened)
        conn.sentText.receive()
        conn.incoming.send(WsEvent.Closed(4001, "auth failed"))
        assertTrue(h.manager.state.first { it is ConnState.RePairNeeded } is ConnState.RePairNeeded)
        assertFalse(h.manager.isRunning)
    }

    @Test
    fun droppedSessionReconnects() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val conn = h.connect(TestServer(pairing.psk, "Mac"))
        conn.incoming.send(WsEvent.Closed(1000, "agent restarting"))
        conn.incoming.close()
        // After a short backoff the manager starts over with the first host.
        val again = h.connections.receive()
        assertEquals("ws://100.64.0.1:48613/", again.url)
        h.manager.stop()
    }

    @Test
    fun pairingWithoutTailscaleAddressNeverConnects() = runTest {
        val lanOnly = pairing.copy(hosts = listOf("192.168.1.20", "mac.local"))
        val h = Harness(this, lanOnly)
        h.manager.start()
        assertTrue(h.manager.state.first { it is ConnState.RePairNeeded } is ConnState.RePairNeeded)
        assertTrue("no connection may be attempted", h.connections.tryReceive().isFailure)
    }

    @Test
    fun quietModeTellsTheMacAndThenStaysSilent() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        h.manager.setQuiet(true)
        assertEquals(Quiet, server.open(conn.sentBinary.receive()))

        // An hour without traffic: no pings (kick() included), and the connection stays up.
        h.manager.kick()
        advanceTimeBy(HOUR_MS)
        runCurrent()
        assertTrue("nothing may be sent in quiet mode", conn.sentBinary.tryReceive().isFailure)
        assertTrue("the connection must not be replaced", h.connections.tryReceive().isFailure)
        assertTrue(h.manager.state.value is ConnState.Connected)

        // Clips from the Mac still arrive at once.
        conn.incoming.send(WsEvent.Binary(server.seal(Clip("c1", "hi", false, 1))))
        assertEquals(Ack("c1"), server.open(conn.sentBinary.receive()))
        assertEquals(listOf("hi"), h.received.map { it.text })
        h.manager.stop()
    }

    @Test
    fun connectionMadeWhileQuietIsQuietFromTheStart() = runTest {
        val h = Harness(this, pairing)
        h.manager.setQuiet(true)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        assertEquals(Quiet, server.open(conn.sentBinary.receive()))
        advanceTimeBy(HOUR_MS)
        runCurrent()
        assertTrue(conn.sentBinary.tryReceive().isFailure)
        h.manager.stop()
    }

    @Test
    fun leavingQuietModeChecksTheLinkAndReconnectsWhenItIsDead() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        h.manager.setQuiet(true)
        assertEquals(Quiet, server.open(conn.sentBinary.receive()))
        advanceTimeBy(HOUR_MS)

        h.manager.setQuiet(false)
        val checkedAt = testScheduler.currentTime
        assertEquals(Ping, server.open(conn.sentBinary.receive()))
        // No answer: the link is dropped after the probe timeout and a new connection starts.
        val again = h.connections.receive()
        val waited = testScheduler.currentTime - checkedAt
        assertTrue("dropped after ${waited}ms", waited >= ConnectionManager.PROBE_TIMEOUT_MS && waited < 20_000)
        assertEquals("ws://100.64.0.1:48613/", again.url)
        h.manager.stop()
    }

    @Test
    fun leavingQuietModeKeepsAnAnsweringLink() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        h.manager.setQuiet(true)
        assertEquals(Quiet, server.open(conn.sentBinary.receive()))
        advanceTimeBy(HOUR_MS)

        h.manager.setQuiet(false)
        assertEquals(Ping, server.open(conn.sentBinary.receive()))
        conn.incoming.send(WsEvent.Binary(server.seal(Pong)))
        advanceTimeBy(25_000)
        runCurrent()
        assertTrue("an answered check keeps the connection", h.connections.tryReceive().isFailure)
        // The regular 30 s pings are back.
        assertEquals(Ping, server.open(conn.sentBinary.receive()))
        h.manager.stop()
    }

    @Test
    fun droppedQuietConnectionIsRetriedOnceThenWaitsForTheScreen() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        h.manager.setQuiet(true)
        assertEquals(Quiet, server.open(conn.sentBinary.receive()))
        conn.incoming.send(WsEvent.Closed(1000, "agent restarting"))
        conn.incoming.close()

        // The one retry of this quiet period tries both Tailscale hosts…
        h.failNextConnection()
        h.failNextConnection()
        // …and after that nothing happens until the screen comes back on.
        advanceTimeBy(HOUR_MS)
        runCurrent()
        assertTrue("no retries in quiet mode", h.connections.tryReceive().isFailure)
        h.manager.setQuiet(false)
        assertEquals("ws://100.64.0.1:48613/", h.connections.receive().url)
        h.manager.stop()
    }

    @Test
    fun quietModeStillReconnectsToDeliverAClip() = runTest {
        val h = Harness(this, pairing)
        h.manager.start()
        val server = TestServer(pairing.psk, "Mac")
        val conn = h.connect(server)
        h.manager.setQuiet(true)
        assertEquals(Quiet, server.open(conn.sentBinary.receive()))
        conn.incoming.send(WsEvent.Closed(1000, "agent restarting"))
        conn.incoming.close()
        h.failNextConnection()
        h.failNextConnection()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(h.connections.tryReceive().isFailure)

        val id = h.manager.send("late copy", sensitive = false)
        val again = h.connect(server)
        assertEquals(Quiet, server.open(again.sentBinary.receive()))
        assertEquals(id, (server.open(again.sentBinary.receive()) as Clip).id)
        h.manager.stop()
    }

    /** Fails the next connection attempt before it opens. */
    private suspend fun Harness.failNextConnection() {
        val attempt = connections.receive()
        attempt.incoming.send(WsEvent.Failed(IOException("connection refused")))
        attempt.incoming.close()
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
    }
}

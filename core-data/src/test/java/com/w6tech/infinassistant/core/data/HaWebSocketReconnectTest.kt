package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the bridge's in-place HA reconnect (BridgeEngine.runConnectedMode, 2.1.74) relies on:
 * the session's listeners keep collecting [HaWebSocketClient.events] across a reconnect of
 * the same client, and a request in flight when the connection is replaced fails with "no
 * response" instead of cancelling the coroutine that made it.
 *
 * MockWebServer plays Home Assistant: auth_required, auth_ok, and a result for every
 * subscribe_events. get_states is left unanswered.
 */
class HaWebSocketReconnectTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var client: HaWebSocketClient

    /** Server side of each connection, in order of arrival. */
    private val connections = Channel<WebSocket>(Channel.UNLIMITED)
    /** The same sockets, closed in tearDown: MockWebServer will not shut down while one is open. */
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    /** The `type` of every request the server received after auth. */
    private val requests = Channel<String>(Channel.UNLIMITED)
    /** How long the server waits before it answers a close (0: at once, as HA does on a LAN). */
    @Volatile private var closeReplyDelayMs = 0L

    private inner class FakeHa : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets += webSocket
            connections.trySend(webSocket)
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            when (type) {
                "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                "subscribe_events" -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                else -> requests.trySend(type ?: "?")
            }
        }

        // Complete the close handshake, as HA does, so the server can shut down.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val wait = closeReplyDelayMs
            if (wait == 0L) {
                webSocket.close(1000, null)
            } else {
                Thread {
                    Thread.sleep(wait)
                    webSocket.close(1000, null)
                }.start()
            }
        }
    }

    private fun event(n: Int) =
        """{"type":"event","event":{"event_type":"state_changed","data":{"n":$n}}}"""

    @Before
    fun setup() {
        server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(FakeHa())) }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        client = HaWebSocketClient(silentLogger, scope)
    }

    @After
    fun tearDown() {
        runBlocking { client.disconnect() }
        scope.cancel()
        // close, not cancel: cancel() throws on the server side of a MockWebServer socket.
        serverSockets.forEach { it.close(1000, null) }
        server.shutdown()
    }

    // A literal loopback address: the client only allows ws:// to a local host.
    private val url get() = "http://127.0.0.1:${server.port}"

    @Test
    fun `events keep flowing to the same collector across a reconnect`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        val first = withTimeout(5_000) { connections.receive() }
        assertTrue(client.subscribeEvents("state_changed") is AppResult.Ok)

        val received = Channel<Int>(Channel.UNLIMITED)
        val collector = scope.launch {
            client.events.collect { e ->
                received.send(e["event"]!!.jsonObject["data"]!!.jsonObject["n"]!!.jsonPrimitive.content.toInt())
            }
        }

        first.send(event(1))
        assertEquals(1, withTimeout(5_000) { received.receive() })

        // HA restarts: it closes the socket cleanly.
        first.close(1001, "restart")
        withTimeout(5_000) { client.state.first { it !is HaConnectionState.Connected } }

        // The bridge reconnects the same client and renews the subscription.
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        val second = withTimeout(5_000) { connections.receive() }
        assertTrue(client.subscribeEvents("state_changed") is AppResult.Ok)

        second.send(event(2))
        assertEquals(2, withTimeout(5_000) { received.receive() })
        collector.cancel()
    }

    @Test
    fun `connecting a client already connected there keeps its connection and its events`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        val first = withTimeout(5_000) { connections.receive() }
        assertTrue(client.subscribeEvents("state_changed") is AppResult.Ok)
        val received = Channel<Int>(Channel.UNLIMITED)
        val collector = scope.launch {
            client.events.collect { e ->
                received.send(e["event"]!!.jsonObject["data"]!!.jsonObject["n"]!!.jsonPrimitive.content.toInt())
            }
        }

        // What HaApi does before each request on the bridge's session client. Until 2.1.108 this
        // dropped the connection, with its subscription, and the bridge took HA as lost.
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        assertEquals(HaConnectionState.Connected, client.state.value)
        assertNull("no second connection", withTimeoutOrNull(500) { connections.receive() })

        first.send(event(3))
        assertEquals(3, withTimeout(5_000) { received.receive() })
        collector.cancel()
    }

    @Test
    fun `a replaced socket that closes late does not mark the new connection lost`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        withTimeout(5_000) { connections.receive() }
        // HA answers the first socket's close only once the second one is up.
        closeReplyDelayMs = 300
        // Another token: a real reconnect, which closes the first socket.
        assertTrue(client.connect(url, "token2") is AppResult.Ok)
        withTimeout(5_000) { connections.receive() }
        delay(800)
        // Until 2.1.108 the first socket's late close set the client Disconnected here.
        assertEquals(HaConnectionState.Connected, client.state.value)
    }

    @Test
    fun `a request in flight when the connection is replaced fails instead of cancelling its caller`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        withTimeout(5_000) { connections.receive() }

        val call = scope.async { client.getStates() }
        assertEquals("get_states", withTimeout(5_000) { requests.receive() })

        // What connect() does first on a reconnect.
        client.disconnect()

        // Before 2.1.74 this await threw CancellationException: the pending request was
        // cancelled, which ended the calling coroutine as if it had been cancelled itself.
        val result = withTimeout(5_000) { call.await() }
        assertTrue("expected an error result, got $result", result is AppResult.Err)
    }
}

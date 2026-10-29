package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The connection's keepalive: HA's ping while connected. Since app 2.1.110 the bridge receives only
 * the watched entities' changes, and HA's side closed a connection silent for about a minute (345
 * drops in 9 h in one bundle). The client pings, and a ping HA does not answer marks the connection
 * dead so the bridge reconnects at once. MockWebServer plays HA, with short intervals.
 */
class HaWebSocketKeepaliveTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val pings = AtomicInteger()
    @Volatile private var answersPings = true

    private inner class FakeHa : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            when (obj["type"]?.jsonPrimitive?.content) {
                "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                "ping" -> {
                    pings.incrementAndGet()
                    // HA answers a ping with a pong, not a result.
                    if (answersPings) webSocket.send("""{"id":$id,"type":"pong"}""")
                }
                else -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeHa()))
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun client() = HaWebSocketClient(silentLogger, scope, keepaliveMs = 200, pongTimeoutMs = 500)

    private fun baseUrl() = "http://127.0.0.1:${server.port}"

    @Test
    fun `a connection HA answers is pinged and stays up`() = runBlocking {
        val ha = client()
        assertTrue(ha.connect(baseUrl(), "token") is AppResult.Ok)
        delay(1_300)
        assertTrue("pinged while connected: ${pings.get()}", pings.get() >= 3)
        assertTrue("still connected", ha.isConnected())
        ha.disconnect()
    }

    @Test
    fun `a ping HA does not answer marks the connection dead`() = runBlocking {
        answersPings = false
        val ha = client()
        assertTrue(ha.connect(baseUrl(), "token") is AppResult.Ok)
        // The first ping at 200 ms, its pong due by 700 ms: then the socket is failed.
        val state = withTimeout(3_000) { ha.state.first { it !is HaConnectionState.Connected } }
        assertTrue("failed, so the bridge reconnects: $state", state is HaConnectionState.Failed)
    }

    @Test
    fun `no ping goes out after the client disconnects`() = runBlocking {
        val ha = client()
        assertTrue(ha.connect(baseUrl(), "token") is AppResult.Ok)
        ha.disconnect()
        val before = pings.get()
        delay(700)
        assertTrue("no ping after disconnect", pings.get() == before)
    }
}

package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * HA's WebSocket never redirects, and a redirect would take the auth message wherever it points: OkHttp
 * adds network interceptors only to calls that are not WebSockets, so the LAN-only guard cannot check a
 * redirect's target. The client therefore follows none, whatever OkHttpClient it is given.
 */
class HaWebSocketRedirectTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private lateinit var redirecting: MockWebServer
    private lateinit var elsewhere: MockWebServer
    private lateinit var scope: CoroutineScope
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private val frames = CopyOnWriteArrayList<String>()

    /** A Home Assistant that asks for auth and records every frame it is sent. */
    private inner class FakeHa : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets += webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames += text
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setup() {
        elsewhere = MockWebServer()
        elsewhere.enqueue(MockResponse().withWebSocketUpgrade(FakeHa()))
        elsewhere.start()
        redirecting = MockWebServer()
        redirecting.enqueue(
            MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${elsewhere.port}/api/websocket"),
        )
        redirecting.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        serverSockets.forEach { it.close(1000, null) }
        redirecting.shutdown()
        elsewhere.shutdown()
    }

    private fun assertRedirectNotFollowed(client: HaWebSocketClient) = runBlocking {
        val connect = scope.async { client.connect("http://127.0.0.1:${redirecting.port}", "secret-token") }
        withTimeout(5_000) { client.state.first { it is HaConnectionState.Failed } }
        assertEquals("the redirect target was never asked", 0, elsewhere.requestCount)
        assertTrue("no frame, and so no auth message, reached it: $frames", frames.isEmpty())
        // connect() would wait out its 15 s auth timeout; the test has what it needs.
        connect.cancel()
        client.disconnect()
    }

    @Test
    fun `the default client does not follow a redirect from the WebSocket endpoint`() {
        assertRedirectNotFollowed(HaWebSocketClient(silentLogger, scope))
    }

    @Test
    fun `an injected client that follows redirects is made not to`() {
        val following = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).lanOnlyCleartext().build()
        assertRedirectNotFollowed(HaWebSocketClient(silentLogger, scope, following))
    }
}

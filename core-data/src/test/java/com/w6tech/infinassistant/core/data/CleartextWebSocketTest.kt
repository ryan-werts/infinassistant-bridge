package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.LocalNetwork
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.AssistPipelineClient
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import com.w6tech.infinassistant.core.ha.LocalOnlyDns
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * A cleartext ws:// connection gets no network interceptor from OkHttp, so its client is narrowed
 * instead: no proxy, local addresses only, no redirects. Everything here runs without real DNS: a
 * [Dns] of the test's answers, and the socket is sent to MockWebServer whatever address it names.
 */
class CleartextWebSocketTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private lateinit var ha: MockWebServer
    private lateinit var proxy: MockWebServer
    private lateinit var scope: CoroutineScope
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    private val frames = CopyOnWriteArrayList<String>()

    private fun field(frame: String, key: String): String? = Json.parseToJsonElement(frame).jsonObject[key]?.jsonPrimitive?.content

    private fun addr(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })

    /** A Home Assistant that asks for auth, accepts any token and records every frame. */
    private inner class FakeHa : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets += webSocket
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames += text
            if (field(text, "type") == "auth") webSocket.send("""{"type":"auth_ok"}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    /** Every connection goes to [ha], whatever address the route says. */
    private inner class ToHa : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) =
                super.connect(InetSocketAddress(addr(127, 0, 0, 1), ha.port), timeout)
        }

        override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException()
    }

    private fun dnsAnswering(vararg answer: InetAddress) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = answer.toList()
    }

    /** A client whose guard believes a .local name is on the LAN, and whose own lookup gives [answer]. */
    private fun client(vararg answer: InetAddress): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .socketFactory(ToHa())
            .dns(dnsAnswering(*answer))
            .lanOnlyCleartext(LocalNetwork.Resolver { listOf(addr(192, 168, 1, 51)) })
            .build()

    @Before
    fun setup() {
        ha = MockWebServer()
        ha.enqueue(MockResponse().withWebSocketUpgrade(FakeHa()))
        ha.start()
        proxy = MockWebServer()
        proxy.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        serverSockets.forEach { it.close(1000, null) }
        ha.shutdown()
        proxy.shutdown()
    }

    // ── the Dns ──

    @Test
    fun `LocalOnlyDns keeps only local addresses and fails when none is left`() {
        val mixed = LocalOnlyDns(dnsAnswering(addr(203, 0, 113, 9), addr(192, 168, 1, 51), addr(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertEquals(listOf(addr(192, 168, 1, 51), addr(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)), mixed.lookup("ha.local"))
        for (none in listOf(dnsAnswering(addr(203, 0, 113, 9)), dnsAnswering())) {
            try {
                LocalOnlyDns(none).lookup("ha.local")
                fail("a name with no local address should not resolve")
            } catch (e: UnknownHostException) {
                assertTrue(e.message, e.message!!.contains("no local address"))
            }
        }
    }

    // ── HaWebSocketClient ──

    @Test
    fun `HA socket - a ws name that resolves to a public address is not connected`() = runBlocking {
        val ws = HaWebSocketClient(silentLogger, scope, client(addr(203, 0, 113, 9)))
        val connect = scope.async { ws.connect("http://ha.local:${ha.port}", "secret-token") }
        withTimeout(5_000) { ws.state.first { it is HaConnectionState.Failed } }
        assertEquals("nothing reached HA", 0, ha.requestCount)
        assertTrue(frames.toString(), frames.isEmpty())
        connect.cancel()
        ws.disconnect()
    }

    @Test
    fun `HA socket - a ws name that resolves to the LAN connects and authenticates`() = runBlocking {
        val ws = HaWebSocketClient(silentLogger, scope, client(addr(192, 168, 1, 51)))
        assertTrue(ws.connect("http://ha.local:${ha.port}", "secret-token") is AppResult.Ok)
        assertTrue(frames.toString(), frames.any { field(it, "access_token") == "secret-token" })
        ws.disconnect()
    }

    @Test
    fun `HA socket - a ws connection does not go through a proxy`() = runBlocking {
        val viaProxy = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(addr(127, 0, 0, 1), proxy.port)))
            .lanOnlyCleartext()
            .build()
        val ws = HaWebSocketClient(silentLogger, scope, viaProxy)
        // A literal address: Dns is not asked, the name and route checks stand.
        assertTrue(ws.connect("http://127.0.0.1:${ha.port}", "secret-token") is AppResult.Ok)
        assertEquals("the proxy was never asked", 0, proxy.requestCount)
        assertTrue(frames.any { field(it, "type") == "auth" })
        ws.disconnect()
    }

    // ── AssistPipelineClient ──

    private fun assist(okHttp: OkHttpClient) = AssistPipelineClient(okHttp, authTimeoutMs = 3_000)

    private fun assertRefused(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("the connection should fail")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `Assist socket - a ws name that resolves to a public address is not connected`() {
        assertRefused { assist(client(addr(203, 0, 113, 9))).connect("http://ha.local:${ha.port}", "secret-token") }
        assertEquals(0, ha.requestCount)
        assertTrue(frames.toString(), frames.isEmpty())
    }

    @Test
    fun `Assist socket - a ws name that resolves to the LAN connects`() = runBlocking {
        val session = assist(client(addr(192, 168, 1, 51))).connect("http://ha.local:${ha.port}", "secret-token")
        session.close()
        assertTrue(frames.toString(), frames.any { field(it, "access_token") == "secret-token" })
    }

    @Test
    fun `Assist socket - a ws connection does not go through a proxy`() = runBlocking {
        val viaProxy = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(addr(127, 0, 0, 1), proxy.port)))
            .lanOnlyCleartext()
            .build()
        val session = assist(viaProxy).connect("http://127.0.0.1:${ha.port}", "secret-token")
        session.close()
        assertEquals("the proxy was never asked", 0, proxy.requestCount)
    }

    @Test
    fun `Assist socket - redirects are not followed even by a client that follows them`() {
        val elsewhere = MockWebServer().apply { enqueue(MockResponse().withWebSocketUpgrade(FakeHa())); start() }
        val redirecting = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${elsewhere.port}/api/websocket"))
            start()
        }
        try {
            val following = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).lanOnlyCleartext().build()
            assertRefused { assist(following).connect("http://127.0.0.1:${redirecting.port}", "secret-token") }
            assertEquals("the redirect target was never asked", 0, elsewhere.requestCount)
            assertTrue(frames.toString(), frames.isEmpty())
        } finally {
            serverSockets.forEach { it.close(1000, null) }
            redirecting.shutdown()
            elsewhere.shutdown()
        }
    }
}

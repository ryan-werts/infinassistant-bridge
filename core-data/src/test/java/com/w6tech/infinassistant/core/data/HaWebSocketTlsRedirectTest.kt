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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The scenario behind HaWebSocketClient's no-redirect rule: a remote HA URL (https, so wss://) answers
 * the upgrade with a 302 to another server. The guard checked only the first URL, so without the rule
 * the auth message would follow the redirect to a cleartext host, or to any other TLS host.
 *
 * Over TLS the client's own rule is the only one in play. A ws:// connection is also narrowed by
 * `forCleartextWebSocket()`, which hides it (HaWebSocketRedirectTest). The client built without an
 * injected OkHttpClient cannot trust a test certificate, so these inject one; the rule is applied to
 * an injected client and to the default one by the same statement.
 */
class HaWebSocketTlsRedirectTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private val held = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()

    private lateinit var remote: MockWebServer       // the wss:// HA URL: answers 302
    private lateinit var otherTls: MockWebServer     // another TLS host
    private lateinit var cleartext: MockWebServer    // a plain http host
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
        otherTls = MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
            enqueue(MockResponse().withWebSocketUpgrade(FakeHa()))
            start()
        }
        cleartext = MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(FakeHa()))
            start()
        }
        remote = MockWebServer().apply {
            useHttps(serverCerts.sslSocketFactory(), false)
            start()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        serverSockets.forEach { it.close(1000, null) }
        remote.shutdown()
        otherTls.shutdown()
        cleartext.shutdown()
    }

    /** A client that trusts the test certificate and, with [follow], would follow every redirect. */
    private fun trusting(follow: Boolean?): OkHttpClient =
        OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .apply { if (follow != null) followRedirects(follow).followSslRedirects(follow) }
            .lanOnlyCleartext()
            .build()

    private fun assertRedirectNotFollowed(okHttp: OkHttpClient, location: String, target: MockWebServer) = runBlocking {
        remote.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
        val client = HaWebSocketClient(silentLogger, scope, okHttp)
        val connect = scope.async { client.connect("https://localhost:${remote.port}", "secret-token") }
        withTimeout(5_000) { client.state.first { it is HaConnectionState.Failed } }
        assertEquals("the endpoint answered the upgrade", 1, remote.requestCount)
        assertEquals("the redirect target was never asked", 0, target.requestCount)
        assertTrue("no frame, and so no auth message, reached it: $frames", frames.isEmpty())
        // connect() would wait out its 15 s auth timeout; the test has what it needs.
        connect.cancel()
        client.disconnect()
    }

    @Test
    fun `wss redirected to a cleartext host is not followed by a client with default redirect settings`() {
        assertRedirectNotFollowed(trusting(follow = null), "http://127.0.0.1:${cleartext.port}/api/websocket", cleartext)
    }

    @Test
    fun `wss redirected to a cleartext host is not followed by a client that follows redirects`() {
        assertRedirectNotFollowed(trusting(follow = true), "http://127.0.0.1:${cleartext.port}/api/websocket", cleartext)
    }

    @Test
    fun `wss redirected to another TLS host is not followed by a client with default redirect settings`() {
        assertRedirectNotFollowed(trusting(follow = null), "https://localhost:${otherTls.port}/api/websocket", otherTls)
    }

    @Test
    fun `wss redirected to another TLS host is not followed by a client that follows redirects`() {
        assertRedirectNotFollowed(trusting(follow = true), "https://localhost:${otherTls.port}/api/websocket", otherTls)
    }
}

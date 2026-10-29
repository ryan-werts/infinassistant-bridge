package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaSubscriber
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * The client's side of the state cache's subscription: subscribe_entities as HA reads it, its
 * events to the subscriber rather than to [HaWebSocketClient.events], and the subscription's end.
 * MockWebServer plays Home Assistant, which sends a subscription's first event right behind its
 * result.
 */
class HaWebSocketSubscriptionTest {

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var client: HaWebSocketClient

    /** Server side of the connection. */
    private val connections = Channel<WebSocket>(Channel.UNLIMITED)
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()
    /** Every request the server received after auth. */
    private val requests = Channel<JsonObject>(Channel.UNLIMITED)
    /** HA before 2022.4: subscribe_entities is an unknown command. */
    @Volatile private var withoutSubscribeEntities = false

    private inner class FakeHa : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSockets += webSocket
            connections.trySend(webSocket)
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            when (obj["type"]?.jsonPrimitive?.content) {
                "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                "subscribe_entities" -> {
                    requests.trySend(obj)
                    if (withoutSubscribeEntities) {
                        webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}""")
                    } else {
                        webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                        webSocket.send("""{"id":$id,"type":"event","event":{"a":{"light.kitchen":{"s":"on","a":{},"c":"01JADJ8A","lc":1791640000.25}}}}""")
                    }
                }
                else -> {
                    requests.trySend(obj)
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    /** Collects a subscriber's events and its end. */
    private class Recorder : HaSubscriber {
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        val ended = Channel<Unit>(Channel.UNLIMITED)
        override fun onEvent(message: JsonObject) {
            events.trySend(message)
        }
        override fun onEnded() {
            ended.trySend(Unit)
        }
    }

    @Before
    fun setup() {
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(FakeHa()))
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        client = HaWebSocketClient(silentLogger, scope)
    }

    @After
    fun tearDown() {
        runBlocking { client.disconnect() }
        scope.cancel()
        serverSockets.forEach { it.close(1000, null) }
        server.shutdown()
    }

    private val url get() = "http://127.0.0.1:${server.port}"

    private fun JsonObject.eventId() = this["id"]?.jsonPrimitive?.intOrNull

    @Test
    fun `subscribe_entities lists the entities, and its events go to its subscriber only`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        val ha = withTimeout(5_000) { connections.receive() }
        val others = Channel<JsonObject>(Channel.UNLIMITED)
        val collector = scope.launch { client.events.collect { others.send(it) } }
        val subscriber = Recorder()

        val subscribed = client.subscribeEntities(listOf("light.kitchen", "sensor.attic"), subscriber)
        val id = (subscribed as AppResult.Ok).value
        val request = withTimeout(5_000) { requests.receive() }
        assertEquals(id, request.eventId())
        assertEquals(listOf("light.kitchen", "sensor.attic"), request["entity_ids"]!!.jsonArray.map { it.jsonPrimitive.content })

        // HA's first event, right behind the result: the subscriber's, not the bridge's.
        val first = withTimeout(5_000) { subscriber.events.receive() }
        assertEquals(id, first.eventId())
        assertEquals("on", first["event"]!!.jsonObject["a"]!!.jsonObject["light.kitchen"]!!.jsonObject["s"]!!.jsonPrimitive.content)

        // Another subscription's event still goes to the bridge's collector.
        ha.send("""{"id":${id + 100},"type":"event","event":{"event_type":"infinassistant_command","data":{}}}""")
        assertEquals(id + 100, withTimeout(5_000) { others.receive() }.eventId())
        assertNull(subscriber.events.tryReceive().getOrNull())
        collector.cancel()
    }

    @Test
    fun `unsubscribe ends the subscription, with HA and here`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        val ha = withTimeout(5_000) { connections.receive() }
        val subscriber = Recorder()
        val id = (client.subscribeEntities(listOf("light.kitchen"), subscriber) as AppResult.Ok).value
        withTimeout(5_000) { requests.receive() }
        withTimeout(5_000) { subscriber.events.receive() }

        assertTrue(client.unsubscribe(id) is AppResult.Ok)
        val request = withTimeout(5_000) { requests.receive() }
        assertEquals("unsubscribe_events", request["type"]!!.jsonPrimitive.content)
        assertEquals(id, request["subscription"]!!.jsonPrimitive.intOrNull)

        // One HA sent before it took the unsubscribe reaches the subscriber no more.
        ha.send("""{"id":$id,"type":"event","event":{"c":{"light.kitchen":{"+":{"s":"off","lc":1791640100.0}}}}}""")
        assertNull(withTimeoutOrNull(500) { subscriber.events.receive() })
    }

    @Test
    fun `an HA without subscribe_entities answers with an error`() = runBlocking {
        withoutSubscribeEntities = true
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        withTimeout(5_000) { connections.receive() }

        val result = client.subscribeEntities(listOf("light.kitchen"), Recorder())

        assertTrue(result is AppResult.Err)
        assertTrue((result as AppResult.Err).error.message!!.contains("Unknown command."))
    }

    @Test
    fun `the subscription ends with its connection`() = runBlocking {
        assertTrue(client.connect(url, "token") is AppResult.Ok)
        val ha = withTimeout(5_000) { connections.receive() }
        val subscriber = Recorder()
        assertTrue(client.subscribeEntities(listOf("light.kitchen"), subscriber) is AppResult.Ok)

        // HA restarts: it closes the socket.
        ha.close(1001, "restart")
        withTimeout(5_000) { subscriber.ended.receive() }
        Unit
    }
}

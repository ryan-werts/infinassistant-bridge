package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.ha.AssistAuthException
import com.w6tech.infinassistant.core.ha.AssistPipelineClient
import com.w6tech.infinassistant.core.ha.PipelineEvent
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Assist client against a fake HA that follows HA's documented websocket protocol: auth,
 * `assist_pipeline/run`, a `run-start` event carrying the binary handler id, audio in binary
 * frames led by that id, an id-only frame as the end of audio, events through `run-end`.
 */
class AssistPipelineClientTest {

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    private val client = AssistPipelineClient(
        OkHttpClient.Builder().lanOnlyCleartext().build(),
        authTimeoutMs = 5_000,
        runStartTimeoutMs = 5_000,
        eventTimeoutMs = 5_000,
    )

    /** What the client sent, in order, and how the fake answers. */
    private class FakeHa(private val firstHandlerId: Int = 7, private val token: String = TOKEN) : WebSocketListener() {
        /** Everything the client sent, as "text:<type>" or "binary". */
        val order = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<JsonObject>()
        val frames = CopyOnWriteArrayList<ByteArray>()
        val tokens = CopyOnWriteArrayList<String>()

        /** What HA does after the end-of-audio frame. */
        @Volatile var finish: (WebSocket, Int) -> Unit = { ws, id -> Events.success(ws, id) }

        private var runs = 0
        private var currentRun = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            order += "text:$type"
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            when (type) {
                "auth" -> {
                    val sent = obj["access_token"]!!.jsonPrimitive.content
                    tokens += sent
                    webSocket.send(if (sent == token) """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"Invalid password"}""")
                }
                "assist_pipeline/run" -> {
                    commands += obj
                    currentRun = id!!
                    val handler = firstHandlerId + runs++
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    Events.send(
                        webSocket, id, "run-start",
                        """{"pipeline":"p1","language":"en","runner_data":{"stt_binary_handler_id":$handler,"timeout":300}}""",
                    )
                }
                "assist_pipeline/pipeline/list" -> {
                    commands += obj
                    webSocket.send(
                        """{"id":$id,"type":"result","success":true,"result":{"pipelines":[""" +
                            """{"id":"p1","name":"Local Assistant","language":"en"},""" +
                            """{"id":"p2","name":"GPT Assistant","language":"en"}],"preferred_pipeline":"p1"}}""",
                    )
                }
                else -> webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            order += "binary"
            frames += bytes.toByteArray()
            // A frame holding only the handler id ends the audio.
            if (bytes.size == 1) finish(webSocket, currentRun)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private object Events {
        fun send(ws: WebSocket, id: Int, type: String, data: String) {
            ws.send("""{"id":$id,"type":"event","event":{"type":"$type","data":$data,"timestamp":"2026-10-18T10:00:00+00:00"}}""")
        }

        fun success(ws: WebSocket, id: Int, text: String = "turn on the kitchen light", conversationId: String = "conv-1") {
            send(ws, id, "stt-start", """{"engine":"stt.faster_whisper"}""")
            send(ws, id, "stt-end", """{"stt_output":{"text":"$text"}}""")
            send(ws, id, "intent-start", """{"engine":"conversation.agent"}""")
            send(
                ws, id, "intent-end",
                """{"intent_output":{"response":{"speech":{"plain":{"speech":"Done.","extra_data":null}},"response_type":"action_done"},""" +
                    """"conversation_id":"$conversationId","continue_conversation":true}}""",
            )
            send(ws, id, "tts-start", """{"engine":"tts.piper"}""")
            send(ws, id, "tts-end", """{"tts_output":{"url":"/api/tts_proxy/abc.mp3","mime_type":"audio/mpeg","media_id":"m"}}""")
            send(ws, id, "run-end", "null")
        }

        fun error(ws: WebSocket, id: Int, code: String, message: String) {
            send(ws, id, "error", """{"code":"$code","message":"$message"}""")
            send(ws, id, "run-end", "null")
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = "http://127.0.0.1:${server.port}"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fakeHa(ha: FakeHa = FakeHa()): FakeHa {
        server.enqueue(MockResponse().withWebSocketUpgrade(ha))
        return ha
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(15_000) { block() } }

    @Test
    fun `a run follows HA's protocol from run command to run-end`() = blocking {
        val ha = fakeHa()
        val session = client.connect(baseUrl, TOKEN)
        val run = session.startRun(pipelineId = null, conversationId = null)

        val audio = byteArrayOf(1, 2, 3, 4)
        run.sendAudio(audio)
        run.endAudio()
        val events = run.events.toList()
        session.close()

        // Auth first, then the run, and audio only after the run was accepted.
        assertEquals(listOf("text:auth", "text:assist_pipeline/run", "binary", "binary"), ha.order.take(4))
        val command = ha.commands.single()
        assertEquals("stt", command["start_stage"]!!.jsonPrimitive.content)
        assertEquals("tts", command["end_stage"]!!.jsonPrimitive.content)
        assertEquals(16000, command["input"]!!.jsonObject["sample_rate"]!!.jsonPrimitive.intOrNull)
        assertFalse("the preferred pipeline is not named", command.containsKey("pipeline"))
        assertFalse("a new conversation has no id", command.containsKey("conversation_id"))

        // The binary handler id is run-start's stt_binary_handler_id (7), not the command id.
        assertArrayEquals(byteArrayOf(7, 1, 2, 3, 4), ha.frames[0])
        assertArrayEquals("the end of audio is the handler id alone", byteArrayOf(7), ha.frames[1])

        assertEquals(
            listOf(
                PipelineEvent.SttEnd("turn on the kitchen light"),
                PipelineEvent.IntentEnd("Done.", "conv-1", continueConversation = true),
                PipelineEvent.TtsEnd("/api/tts_proxy/abc.mp3", "audio/mpeg"),
                PipelineEvent.RunEnd,
            ),
            events,
        )
    }

    @Test
    fun `the pipeline and conversation id go with the run when given`() = blocking {
        val ha = fakeHa()
        val session = client.connect(baseUrl, TOKEN)
        session.startRun(pipelineId = "p2", conversationId = "conv-9")
        session.close()

        val command = ha.commands.single()
        assertEquals("p2", command["pipeline"]!!.jsonPrimitive.content)
        assertEquals("conv-9", command["conversation_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `audio is streamed as frames in order`() = blocking {
        val ha = fakeHa()
        val session = client.connect(baseUrl, TOKEN)
        val run = session.startRun(null, null)
        repeat(5) { i -> run.sendAudio(ByteArray(3200) { i.toByte() }) }
        run.endAudio()
        run.events.toList()
        session.close()

        assertEquals(6, ha.frames.size)
        for (i in 0 until 5) {
            assertEquals(3201, ha.frames[i].size)
            assertEquals(7, ha.frames[i][0].toInt())
            assertEquals(i, ha.frames[i][1].toInt())
        }
        assertEquals(1, ha.frames[5].size)
    }

    @Test
    fun `a conversation runs several turns on one socket with each run's own handler id`() = blocking {
        val ha = fakeHa()
        val session = client.connect(baseUrl, TOKEN)

        val first = session.startRun(null, null)
        first.sendAudio(byteArrayOf(9))
        first.endAudio()
        val firstEvents = first.events.toList()

        val second = session.startRun(null, "conv-1")
        second.sendAudio(byteArrayOf(8))
        second.endAudio()
        val secondEvents = second.events.toList()
        session.close()

        assertEquals(PipelineEvent.RunEnd, firstEvents.last())
        assertEquals(PipelineEvent.RunEnd, secondEvents.last())
        assertEquals("one auth for the whole conversation", 1, ha.tokens.size)
        assertArrayEquals(byteArrayOf(7, 9), ha.frames[0])
        assertArrayEquals(byteArrayOf(8, 8), ha.frames[2])
        assertEquals("conv-1", ha.commands[1]["conversation_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the same client works again after a session ended`() = blocking {
        repeat(3) { n ->
            val ha = fakeHa()
            val session = client.connect(baseUrl, TOKEN)
            val run = session.startRun(null, null)
            run.sendAudio(byteArrayOf(n.toByte()))
            run.endAudio()
            val events = run.events.toList()
            session.close()
            assertEquals("session ${n + 1}", PipelineEvent.RunEnd, events.last())
            assertEquals(1, ha.tokens.size)
        }
    }

    @Test
    fun `an error event ends the run with its code and message`() = blocking {
        val ha = fakeHa()
        ha.finish = { ws, id -> Events.error(ws, id, "stt-no-text-recognized", "No text recognized") }
        val session = client.connect(baseUrl, TOKEN)
        val run = session.startRun(null, null)
        run.sendAudio(byteArrayOf(1))
        run.endAudio()
        val events = run.events.toList()
        session.close()

        assertEquals(listOf<PipelineEvent>(PipelineEvent.Error("stt-no-text-recognized", "No text recognized")), events)
    }

    @Test
    fun `a run HA refuses fails the start`() = blocking {
        val refusing = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"auth_required"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = Json.parseToJsonElement(text).jsonObject
                val id = obj["id"]?.jsonPrimitive?.intOrNull
                if (obj["type"]!!.jsonPrimitive.content == "auth") {
                    webSocket.send("""{"type":"auth_ok"}""")
                } else {
                    webSocket.send("""{"id":$id,"type":"result","success":false,"error":{"code":"pipeline-not-found","message":"Pipeline not found"}}""")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(refusing))
        val session = client.connect(baseUrl, TOKEN)
        try {
            session.startRun("gone", null)
            fail("the run should not start")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("pipeline-not-found"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `a silent HA ends the events with a timeout error`() = blocking {
        val ha = fakeHa()
        ha.finish = { _, _ -> /* never answers */ }
        val impatient = AssistPipelineClient(OkHttpClient(), eventTimeoutMs = 300)
        val session = impatient.connect(baseUrl, TOKEN)
        val run = session.startRun(null, null)
        run.endAudio()
        val events = run.events.toList()
        session.close()

        assertEquals(1, events.size)
        assertEquals("timeout", (events.single() as PipelineEvent.Error).code)
    }

    @Test
    fun `a connection lost mid-run ends the events with an error`() = blocking {
        val ha = fakeHa()
        ha.finish = { ws, _ -> ws.close(1001, "going away") }
        val session = client.connect(baseUrl, TOKEN)
        val run = session.startRun(null, null)
        run.endAudio()
        val events = run.events.toList()
        session.close()

        assertEquals("connection_lost", (events.single() as PipelineEvent.Error).code)
    }

    @Test
    fun `the pipeline list gives the pipelines and the preferred one`() = blocking {
        val ha = fakeHa()
        val list = client.listPipelines(baseUrl, TOKEN)

        assertEquals(listOf("p1" to "Local Assistant", "p2" to "GPT Assistant"), list.pipelines.map { it.id to it.name })
        assertEquals("p1", list.preferredId)
        assertEquals(listOf("text:auth", "text:assist_pipeline/pipeline/list"), ha.order)
    }

    @Test
    fun `a refused token fails the connect and is not retried`() = blocking {
        val ha = fakeHa()
        try {
            client.connect(baseUrl, "wrong-token")
            fail("the connect should fail")
        } catch (e: AssistAuthException) {
            // expected
        }
        // HA bans an address after ten failed logins: one attempt, and nothing else sent.
        Thread.sleep(200)
        assertEquals(listOf("text:auth"), ha.order)
        assertEquals(listOf("wrong-token"), ha.tokens)
    }

    @Test
    fun `the reply is fetched with the token from the address the session used`() = blocking {
        server.enqueue(MockResponse().setBody("mp3-bytes"))
        val bytes = client.fetchTtsAudio(baseUrl, "/api/tts_proxy/abc.mp3", TOKEN)

        assertEquals("mp3-bytes", String(bytes))
        val request = server.takeRequest()
        assertEquals("/api/tts_proxy/abc.mp3", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
    }

    @Test
    fun `a reply URL HA gives in full is fetched from the session's address`() = blocking {
        server.enqueue(MockResponse().setBody("x"))
        client.fetchTtsAudio(baseUrl, "http://homeassistant.local:8123/api/tts_proxy/abc.mp3?token=1", TOKEN)

        val request = server.takeRequest()
        assertEquals("/api/tts_proxy/abc.mp3?token=1", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
    }

    @Test
    fun `a failed reply fetch is an IOException`() = blocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            client.fetchTtsAudio(baseUrl, "/api/tts_proxy/gone.mp3", TOKEN)
            fail("the fetch should fail")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("404"))
        }
    }

    @Test
    fun `a reply over five megabytes is refused`() = blocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(AssistPipelineClient.MAX_TTS_BYTES.toInt() + 1))))
        try {
            client.fetchTtsAudio(baseUrl, "/api/tts_proxy/big.mp3", TOKEN)
            fail("the reply is too large")
        } catch (e: IOException) {
            assertEquals("TTS reply too large", e.message)
        }
    }

    @Test
    fun `a reply of exactly five megabytes is taken`() = blocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(AssistPipelineClient.MAX_TTS_BYTES.toInt()))))
        assertEquals(AssistPipelineClient.MAX_TTS_BYTES.toInt(), client.fetchTtsAudio(baseUrl, "/api/tts_proxy/ok.mp3", TOKEN).size)
    }

    @Test
    fun `a redirect is not followed, so the token goes nowhere else`() = blocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:1/elsewhere"))
        try {
            client.fetchTtsAudio(baseUrl, "/api/tts_proxy/moved.mp3", TOKEN)
            fail("a redirect is a failure")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("302"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancelling the caller cancels the reply fetch at once`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val fetch = launch(Dispatchers.Default) { client.fetchTtsAudio(baseUrl, "/api/tts_proxy/slow.mp3", TOKEN) }
        server.takeRequest()
        withTimeout(5_000) { fetch.cancelAndJoin() }
        assertTrue(fetch.isCancelled)
    }

    @Test
    fun `reply URLs resolve onto the session's base URL`() {
        assertEquals("http://h:8123/api/tts_proxy/a.mp3", AssistPipelineClient.resolveAudioUrl("http://h:8123/", "/api/tts_proxy/a.mp3"))
        assertEquals("http://h:8123/api/tts_proxy/a.mp3", AssistPipelineClient.resolveAudioUrl("http://h:8123", "api/tts_proxy/a.mp3"))
        assertEquals(
            "https://ha.example.com/api/tts_proxy/a.mp3?x=1",
            AssistPipelineClient.resolveAudioUrl("https://ha.example.com", "http://192.168.1.5:8123/api/tts_proxy/a.mp3?x=1"),
        )
    }

    @Test
    fun `a closed session cannot start a run`() = blocking {
        fakeHa()
        val session = client.connect(baseUrl, TOKEN)
        session.close()
        try {
            session.startRun(null, null)
            fail("a closed session cannot start a run")
        } catch (e: IOException) {
            // expected
        }
    }

    private companion object {
        const val TOKEN = "test-token"
    }
}

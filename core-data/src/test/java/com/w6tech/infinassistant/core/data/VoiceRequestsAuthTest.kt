package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.AssistPipelineClient
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FR-P6: nothing the voice path sends can count as a failed HA login. HA bans an address after ten
 * failed logins (`ip_ban_enabled`), and the phone's whole connection to HA goes with it. The fake
 * HA here counts a failed login the way HA does: an HTTP request to an endpoint that needs auth
 * without a valid bearer token, a websocket that sends anything before `auth`, or an `auth` with
 * the wrong token. A whole voice conversation, the pipeline list and the reply fetch run against
 * it, and the count must stay zero.
 *
 * HA serves `/api/websocket` without a header by design: the websocket authenticates with its
 * first message instead, which is what the test holds the client to.
 */
class VoiceRequestsAuthTest {

    private lateinit var server: MockWebServer
    private val failedLogins = CopyOnWriteArrayList<String>()
    private val httpRequests = CopyOnWriteArrayList<String>()
    private val websocketOrder = CopyOnWriteArrayList<String>()

    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private inner class AuthCountingHa : WebSocketListener() {
        private var authed = false
        private var runId = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            websocketOrder += type
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            if (type == "auth") {
                if (obj["access_token"]?.jsonPrimitive?.content == TOKEN && !authed) {
                    authed = true
                    webSocket.send("""{"type":"auth_ok"}""")
                } else {
                    failedLogins += "websocket auth with a wrong or repeated token"
                    webSocket.send("""{"type":"auth_invalid"}""")
                }
                return
            }
            if (!authed) {
                failedLogins += "websocket message '$type' before auth"
                return
            }
            when (type) {
                "assist_pipeline/run" -> {
                    runId = id!!
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":null}""")
                    event(webSocket, "run-start", """{"runner_data":{"stt_binary_handler_id":3}}""")
                }
                "assist_pipeline/pipeline/list" ->
                    webSocket.send("""{"id":$id,"type":"result","success":true,"result":{"pipelines":[],"preferred_pipeline":null}}""")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            websocketOrder += "binary"
            if (!authed) failedLogins += "websocket audio before auth"
            if (bytes.size == 1) {
                event(webSocket, "stt-end", """{"stt_output":{"text":"thanks"}}""")
                event(webSocket, "intent-end", """{"intent_output":{"response":{"speech":{"plain":{"speech":"Welcome."}}},"conversation_id":"c"}}""")
                event(webSocket, "tts-end", """{"tts_output":{"url":"/api/tts_proxy/r.mp3","mime_type":"audio/mpeg"}}""")
                event(webSocket, "run-end", "null")
            }
        }

        private fun event(ws: WebSocket, type: String, data: String) {
            ws.send("""{"id":$runId,"type":"event","event":{"type":"$type","data":$data}}""")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                httpRequests += "${request.method} ${request.path}"
                if (request.path == "/api/websocket") {
                    // The one endpoint HA serves without a header: the websocket authenticates by message.
                    return MockResponse().withWebSocketUpgrade(AuthCountingHa())
                }
                if (request.getHeader("Authorization") != "Bearer $TOKEN") {
                    failedLogins += "${request.method} ${request.path} without a valid bearer token"
                    return MockResponse().setResponseCode(401)
                }
                return MockResponse().setBody("audio")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val speech = ByteArray(3200).also { it[0] = 1 }
    private val endOfSpeech = ByteArray(3200).also { it[0] = 2 }

    private val recorder = object : SpeechRecorder {
        override suspend fun startRecording(): Flow<ByteArray> = flowOf(speech, endOfSpeech)
        override fun stopRecording() {}
    }

    private val detector = object : SpeechDetector {
        override fun reset() {}
        override fun analyze(audioChunk: ByteArray) = when (audioChunk[0].toInt()) {
            1 -> VoiceActivityDetector.VadResult.Speaking
            else -> VoiceActivityDetector.VadResult.SilenceAfterSpeech
        }
    }

    private val player = object : SpeechPlayer {
        override fun requestFocus() = true
        override fun releaseFocus() {}
        override suspend fun play(audio: ByteArray, mimeType: String) {}
    }

    @Test
    fun `a whole voice conversation, the pipeline list and the reply fetch cause no failed login`() = runBlocking {
        withTimeout(20_000) {
            val baseUrl = "http://127.0.0.1:${server.port}"
            val client = AssistPipelineClient(OkHttpClient.Builder().lanOnlyCleartext().build())
            val manager = VoiceAssistantManager(silentLogger, recorder, detector, player, client)

            client.listPipelines(baseUrl, TOKEN)
            val result = manager.startSession(VoiceSessionRequest(baseUrl, TOKEN)) { }

            assertTrue("the conversation ran: $result", result is AppResult.Ok)
            assertEquals("no request counted as a failed login", emptyList<String>(), failedLogins.toList())
        }
    }

    @Test
    fun `every http request but the websocket upgrade carries the bearer token`() = runBlocking {
        withTimeout(20_000) {
            val baseUrl = "http://127.0.0.1:${server.port}"
            val client = AssistPipelineClient(OkHttpClient.Builder().lanOnlyCleartext().build())
            val manager = VoiceAssistantManager(silentLogger, recorder, detector, player, client)

            manager.startSession(VoiceSessionRequest(baseUrl, TOKEN)) { }

            // The upgrade for the conversation, then the reply fetch: nothing else goes out.
            assertEquals(listOf("GET /api/websocket", "GET /api/tts_proxy/r.mp3"), httpRequests.toList())
            assertTrue(failedLogins.isEmpty())
        }
    }

    @Test
    fun `the websocket sends auth first and the token once`() = runBlocking {
        withTimeout(20_000) {
            val baseUrl = "http://127.0.0.1:${server.port}"
            val client = AssistPipelineClient(OkHttpClient.Builder().lanOnlyCleartext().build())
            val manager = VoiceAssistantManager(silentLogger, recorder, detector, player, client)

            manager.startSession(VoiceSessionRequest(baseUrl, TOKEN)) { }

            assertEquals("auth", websocketOrder.first())
            assertEquals(1, websocketOrder.count { it == "auth" })
        }
    }

    @Test
    fun `a wrong token costs one failed login and the session gives up`() = runBlocking {
        withTimeout(20_000) {
            val baseUrl = "http://127.0.0.1:${server.port}"
            val client = AssistPipelineClient(OkHttpClient.Builder().lanOnlyCleartext().build())
            val manager = VoiceAssistantManager(silentLogger, recorder, detector, player, client)

            val result = manager.startSession(VoiceSessionRequest(baseUrl, "stale-token")) { }

            assertTrue(result is AppResult.Err)
            assertEquals("HA auth failed", (result as AppResult.Err).error.message)
            assertEquals("one attempt, no retry", 1, failedLogins.size)
        }
    }

    private companion object {
        const val TOKEN = "good-token"
    }
}

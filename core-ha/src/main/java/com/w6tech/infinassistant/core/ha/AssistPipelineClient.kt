package com.w6tech.infinassistant.core.ha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Events emitted during Assist pipeline processing.
 */
sealed interface PipelineEvent {
    /**
     * Speech-to-text processing completed.
     * @param transcript The recognized text from audio input.
     */
    data class SttEnd(val transcript: String) : PipelineEvent

    /**
     * Intent processing completed.
     * @param response The assistant's response text.
     * @param conversationId Conversation ID to send with the next run so HA keeps the context.
     * @param continueConversation True when HA says the reply is a question that expects an answer.
     */
    data class IntentEnd(
        val response: String,
        val conversationId: String?,
        val continueConversation: Boolean = false,
    ) : PipelineEvent

    /**
     * Text-to-speech generation completed.
     * @param audioUrl URL of the generated audio, as HA sent it: usually a path on HA.
     * @param mimeType MIME type of the audio (e.g., "audio/mpeg").
     */
    data class TtsEnd(val audioUrl: String, val mimeType: String) : PipelineEvent

    /**
     * An error occurred during pipeline processing.
     * @param code Error code from Home Assistant.
     * @param message Human-readable error description.
     */
    data class Error(val code: String, val message: String) : PipelineEvent

    /**
     * Pipeline run completed successfully.
     */
    data object RunEnd : PipelineEvent
}

/** One Assist pipeline as HA lists it. */
data class AssistPipeline(val id: String, val name: String)

/** HA's Assist pipelines and the id of the one it marks preferred. */
data class AssistPipelineList(val pipelines: List<AssistPipeline>, val preferredId: String?)

/** HA refused the token. Kept apart from other failures so a caller can say so. */
class AssistAuthException : IOException("Authentication failed")

/**
 * One authenticated websocket to HA, over which any number of Assist runs go in turn. The voice
 * conversation opens one and keeps it for all its turns.
 */
interface AssistSession {
    /** The HA base URL this session connected to; a TTS reply is fetched from the same one. */
    val baseUrl: String

    /**
     * Starts an Assist run from speech to speech, and returns once HA has accepted it
     * (`run-start`), which is when audio may be sent.
     *
     * @param pipelineId The pipeline to run; null for HA's preferred one.
     * @param conversationId The conversation to continue, from an earlier run's
     *   [PipelineEvent.IntentEnd]; null to start a new one.
     * @throws IOException When HA does not start the run, or the connection is lost.
     */
    suspend fun startRun(pipelineId: String?, conversationId: String?): AssistRun

    /** Closes the websocket. A run still going ends with an error event. */
    fun close()
}

/** One Assist run: audio in as it is recorded, events out through `run-end` or `error`. */
interface AssistRun {
    /** Sends 16 kHz mono PCM16 audio to the run's speech-to-text stage. */
    fun sendAudio(pcm: ByteArray)

    /** Tells HA the audio is complete. */
    fun endAudio()

    /**
     * The run's events, in order, ending after [PipelineEvent.RunEnd] or [PipelineEvent.Error].
     * HA silent for longer than the client's event timeout ends the flow with an error event too.
     * Events from the run's start are kept, so collecting may begin late.
     */
    val events: Flow<PipelineEvent>
}

/** What the voice conversation needs from HA, so a test can stand in for it. */
interface AssistGateway {
    /** Opens and authenticates a session on [baseUrl] with [token]. */
    suspend fun connect(baseUrl: String, token: String): AssistSession

    /**
     * Fetches the reply audio a [PipelineEvent.TtsEnd] names, from [baseUrl] with [token].
     * @throws IOException If the fetch fails.
     */
    suspend fun fetchTtsAudio(baseUrl: String, audioUrl: String, token: String): ByteArray
}

/**
 * Home Assistant's Assist pipeline over its websocket API
 * (https://developers.home-assistant.io/docs/voice/pipelines/).
 *
 * A session is one websocket: HA sends `auth_required`, the client answers with the token, HA
 * answers `auth_ok`. Nothing else is sent before that, and the token is sent once: HA bans an
 * address after ten failed logins. A run is `assist_pipeline/run`; HA answers with a `run-start`
 * event whose `runner_data.stt_binary_handler_id` is the first byte of every audio frame, and an
 * empty frame (the id alone) ends the audio. Events follow until `run-end` or `error`.
 *
 * The client keeps no state between calls, so it works any number of times.
 *
 * @param okHttp Should have a long read timeout and a ping interval, since a conversation's socket
 *   idles while the reply plays.
 */
class AssistPipelineClient(
    private val okHttp: OkHttpClient,
    private val authTimeoutMs: Long = AUTH_TIMEOUT_MS,
    private val runStartTimeoutMs: Long = RUN_START_TIMEOUT_MS,
    private val eventTimeoutMs: Long = PIPELINE_TIMEOUT_MS,
) : AssistGateway {
    companion object {
        /** How long HA has to authenticate the socket. */
        const val AUTH_TIMEOUT_MS = 10_000L

        /** How long HA has to accept a run (`run-start`). */
        const val RUN_START_TIMEOUT_MS = 10_000L

        /** How long a run may go without an event: the speech-to-text, the model or the voice can be slow. */
        const val PIPELINE_TIMEOUT_MS = 30_000L

        /** How long the reply audio may take to arrive. */
        const val TTS_READ_TIMEOUT_MS = 30_000L

        /** The most reply audio taken: a few seconds of speech is well under a megabyte. */
        const val MAX_TTS_BYTES = 5L * 1024 * 1024

        /**
         * The URL to fetch a reply from. HA sends a path (`/api/tts_proxy/...`), or in some versions
         * the full URL of its configured address. Only the path is kept, put on [baseUrl]: the
         * token is only ever sent to the address the session connected to.
         */
        fun resolveAudioUrl(baseUrl: String, audioUrl: String): String {
            val base = baseUrl.trimEnd('/')
            val absolute = audioUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.toHttpUrlOrNull()
            if (absolute != null) {
                val query = absolute.encodedQuery?.let { "?$it" }.orEmpty()
                return base + absolute.encodedPath + query
            }
            return base + (if (audioUrl.startsWith("/")) audioUrl else "/$audioUrl")
        }

        internal fun toWebSocketUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            val wsUrl = when {
                trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
                trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
                trimmed.startsWith("http://") -> "ws://${trimmed.removePrefix("http://")}"
                else -> "wss://$trimmed"
            }
            return "$wsUrl/api/websocket"
        }
    }

    /**
     * The client of the sockets: no redirects whatever [okHttp] says (OkHttp adds network interceptors
     * only to calls that are not WebSockets, so nothing would check where a 302 leads, and the token
     * would follow it), and for a cleartext ws:// socket no proxy and local addresses only.
     */
    private val webSocketClient: OkHttpClient by lazy {
        okHttp.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    private val cleartextWebSocketClient: OkHttpClient by lazy { webSocketClient.forCleartextWebSocket() }

    override suspend fun connect(baseUrl: String, token: String): AssistSession =
        withContext(Dispatchers.IO) {
            val socketClient = if (toWebSocketUrl(baseUrl).startsWith("ws://")) cleartextWebSocketClient else webSocketClient
            val session = WebSocketAssistSession(socketClient, baseUrl, token, runStartTimeoutMs, eventTimeoutMs)
            try {
                session.open(authTimeoutMs)
            } catch (e: Throwable) {
                session.close()
                throw e
            }
            session
        }

    /**
     * HA's Assist pipelines, for the app's picker. Opens a session of its own for the one request.
     * @throws IOException If HA cannot be reached or refuses the token.
     */
    suspend fun listPipelines(baseUrl: String, token: String): AssistPipelineList {
        val session = connect(baseUrl, token) as WebSocketAssistSession
        try {
            val result = session.request("assist_pipeline/pipeline/list")
            val pipelines = (result["pipelines"] as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj.string("id") ?: return@mapNotNull null
                AssistPipeline(id = id, name = obj.string("name") ?: id)
            }
            return AssistPipelineList(pipelines, result.string("preferred_pipeline"))
        } finally {
            session.close()
        }
    }

    override suspend fun fetchTtsAudio(baseUrl: String, audioUrl: String, token: String): ByteArray {
        val request = Request.Builder()
            .url(resolveAudioUrl(baseUrl, audioUrl))
            .header("Authorization", "Bearer $token")
            .build()

        // No redirects: a redirect would send the token to wherever it points.
        val call = okHttp.newBuilder()
            .readTimeout(TTS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
            .newCall(request)

        // Cancelling the caller cancels the request at once, so a cancel from the watch is not
        // held up by a slow reply.
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { readReply(it) }
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                        return
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }

    private fun readReply(response: Response): ByteArray {
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch TTS audio: ${response.code}")
        }
        val body = response.body ?: throw IOException("Empty TTS response")
        if (body.contentLength() > MAX_TTS_BYTES) throw IOException("TTS reply too large")
        val source = body.source()
        // One byte past the cap tells a reply that is too long from one that is exactly at it.
        if (source.request(MAX_TTS_BYTES + 1)) throw IOException("TTS reply too large")
        return source.readByteArray()
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** The websocket behind an [AssistSession]. */
private class WebSocketAssistSession(
    private val okHttp: OkHttpClient,
    override val baseUrl: String,
    private val token: String,
    private val runStartTimeoutMs: Long,
    private val eventTimeoutMs: Long,
) : AssistSession {

    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(1)
    private val auth = CompletableDeferred<Unit>()
    private val authSent = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Commands waiting for their `result`, by command id. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val runs = ConcurrentHashMap<Int, WebSocketAssistRun>()

    @Volatile private var webSocket: WebSocket? = null

    /** Connects and authenticates. @throws IOException On a refused token, a failed connection or a timeout. */
    suspend fun open(authTimeoutMs: Long) {
        val request = Request.Builder().url(AssistPipelineClient.toWebSocketUrl(baseUrl)).build()
        webSocket = okHttp.newWebSocket(request, Listener())
        withTimeoutOrNull(authTimeoutMs) { auth.await() }
            ?: throw IOException("Timed out connecting to Home Assistant")
    }

    /** Sends a command and returns its `result` object; the result's `result` field is returned. */
    suspend fun request(type: String): JsonObject {
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            send(buildJsonObject {
                put("id", id)
                put("type", type)
            })
            return withTimeoutOrNull(runStartTimeoutMs) { reply.await() }
                ?: throw IOException("Home Assistant did not answer $type")
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun startRun(pipelineId: String?, conversationId: String?): AssistRun {
        val id = nextId.getAndIncrement()
        val run = WebSocketAssistRun(id, this, eventTimeoutMs)
        runs[id] = run
        try {
            send(buildJsonObject {
                put("id", id)
                put("type", "assist_pipeline/run")
                put("start_stage", "stt")
                put("end_stage", "tts")
                put("input", buildJsonObject { put("sample_rate", 16000) })
                pipelineId?.let { put("pipeline", it) }
                conversationId?.let { put("conversation_id", it) }
            })
            withTimeoutOrNull(runStartTimeoutMs) { run.started.await() }
                ?: throw IOException("Home Assistant did not start the run")
            return run
        } catch (e: Throwable) {
            runs.remove(id)
            throw e
        }
    }

    fun sendBinary(frame: ByteArray) {
        webSocket?.send(frame.toByteString())
    }

    fun forget(runId: Int) {
        runs.remove(runId)
    }

    private fun send(message: JsonObject) {
        val ws = webSocket
        if (ws == null || closed.get() || !ws.send(message.toString())) {
            throw IOException("Connection to Home Assistant lost")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        webSocket?.close(1000, "Done")
        failAll(IOException("Connection closed"))
    }

    /** Ends everything waiting on this socket: the auth, the commands and the runs. */
    private fun failAll(cause: IOException) {
        auth.completeExceptionally(cause)
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        runs.values.toList().forEach { it.fail("connection_lost", cause.message ?: "Connection lost") }
        runs.clear()
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return
            when (message.string("type")) {
                "auth_required" -> {
                    // The token goes out once: a second auth_required gets no second attempt.
                    if (authSent.compareAndSet(false, true)) {
                        webSocket.send(buildJsonObject {
                            put("type", "auth")
                            put("access_token", token)
                        }.toString())
                    }
                }
                "auth_ok" -> auth.complete(Unit)
                "auth_invalid" -> auth.completeExceptionally(AssistAuthException())
                "result" -> onResult(message)
                "event" -> onEvent(message)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failAll(IOException("WebSocket connection failed: ${t.message}", t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            failAll(IOException("WebSocket closed: $reason"))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            failAll(IOException("WebSocket closed: $reason"))
        }
    }

    private fun onResult(message: JsonObject) {
        val id = message["id"].intValue() ?: return
        val success = (message["success"] as? JsonPrimitive)?.booleanOrNull ?: false
        val reply = pending[id]
        if (reply != null) {
            if (success) {
                reply.complete(message.obj("result") ?: JsonObject(emptyMap()))
            } else {
                reply.completeExceptionally(IOException(errorText(message)))
            }
            return
        }
        // A run's success needs no action: its events follow. A failure is the run's end.
        if (!success) {
            val error = message.obj("error")
            runs[id]?.fail(error?.string("code") ?: "unknown", error?.string("message") ?: "Unknown error")
        }
    }

    private fun errorText(message: JsonObject): String {
        val error = message.obj("error")
        return error?.string("message") ?: error?.string("code") ?: "Home Assistant refused the request"
    }

    private fun onEvent(message: JsonObject) {
        val id = message["id"].intValue() ?: return
        val event = message.obj("event") ?: return
        val type = event.string("type") ?: return
        val data = event.obj("data")
        val run = runs[id] ?: return
        when (type) {
            "run-start" -> {
                val handler = data?.obj("runner_data")?.get("stt_binary_handler_id").intValue()
                if (handler != null && handler in 0..255) {
                    run.started.complete(handler)
                } else {
                    run.fail("no_handler", "Home Assistant sent no audio handler id")
                }
            }
            "stt-end" -> {
                val text = data?.obj("stt_output")?.string("text") ?: ""
                run.emit(PipelineEvent.SttEnd(text))
            }
            "intent-end" -> {
                val output = data?.obj("intent_output")
                val speech = output?.obj("response")?.obj("speech")?.obj("plain")?.string("speech") ?: ""
                val continues = (output?.get("continue_conversation") as? JsonPrimitive)?.booleanOrNull ?: false
                run.emit(PipelineEvent.IntentEnd(speech, output?.string("conversation_id"), continues))
            }
            "tts-end" -> {
                val output = data?.obj("tts_output")
                run.emit(
                    PipelineEvent.TtsEnd(
                        audioUrl = output?.string("url") ?: "",
                        mimeType = output?.string("mime_type") ?: "audio/mpeg",
                    ),
                )
            }
            "error" -> run.fail(data?.string("code") ?: "unknown", data?.string("message") ?: "Unknown error")
            "run-end" -> run.emit(PipelineEvent.RunEnd)
        }
    }

    private fun JsonElement?.intValue(): Int? = (this as? JsonPrimitive)?.intOrNull
}

private class WebSocketAssistRun(
    private val id: Int,
    private val session: WebSocketAssistSession,
    private val eventTimeoutMs: Long,
) : AssistRun {

    /** The binary handler id from `run-start`, or the failure that came before it. */
    val started = CompletableDeferred<Int>()

    private val channel = Channel<PipelineEvent>(Channel.UNLIMITED)

    override fun sendAudio(pcm: ByteArray) {
        val handler = started.getCompleted()
        val frame = ByteArray(1 + pcm.size)
        frame[0] = handler.toByte()
        System.arraycopy(pcm, 0, frame, 1, pcm.size)
        session.sendBinary(frame)
    }

    override fun endAudio() {
        session.sendBinary(byteArrayOf(started.getCompleted().toByte()))
    }

    override val events: Flow<PipelineEvent> = flow {
        while (true) {
            val received = withTimeoutOrNull(eventTimeoutMs) { channel.receiveCatching() }
            val event = when {
                received == null -> PipelineEvent.Error("timeout", "Home Assistant did not answer in time")
                received.isClosed -> break
                else -> received.getOrThrow()
            }
            emit(event)
            if (event is PipelineEvent.RunEnd || event is PipelineEvent.Error) break
        }
        session.forget(id)
    }

    fun emit(event: PipelineEvent) {
        channel.trySend(event)
        if (event is PipelineEvent.RunEnd) channel.close()
    }

    /** Ends the run with an error: before `run-start` the start fails, after it an error event goes out. */
    fun fail(code: String, message: String) {
        if (!started.isCompleted) {
            started.completeExceptionally(IOException("$code: $message"))
        } else {
            channel.trySend(PipelineEvent.Error(code, message))
            channel.close()
        }
    }
}

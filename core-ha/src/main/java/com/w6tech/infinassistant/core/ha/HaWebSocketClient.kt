package com.w6tech.infinassistant.core.ha

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.LocalNetwork
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.common.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

sealed interface HaConnectionState {
    data object Disconnected : HaConnectionState
    data object Connecting : HaConnectionState
    data object Authenticating : HaConnectionState
    data object Connected : HaConnectionState
    data class Failed(val error: Throwable) : HaConnectionState
}

@Serializable
data class HaState(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    /** When the state last changed, an ISO-8601 time; null when HA did not say. */
    @SerialName("last_changed") val lastChanged: String? = null,
    /** When the state or an attribute last changed, an ISO-8601 time; null when HA did not say. */
    @SerialName("last_updated") val lastUpdated: String? = null,
)

/**
 * Gets one subscription's events in place of [HaWebSocketClient.events]
 * ([HaWebSocketClient.subscribeEntities], [HaWebSocketClient.subscribeEvents]). Both run on the
 * client's own threads and must not block.
 */
interface HaSubscriber {
    /** One of the subscription's events, in the order HA sent them. */
    fun onEvent(message: JsonObject)

    /** The connection the subscription was made on is gone: closed, failed or replaced. */
    fun onEnded()
}

/**
 * HA answered a command with an error ([code] is HA's, such as "unknown_command"), as opposed to
 * no answer at all: a timeout or a connection that went away.
 */
class HaErrorResult(val code: String?, message: String) : IllegalStateException(message)

@Serializable
data class HaEntityRegistryEntry(
    @SerialName("entity_id") val entityId: String,
    @SerialName("device_id") val deviceId: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val labels: List<String> = emptyList(),
)

@Serializable
data class HaDeviceRegistryEntry(
    val id: String,
    val name: String? = null,
    @SerialName("name_by_user") val nameByUser: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    // identifiers can come as array of arrays [[domain, unique_id], ...] or as set-like object
    // Using JsonElement to handle both formats from different HA versions
    @SerialName("identifiers") val identifiersRaw: JsonElement? = null,
) {
    /**
     * Parse identifiers as list of [domain, unique_id] pairs.
     * Handles both array format and set/object format from different HA versions.
     */
    val identifiers: List<List<String>>
        get() {
            val raw = identifiersRaw ?: return emptyList()
            return try {
                when (raw) {
                    is JsonArray -> {
                        // Standard format: [["domain", "id"], ["domain2", "id2"]]
                        raw.mapNotNull { element ->
                            when (element) {
                                is JsonArray -> element.mapNotNull { it.jsonPrimitive.content }
                                else -> null
                            }
                        }
                    }
                    is JsonObject -> {
                        // Set-like format: {"0": ["domain", "id"], "1": ["domain2", "id2"]}
                        raw.values.mapNotNull { element ->
                            when (element) {
                                is JsonArray -> element.mapNotNull { it.jsonPrimitive.content }
                                else -> null
                            }
                        }
                    }
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
}

class HaWebSocketClient(
    private val logger: Logger,
    private val scope: CoroutineScope,
    okHttp: OkHttpClient? = null,
    /** How often HA is pinged while connected ([startKeepalive]); the tests shorten it. */
    private val keepaliveMs: Long = KEEPALIVE_MS,
    /** How long a ping waits for HA's pong before the connection counts as dead. */
    private val pongTimeoutMs: Long = PONG_TIMEOUT_MS,
) {
    private val tag = "HaWebSocket"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true  // Required to include 'type' field with default value
    }

    // No redirects, whichever client this is. OkHttp adds network interceptors only for calls that are
    // not WebSockets, so the LAN-only guard's application half checks the first URL and nothing checks
    // where a 302 leads: the auth message would go to a cleartext host. HA never redirects /api/websocket.
    private val client = (
        okHttp ?: OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .lanOnlyCleartext()
            .build()
        ).newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val cleartextClient by lazy { client.forCleartextWebSocket() }

    private val _state = MutableStateFlow<HaConnectionState>(HaConnectionState.Disconnected)
    val state: StateFlow<HaConnectionState> = _state.asStateFlow()

    private val incomingText = Channel<String>(Channel.BUFFERED)

    private val eventsOut = Channel<JsonObject>(Channel.BUFFERED)
    val events = eventsOut.receiveAsFlow()

    private var ws: WebSocket? = null
    private var processorJob: Job? = null
    private var keepaliveJob: Job? = null

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

    /** The subscriptions whose events go to a subscriber of their own, by subscription ID. */
    private val subscribers = ConcurrentHashMap<Int, HaSubscriber>()

    /**
     * Subscriptions ended on this connection whose events HA may still send (one unsubscribed, one
     * whose request timed out after HA took it): dropped, not put on [events]'s 64 places.
     */
    private val endedSubscriptions: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** The base URL and token of the connection that authenticated, null when there is none. */
    @Volatile private var connectedTo: Pair<String, String>? = null

    /**
     * Bumped by every connect() and disconnect(). A socket's callbacks act only while it is the
     * current one: a replaced socket's close or failure, which arrives after its successor is up,
     * used to set this client Disconnected or Failed, and the bridge then took HA as lost.
     */
    private val socketGeneration = AtomicInteger(0)

    /**
     * Connects to [baseUrl] and authenticates with [token]. A client already connected there
     * with the same token is left as it is: HaApi hands the bridge's session client to its
     * requests, each of which connects first, and reconnecting it dropped the session and the
     * events it had subscribed to (app 2.1.107 did that every few seconds, through the
     * dashboard's forecast read).
     */
    suspend fun connect(baseUrl: String, token: String): AppResult<Unit> {
        if (isConnected() && connectedTo == (baseUrl to token)) return AppResult.Ok(Unit)
        return try {
            disconnect()
            val generation = socketGeneration.incrementAndGet()
            _state.value = HaConnectionState.Connecting

            val url = toWebSocketUrl(baseUrl)
            val req = Request.Builder().url(url).build()

            val authOk = CompletableDeferred<Unit>()
            val listener = object : WebSocketListener() {
                private fun current() = socketGeneration.get() == generation

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Wait for auth_required before sending auth; HA will send it quickly.
                    logger.i(tag, "WebSocket opened")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (current()) incomingText.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    logger.w(tag, "onClosing code=$code reason=$reason${if (current()) "" else " (a replaced socket)"}")
                    if (current()) {
                        _state.value = HaConnectionState.Disconnected
                        endSubscriptions()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    logger.e(tag, "onFailure ${t.message}${if (current()) "" else " (a replaced socket)"}", t)
                    if (current()) {
                        _state.value = HaConnectionState.Failed(t)
                        endSubscriptions()
                    }
                }
            }

            // A ws:// connection gets no network interceptor from OkHttp: its client has no proxy and local addresses only.
            ws = (if (url.startsWith("ws://")) cleartextClient else client).newWebSocket(req, listener)

            _state.value = HaConnectionState.Authenticating

            // Central processor: routes results/events and completes auth.
            processorJob = scope.launch(Dispatchers.Default) {
                try {
                    while (true) {
                        val text = incomingText.receive()
                        try {
                            val obj = json.parseToJsonElement(text).jsonObject
                            when (obj["type"]?.jsonPrimitive?.content) {
                                "auth_required" -> {
                                    logger.d(tag, "auth_required received, sending token (len=${token.length})")
                                    val msg = AuthMessage(accessToken = token)
                                    val payload = json.encodeToString(AuthMessage.serializer(), msg)
                                    ws?.send(payload)
                                }
                                "auth_ok" -> {
                                    connectedTo = baseUrl to token
                                    if (!authOk.isCompleted) authOk.complete(Unit)
                                    _state.value = HaConnectionState.Connected
                                }
                                "auth_invalid" -> {
                                    val msg = obj["message"]?.jsonPrimitive?.content ?: "no message"
                                    logger.e(tag, "auth_invalid: $msg")
                                    if (!authOk.isCompleted) authOk.completeExceptionally(IllegalStateException("auth_invalid: $msg"))
                                }
                                // A ping's answer is a "pong", not a "result".
                                "result", "pong" -> {
                                    val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull()
                                    if (id != null) {
                                        pending.remove(id)?.complete(obj)
                                    }
                                }
                                "event" -> {
                                    val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull()
                                    val subscriber = id?.let { subscribers[it] }
                                    when {
                                        subscriber != null -> subscriber.onEvent(obj)
                                        id != null && id in endedSubscriptions -> Unit
                                        else -> eventsOut.trySend(obj)
                                    }
                                }
                                else -> Unit
                            }
                        } catch (e: kotlinx.serialization.SerializationException) {
                            logger.w(tag, "Malformed JSON message, skipping: ${e.message}")
                            continue
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            logger.e(tag, "Error processing message: ${e.message}")
                        }
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    // Don't pass throwable — auth exceptions flow through here
                    logger.e(tag, "WebSocket processor error: ${Redactor.redact(t.message ?: "")}")
                    _state.value = HaConnectionState.Disconnected
                    endSubscriptions()
                    if (!authOk.isCompleted) authOk.completeExceptionally(t)
                }
            }

            withTimeout(15_000) { authOk.await() }
            startKeepalive(generation)
            AppResult.Ok(Unit)
        } catch (t: Throwable) {
            // Don't pass throwable — stacktrace may contain auth tokens
            logger.e(tag, "connect failed: ${Redactor.redact(t.message ?: "")}")
            _state.value = HaConnectionState.Failed(t)
            disconnect()
            AppResult.Err(t)
        }
    }

    fun isConnected(): Boolean = state.value == HaConnectionState.Connected

    suspend fun disconnect() {
        socketGeneration.incrementAndGet()
        connectedTo = null
        try {
            ws?.close(1000, "bye")
        } catch (_: Throwable) {
        }
        ws = null
        processorJob?.cancel()
        processorJob = null
        keepaliveJob?.cancel()
        keepaliveJob = null
        // What the old socket had sent and was not read yet: none of it is for the next one.
        while (incomingText.tryReceive().isSuccess) Unit
        // Failed, not cancelled: a cancelled deferred throws CancellationException into
        // the caller, which reads as its own cancellation and can silently end the
        // coroutine it runs in. connect() calls this, so the bridge's in-place reconnect
        // would do that to whatever request was in flight when HA dropped.
        pending.values.forEach { it.completeExceptionally(ConnectionClosedException()) }
        pending.clear()
        endSubscriptions()
        _state.value = HaConnectionState.Disconnected
    }

    /** The connection is gone, and its subscriptions with it: their subscribers are told. */
    private fun endSubscriptions() {
        val ended = subscribers.values.toList()
        subscribers.clear()
        // The IDs keep counting up on the next connection: none of these comes back.
        endedSubscriptions.clear()
        ended.forEach { subscriber ->
            try {
                subscriber.onEnded()
            } catch (e: Exception) {
                logger.w(tag, "Subscriber failed on the connection's end: ${e.message}")
            }
        }
    }

    suspend fun getStates(): AppResult<List<HaState>> {
        val id = nextId.getAndIncrement()
        val req = json.encodeToString(SimpleRequest.serializer(), SimpleRequest(id = id, type = "get_states"))
        val result = awaitResult(id, req) ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!success) return AppResult.Err(IllegalStateException("get_states failed"))
        val arr = result["result"] ?: return AppResult.Err(IllegalStateException("missing result"))
        return try {
            val states = json.decodeFromJsonElement(ListSerializer(HaState.serializer()), arr)
            AppResult.Ok(states)
        } catch (t: Throwable) {
            AppResult.Err(t)
        }
    }

    /**
     * Subscribes to [eventType] (every type when null). Its events go to [events], or to
     * [subscriber] when there is one. Ok with the subscription's ID, for [unsubscribe].
     */
    suspend fun subscribeEvents(eventType: String? = null, subscriber: HaSubscriber? = null): AppResult<Int> {
        val id = nextId.getAndIncrement()
        return subscribe(id, buildSubscribeEventsRequest(id, eventType), subscriber, "subscribe_events")
    }

    /**
     * Subscribes to the states of [entityIds] (subscribe_entities, HA 2022.4 and later). Their
     * events go to [subscriber]: the first has the state of every one of them HA has, the later
     * ones their changes, both in HA's compressed form ([HaCompressedStates]). Ok with the
     * subscription's ID, for [unsubscribe]; an older HA answers with an error. Never called with
     * no entities: HA reads an empty list as every entity it has.
     */
    suspend fun subscribeEntities(entityIds: Collection<String>, subscriber: HaSubscriber): AppResult<Int> {
        if (entityIds.isEmpty()) return AppResult.Err(IllegalArgumentException("subscribe_entities without entities"))
        val id = nextId.getAndIncrement()
        val request = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("subscribe_entities"),
                "entity_ids" to JsonArray(entityIds.map { JsonPrimitive(it) }),
            )
        )
        return subscribe(id, request, subscriber, "subscribe_entities")
    }

    /**
     * Ends a subscription of any kind (unsubscribe_events). Its subscriber gets nothing more from
     * here on, whatever HA answers.
     */
    suspend fun unsubscribe(subscriptionId: Int): AppResult<Unit> {
        subscribers.remove(subscriptionId)
        endedSubscriptions += subscriptionId
        val id = nextId.getAndIncrement()
        val request = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("unsubscribe_events"),
                "subscription" to JsonPrimitive(subscriptionId),
            )
        )
        val result = awaitResult(id, json.encodeToString(JsonObject.serializer(), request))
            ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        return if (success) AppResult.Ok(Unit) else AppResult.Err(IllegalStateException("unsubscribe_events failed"))
    }

    /**
     * Sends a subscription request. [subscriber] is in place before the request goes out: HA can
     * send the subscription's first event right behind its result, before this call returns. HA's
     * error answer is an [HaErrorResult]; no answer is any other error.
     */
    private suspend fun subscribe(id: Int, request: JsonObject, subscriber: HaSubscriber?, command: String): AppResult<Int> {
        if (subscriber != null) subscribers[id] = subscriber
        var subscribed = false
        try {
            val result = awaitResult(id, json.encodeToString(JsonObject.serializer(), request))
                ?: return AppResult.Err(IllegalStateException("no response"))
            subscribed = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
            if (subscribed) return AppResult.Ok(id)
            val error = result["error"] as? JsonObject
            val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
            val reason = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            return AppResult.Err(HaErrorResult(code, if (reason == null) "$command failed" else "$command failed: $reason"))
        } finally {
            if (!subscribed) {
                if (subscriber != null) subscribers.remove(id)
                // A request that timed out may still be taken: its events are not this client's.
                endedSubscriptions += id
            }
        }
    }

    suspend fun callService(domain: String, service: String, serviceData: JsonObject, target: JsonObject? = null): AppResult<Unit> {
        val id = nextId.getAndIncrement()
        val fields = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive("call_service"),
            "domain" to JsonPrimitive(domain),
            "service" to JsonPrimitive(service),
            "service_data" to serviceData,
        )
        if (target != null) {
            fields["target"] = target
        }
        val reqObj = JsonObject(fields)
        val req = json.encodeToString(JsonObject.serializer(), reqObj)
        val result = awaitResult(id, req) ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        return if (success) AppResult.Ok(Unit) else AppResult.Err(IllegalStateException("call_service failed"))
    }

    /**
     * Call a service with return_response flag to get response data.
     * Used for services that return data, like infinassistant.get_watchface.
     */
    suspend fun callServiceWithResponse(domain: String, service: String, serviceData: JsonObject): AppResult<JsonObject> {
        val id = nextId.getAndIncrement()
        val reqObj = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("call_service"),
                "domain" to JsonPrimitive(domain),
                "service" to JsonPrimitive(service),
                "service_data" to serviceData,
                "return_response" to JsonPrimitive(true),
            )
        )
        val req = json.encodeToString(JsonObject.serializer(), reqObj)
        val result = awaitResult(id, req) ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!success) {
            // Extract error message from HA response if available
            val errorMsg = result["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: "call_service failed"
            return AppResult.Err(IllegalStateException(errorMsg))
        }
        val response = result["result"]?.jsonObject?.get("response")?.jsonObject
            ?: return AppResult.Err(IllegalStateException("no response data"))
        return AppResult.Ok(response)
    }

    /**
     * Fetch entity registry entries with their label assignments.
     * Requires HA 2023.4+
     */
    suspend fun getEntityRegistry(): AppResult<List<HaEntityRegistryEntry>> {
        val id = nextId.getAndIncrement()
        val reqObj = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("config/entity_registry/list"),
            )
        )
        val req = json.encodeToString(JsonObject.serializer(), reqObj)
        val result = awaitResult(id, req) ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!success) return AppResult.Err(IllegalStateException("entity_registry/list failed"))
        val arr = result["result"] ?: return AppResult.Err(IllegalStateException("missing result"))
        return try {
            val entries = json.decodeFromJsonElement(ListSerializer(HaEntityRegistryEntry.serializer()), arr)
            AppResult.Ok(entries)
        } catch (t: Throwable) {
            AppResult.Err(t)
        }
    }

    /**
     * Fetch device registry entries.
     * Requires HA 2023.4+
     */
    suspend fun getDeviceRegistry(): AppResult<List<HaDeviceRegistryEntry>> {
        val id = nextId.getAndIncrement()
        val reqObj = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("config/device_registry/list"),
            )
        )
        val req = json.encodeToString(JsonObject.serializer(), reqObj)
        val result = awaitResult(id, req) ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!success) return AppResult.Err(IllegalStateException("device_registry/list failed"))
        val arr = result["result"] ?: return AppResult.Err(IllegalStateException("missing result"))
        return try {
            val devices = json.decodeFromJsonElement(ListSerializer(HaDeviceRegistryEntry.serializer()), arr)
            AppResult.Ok(devices)
        } catch (t: Throwable) {
            AppResult.Err(t)
        }
    }

    /**
     * Fetch logbook events for specific entities within a time window.
     * Uses HA WebSocket `logbook/get_events` message type.
     *
     * @param entityIds List of entity IDs to fetch logbook events for.
     * @param hoursBack How many hours back to query (default 12).
     * @param maxEntries Maximum entries to return, the newest (default 15).
     * @return List of logbook event objects (raw JsonObject), oldest first.
     */
    suspend fun getLogbook(
        entityIds: List<String>,
        hoursBack: Int = 12,
        maxEntries: Int = 15,
    ): AppResult<List<JsonObject>> {
        if (entityIds.isEmpty()) return AppResult.Ok(emptyList())

        val id = nextId.getAndIncrement()
        val now = java.time.Instant.now()
        val startTime = now.minus(hoursBack.toLong(), java.time.temporal.ChronoUnit.HOURS)
        val startIso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(startTime)
        val endIso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now)

        val reqObj = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "type" to JsonPrimitive("logbook/get_events"),
                "start_time" to JsonPrimitive(startIso),
                "end_time" to JsonPrimitive(endIso),
                "entity_ids" to JsonArray(entityIds.map { JsonPrimitive(it) }),
            )
        )
        val req = json.encodeToString(JsonObject.serializer(), reqObj)
        val result = awaitResult(id, req) ?: return AppResult.Err(IllegalStateException("no response"))
        val success = result["success"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        if (!success) return AppResult.Err(IllegalStateException("logbook/get_events failed"))
        val arr = result["result"] ?: return AppResult.Err(IllegalStateException("missing result"))
        return try {
            // HA returns the window oldest first: past maxEntries, keep the newest.
            val events = arr.jsonArray.map { it.jsonObject }.takeLast(maxEntries)
            AppResult.Ok(events)
        } catch (t: Throwable) {
            AppResult.Err(t)
        }
    }

    /**
     * Send a request and wait for its result. Null when there is no answer: no connection,
     * a socket that refuses the send (it failed or is closing), the connection closed while
     * waiting, or 15 s of silence. Callers report null as "no response".
     */
    private suspend fun awaitResult(id: Int, payload: String): JsonObject? {
        val w = ws ?: return null
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        if (!w.send(payload)) {
            pending.remove(id)
            logger.w(tag, "Request $id not sent: the connection is closed")
            return null
        }
        return try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.w(tag, "Request $id timed out after 15000ms")
            pending.remove(id)
            null
        } catch (e: ConnectionClosedException) {
            logger.w(tag, "Request $id dropped: the connection closed")
            null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Keeps the connection busy and checks that it is alive: HA's ping every [keepaliveMs] while
     * connected. Since app 2.1.110 the bridge receives only the watched entities' changes, so the
     * socket can stay silent for minutes, and HA's side closed such a connection after about a
     * minute of silence (a debug bundle of 2026-10-16: 345 drops in 9 h, in 30 s steps of
     * silence; before, the house's every state change kept it busy). A message every 25 s is
     * traffic any idle check counts. A pong that does not come within [pongTimeoutMs] means the
     * connection is dead: the socket is cancelled, which fails it, and the bridge reconnects now.
     */
    private fun startKeepalive(generation: Int) {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(keepaliveMs)
                if (socketGeneration.get() != generation || !isConnected()) return@launch
                val w = ws ?: return@launch
                val id = nextId.getAndIncrement()
                val pong = CompletableDeferred<JsonObject>()
                pending[id] = pong
                val answered = try {
                    w.send("""{"id":$id,"type":"ping"}""") && withTimeoutOrNull(pongTimeoutMs) { pong.await() } != null
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch  // the connection closed under the ping (disconnect)
                } finally {
                    pending.remove(id)
                }
                if (!answered && socketGeneration.get() == generation) {
                    logger.w(tag, "No pong from HA within ${pongTimeoutMs / 1000} s: closing the connection")
                    w.cancel()
                    return@launch
                }
            }
        }
    }

    /** Fails the requests still waiting when [disconnect] closes the connection. */
    private class ConnectionClosedException : Exception("HA connection closed")

    companion object {
        /** HA's ping interval while connected: under the minute of silence HA's side allowed. */
        const val KEEPALIVE_MS = 25_000L
        const val PONG_TIMEOUT_MS = 15_000L
    }

    private fun buildSubscribeEventsRequest(id: Int, eventType: String?): JsonObject {
        val base = mutableMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "type" to JsonPrimitive("subscribe_events"),
        )
        if (!eventType.isNullOrBlank()) base["event_type"] = JsonPrimitive(eventType)
        return JsonObject(base)
    }

    private fun toWebSocketUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val isLocal = LocalNetwork.isCleartextAllowed(trimmed)

        val wsUrl = when {
            trimmed.startsWith("wss://") -> trimmed
            trimmed.startsWith("ws://") -> {
                require(isLocal) { "Remote WebSocket URLs must use wss://" }
                trimmed
            }
            trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}"
            trimmed.startsWith("http://") -> {
                require(isLocal) { "Remote HA URLs must use https://" }
                "ws://${trimmed.removePrefix("http://")}"
            }
            else -> "wss://$trimmed"
        }
        return "$wsUrl/api/websocket"
    }

    @Serializable
    private data class SimpleRequest(
        val id: Int,
        val type: String,
    )

    @Serializable
    private data class AuthMessage(
        val type: String = "auth",
        @SerialName("access_token") val accessToken: String,
    )
}

package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import com.w6tech.infinassistant.core.ha.lanOnlyCleartext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class HaApi(
    private val logger: Logger,
    private val settingsRepo: SettingsRepository,
    private val tokenStore: HaTokenStore,
    private val urlSelector: UrlSelector,
) {
    /**
     * Singleton OkHttpClient for calendar API requests.
     */
    private val calendarHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .lanOnlyCleartext()
            .build()
    }

    /**
     * Session-scoped WebSocket client set by BridgeEngine during connected mode.
     * When set, [withWebSocketClient] reuses this client instead of creating new connections.
     */
    @Volatile
    private var sessionClient: HaWebSocketClient? = null

    /** Set the session WebSocket client for connected mode. Avoids creating new connections per API call. */
    fun setSessionClient(client: HaWebSocketClient) {
        sessionClient = client
    }

    /** Clear the session WebSocket client when connected mode ends. */
    fun clearSessionClient() {
        sessionClient = null
    }

    /**
     * Execute an action with a WebSocket client.
     * If a session client is available and connected, reuses it.
     * Otherwise creates a scoped client that's cleaned up automatically.
     */
    private suspend fun <T> withWebSocketClient(
        reuseSession: Boolean = true,
        action: suspend (HaWebSocketClient) -> T
    ): T {
        val session = sessionClient
        if (reuseSession && session != null && session.isConnected()) {
            return action(session)
        }
        return coroutineScope {
            val ws = HaWebSocketClient(logger, this)
            try {
                action(ws)
            } finally {
                ws.disconnect()
            }
        }
    }

    /**
     * Connects [ws] for a request. The session's client, when connected, is used as it is: a
     * connect() to the URL this request selected replaces its socket whenever the selection differs
     * (another network since the session connected), and the session's subscriptions go with it.
     */
    private suspend fun connectFor(ws: HaWebSocketClient, url: String, token: String): AppResult<Unit> =
        if (ws === sessionClient && ws.isConnected()) AppResult.Ok(Unit) else ws.connect(url, token)

    /**
     * Helper method to select the appropriate URL.
     */
    private suspend fun selectUrl(): AppResult<String> {
        val settings = settingsRepo.settings.first()
        val urlResult = urlSelector.selectUrl(settings.ha)

        return when (urlResult) {
            is UrlSelectionResult.Selected -> {
                logger.d("HaApi", "Selected URL: ${urlResult.url} (source: ${urlResult.source})")
                AppResult.Ok(urlResult.url)
            }
            is UrlSelectionResult.Error -> {
                AppResult.Err(IllegalStateException(urlResult.reason))
            }
        }
    }

    suspend fun testConnection(): AppResult<Unit> = withContext(Dispatchers.Default) {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return@withContext AppResult.Err(IllegalStateException("HA token missing"))
        }

        val urlResult = selectUrl()
        if (urlResult is AppResult.Err) {
            return@withContext urlResult
        }
        val url = (urlResult as AppResult.Ok).value

        logger.d("HaApi", "testConnection: url=$url, tokenLen=${token.length}")

        // A client of its own: the test is of the URL just selected, and the session's is left alone.
        withWebSocketClient(reuseSession = false) { ws ->
            ws.connect(url, token)
        }
    }

    suspend fun callService(domain: String, service: String, serviceData: JsonObject): AppResult<Unit> = withContext(Dispatchers.Default) {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return@withContext AppResult.Err(IllegalStateException("HA token missing"))
        }

        val urlResult = selectUrl()
        if (urlResult is AppResult.Err) {
            return@withContext urlResult
        }
        val url = (urlResult as AppResult.Ok).value

        withWebSocketClient { ws ->
            when (val c = connectFor(ws, url, token)) {
                is AppResult.Err -> c
                is AppResult.Ok -> ws.callService(domain, service, serviceData)
            }
        }
    }

    /**
     * Call a Home Assistant service with response data.
     * Used for services that return data, like infinassistant.get_watchface.
     */
    suspend fun callServiceWithResponse(domain: String, service: String, serviceData: JsonObject): AppResult<JsonObject> = withContext(Dispatchers.Default) {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return@withContext AppResult.Err(IllegalStateException("HA token missing"))
        }

        val urlResult = selectUrl()
        if (urlResult is AppResult.Err) {
            return@withContext urlResult
        }
        val url = (urlResult as AppResult.Ok).value

        withWebSocketClient { ws ->
            when (val c = connectFor(ws, url, token)) {
                is AppResult.Err -> c
                is AppResult.Ok -> ws.callServiceWithResponse(domain, service, serviceData)
            }
        }
    }

    /**
     * Fetch device registry entries.
     * Requires HA 2023.4+
     */
    suspend fun fetchDeviceRegistry(): AppResult<List<com.w6tech.infinassistant.core.ha.HaDeviceRegistryEntry>> = withContext(Dispatchers.Default) {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return@withContext AppResult.Err(IllegalStateException("HA token missing"))
        }

        val urlResult = selectUrl()
        if (urlResult is AppResult.Err) {
            return@withContext urlResult
        }
        val url = (urlResult as AppResult.Ok).value

        withWebSocketClient { ws ->
            when (val c = connectFor(ws, url, token)) {
                is AppResult.Err -> c
                is AppResult.Ok -> ws.getDeviceRegistry()
            }
        }
    }

    /**
     * Fetch today's forecast high/low for a weather entity.
     *
     * Home Assistant REMOVED the `forecast` attribute from weather entities in
     * 2024.4 — it now only comes from the weather.get_forecasts service. Reading
     * state.attributes["forecast"] therefore always returns null on any modern
     * HA, and callers that fall back to the current temperature end up showing
     * hi == lo == temp, which is exactly what the watch was displaying
     * ("H:74 L:74", "H:69 L:69", "H:99 L:99" on successive days).
     *
     * Returns (hi, lo) or null when the forecast is unavailable, so the caller
     * can keep whatever fallback it had rather than showing a wrong number.
     */
    suspend fun fetchWeatherForecast(entityId: String): AppResult<Pair<Int, Int>?> =
        withContext(Dispatchers.Default) {
            val token = tokenStore.getToken()
            if (token.isNullOrBlank()) {
                return@withContext AppResult.Err(IllegalStateException("HA token missing"))
            }
            val urlResult = selectUrl()
            if (urlResult is AppResult.Err) return@withContext urlResult
            val url = (urlResult as AppResult.Ok).value

            withWebSocketClient { ws ->
                when (val c = connectFor(ws, url, token)) {
                    is AppResult.Err -> c
                    is AppResult.Ok -> {
                        val data = JsonObject(
                            mapOf(
                                "entity_id" to JsonPrimitive(entityId),
                                "type" to JsonPrimitive("daily"),
                            )
                        )
                        when (val r = ws.callServiceWithResponse("weather", "get_forecasts", data)) {
                            is AppResult.Err -> AppResult.Ok(null)  // no forecast: caller keeps its fallback
                            is AppResult.Ok -> try {
                                // Response shape: { "<entity_id>": { "forecast": [ {...}, ... ] } }
                                val today = r.value[entityId]?.jsonObject
                                    ?.get("forecast")?.jsonArray
                                    ?.firstOrNull()?.jsonObject
                                val hi = today?.get("temperature")?.jsonPrimitive?.doubleOrNull?.toInt()
                                val lo = today?.get("templow")?.jsonPrimitive?.doubleOrNull?.toInt()
                                if (hi != null && lo != null) {
                                    AppResult.Ok(hi to lo)
                                } else {
                                    logger.d("HaApi", "get_forecasts returned no temperature/templow for $entityId")
                                    AppResult.Ok(null)
                                }
                            } catch (e: Exception) {
                                logger.w("HaApi", "Failed to parse get_forecasts for $entityId: ${e.message}")
                                AppResult.Ok(null)
                            }
                        }
                    }
                }
            }
        }

    /**
     * Fetch watch face configuration for a specific device from Home Assistant.
     * Calls infinassistant.get_watchface service with return_response.
     * Returns the JSON as a String, unwrapping the {"layout": {...}} wrapper if present.
     */
    suspend fun fetchWatchFaceForDevice(deviceId: String): AppResult<String> = withContext(Dispatchers.Default) {
        val token = tokenStore.getToken()
        if (token.isNullOrBlank()) {
            return@withContext AppResult.Err(IllegalStateException("HA token missing"))
        }

        val urlResult = selectUrl()
        if (urlResult is AppResult.Err) {
            return@withContext urlResult
        }
        val url = (urlResult as AppResult.Ok).value

        withWebSocketClient { ws ->
            when (val c = connectFor(ws, url, token)) {
                is AppResult.Err -> c
                is AppResult.Ok -> {
                    val serviceData = JsonObject(
                        mapOf("device_id" to kotlinx.serialization.json.JsonPrimitive(deviceId))
                    )
                    when (val result = ws.callServiceWithResponse("infinassistant", "get_watchface", serviceData)) {
                        is AppResult.Ok -> {
                            // Parse response and unwrap if it has {"layout": {...}} structure
                            val responseBody = result.value.toString()
                            try {
                                val rootElement = Json.parseToJsonElement(responseBody)

                                // Check if root is a JsonObject containing a "layout" key
                                if (rootElement is JsonObject && rootElement.containsKey("layout")) {
                                    val layoutElement = rootElement["layout"]

                                    // Handle layout being JsonNull
                                    if (layoutElement is JsonNull || layoutElement == null) {
                                        logger.w("HaApi", "HA service response contains null 'layout' value")
                                        AppResult.Err(IllegalStateException("Layout is null"))
                                    } else {
                                        // Extract layout and serialize back to string
                                        val unwrappedJson = layoutElement.toString()
                                        logger.d("HaApi", "Detected HA service response wrapper with keys: [${rootElement.keys}]. Extracting 'layout'.")
                                        AppResult.Ok(unwrappedJson)
                                    }
                                } else {
                                    // Response is already unwrapped or doesn't have "layout" key
                                    logger.d("HaApi", "HA response already unwrapped (no 'layout' key at root)")
                                    AppResult.Ok(responseBody)
                                }
                            } catch (e: Exception) {
                                logger.w("HaApi", "Failed to parse HA response as JSON: ${e.message}")
                                AppResult.Err(e)
                            }
                        }
                        is AppResult.Err -> result
                    }
                }
            }
        }
    }

    /**
     * Fetch upcoming calendar events for a specific calendar entity.
     * Returns up to maxEvents upcoming events within the next 7 days.
     *
     * Uses HA REST API: GET /api/calendars/<entity_id>?start=<iso>&end=<iso>
     */
    suspend fun fetchCalendarEvents(calendarEntityId: String, maxEvents: Int = 3): AppResult<List<CalendarEvent>> {
        return withContext(Dispatchers.IO) {
            try {
                val urlResult = selectUrl()
                if (urlResult is AppResult.Err) return@withContext AppResult.Err(urlResult.error)
                val baseUrl = (urlResult as AppResult.Ok).value

                val token = tokenStore.getToken()
                if (token.isNullOrEmpty()) {
                    return@withContext AppResult.Err(IllegalStateException("No HA token configured"))
                }

                // Query next 7 days of events
                val now = java.time.Instant.now()
                val endTime = now.plus(7, java.time.temporal.ChronoUnit.DAYS)
                val startIso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now)
                val endIso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(endTime)

                val url = "$baseUrl/api/calendars/$calendarEntityId?start=$startIso&end=$endIso"

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .get()
                    .build()

                val response = calendarHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    logger.w("HaApi", "Calendar fetch failed: HTTP ${response.code}")
                    return@withContext AppResult.Err(IllegalStateException("HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: return@withContext AppResult.Err(IllegalStateException("Empty response"))

                // Parse JSON array of calendar events
                val jsonParser = Json { ignoreUnknownKeys = true }
                val events = jsonParser.decodeFromString<List<CalendarEventRaw>>(body)

                // Convert to simplified format, take first maxEvents
                val simplified = mutableListOf<CalendarEvent>()
                for (rawEvent in events.take(maxEvents)) {
                    // Parse start time to HH:mm format and Instant for sorting. All-day events
                    // have a date and no time: they keep a null start.
                    val startInstant = parseEventInstant(rawEvent.start.dateTime)
                    if (startInstant == null && rawEvent.start.dateTime != null) {
                        logger.w("HaApi", "Failed to parse calendar event start time: ${rawEvent.start.dateTime}")
                    }

                    val timeStr = try {
                        if (startInstant != null) {
                            val zonedTime = startInstant.atZone(ZoneId.systemDefault())
                            DateTimeFormatter.ofPattern("HH:mm").format(zonedTime)
                        } else {
                            "--:--"
                        }
                    } catch (e: Exception) {
                        logger.w("HaApi", "Failed to format event time: ${e.message}")
                        "--:--"
                    }

                    simplified.add(CalendarEvent(
                        time = timeStr,
                        title = rawEvent.summary.take(32),  // Firmware limit
                        startInstant = startInstant,
                        startEpoch = startInstant?.epochSecond ?: 0L,
                    ))
                }

                logger.d("HaApi", "Fetched ${simplified.size} events from $calendarEntityId")
                AppResult.Ok(simplified)

            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.w("HaApi", "Calendar fetch exception: ${e.message}", e)
                AppResult.Err(e)
            }
        }
    }

    /**
     * One calendar's events on one local day, for the watch's calendar app: the timed events that
     * start on [date] (from [from] on, when given: today keeps what is still ahead or running) and
     * the all-day events that cover it. Times are local "HH:mm"; an all-day event reads "All", has
     * [CalendarEvent.allDay] set and no start.
     *
     * Uses HA REST API: GET /api/calendars/<entity_id>?start=<iso>&end=<iso>, which returns every
     * event overlapping the window, past days included.
     */
    suspend fun fetchCalendarDay(
        calendarEntityId: String,
        date: LocalDate,
        from: Instant? = null,
    ): AppResult<List<CalendarEvent>> {
        return withContext(Dispatchers.IO) {
            try {
                val urlResult = selectUrl()
                if (urlResult is AppResult.Err) return@withContext AppResult.Err(urlResult.error)
                val baseUrl = (urlResult as AppResult.Ok).value

                val token = tokenStore.getToken()
                if (token.isNullOrEmpty()) {
                    return@withContext AppResult.Err(IllegalStateException("No HA token configured"))
                }

                val zone = ZoneId.systemDefault()
                val dayStart = date.atStartOfDay(zone).toInstant()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
                val windowStart = if (from != null && from.isAfter(dayStart) && from.isBefore(dayEnd)) from else dayStart
                val startIso = DateTimeFormatter.ISO_INSTANT.format(windowStart)
                val endIso = DateTimeFormatter.ISO_INSTANT.format(dayEnd)

                val request = Request.Builder()
                    .url("$baseUrl/api/calendars/$calendarEntityId?start=$startIso&end=$endIso")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .get()
                    .build()

                val response = calendarHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    logger.w("HaApi", "Calendar day fetch failed: HTTP ${response.code}")
                    return@withContext AppResult.Err(IllegalStateException("HTTP ${response.code}"))
                }
                val body = response.body?.string() ?: return@withContext AppResult.Err(IllegalStateException("Empty response"))
                val events = Json { ignoreUnknownKeys = true }.decodeFromString<List<CalendarEventRaw>>(body)

                val dayEvents = mutableListOf<CalendarEvent>()
                for (raw in events) {
                    val start = parseEventInstant(raw.start.dateTime)
                    if (start != null) {
                        if (start.isBefore(dayStart) || !start.isBefore(dayEnd)) continue
                        dayEvents += CalendarEvent(
                            time = DateTimeFormatter.ofPattern("HH:mm").format(start.atZone(zone)),
                            title = raw.summary,
                            startInstant = start,
                            startEpoch = start.epochSecond,
                        )
                        continue
                    }
                    // All-day: start date inclusive, end date exclusive (one day when absent).
                    val firstDay = raw.start.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: continue
                    val endDay = raw.end?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: firstDay.plusDays(1)
                    if (date.isBefore(firstDay) || !date.isBefore(endDay)) continue
                    dayEvents += CalendarEvent(time = "All", title = raw.summary, allDay = true)
                }
                AppResult.Ok(dayEvents)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.w("HaApi", "Calendar day fetch exception: ${e.message}", e)
                AppResult.Err(e)
            }
        }
    }

    /**
     * A timed event's start. HA writes local time with its offset ("2026-10-10T09:00:00-06:00"),
     * which Instant.parse rejects on older Android releases, so the offset form is tried first.
     * Null for an all-day event (no dateTime) or text that parses neither way.
     */
    private fun parseEventInstant(dateTime: String?): Instant? {
        if (dateTime.isNullOrEmpty()) return null
        return runCatching { OffsetDateTime.parse(dateTime).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(dateTime) }.getOrNull()
    }
}

/**
 * Calendar event returned by HA REST API.
 */
@Serializable
private data class CalendarEventRaw(
    val summary: String,
    val start: CalendarDateTime,
    val end: CalendarDateTime? = null,
)

@Serializable
private data class CalendarDateTime(
    @SerialName("dateTime") val dateTime: String? = null,
    val date: String? = null,  // All-day events use date instead of dateTime
)

/**
 * Simplified calendar event for watch display.
 */
data class CalendarEvent(
    val time: String,  // HH:mm format
    val title: String,
    val startInstant: Instant? = null,  // Parsed ISO timestamp for sorting
    /** UTC epoch seconds of event start. Zero when not available (all-day events or parse failure). */
    val startEpoch: Long = 0,
    /** An all-day event (fetchCalendarDay only; fetchCalendarEvents leaves it false). */
    val allDay: Boolean = false,
)

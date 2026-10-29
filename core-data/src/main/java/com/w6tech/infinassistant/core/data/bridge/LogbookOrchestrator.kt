package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Feeds the logbook app on the watch (HaLogbook) and the face's logbook screens.
 *
 * The watch shows the entries the phone last sent, as "time state subject", at most [MAX_ENTRIES].
 * The logbook app's entries go out unasked: on every connection, after a layout push, and
 * [PUSH_DELAY_MS] after a state change of one of its entities (sendLogbookApp, onStateChange).
 *
 * The app logs the entities the face lists for it or, when it lists none, the face's motion
 * sensors ([appLogbook]).
 *
 * The entries are HA's logbook plus the state changes seen live ([withLive]). HA's recorder writes a
 * change to its database at its next commit, every 5 s by default, so a logbook fetch made 2 s after
 * the change does not have it yet. Until 2.1.100 the push a change caused usually missed that very
 * change, and the watch kept showing the older entries.
 */
class LogbookOrchestrator(
    private val logger: Logger,
    private val protocolHandler: ProtocolHandler,
    private val layoutProvider: () -> WatchFaceLayoutPayload?,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
) {
    private val tag = "LogbookOrchestrator"
    private var pushJob: Job? = null

    /** State changes of logbook entities seen live, oldest first, at most [MAX_LIVE_EVENTS]. */
    private val liveEvents = ArrayDeque<JsonObject>()

    /** The HaLogbook app's entries, unasked. Nothing is sent when it has no entities ([appLogbook]). */
    fun sendLogbookApp(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        val config = layoutProvider()?.let { appLogbook(it) } ?: return
        send(APP_SCREEN_INDEX, config, ha, transport, sessionScope)
    }

    /**
     * A watched entity's state changed (HaStateCache). A state change of one of the logbook app's
     * entities is a new logbook entry: it is kept ([liveEvents]) and the entries go out again
     * [PUSH_DELAY_MS] later, once for a burst.
     */
    fun onStateChange(change: HaStateChange, ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        val entityIds = layoutProvider()?.let { appLogbook(it) }?.entityIds.orEmpty()
        if (change.entityId !in entityIds) return
        val entry = liveEvent(change) ?: return
        synchronized(liveEvents) {
            liveEvents.addLast(entry)
            while (liveEvents.size > MAX_LIVE_EVENTS) liveEvents.removeFirst()
        }
        pushJob?.cancel()
        pushJob = sessionScope.launch {
            delay(PUSH_DELAY_MS)
            sendLogbookApp(ha, transport, sessionScope)
        }
    }

    private fun send(
        screenIndex: Int,
        config: WatchLogbook?,
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
    ) {
        sessionScope.launch {
            try {
                val entries = if (config == null || config.entityIds.isEmpty()) {
                    emptyList()
                } else {
                    // HA did not answer: the watch keeps the entries it has.
                    fetchEntries(config, ha) ?: return@launch
                }
                val payload = WireFrame.fit(entries, { ProtocolCbor.encode(LogbookUpdatePayload.serializer(), it) }) {
                    LogbookUpdatePayload(screenIndex = screenIndex, entries = it)
                }
                protocolHandler.sendToWatch(
                    transport, Protocol.Type.LOGBOOK_UPDATE, protocolHandler.nextMsgId(), payload,
                )
                logCallback("logbook", "LOGBOOK_UPDATE sent", null,
                    "screen=$screenIndex, ${payload.entries.size} entries, ${config?.entityIds?.size ?: 0} entities")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e(tag, "Logbook update for screen $screenIndex failed: ${e.message}", e)
            }
        }
    }

    /** The newest entries for [config], or null when HA's logbook could not be read. */
    private suspend fun fetchEntries(config: WatchLogbook, ha: HaWebSocketClient): List<LogbookEntry>? {
        val entityIdsSet = config.entityIds.toSet()
        // The names are the state cache's: HA's logbook/get_events gives none.
        val stateCache = protocolHandler.stateCache
        val friendlyNames: Map<String, String> = if (stateCache.awaitSnapshot(ha)) {
            stateCache.states(entityIdsSet).mapNotNull { (entityId, s) ->
                val name = (s.attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull
                if (!name.isNullOrBlank()) entityId to name else null
            }.toMap()
        } else {
            emptyMap()
        }
        // The window's events come oldest first: fetch them all and keep the newest here.
        val events = when (val result = ha.getLogbook(entityIds = config.entityIds, hoursBack = config.hoursBack, maxEntries = FETCH_LIMIT)) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> {
                logger.w(tag, "Logbook fetch failed: ${result.error.message}")
                return null
            }
        }
        val live = synchronized(liveEvents) { liveEvents.toList() }
        val windowStart = Instant.now().minusSeconds(config.hoursBack * 3600L)
        return toEntries(withLive(events, live, entityIdsSet, windowStart), friendlyNames, ZoneId.systemDefault())
    }

    companion object {
        /** LOGBOOK_UPDATE screen index of the HaLogbook app. */
        const val APP_SCREEN_INDEX = 0xFF
        /** HomeAssistantService::kMaxLogbookEntries (8 from firmware 2.4.91; 4 before, which keeps the first 4). */
        const val MAX_ENTRIES = 8
        /** LogbookEntry::time and ::state are char[8], ::subject char[32]. */
        const val MAX_TIME_BYTES = 7
        const val MAX_STATE_BYTES = 7
        const val MAX_SUBJECT_BYTES = 31
        /** Events fetched per update, before the newest are kept. */
        const val FETCH_LIMIT = 500
        const val PUSH_DELAY_MS = 2_000L
        /** Live state changes kept; older ones are in HA's logbook by the time they would matter. */
        const val MAX_LIVE_EVENTS = 16
        /** WatchStatusGroup::type of the face's motion sensors. */
        private const val MOTION_GROUP = "motion"

        /**
         * The logbook app's settings: the entities the face lists for it or, when it lists none,
         * the face's motion sensors (the glyphs of its dashboards' "motion" groups). Null when
         * there are neither.
         */
        internal fun appLogbook(layout: WatchFaceLayoutPayload): WatchLogbook? {
            val app = layout.logbookApp ?: WatchLogbookAppConfig()
            val entityIds = app.entityIds.ifEmpty { motionSensors(layout) }
            if (entityIds.isEmpty()) return null
            return WatchLogbook(entityIds, app.maxEntries, app.hoursBack)
        }

        /** The entities of the face's motion glyphs, each once, in the order the face shows them. */
        internal fun motionSensors(layout: WatchFaceLayoutPayload): List<String> =
            layout.screens.asSequence()
                .mapNotNull { it.dashboard }
                .flatMap { it.statusGroups }
                .filter { it.type.equals(MOTION_GROUP, ignoreCase = true) }
                .flatMap { it.glyphs }
                .mapNotNull { glyph -> glyph.entityId?.takeIf { it.isNotBlank() } }
                .distinct()
                .toList()

        /**
         * A state change as a logbook event ("when", "entity_id", "state" and the friendly name as
         * "name"), or null for what HA's logbook does not list either: an attribute change, and the
         * first state of an entity that had none (new to HA, or loaded only now).
         */
        internal fun liveEvent(change: HaStateChange): JsonObject? {
            val old = change.old ?: return null
            val new = change.new ?: return null
            if (new.state == old.state) return null
            val at = listOfNotNull(new.lastChanged, new.lastUpdated).firstNotNullOfOrNull { parseWhen(it) } ?: return null
            val name = (new.attributes["friendly_name"] as? JsonPrimitive)?.contentOrNull
            return buildJsonObject {
                put("when", at.epochSecond + at.nano / 1e9)
                put("entity_id", change.entityId)
                put("state", new.state)
                if (!name.isNullOrBlank()) put("name", name)
            }
        }

        /**
         * HA's logbook [events] plus the [live] changes it does not list yet. A live change is listed
         * when an event has the same entity, state and second. Live changes of entities outside
         * [entityIds], or from before [windowStart], are left out.
         */
        internal fun withLive(
            events: List<JsonObject>,
            live: List<JsonObject>,
            entityIds: Set<String>,
            windowStart: Instant,
        ): List<JsonObject> {
            fun text(e: JsonObject, key: String) = (e[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun at(e: JsonObject) = (e["when"] as? JsonPrimitive)?.let { p -> p.doubleOrNull?.let { Instant.ofEpochMilli((it * 1000).toLong()) } ?: parseWhen(p.content) }
            fun key(e: JsonObject) = at(e)?.let { Triple(text(e, "entity_id"), text(e, "state"), it.epochSecond) }
            val listed = events.mapNotNull(::key).toMutableSet()
            val missing = live.filter { e ->
                val t = at(e) ?: return@filter false
                val k = Triple(text(e, "entity_id"), text(e, "state"), t.epochSecond)
                k.first in entityIds && !t.isBefore(windowStart) && listed.add(k)
            }
            return events + missing
        }

        /** HA logbook events as the watch shows them: newest first, at most [MAX_ENTRIES], cut to its buffers. */
        internal fun toEntries(events: List<JsonObject>, friendlyNames: Map<String, String>, zone: ZoneId): List<LogbookEntry> {
            val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
            return events.mapNotNull { event ->
                fun field(vararg keys: String): String =
                    keys.firstNotNullOfOrNull { k -> (event[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }.orEmpty()
                val at = parseWhen(field("when")) ?: return@mapNotNull null
                val entityId = field("entity_id", "context_entity_id")
                val subject = field("name", "entity_name", "friendly_name").ifBlank {
                    friendlyNames[entityId]
                        ?: entityId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }
                }
                val state = shortState(field("state").ifBlank { field("message", "new_state", "context_message", "context_state") })
                at to LogbookEntry(
                    time = WireText.fit(timeFormat.format(at.atZone(zone)), MAX_TIME_BYTES),
                    state = state,
                    subject = WireText.fit(subject, MAX_SUBJECT_BYTES),
                )
            }.sortedByDescending { it.first }.take(MAX_ENTRIES).map { it.second }
        }

        /** "when": epoch seconds (logbook/get_events), or an ISO time. */
        private fun parseWhen(text: String): Instant? {
            if (text.isEmpty()) return null
            text.toDoubleOrNull()?.let { return Instant.ofEpochMilli((it * 1000).toLong()) }
            return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(text) }.getOrNull()
        }

        /**
         * A state or logbook message ("turned on", "changed to Home", "was unlocked") as the short
         * upper-case word the watch's 7 bytes hold.
         */
        internal fun shortState(raw: String): String {
            var s = raw.trim().lowercase()
            for (prefix in listOf("changed to ", "turned ", "was ", "is ", "became ")) {
                if (s.startsWith(prefix)) {
                    s = s.removePrefix(prefix).trim()
                    break
                }
            }
            val short = when {
                s.isEmpty() || s == "unknown" -> "?"
                s == "on" -> "ON"
                s == "off" -> "OFF"
                s == "open" || s == "opened" || s == "opening" -> "OPN"
                s == "closed" || s == "closing" -> "CLSD"
                s == "locked" || s == "locking" -> "LCKD"
                s == "unlocked" || s == "unlocking" -> "ULKD"
                s == "home" -> "HOME"
                s == "not_home" || s == "not home" || s == "away" -> "AWAY"
                s == "unavailable" -> "N/A"
                s == "triggered" || s.startsWith("detected") -> "TRGD"
                s == "clear" || s.startsWith("cleared") -> "CLR"
                s == "playing" -> "PLAY"
                s == "paused" -> "PAUS"
                s == "idle" -> "IDLE"
                s == "standby" -> "STBY"
                else -> s.uppercase()
            }
            return WireText.fit(short, MAX_STATE_BYTES)
        }
    }
}

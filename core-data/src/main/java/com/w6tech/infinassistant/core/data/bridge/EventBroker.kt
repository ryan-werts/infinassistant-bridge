package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles Home Assistant event processing and routing.
 *
 * Responsibilities:
 * - Forwarding the state changes of the tiles' entities ([onStateChange], from HaStateCache)
 * - Processing notification events
 * - Processing infinassistant_watchface_layout_changed events
 * - Processing infinassistant_command events
 */
class EventBroker(
    private val logger: Logger,
    private val stateManager: StateManager,
    private val protocolHandler: ProtocolHandler,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
) {
    private val tag = "EventBroker"

    private val json = Json { ignoreUnknownKeys = true }

    private val tileEntityIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // State update batching: collect changes for 500ms before sending to watch.
    // Reduces BLE traffic when multiple entities change in quick succession (e.g., motion sensors, lights).
    private val pendingStateItems = java.util.concurrent.ConcurrentHashMap<String, StateItem>()
    private val batchMutex = Mutex()
    private var batchFlushJob: Job? = null
    private data class BatchContext(val transport: BleTransport, val scope: CoroutineScope)
    private val batchContext = AtomicReference<BatchContext?>(null)
    private companion object {
        const val BATCH_WINDOW_MS = 500L
    }

    fun updateTileEntityIds(ids: Set<String>) {
        tileEntityIds.clear()
        tileEntityIds.addAll(ids)
    }

    fun getTileEntityConfigs(): List<EntityConfig> {
        return tileEntityIds.map { EntityConfig(entityId = it) }
    }

    /**
     * Handle an incoming HA event.
     * Routes to appropriate handler based on event type.
     */
    suspend fun handleHaEvent(
        msg: JsonObject,
        settings: BridgeSettings,
        ha: HaWebSocketClient,
        transport: BleTransport,
        scope: CoroutineScope,
        onWatchFaceLayout: suspend (WatchFaceLayoutPayload) -> Unit,
        onNotify: suspend (NotifyPayload) -> Unit,
        onHapticFeedback: suspend (String) -> Unit,
        onBridgeCommand: (suspend (String) -> Unit)? = null,
    ) {
        // HA WS event envelope: {"id":...,"type":"event","event":{...}}
        val event = msg["event"]?.jsonObject ?: return
        val eventType = event["event_type"]?.jsonPrimitive?.contentOrNull ?: return
        val data = event["data"]?.jsonObject ?: JsonObject(emptyMap())

        when (eventType) {
            // The states come from HaStateCache ([onStateChange]).
            "state_changed" -> Unit
            settings.notificationEventType -> handleNotificationEvent(data, settings, ha, onNotify)
            "infinassistant_watchface_layout_changed" -> handleWatchFaceLayoutChanged(data, onWatchFaceLayout)
            "infinassistant_command" -> handleInfinassistantCommand(data, onNotify, onHapticFeedback, onBridgeCommand)
        }
    }

    /**
     * A watched entity's state changed (HaStateCache): a tile entity's goes to the
     * watch, batched with the others that change within [BATCH_WINDOW_MS].
     */
    suspend fun onStateChange(
        change: HaStateChange,
        transport: BleTransport,
        scope: CoroutineScope,
    ) {
        val entityId = change.entityId
        if (entityId !in tileEntityIds) return

        val newState = change.new ?: return
        val unit = (newState.attributes["unit_of_measurement"] as? JsonPrimitive)?.contentOrNull

        val item = StateItem(entityId = entityId, state = newState.state, unit = unit, ts = System.currentTimeMillis())
        stateManager.updateEntityState(entityId, item)

        // Batch state updates: collect for BATCH_WINDOW_MS before sending to watch.
        // This reduces BLE traffic when multiple entities change simultaneously
        // (e.g., motion sensors, scene activations, group toggles).
        pendingStateItems[entityId] = item
        batchContext.set(BatchContext(transport, scope))
        batchMutex.withLock {
            batchFlushJob?.cancel()
            batchFlushJob = scope.launch {
                delay(BATCH_WINDOW_MS)
                flushPendingStateUpdates()
            }
        }
    }

    private suspend fun flushPendingStateUpdates() {
        val ctx = batchContext.get() ?: return
        val items = pendingStateItems.values.toList()
        pendingStateItems.clear()
        if (items.isEmpty()) return

        try {
            protocolHandler.sendStateUpdate(ctx.transport, protocolHandler.nextMsgId(), items)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(tag, "Batched state update to watch failed (${items.size} items): ${e.message}")
        }
    }

    private suspend fun handleNotificationEvent(
        data: JsonObject,
        settings: BridgeSettings,
        ha: HaWebSocketClient,
        onNotify: suspend (NotifyPayload) -> Unit,
    ) {
        if (!settings.notificationPrefs.deliverToWatch && settings.notificationPrefs.deliveryMode == NotificationDeliveryMode.WATCH) return

        val title = data["title"]?.jsonPrimitive?.contentOrNull ?: "Home Assistant"
        val body = data["body"]?.jsonPrimitive?.contentOrNull ?: ""
        val sev = when (data["severity"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "critical" -> Severity.CRITICAL
            "warn", "warning" -> Severity.WARN
            else -> Severity.INFO
        }
        val tag = data["tag"]?.jsonPrimitive?.contentOrNull

        if (isQuietHours(settings.notificationPrefs.quietHours) && sev != Severity.CRITICAL) {
            return
        }

        // Optional: also create a persistent notification in HA.
        if (settings.notificationPrefs.deliveryMode == NotificationDeliveryMode.HA_INBOX ||
            settings.notificationPrefs.deliveryMode == NotificationDeliveryMode.BOTH
        ) {
            val serviceData = JsonObject(
                mapOf(
                    "title" to JsonPrimitive(title),
                    "message" to JsonPrimitive(body),
                    // Use tag as notification_id for updates.
                    "notification_id" to JsonPrimitive(tag ?: "pt_bridge"),
                )
            )
            ha.callService("persistent_notification", "create", serviceData)
        }

        if (settings.notificationPrefs.deliveryMode == NotificationDeliveryMode.WATCH ||
            settings.notificationPrefs.deliveryMode == NotificationDeliveryMode.BOTH
        ) {
            val outboundTag = if (settings.notificationPrefs.dedupeByTag) tag else null
            val payload = NotifyPayload(title = title, body = body, severity = sev, tag = outboundTag, ts = System.currentTimeMillis())
            onNotify(payload)
        }
    }

    private suspend fun handleWatchFaceLayoutChanged(
        data: JsonObject,
        onWatchFaceLayout: suspend (WatchFaceLayoutPayload) -> Unit,
    ) {
        // Watch face layout has been updated in HA - layout is in the event data
        val deviceId = data["device_id"]?.jsonPrimitive?.contentOrNull ?: return

        // Note: deviceId is HA device ID (UUID), associatedDeviceAddress is BLE MAC
        // For now, accept all watchface events since there's typically only one watch
        logger.d(tag, "Received watchface event for HA device: $deviceId")
        logCallback("ha->phone", "watchface_layout_changed", null, "device=$deviceId")

        // Extract layout from event data
        try {
            val layoutElement = data["layout"]
            if (layoutElement == null) {
                logger.w(tag, "Watchface layout event missing 'layout' field")
                return
            }

            // Convert JsonElement to JSON string for parsing
            val layoutJson = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), layoutElement)
            logger.d(tag, "Parsing watchface layout: ${layoutJson.take(200)}...")

            val parseResult = WatchFaceParser.parseWatchFaceJson(layoutJson)

            when (parseResult) {
                is ParseResult.Success -> {
                    // Log what was parsed
                    logger.d(tag, "Watchface parse successful: ${parseResult.payload.screens.size} screens")
                    parseResult.payload.screens.forEachIndexed { idx, screen ->
                        logger.d(tag, "  Screen $idx: type=${screen.screenType}, ${screen.tiles.size} tiles, hasDashboard=${screen.dashboard != null}")
                        screen.dashboard?.let { dash ->
                            logger.d(tag, "    Dashboard: ${dash.events.size} events, weather=${dash.weather != null}")
                        }
                    }

                    logCallback("ha->phone", "watchface_parsed", null, "${parseResult.payload.screens.size} screens, ${parseResult.payload.tiles.size} legacy tiles")
                    onWatchFaceLayout(parseResult.payload)
                }
                is ParseResult.Error -> {
                    logger.w(tag, "Failed to parse watchface layout: ${parseResult.message}")
                }
            }
        } catch (e: Exception) {
            logger.w(tag, "Error handling watchface layout event: ${e.message}", e)
        }
    }

    private suspend fun handleInfinassistantCommand(
        data: JsonObject,
        onNotify: suspend (NotifyPayload) -> Unit,
        onHapticFeedback: suspend (String) -> Unit,
        onBridgeCommand: (suspend (String) -> Unit)? = null,
    ) {
        // The integration nests the command arguments under "data"
        // (services.py / device_action.py both fire {command, data:{...}}).
        // Older builds emitted the arguments at the top level, so fall back to
        // the envelope itself to stay compatible with both shapes.
        val args = data["data"]?.jsonObject ?: data

        val deviceId = args["device_id"]?.jsonPrimitive?.contentOrNull
            ?: data["device_id"]?.jsonPrimitive?.contentOrNull
        val command = data["command"]?.jsonPrimitive?.contentOrNull ?: return

        logger.d(tag, "Received infinassistant_command: $command for device: $deviceId")
        logCallback("ha->phone", "infinassistant_command", null, "cmd=$command")

        // Canonical names are the ones the integration emits; the second name in
        // each branch is the legacy alias earlier app builds matched on.
        when (command) {
            "notify", "send_notification" -> {
                val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "Home Assistant"
                val body = args["message"]?.jsonPrimitive?.contentOrNull ?: ""
                val severityStr = args["severity"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val severity = when (severityStr) {
                    "critical" -> Severity.CRITICAL
                    "warn", "warning" -> Severity.WARN
                    else -> Severity.INFO
                }
                val notifyTag = args["tag"]?.jsonPrimitive?.contentOrNull

                val payload = NotifyPayload(
                    title = title,
                    body = body,
                    severity = severity,
                    tag = notifyTag,
                    ts = System.currentTimeMillis()
                )
                onNotify(payload)
            }
            "vibrate", "trigger_vibration" -> {
                val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull ?: "short"
                onHapticFeedback(pattern)
            }
            "ping", "resync", "config_push", "find_watch" -> {
                if (onBridgeCommand == null) {
                    logger.w(tag, "No bridge-command sink wired; dropping '$command'")
                } else {
                    onBridgeCommand(command)
                }
            }
            "set_alarm" -> {
                // No alarm opcode exists in the bridge protocol yet, so this is
                // accepted and acknowledged rather than silently reported as unknown.
                logger.w(tag, "set_alarm is not supported by the current firmware protocol")
            }
            else -> {
                logger.w(tag, "Unknown infinassistant_command: $command")
            }
        }
    }

    /**
     * Check if current time is within quiet hours.
     */
    private fun isQuietHours(quiet: QuietHours?): Boolean {
        if (quiet == null) return false
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (quiet.startHour <= quiet.endHour) {
            h in quiet.startHour until quiet.endHour
        } else {
            // Overnight window, e.g. 22..7
            h >= quiet.startHour || h < quiet.endHour
        }
    }
}

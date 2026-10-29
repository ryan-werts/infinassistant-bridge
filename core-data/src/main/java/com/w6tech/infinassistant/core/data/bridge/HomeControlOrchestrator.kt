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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Manages the Home Control app (HaHomeControl) on the watch.
 *
 * Responsibilities:
 * - Sends HOME_CONTROL_UPDATE (0x15) to the watch on every connection, after a layout push, after a
 *   command, and [PUSH_DELAY_MS] after one of its devices changes state in HA. The watch keeps the
 *   devices in RAM only.
 * - Handles HOME_CONTROL_COMMAND: ACTION_INVOKE(actionId=0xFFFC) with entityId —
 *   calls the appropriate HA service to toggle/control the entity. Firmware before 2.4.89 sent
 *   these taps without the entity ID, so they reach HA only from 2.4.89 on.
 *
 * The app shows the devices the face lists for it or, when it lists none, the face's first
 * lights, switches and fans ([appDevices]).
 */
class HomeControlOrchestrator(
    private val logger: Logger,
    private val protocolHandler: ProtocolHandler,
    private val layoutProvider: () -> WatchFaceLayoutPayload?,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
) {
    private val tag = "HomeControlOrchestrator"
    private var pushJob: Job? = null
    private val stateCache get() = protocolHandler.stateCache

    /**
     * Push current home control device states to the watch.
     * Called on connection, after a layout push, after a command and when a device changes state.
     */
    fun sendHomeControlUpdate(
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
    ) {
        val configured = layoutProvider()?.let { appDevices(it) }.orEmpty()
        if (configured.isEmpty()) return

        sessionScope.launch {
            try {
                // The state cache's, once it has HA's answer: after a push the devices it watches change.
                val stateMap: Map<String, String> = if (stateCache.awaitSnapshot(ha)) {
                    stateCache.states(configured.map { it.entityId }).mapValues { it.value.state }
                } else {
                    emptyMap()
                }

                val payload = WireFrame.fit(toDevices(configured, stateMap),
                    { ProtocolCbor.encode(HomeControlUpdatePayload.serializer(), it) }) { HomeControlUpdatePayload(devices = it) }

                protocolHandler.sendToWatch(
                    transport, Protocol.Type.HOME_CONTROL_UPDATE, protocolHandler.nextMsgId(), payload,
                )
                logCallback("homecontrol", "HOME_CONTROL_UPDATE sent", null, "${payload.devices.size} devices")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e(tag, "Failed to send home control update: ${e.message}", e)
            }
        }
    }

    /**
     * A watched entity's state changed (HaStateCache). One of the app's devices goes out
     * [PUSH_DELAY_MS] later, once for a burst, so the watch does not show a light as off after
     * someone turned it on elsewhere. An attribute change leaves the devices as they are.
     */
    fun onStateChange(change: HaStateChange, ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        val entityIds = layoutProvider()?.let { appDevices(it) }.orEmpty().map { it.entityId }
        if (change.entityId !in entityIds) return
        if (change.new?.state == change.old?.state) return
        pushJob?.cancel()
        pushJob = sessionScope.launch {
            delay(PUSH_DELAY_MS)
            sendHomeControlUpdate(ha, transport, sessionScope)
        }
    }

    /**
     * Handle a tile tap from the Home Control app on the watch.
     * ACTION_INVOKE(actionId=0xFFFC, entityId=...) → toggle entity in HA.
     */
    fun handleHomeControlCommand(
        entityId: String,
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
    ) {
        if (entityId.isBlank()) return

        sessionScope.launch {
            try {
                val domain = entityId.substringBefore('.')
                val serviceData = buildJsonObject { put("entity_id", entityId) }

                when (domain) {
                    "cover"   -> ha.callService("cover", "toggle", serviceData)
                    "lock"    -> {
                        // Toggle lock: check state first
                        val currentState = if (stateCache.awaitSnapshot(ha)) stateCache[entityId]?.state else null
                        val lockService = if ((currentState ?: "locked") == "unlocked") "lock" else "unlock"
                        ha.callService("lock", lockService, serviceData)
                    }
                    "climate" -> {
                        logger.d(tag, "Climate toggle not supported — ignoring $entityId")
                        return@launch
                    }
                    else      -> ha.callService(domain, "toggle", serviceData)
                }

                logCallback("homecontrol", "HOME_CONTROL_COMMAND", null, "toggle $entityId")

                // Brief delay then push updated state
                delay(500)
                sendHomeControlUpdate(ha, transport, sessionScope)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e(tag, "Home control command failed for $entityId: ${e.message}", e)
            }
        }
    }

    companion object {
        private val ACTIVE_STATES = setOf("on", "open", "opening", "playing", "home", "true", "unlocked")

        /**
         * Domains the app falls back to when the face lists no devices for it. Covers, locks and
         * alarms are left out: a tap on the wrist should not open a door no one chose to put there.
         */
        private val DEFAULT_DOMAINS = setOf("light", "switch", "fan", "input_boolean")

        /** HomeAssistantService::kMaxHomeControlDevices (4 from firmware 2.4.91; 2 before, which keeps the first 2). */
        const val MAX_DEVICES = 4
        /** HomeControlDevice::label is char[20]. */
        const val MAX_LABEL_BYTES = 19
        /** HomeControlDevice::entityId is char[64]. */
        const val MAX_ENTITY_ID_BYTES = 63
        /** HomeControlDevice::state is char[16]. */
        const val MAX_STATE_BYTES = 15
        const val PUSH_DELAY_MS = 1_000L

        /**
         * The app's devices: the ones the face lists for it or, when it lists none, the face's tiles
         * on lights, switches and fans ([DEFAULT_DOMAINS]) in the order the face shows them, named
         * as the tiles are.
         */
        internal fun appDevices(layout: WatchFaceLayoutPayload): List<WatchHomeControlDevice> {
            val configured = layout.homeControlConfig?.devices.orEmpty().filter { it.entityId.isNotBlank() }
            if (configured.isNotEmpty()) return configured
            return layout.screens.asSequence()
                .filter { it.screenType == 1 }
                .flatMap { it.tiles }
                .filter { it.entityId.substringBefore('.') in DEFAULT_DOMAINS }
                .distinctBy { it.entityId }
                .map { WatchHomeControlDevice(entityId = it.entityId, name = it.label.ifBlank { null }) }
                .toList()
        }

        /**
         * The devices as the watch stores them. An entity ID too long for the watch is skipped,
         * not cut: a cut ID would come back in the tap and toggle nothing.
         */
        internal fun toDevices(devices: List<WatchHomeControlDevice>, stateMap: Map<String, String>): List<HomeControlDevicePayload> =
            devices
                .filter { it.entityId.isNotBlank() && WireText.utf8Length(it.entityId) <= MAX_ENTITY_ID_BYTES }
                .take(MAX_DEVICES)
                .map { dev ->
                    val state = stateMap[dev.entityId] ?: "unavailable"
                    val label = (dev.name ?: dev.entityId.substringAfter('.')).replace('_', ' ')
                    HomeControlDevicePayload(
                        label = WireText.fit(label, MAX_LABEL_BYTES),
                        entityId = dev.entityId,
                        state = WireText.fit(state, MAX_STATE_BYTES),
                        active = state in ACTIVE_STATES,
                    )
                }
    }
}

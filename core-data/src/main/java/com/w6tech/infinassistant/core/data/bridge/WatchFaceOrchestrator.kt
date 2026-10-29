package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaEntityRegistryEntry
import com.w6tech.infinassistant.core.ha.HaState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import com.w6tech.infinassistant.core.domain.FirmwareLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Handles watch face layout enrichment, tile entity resolution, and push to watch.
 * Extracted from BridgeEngine to reduce its complexity.
 */
class WatchFaceOrchestrator(
    private val logger: Logger,
    private val haApi: HaApi,
    private val settingsRepo: SettingsRepository,
    private val protocolHandler: ProtocolHandler,
    private val stateManager: StateManager,
    private val eventBroker: EventBroker,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
    /** Epoch milliseconds; the dashboard's lastEnrichedAt is stamped from it. */
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val tag = "WatchFaceOrchestrator"
    private val persistenceJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    companion object {
        /** Timeout for waiting for WATCHFACE_ACK from the watch (ms). */
        const val WATCHFACE_ACK_TIMEOUT_MS = 15_000L

        // Firmware render caps (must match HomeAssistantService.h kMaxScreens /
        // kMaxTilesPerScreen). Trim to them before sending so the watch only
        // receives a layout it can render in full.
        //
        // NOT a brick guard, despite what this comment used to claim. The
        // firmware decoder self-clamps every count to its backing array —
        // BridgeController.cpp screens `layout->screenCount < layout->screens.size()`
        // and tiles `screen.tileCount < screen.tiles.size()` — so an oversized
        // layout is silently TRUNCATED, not overflowed. The old wording ("the
        // watch HARDFAULTS + reboots when handed a layout heavier than these
        // caps") described a much older firmware and has since been quoted as
        // justification for treating a cap mismatch as a brick risk. It isn't;
        // it is a correctness issue — the user loses screens without being told.
        // Bump these in lockstep whenever the firmware caps are raised.
        // Raised to 4/4 for multi-screen swipe (fw 2.4.32+); see
        // docs/Plans/_archive/ha-multiscreen-swipe-plan.md. The screen cap went to 6 in
        // fw 2.4.100, which says so in its HELLO ([Protocol.Features.SIX_SCREENS]); an older
        // watch, or one whose HELLO carries no feature word, still holds 4.
        /** The most screens any firmware holds (kMaxScreens of the newest firmware). */
        const val MAX_FW_SCREENS = 6
        /** The screens a watch holds that does not advertise [Protocol.Features.SIX_SCREENS]. */
        const val LEGACY_FW_SCREENS = 4
        const val MAX_FW_TILES_PER_SCREEN = 4

        /** The screens the watch with the HELLO feature word [peerFeatures] holds: 6 with the six-screen bit, else 4. */
        fun screenCap(peerFeatures: Int): Int =
            if ((peerFeatures and Protocol.Features.SIX_SCREENS) != 0) MAX_FW_SCREENS else LEGACY_FW_SCREENS

        /**
         * Must match kReassemblyHeadroomBytes in the firmware's FrameCodec.h.
         * The watch refuses a layout push unless this much heap would remain
         * after the reassembly buffer is taken, so the phone applies the same
         * arithmetic to fail fast with a reason instead of timing out.
         */
        const val FW_REASSEMBLY_HEADROOM_BYTES = 3584

        /** How long to wait for the watch's HELLO when refreshing its free-heap reading. */
        const val HEAP_REFRESH_TIMEOUT_MS = 3000L

        /**
         * The WATCHFACE_ACK `reason` the watch sends with `ok=false` when it refuses a transfer
         * because an earlier layout push is still pending or being written (REQ009-FR-D4, the
         * pending-push "refuse" rule). It is compared for exact, case-sensitive equality with
         * [WatchFaceAckPayload.reason]; any other reason (`validation_error`, `oom`,
         * `decode_error`, or a preflight refusal, which never reaches the watch) does not
         * trigger the retry. Firmware item 7b.1 must send exactly this string.
         */
        const val PENDING_PUSH_REASON = "pending"

        /**
         * How long to wait before the single retry after a [PENDING_PUSH_REASON] refusal: the
         * worst-case completion bound of a pending push on the watch.
         *
         * Derived from the evidence log's "3b.1 revision 2" bound, which was 34 s for a record of
         * at most 2,220 B (139 data programs of 16 B) and counts 1,469 programs, 6 erases and
         * 5,315 read transactions in its worst case (32.91 s with the display saturating the
         * bus, plus 0.5 s dispatch). Only the data programs grow with the record: format 2 holds
         * up to 3,154 B (198 programs), 59 more. Each costs 11.93 ms and its verify read 1.98 ms
         * (13.91 ms), so the bound becomes 32.91 + 0.82 + 0.5 = 34.23 s, rounded up to 35 s. The
         * compaction, split and create terms do not depend on the record's size. This is a
         * derived worst case, not a measurement; the typical write is well under a second.
         */
        const val PENDING_PUSH_RETRY_DELAY_MS = 35_000L
    }

    /** Last sent watch face layout (used for logbook screen configs). */
    private val _lastWatchFaceLayout = MutableStateFlow<WatchFaceLayoutPayload?>(null)
    val lastWatchFaceLayout: WatchFaceLayoutPayload? get() = _lastWatchFaceLayout.value

    /** Restore layout from persisted JSON (e.g., on app restart). */
    fun restoreLayout(json: String?) {
        json ?: return
        try {
            _lastWatchFaceLayout.value = persistenceJson.decodeFromString(WatchFaceLayoutPayload.serializer(), json)
            logger.d(tag, "Restored lastWatchFaceLayout from DataStore (${lastWatchFaceLayout?.screens?.size} screens)")
        } catch (e: Exception) {
            logger.w(tag, "Failed to restore watch face layout from DataStore: ${e.message}")
        }
    }

    /**
     * Handle a watch face layout push command.
     * Enriches with calendar data, resolves tile entities, sends to watch.
     *
     * Steps that correspond to a disabled HELLO feature flag are skipped to save BLE
     * bandwidth and battery.  [peerFeatures] should be [ProtocolHandler.peerFeatures].
     */
    suspend fun handleWatchFaceLayoutCommand(
        payload: WatchFaceLayoutPayload,
        ha: HaWebSocketClient,
        transport: BleTransport,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        peerFeatures: Int = 0,
        peerHelloDiagRev: Int = 0,
    ): WatchFaceLayoutResult {
        // REQ-034 — capability flag: when the watch's HELLO diagnostics revision
        // is below 1, the modern WATCHFACE_ACK contract is not yet implemented
        // on the peer, so we accept a CONFIG_ACK (or a timeout) as a successful
        // layout commit. Above rev 1, only an explicit WATCHFACE_ACK ok=true
        // counts; CONFIG_ACK or timeout → AckFailed.
        val legacyConfigAckIsLayoutAck = peerHelloDiagRev < 1
        // Only run calendar enrichment if the watch supports the CALENDAR feature.
        val enrichedPayload = if (peerFeatures == 0 || (peerFeatures and Protocol.Features.CALENDAR) != 0) {
            enrichWatchFaceWithCalendarData(payload)
        } else {
            logger.d(tag, "Skipping calendar enrichment: peer does not advertise CALENDAR feature")
            payload
        }

        // Dashboard enrichment requires the watch to have a dashboard screen. Skip if no
        // dashboard screens exist or the peer has no feature flags at all (old firmware —
        // in that case we still enrich for backward compatibility).
        val dashEnriched = if (peerFeatures == 0 || enrichedPayload.screens.any { it.screenType == 0 && it.dashboard != null }) {
            enrichDashboardWithEntityStates(enrichedPayload, ha)
        } else {
            logger.d(tag, "Skipping dashboard enrichment: no dashboard screens in payload")
            enrichedPayload
        }

        val resolvedPayload = resolveAndRegisterTileEntities(dashEnriched, ha)

        // Strip phone-side-only config before BLE transmission (saves BLE bandwidth).
        // Also strip entityId from dashboard glyphs/sensors — firmware doesn't use them
        // and they add ~1KB to the CBOR payload.
        // CRITICAL: trim to the firmware render caps (screenCap(peerFeatures) / MAX_FW_TILES_PER_SCREEN).
        // A layout with more screens/tiles than the watch can hold hardfaults + reboots it
        // (confirmed via debug bundle: a 4-screen/12-tile push resets the watch). Clamp here
        // so the watch only ever receives a layout it can decode + render. Screens cut by the
        // watch's cap are reported (the log and the successful result), never dropped silently.
        val watchScreenCap = screenCap(peerFeatures)
        val trimWarning = screenTrimWarning(resolvedPayload.screens.size, watchScreenCap)
        if (trimWarning != null) {
            logger.w(tag, trimWarning)
            logCallback("watchface", "WATCHFACE_LAYOUT screens trimmed", null, trimWarning)
        }
        val cappedPayload = resolvedPayload.copy(
            screens = resolvedPayload.screens.take(watchScreenCap).map { screen ->
                val strippedDash = screen.dashboard?.let { dash ->
                    dash.copy(
                        statusGroups = dash.statusGroups.map { g ->
                            g.copy(glyphs = g.glyphs.map { it.copy(entityId = "") })
                        },
                        // displayType goes too: it is the ONLY key in the whole
                        // layout the firmware's decoder never matches (verified
                        // against every text_string_equals in BridgeController),
                        // yet it costs ~190 B — the key plus "temperature" for
                        // each of 8 sensors. The watch renders sensor values
                        // numerically and infers nothing from this.
                        sensorGroups = dash.sensorGroups.map { g ->
                            g.copy(sensors = g.sensors.map { it.copy(entityId = "", displayType = "") })
                        },
                    )
                }
                screen.copy(
                    logbook = null,
                    dashboard = strippedDash,
                    tiles = screen.tiles.take(MAX_FW_TILES_PER_SCREEN),
                )
            },
            logbookApp = null,
            homeControlConfig = null,
            calendarEntityIds = emptyList(),
        )
        // REQ009-FR-B4: fields the watch never renders keep their defaults, so the encoder omits
        // them. Only this wire copy is stripped; resolvedPayload (persisted, phone-side) is whole.
        val blePayload = WatchFaceWire.stripNonRendered(cappedPayload)
        logCallback("watchface", "Trimmed layout to firmware caps: ${blePayload.screens.size} screen(s), " +
            "tiles/screen=${blePayload.screens.joinToString(",") { it.tiles.size.toString() }}", null, "")
        var attempt = 1
        var outcome = pushAttempt(
            attempt, blePayload, resolvedPayload, transport, onProgress,
            legacyConfigAckIsLayoutAck, peerHelloDiagRev,
        )
        if (outcome.watchPushPending) {
            // REQ009-FR-D4 / N6: the watch refused this transfer because an earlier push was
            // still being written. That write finishes within PENDING_PUSH_RETRY_DELAY_MS, so
            // wait it out and send once more. Exactly one retry per command: a second pending
            // refusal is returned as the failure it is, and nothing else triggers a retry.
            // The retry runs the whole attempt again (HELLO refresh, encode, preflight), because
            // the heap and the key-spelling bit may have changed while the write completed.
            logCallback("watchface", "WATCHFACE_LAYOUT retry", null,
                "reason=$PENDING_PUSH_REASON afterMs=$PENDING_PUSH_RETRY_DELAY_MS")
            delay(PENDING_PUSH_RETRY_DELAY_MS)
            attempt = 2
            outcome = pushAttempt(
                attempt, blePayload, resolvedPayload, transport, onProgress,
                legacyConfigAckIsLayoutAck, peerHelloDiagRev,
            )
        }
        if (outcome.result !is WatchFaceLayoutResult.Success) return outcome.result

        // The stored layout keeps each dashboard's calendars and sensors as configured, not the
        // events and readings of this push: DashboardStateOrchestrator reads them from it.
        val storedPayload = storedLayout(resolvedPayload, payload)
        _lastWatchFaceLayout.value = storedPayload

        // Persist to DataStore so logbook requests work even after app restart
        try {
            val layoutJson = persistenceJson.encodeToString(WatchFaceLayoutPayload.serializer(), storedPayload)
            settingsRepo.setLastResolvedWatchFaceLayoutJson(layoutJson)
        } catch (e: Exception) {
            logger.w(tag, "Failed to persist watch face layout: ${e.message}")
        }

        // The new layout's dashboard, home-control and logbook entities are watched from here on.
        protocolHandler.stateCache.sync(ha)

        // Fetch initial states for tile entities (skip unresolved device: refs)
        val tileEntityIds = resolvedPayload.screens.flatMap { it.tiles }.map { it.entityId }
            .filter { it.isNotBlank() && !it.startsWith("device:") }
        if (tileEntityIds.isNotEmpty()) {
            val tileConfigs = tileEntityIds.map { EntityConfig(entityId = it) }
            val tileStates = protocolHandler.fetchSelectedStates(ha, tileConfigs)
            if (tileStates is AppResult.Ok && tileStates.value.isNotEmpty()) {
                stateManager.updateEntityStates(tileStates.value)
                protocolHandler.sendStateUpdate(transport, protocolHandler.nextMsgId(), tileStates.value)
                logCallback("watchface", "Sent initial tile states: ${tileStates.value.size} entities", null, "")
            }
        }

        return if (trimWarning == null) WatchFaceLayoutResult.Success else WatchFaceLayoutResult.SuccessTrimmed(trimWarning)
    }

    /** The warning for a layout of [screens] screens sent to a watch that holds [cap], or null when all fit. */
    internal fun screenTrimWarning(screens: Int, cap: Int): String? =
        if (screens <= cap) null
        else "This layout has $screens screens but the watch holds $cap; screens ${cap + 1}-$screens were not sent."

    /** Result of one push attempt, and whether the watch refused it because a push was pending. */
    private data class PushAttemptOutcome(
        val result: WatchFaceLayoutResult,
        val watchPushPending: Boolean = false,
    )

    /**
     * One attempt at sending [blePayload]: refresh the watch's HELLO, encode once, preflight,
     * send, and wait for the WATCHFACE_ACK. Logs exactly one OBS-4 entry (type
     * `WATCHFACE_LAYOUT push`, see [logPushAttempt]) for the attempt.
     *
     * [PushAttemptOutcome.watchPushPending] is true only for a WATCHFACE_ACK with `ok=false`
     * and `reason` equal to [PENDING_PUSH_REASON]; no other outcome asks for a retry.
     */
    private suspend fun pushAttempt(
        attempt: Int,
        blePayload: WatchFaceLayoutPayload,
        resolvedPayload: WatchFaceLayoutPayload,
        transport: BleTransport,
        onProgress: ((current: Int, total: Int) -> Unit)?,
        legacyConfigAckIsLayoutAck: Boolean,
        peerHelloDiagRev: Int,
    ): PushAttemptOutcome {
        // The watch's HELLO comes first: it refreshes both the free-heap reading and the
        // feature bits, and the key spelling below is chosen from the live bits. The
        // peerFeatures parameter of the caller was captured before this refresh and is
        // not used for it.
        refreshPeerHello(transport)

        // One encode, after the refresh. The preflight byte cap, the heap check and the
        // transport all see these same bytes, so the size that was checked is the size sent.
        val encoded = protocolHandler.encodeWatchFaceLayout(blePayload)
        val layoutBytes = encoded.bytes

        // REQ-073 — pre-flight: check all group/byte budgets before initiating any BLE
        // write.  Fail fast here to keep the watch's reassembly state clean.
        val preflight = preflightCheck(blePayload, layoutBytes) ?: checkWatchHeap(layoutBytes.size)
        logPushAttempt(attempt, encoded, layoutBytes.size, preflight)
        if (preflight != null) {
            logger.w(tag, "Preflight failed — ${preflight.reason}")
            logCallback("watchface", "WATCHFACE_LAYOUT preflight failed: ${preflight.reason}", null, "field=${preflight.offendingField}")
            return PushAttemptOutcome(preflight)
        }

        val mid = protocolHandler.nextMsgId()

        // Registered before the write. A layout push is multi-chunk and can take
        // seconds, so the ACK may well arrive while the sender is still inside
        // sendToWatchSafe — subscribing afterwards would miss it.
        val pendingAck = transport.expect { m ->
            m.type == Protocol.Type.WATCHFACE_ACK.wire ||
                (legacyConfigAckIsLayoutAck && m.type == Protocol.Type.CONFIG_ACK.wire)
        }

        val sendResult = protocolHandler.sendWatchFaceLayoutBytes(transport, mid, layoutBytes, onProgress)

        if (sendResult is AppResult.Err) {
            transport.cancelExpect(pendingAck)
            logCallback("watchface", "WATCHFACE_LAYOUT send failed: ${sendResult.error.message}", mid, "")
            return PushAttemptOutcome(WatchFaceLayoutResult.AckFailed("BLE write failed: ${sendResult.error.message}"))
        }

        logCallback("watchface", "WATCHFACE_LAYOUT sent: ${resolvedPayload.screens.size} screens", mid, "")

        // REQ-034 — when helloDiagRev >= 1 the watch promises an explicit
        // WATCHFACE_ACK; we wait only for that frame. legacyConfigAckIsLayoutAck
        // mode (older firmware) also accepts CONFIG_ACK as a stand-in.
        val ack = try {
            withTimeoutOrNull(WATCHFACE_ACK_TIMEOUT_MS) { pendingAck.await() }
        } finally {
            // No-op once completed; covers timeout and cancellation alike.
            transport.cancelExpect(pendingAck)
        }

        if (ack != null && ack.type == Protocol.Type.WATCHFACE_ACK.wire) {
            try {
                val ackPayload = ProtocolCbor.decode(WatchFaceAckPayload.serializer(), ack.payload)
                if (!ackPayload.ok) {
                    logCallback("watchface", "WATCHFACE_ACK: rejected", null, "reason=${ackPayload.reason}")
                    return PushAttemptOutcome(
                        WatchFaceLayoutResult.AckFailed("Watch rejected layout: ${ackPayload.reason}"),
                        watchPushPending = ackPayload.reason == PENDING_PUSH_REASON,
                    )
                }
            } catch (e: Exception) {
                logger.w(tag, "Failed to decode WATCHFACE_ACK: ${e.message}")
                // Modern firmware that sent a malformed ack — treat as failure.
                if (!legacyConfigAckIsLayoutAck) {
                    return PushAttemptOutcome(WatchFaceLayoutResult.AckFailed("WATCHFACE_ACK decode failed: ${e.message}"))
                }
            }
        } else if (!legacyConfigAckIsLayoutAck) {
            // Modern firmware: a missing WATCHFACE_ACK (CONFIG_ACK arrived or timed
            // out) is a failure — old behavior of inferring success would mask
            // dropped CBOR frames on the watch side.
            val reason = if (ack == null) "WATCHFACE_ACK timeout" else "CONFIG_ACK received instead of WATCHFACE_ACK"
            logCallback("watchface", reason, null, "helloDiagRev=$peerHelloDiagRev")
            return PushAttemptOutcome(WatchFaceLayoutResult.AckFailed(reason))
        }
        // legacyConfigAckIsLayoutAck branch with CONFIG_ACK or timeout: treat as success.
        return PushAttemptOutcome(WatchFaceLayoutResult.Success)
    }

    /**
     * REQ009-OBS-4: the one log entry per WATCHFACE_LAYOUT push attempt. It goes to
     * [logCallback] with direction `watchface` and type `WATCHFACE_LAYOUT push`, so it lands in
     * `BridgeEngine.protocolLog` (the Debug screen's list and the debug bundle) and in the
     * diagnostic log export. The summary reads
     * `attempt=N keys=short|long bytes=B preflight=pass peerFeatures=0x..` or
     * `attempt=N keys=short|long bytes=B preflight=PreflightFailed freeHeap=H field=F peerFeatures=0x..`,
     * where `H` is the watch's free-heap reading at the time (`unknown` when there is none).
     */
    private fun logPushAttempt(
        attempt: Int,
        encoded: EncodedWatchFaceLayout,
        byteCount: Int,
        preflight: WatchFaceLayoutResult.PreflightFailed?,
    ) {
        val keys = if (encoded.shortKeys) "short" else "long"
        val outcome = if (preflight == null) {
            "preflight=pass"
        } else {
            "preflight=PreflightFailed freeHeap=${protocolHandler.peerFreeHeap ?: "unknown"} " +
                "field=${preflight.offendingField}"
        }
        logCallback("watchface", "WATCHFACE_LAYOUT push", null,
            "attempt=$attempt keys=$keys bytes=$byteCount $outcome " +
                "peerFeatures=0x${protocolHandler.peerFeatures.toString(16)}")
    }

    /** Result of sending watch face layout to the watch. */
    /**
     * Ask the watch for a fresh HELLO and apply it to [protocolHandler].
     *
     * The reading has to be refreshed before the layout is encoded or checked.
     * peerFreeHeap is only written from handleHello, so on its own it is a
     * connect-time snapshot: if the watch happened to be showing the dashboard when
     * the phone connected, a stale value would refuse every later push even after
     * the user left the HA face. REQ-066 already provides the refresh — a phone
     * HELLO makes the watch answer with its diagnostic HELLO — so ask, wait
     * briefly, then read the result. The same reply carries the feature bits that
     * choose the layout's key spelling.
     *
     * The transport hands a reply claimed by an expect() waiter to that waiter only,
     * so it never reaches the dispatch loop's HELLO handling; it is applied here.
     * On a timeout nothing is applied and the previous readings stay.
     */
    private suspend fun refreshPeerHello(transport: BleTransport) {
        val pendingHello = transport.expect { m -> m.type == Protocol.Type.HELLO.wire }
        try {
            protocolHandler.sendToWatch(
                transport,
                Protocol.Type.HELLO,
                protocolHandler.nextMsgId(),
                HelloPayload(device = "phone", protoVersionString = Protocol.PROTOCOL_VERSION),
            )
            logCallback("watchface", "HELLO", null, "heap refresh before layout push")
            withTimeoutOrNull(HEAP_REFRESH_TIMEOUT_MS) { pendingHello.await() }
                ?.let { protocolHandler.handleHello(it) }
        } finally {
            transport.cancelExpect(pendingHello)
        }
    }

    /**
     * Refuse a push the watch cannot reassemble, against the heap reading that
     * [refreshPeerHello] just took.
     *
     * FrameCodec refuses to allocate a reassembly buffer unless free heap covers
     * the payload plus [FW_REASSEMBLY_HEADROOM_BYTES], and that refusal is silent
     * on the wire — the user would otherwise wait out a 15 s ACK timeout and be
     * told nothing.
     *
     * Free heap is around 11 KB idle but only 4-5 KB with the dashboard rendered,
     * because the layout buffer and the dashboard's LVGL widgets come from the same
     * pool. Hence the actionable half of the message.
     */
    private fun checkWatchHeap(byteSize: Int): WatchFaceLayoutResult.PreflightFailed? {
        // Absent reading: say nothing and let the push try. The firmware guard is
        // the real protection, and refusing on no evidence would be worse.
        val freeHeap = protocolHandler.peerFreeHeap ?: return null
        val needed = byteSize + FW_REASSEMBLY_HEADROOM_BYTES
        if (needed <= freeHeap) return null

        return WatchFaceLayoutResult.PreflightFailed(
            reason = "Watch has $freeHeap B free; this layout needs $byteSize B plus " +
                "$FW_REASSEMBLY_HEADROOM_BYTES B of headroom to reassemble. Switch the watch " +
                "off the HA face (which frees its LVGL widgets) and send again.",
            offendingField = "freeHeap",
        )
    }

    sealed interface WatchFaceLayoutResult {
        data object Success : WatchFaceLayoutResult
        /** The push succeeded, but screens beyond the watch's cap were left out; [warning] says which. */
        data class SuccessTrimmed(val warning: String) : WatchFaceLayoutResult
        data class AckFailed(val message: String) : WatchFaceLayoutResult
        data object Timeout : WatchFaceLayoutResult
        data object NotConnected : WatchFaceLayoutResult
        /** REQ-073 — layout rejected before BLE send due to budget overflow. */
        data class PreflightFailed(val reason: String, val offendingField: String) : WatchFaceLayoutResult
    }

    /**
     * REQ-073 — Verify [blePayload] and its already-encoded CBOR [encoded] satisfy all
     * firmware budget limits before attempting a BLE transmission. [encoded] is the exact
     * byte array the transport will be handed, so the size checked here is the size sent.
     *
     * Checks performed (in order):
     *  1. status-group count   ≤ [FirmwareLimits.MAX_STATUS_GROUPS]
     *  2. glyphs per group     ≤ [FirmwareLimits.MAX_GLYPHS_PER_GROUP]
     *  3. sensor-group count   ≤ [FirmwareLimits.MAX_SENSOR_GROUPS]
     *  4. sensors per group    ≤ [FirmwareLimits.MAX_SENSORS_PER_GROUP]
     *  5. encoded CBOR size    ≤ [FirmwareLimits.MAX_REASSEMBLY_SIZE] (8 KiB)
     *
     * Returns [WatchFaceLayoutResult.PreflightFailed] naming the first offending field on
     * any violation; returns `null` (pass) when all checks clear.
     */
    fun preflightCheck(blePayload: WatchFaceLayoutPayload, encoded: ByteArray): WatchFaceLayoutResult.PreflightFailed? {
        for (screen in blePayload.screens) {
            val dash = screen.dashboard ?: continue
            if (dash.statusGroups.size > FirmwareLimits.MAX_STATUS_GROUPS) {
                return WatchFaceLayoutResult.PreflightFailed(
                    reason = "statusGroups count ${dash.statusGroups.size} exceeds MAX_STATUS_GROUPS (${FirmwareLimits.MAX_STATUS_GROUPS})",
                    offendingField = "statusGroups",
                )
            }
            for ((idx, group) in dash.statusGroups.withIndex()) {
                if (group.glyphs.size > FirmwareLimits.MAX_GLYPHS_PER_GROUP) {
                    return WatchFaceLayoutResult.PreflightFailed(
                        reason = "statusGroups[$idx].glyphs count ${group.glyphs.size} exceeds MAX_GLYPHS_PER_GROUP (${FirmwareLimits.MAX_GLYPHS_PER_GROUP})",
                        offendingField = "statusGroups[$idx].glyphs",
                    )
                }
            }
            if (dash.sensorGroups.size > FirmwareLimits.MAX_SENSOR_GROUPS) {
                return WatchFaceLayoutResult.PreflightFailed(
                    reason = "sensorGroups count ${dash.sensorGroups.size} exceeds MAX_SENSOR_GROUPS (${FirmwareLimits.MAX_SENSOR_GROUPS})",
                    offendingField = "sensorGroups",
                )
            }
            for ((idx, group) in dash.sensorGroups.withIndex()) {
                if (group.sensors.size > FirmwareLimits.MAX_SENSORS_PER_GROUP) {
                    return WatchFaceLayoutResult.PreflightFailed(
                        reason = "sensorGroups[$idx].sensors count ${group.sensors.size} exceeds MAX_SENSORS_PER_GROUP (${FirmwareLimits.MAX_SENSORS_PER_GROUP})",
                        offendingField = "sensorGroups[$idx].sensors",
                    )
                }
            }
        }
        val byteSize = encoded.size
        if (byteSize > FirmwareLimits.MAX_REASSEMBLY_SIZE) {
            return WatchFaceLayoutResult.PreflightFailed(
                reason = "CBOR payload byteSize $byteSize > MAX_REASSEMBLY_SIZE (${FirmwareLimits.MAX_REASSEMBLY_SIZE}); layout too large to transmit",
                offendingField = "blePayload",
            )
        }

        // Fitting MAX_REASSEMBLY_SIZE is not the same as fitting the heap the
        // watch has free RIGHT NOW. Since 2.4.45 the firmware refuses a push it
        // cannot take with headroom (FrameCodec kReassemblyHeadroomBytes), and
        // that refusal is silent on the wire — Reassembler::Ingest returns
        // nullopt — so without this the user waits out a 15 s WATCHFACE_ACK
        // timeout and is told nothing useful.
        //
        // Free heap is ~11 KB idle but only ~4 KB with the dashboard rendered,
        // because the layout buffer and the dashboard's LVGL widgets come from
        // the same pool. Hence the actionable half of the message: leaving the
        // HA face frees the widgets and the same push then succeeds.
        // Heap is checked separately in checkWatchHeap(), on these same bytes, against
        // the reading refreshPeerHello() takes before the layout is encoded.
        return null
    }

    /**
     * [resolved] with each dashboard's events and sensor groups as [original] has them: the
     * "calendar:" references, and every configured sensor (the enrichment drops a sensor without
     * a reading, such as a device tracker or a freezer that is offline at the push).
     */
    internal fun storedLayout(resolved: WatchFaceLayoutPayload, original: WatchFaceLayoutPayload): WatchFaceLayoutPayload =
        resolved.copy(
            screens = resolved.screens.mapIndexed { i, screen ->
                val configured = original.screens.getOrNull(i)?.dashboard
                screen.dashboard?.let { dash ->
                    if (configured == null) {
                        screen
                    } else {
                        screen.copy(dashboard = dash.copy(events = configured.events, sensorGroups = configured.sensorGroups))
                    }
                } ?: screen
            },
        )

    suspend fun enrichWatchFaceWithCalendarData(payload: WatchFaceLayoutPayload): WatchFaceLayoutPayload {
        try {
            val enrichedScreens = payload.screens.map { screen ->
                val enrichedDashboard = screen.dashboard?.let { dashboard ->
                    val calendarEntityIds = dashboard.events
                        .filter { it.title.startsWith("calendar:") }
                        .map { it.title.removePrefix("calendar:") }

                    if (calendarEntityIds.isEmpty()) return@let dashboard

                    // All at once: a dashboard reads every calendar of the face (16 on one), and one
                    // after another, each with its URL check, they could outlast the push's timeout.
                    val allEvents = coroutineScope {
                        calendarEntityIds.map { calendarId ->
                            async {
                                when (val eventsResult = haApi.fetchCalendarEvents(calendarId, maxEvents = 3)) {
                                    is AppResult.Ok -> eventsResult.value
                                    is AppResult.Err -> {
                                        logger.w(tag, "Failed to fetch calendar $calendarId: ${eventsResult.error.message}")
                                        emptyList()
                                    }
                                }
                            }
                        }.awaitAll().flatten()
                    }

                    val sortedEvents = allEvents
                        .sortedWith(compareBy(nullsLast()) { it.startInstant })
                        .take(2)
                        .map { WatchEvent(time = it.time, title = it.title, startEpoch = it.startEpoch) }

                    dashboard.copy(events = sortedEvents)
                }
                screen.copy(dashboard = enrichedDashboard)
            }
            return payload.copy(screens = enrichedScreens)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(tag, "Failed to enrich calendar data: ${e.message}", e)
            return payload
        }
    }

    /**
     * Enrich dashboard screens with live entity states from HA.
     * Populates glyph active flags, sensor values, and weather data.
     */
    suspend fun enrichDashboardWithEntityStates(
        payload: WatchFaceLayoutPayload,
        ha: HaWebSocketClient,
    ): WatchFaceLayoutPayload {
        // Collect all dashboard entity IDs
        val dashScreens = payload.screens.filter { it.screenType == 0 && it.dashboard != null }
        if (dashScreens.isEmpty()) return payload

        val allEntityIds = mutableSetOf<String>()
        for (screen in dashScreens) {
            val dash = screen.dashboard ?: continue
            dash.statusGroups.forEach { g -> g.glyphs.forEach { allEntityIds.add(it.entityId.orEmpty()) } }
            dash.sensorGroups.forEach { g -> g.sensors.forEach { allEntityIds.add(it.entityId) } }
            dash.weather?.let { allEntityIds.add(it.entityId) }
            // Configurable value and inline metric entity IDs (phone-side-only fields).
            if (dash.configurableValueEntityId.isNotBlank()) allEntityIds.add(dash.configurableValueEntityId)
            dash.inlineMetricEntityIds.forEach { if (it.isNotBlank()) allEntityIds.add(it) }
        }
        allEntityIds.removeAll { it.isBlank() }
        if (allEntityIds.isEmpty()) return payload

        // HA's whole state list, not the state cache: the cache watches the layout the watch has,
        // and this one's entities only once the push has landed. Watching them first would take one
        // more subscription per push, or drop the current layout's entities before the watch has
        // taken the new one. A push is rare.
        val statesResult = try { ha.getStates() } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(tag, "Failed to fetch states for dashboard enrichment: ${e.message}")
            return payload
        }
        if (statesResult is AppResult.Err) {
            logger.w(tag, "Failed to fetch states for dashboard: ${statesResult.error.message}")
            return payload
        }
        val stateMap = (statesResult as AppResult.Ok).value
            .filter { it.entityId in allEntityIds }
            .associateBy { it.entityId }

        logger.d(tag, "Dashboard enrichment: ${stateMap.size}/${allEntityIds.size} entities resolved")

        val enrichedAtSec = clockMs() / 1000

        return payload.copy(
            screens = payload.screens.map { screen ->
                if (screen.screenType != 0 || screen.dashboard == null) return@map screen
                val dash = screen.dashboard!!
                screen.copy(dashboard = dash.copy(
                    statusGroups = dash.statusGroups.map { group ->
                        group.copy(glyphs = group.glyphs.map { glyph ->
                            val state = stateMap[glyph.entityId]
                            glyph.copy(active = state != null && isEntityActive(state.state))
                        })
                    },
                    sensorGroups = dash.sensorGroups.map { group ->
                        // Drop sensors with no real reading instead of sending 0.
                        // The firmware skips a group whose value count is zero
                        // (`if (count == 0) continue;`), so an entirely dead
                        // group simply does not render — which is the honest
                        // outcome for e.g. a beacons group pointed at
                        // device_tracker entities that can never yield a number.
                        group.copy(
                            sensors = group.sensors.mapNotNull { sensor ->
                                extractSensorValue(sensor.entityId, stateMap[sensor.entityId])
                                    ?.let { sensor.copy(value = it) }
                            }
                        )
                    },
                    weather = dash.weather?.let { w ->
                        val state = stateMap[w.entityId]
                        if (state != null) enrichWeather(w, state) else w
                    },
                    configurableValueLabel = if (dash.configurableValueEntityId.isNotBlank()) {
                        (dash.configurableValueLabel ?: "").take(11).ifEmpty { null }
                    } else dash.configurableValueLabel,
                    configurableValueValue = if (dash.configurableValueEntityId.isNotBlank()) {
                        val state = stateMap[dash.configurableValueEntityId]
                        state?.state?.take(19)
                    } else dash.configurableValueValue,
                    inlineMetric0Label = dash.inlineMetricEntityIds.getOrNull(0)?.let { entityId ->
                        if (entityId.isNotBlank()) (dash.inlineMetric0Label ?: "").take(2) else null
                    } ?: dash.inlineMetric0Label,
                    inlineMetric0Value = dash.inlineMetricEntityIds.getOrNull(0)?.let { entityId ->
                        val state = if (entityId.isNotBlank()) stateMap[entityId] else null
                        if (state != null) coerceToMetricValue(state.state) else dash.inlineMetric0Value
                    } ?: dash.inlineMetric0Value,
                    inlineMetric1Label = dash.inlineMetricEntityIds.getOrNull(1)?.let { entityId ->
                        if (entityId.isNotBlank()) (dash.inlineMetric1Label ?: "").take(2) else null
                    } ?: dash.inlineMetric1Label,
                    inlineMetric1Value = dash.inlineMetricEntityIds.getOrNull(1)?.let { entityId ->
                        val state = if (entityId.isNotBlank()) stateMap[entityId] else null
                        if (state != null) coerceToMetricValue(state.state) else dash.inlineMetric1Value
                    } ?: dash.inlineMetric1Value,
                    lastEnrichedAt = enrichedAtSec,
                ))
            }
        )
    }

    // SYNC: This active-state list must match firmware HomeAssistant.cpp::IsEntityActive().
    // If you add/remove states here, update the firmware side too. It sets the layout record's
    // glyph flags only: from firmware 2.4.96 the dashboard draws its status from DASHBOARD_STATE,
    // whose list is DashboardStateBuilder.ACTIVE_STATES (this one plus "opening" and "closing").
    private fun isEntityActive(state: String): Boolean {
        return state in setOf("on", "open", "home", "playing", "heat", "cool",
            "heat_cool", "auto", "unlocked", "above_horizon", "fan_only", "dry")
    }

    private fun coerceToMetricValue(stateStr: String): String {
        val numeric = stateStr.toDoubleOrNull()
        if (numeric != null) {
            return numeric.toInt().toString().take(3)
        }
        return stateStr.take(3)
    }

    /**
     * Resolve a dashboard sensor to a number, or null when there isn't one.
     *
     * Returning 0 for "no value" was indistinguishable on the watch from a real
     * reading of zero, which is how the face came to show "0 2 0" for a freezer
     * group and "0 0 0" for a beacons group. Three different causes all
     * collapsed onto the same 0:
     *   - the entity was missing from the state map,
     *   - its state was non-numeric ("unavailable", "unknown", or a
     *     device_tracker reporting "home"/"not_home"),
     *   - it genuinely read zero.
     * Only the third deserves to be rendered. Null lets the caller drop the
     * other two instead of inventing a reading.
     */
    internal fun extractSensorValue(entityId: String, state: HaState?): Int? {
        if (state == null) {
            logger.d(tag, "No state for sensor $entityId — omitting from the dashboard")
            return null
        }
        // For climate entities, use current_temperature attribute
        if (entityId.startsWith("climate.")) {
            val currentTemp = state.attributes["current_temperature"]
                ?.jsonPrimitive?.doubleOrNull
            if (currentTemp != null) return Math.round(currentTemp).toInt()
        }
        // For sensor entities, parse state as number
        val value = state.state.toDoubleOrNull()
        if (value == null) {
            logger.d(tag, "Non-numeric sensor state for $entityId: '${state.state}' — omitting")
            return null
        }
        // Round rather than truncate: toInt() truncates toward zero, so a
        // freezer at -17.8 read as -17 and 2.6 read as 2. Half a degree of
        // silent error on every reading is not worth the one saved call.
        return Math.round(value).toInt()
    }

    private suspend fun enrichWeather(weather: WatchWeather, state: HaState): WatchWeather {
        val condition = state.state // "sunny", "cloudy", etc.
        val temp = state.attributes["temperature"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
        // Forecast is an array of day objects; use current temp as fallback for hi/lo
        var hi = temp
        var lo = temp
        try {
            // Legacy path: HA exposed today's forecast as a state attribute up to
            // 2024.4. Kept for older installs, but it is null on anything modern.
            val forecast = state.attributes["forecast"]?.jsonArray
            if (forecast != null && forecast.isNotEmpty()) {
                val today = forecast[0].jsonObject
                hi = today["temperature"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: hi
                lo = today["templow"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: lo
            } else {
                // Modern path: the forecast moved to the weather.get_forecasts
                // service. Without this the fallback below leaves hi == lo ==
                // current temp, which rendered on the watch as "H:74 L:74",
                // "H:69 L:69", "H:99 L:99" — a different number each day, always
                // identical, which is what gave the bug away.
                when (val f = haApi.fetchWeatherForecast(weather.entityId)) {
                    is AppResult.Ok -> f.value?.let { (fhi, flo) ->
                        hi = fhi
                        lo = flo
                    } ?: logger.d(tag, "No forecast available for ${weather.entityId}, using current temp as hi/lo")
                    is AppResult.Err -> logger.w(
                        tag,
                        "get_forecasts failed for ${weather.entityId}: ${f.error.message}, using current temp as hi/lo",
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(tag, "Failed to parse weather forecast for ${weather.entityId}: ${e.message}, using current temp as hi/lo")
        }
        return weather.copy(condition = condition, temp = temp, hi = hi, lo = lo)
    }

    private val entityDomainPriority = listOf(
        "cover", "light", "switch", "lock", "fan", "climate",
        "media_player", "binary_sensor", "sensor",
    )

    private fun pickBestEntity(candidates: List<HaEntityRegistryEntry>): HaEntityRegistryEntry? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        for (domain in entityDomainPriority) {
            val match = candidates.firstOrNull { it.entityId.startsWith("$domain.") }
            if (match != null) return match
        }
        val excluded = setOf("button", "number", "select", "update")
        val useful = candidates.firstOrNull { entry ->
            val domain = entry.entityId.substringBefore(".")
            domain !in excluded
        }
        return useful ?: candidates.first()
    }

    suspend fun resolveAndRegisterTileEntities(
        payload: WatchFaceLayoutPayload,
        ha: HaWebSocketClient,
    ): WatchFaceLayoutPayload {
        val allTiles = payload.screens.flatMap { it.tiles }
        if (allTiles.isEmpty()) {
            eventBroker.updateTileEntityIds(emptySet())
            protocolHandler.stateCache.sync(ha)
            return payload
        }

        val hasDeviceRefs = allTiles.any { it.entityId.startsWith("device:") }

        var resolvedPayload = payload
        if (hasDeviceRefs) {
            val registryResult = ha.getEntityRegistry()
            if (registryResult is AppResult.Ok) {
                val registry = registryResult.value
                resolvedPayload = payload.copy(
                    screens = payload.screens.map { screen ->
                        screen.copy(
                            tiles = screen.tiles.map { tile ->
                                if (tile.entityId.startsWith("device:")) {
                                    val deviceId = tile.entityId.removePrefix("device:")
                                    val candidates = registry.filter { it.deviceId == deviceId }
                                    val best = pickBestEntity(candidates)
                                    if (best != null) {
                                        logger.d(tag, "Resolved device:$deviceId -> ${best.entityId} (from ${candidates.size} entities)")
                                        tile.copy(entityId = best.entityId)
                                    } else {
                                        logger.w(tag, "Could not resolve device:$deviceId -- no entities found")
                                        tile
                                    }
                                } else tile
                            }
                        )
                    }
                )
            } else {
                logger.w(tag, "Entity registry fetch failed, device: refs won't be resolved")
            }
        }

        val tileEntityIds = resolvedPayload.screens
            .flatMap { it.tiles }
            .map { it.entityId }
            .filter { it.isNotBlank() && !it.startsWith("device:") }
            .toSet()
        eventBroker.updateTileEntityIds(tileEntityIds)
        logger.d(tag, "Registered ${tileEntityIds.size} tile entities for state tracking")
        // The state cache watches the tiles from here on.
        protocolHandler.stateCache.sync(ha)

        return resolvedPayload
    }
}

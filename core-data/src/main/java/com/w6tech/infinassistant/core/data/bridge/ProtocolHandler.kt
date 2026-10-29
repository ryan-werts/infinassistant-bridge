package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.HealthSummary
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles protocol message encoding/decoding and watch message processing.
 *
 * Responsibilities:
 * - Message ID generation
 * - CBOR encoding for outgoing messages
 * - Processing incoming watch messages (HELLO, ACTION_INVOKE, etc.)
 * - Protocol logging
 */
class ProtocolHandler(
    private val logger: Logger,
    private val stateManager: StateManager,
    /** The watched entities' states, the one cache every consumer reads (BridgeEngine's). */
    val stateCache: HaStateCache = HaStateCache(logger),
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
) {
    private val tag = "ProtocolHandler"

    companion object {
        /** TIME_SYNC_ACK status: the watch set its clock. */
        const val TIME_SYNC_APPLIED = 0

        /**
         * TIME_SYNC_ACK status: the watch clock was already within its tolerance (5 s) and the
         * zone unchanged, so it was left alone. The sync is done, not refused.
         */
        const val TIME_SYNC_IN_TOLERANCE = 1

        /** Timeout waiting for TIME_SYNC_ACK from the watch (ms). */
        const val TIME_SYNC_ACK_TIMEOUT_MS = 5_000L

        /** Timeout for the watch's reply to CRASH_DUMP_REQUEST (ms); it answers at once. */
        const val CRASH_REPORT_TIMEOUT_MS = 2_000L

        /** Reset reasons (the watch's ResetReasonToString) that mean the boot did not end by choice. */
        val UNEXPECTED_RESETS = setOf("Watchdog", "CPU Lock-up")

        /**
         * The HELLO diagnostics revision that arrived together with the dual-key layout
         * decoders (firmware 2.4.62). See [peerAcceptsShortKeys].
         */
        const val SHORT_KEYS_MIN_HELLO_DIAG_REV = 3
    }

    private val msgIdCounter = AtomicInteger(100)

    /**
     * Feature flags reported by the connected watch in its HELLO message.
     * Defaults to 0 (no features) for backward compatibility with older firmware.
     * See [Protocol.Features] for flag definitions.
     */
    var peerFeatures: Int = 0
        private set

    /**
     * REQ-034 — peer's HELLO diagnostics protocol revision. Null/0 = legacy
     * watch (pre-revisioned protocol); ≥ 1 = modern. Drives the
     * legacyConfigAckIsLayoutAck branch in WatchFaceOrchestrator: on legacy
     * firmware, a CONFIG_ACK is treated as an implicit WATCHFACE_ACK because
     * old firmware never sent the latter.
     */
    var peerHelloDiagRev: Int = 0
        private set

    /**
     * Whether the link was encrypted at the watch's last HELLO or PONG, as the watch reports it
     * (`enc`, firmware 2.4.101+). Null while it has said nothing, and from older firmware.
     */
    @Volatile
    var peerEnc: Boolean? = null
        private set

    /** Whether a HELLO (even an empty one from old firmware) has arrived since [resetSession]. */
    @Volatile
    var helloSeen: Boolean = false
        private set

    /**
     * A new connection to the watch begins: what its last HELLO said no longer holds. The watch
     * may have been replaced or updated, and the features and encryption of the next connection
     * are only known once its HELLO arrives.
     */
    fun resetSession() {
        peerFeatures = 0
        peerHelloDiagRev = 0
        peerEnc = null
        helloSeen = false
    }

    /**
     * Free heap the watch reported in its most recent HELLO, or null if unknown.
     *
     * The firmware allocates the layout reassembly buffer out of the SAME heap
     * LVGL renders from, and since 2.4.45 refuses a push it cannot fit with
     * headroom. That refusal is silent on the wire — Reassembler::Ingest just
     * returns nullopt — so the phone only learns about it as a 15 s
     * WATCHFACE_ACK timeout. Keeping the last reported figure lets preflight
     * say so up front instead.
     */
    var peerFreeHeap: Long? = null
        private set

    /**
     * Check whether the connected peer supports a given feature flag.
     * @param flag One of the [Protocol.Features] constants.
     */
    fun peerSupports(flag: Int): Boolean = (peerFeatures and flag) != 0

    /**
     * Whether this connection's HELLO shows a watch whose CONFIG decoder does not read the
     * renamed `infinassistant` key: it lacks [Protocol.Features.CONFIG_KEY_V2] (firmware before
     * 2.4.103). A HELLO with no feature word, or an empty one, counts, as those watches are
     * older still. False until a HELLO has arrived, so a watch that answers late is not held
     * back; its CONFIG_PUSH is merely ignored if it is old.
     */
    val watchPredatesConfigKey: Boolean
        get() = helloSeen && !peerSupports(Protocol.Features.CONFIG_KEY_V2)

    /**
     * Called after every HELLO this handler applies ([handleHello]), whether it decoded or not, with
     * [helloSeen] and the features already updated. The engine uses it to keep
     * [watchPredatesConfigKey] current in the status.
     */
    @Volatile
    var onHelloSeen: (() -> Unit)? = null

    /**
     * Called once when a HELLO takes [watchPredatesConfigKey] from true to false within a connection:
     * a CONFIG_PUSH was withheld while the watch looked old, so the engine sends it now. Not called
     * for the first HELLO of a connection (before it the flag is false), nor when the flag stays
     * false or turns true.
     */
    @Volatile
    var onConfigKeySupportGained: (() -> Unit)? = null

    /**
     * Sends [payload] as CONFIG_PUSH, unless [watchPredatesConfigKey]: such a watch would ignore
     * the key it carries and silently stop its sensor reports, so the app asks for a firmware
     * update instead. Returns false, with nothing sent, when it is withheld.
     */
    suspend fun sendConfigPush(transport: BleTransport, msgId: Int, payload: ConfigPushPayload): Boolean {
        if (watchPredatesConfigKey) return false
        sendToWatch(transport, Protocol.Type.CONFIG_PUSH, msgId, payload)
        return true
    }

    /**
     * Whether the watch's layout decoders accept short keys.
     *
     * The watch advertises that as [Protocol.Features.SHORT_KEYS], but only its rich HELLO
     * carries the feature word, and that HELLO does not fit one frame at a 256-byte MTU.
     * Firmware 2.4.62 and 2.4.63 therefore always answer with the compact HELLO, which has
     * no `features` field, so [peerFeatures] stays 0 on real hardware and every push went
     * out with long keys (1509 B where short keys need 1086 B for the same layout).
     *
     * The dual-key decoders and HELLO diagnostics revision
     * [SHORT_KEYS_MIN_HELLO_DIAG_REV] arrived together in 2.4.62, so a HELLO without a
     * feature word at that revision or later is short-key capable. A feature word that is
     * present is believed as it stands. [peerFeatures] itself is left at 0: elsewhere 0
     * means "unknown, do everything", and that must not change.
     */
    fun peerAcceptsShortKeys(): Boolean =
        peerSupports(Protocol.Features.SHORT_KEYS) ||
            (peerFeatures == 0 && peerHelloDiagRev >= SHORT_KEYS_MIN_HELLO_DIAG_REV)

    /**
     * Generate the next message ID (wraps at 0xFFFF, skips 0).
     */
    fun nextMsgId(): Int {
        val id = msgIdCounter.incrementAndGet() and 0xFFFF
        return if (id == 0) {
            msgIdCounter.set(1)
            1
        } else id
    }

    /**
     * Send a typed payload to the watch.
     * Throws on failure.
     */
    suspend fun <T> sendToWatch(
        transport: BleTransport,
        type: Protocol.Type,
        msgId: Int,
        payload: T,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        val bytes = encodePayload(payload)
        val r = transport.send(type.wire, flags = 0, msgId = msgId, payload = bytes, onProgress = onProgress)
        if (r is AppResult.Err) throw r.error
        logCallback("phone->watch", type.name, msgId, "${bytes.size} bytes")
    }

    /**
     * Send a typed payload to the watch (safe version).
     * Returns AppResult instead of throwing.
     */
    suspend fun <T> sendToWatchSafe(
        transport: BleTransport,
        type: Protocol.Type,
        msgId: Int,
        payload: T,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): AppResult<Unit> {
        val bytes = try {
            encodePayload(payload)
        } catch (e: Exception) {
            return AppResult.Err(e)
        }

        val r = transport.send(type.wire, flags = 0, msgId = msgId, payload = bytes, onProgress = onProgress)
        if (r is AppResult.Ok) {
            logCallback("phone->watch", type.name, msgId, "${bytes.size} bytes")
        }
        return r
    }

    /**
     * Encode a WATCHFACE_LAYOUT payload with the key spelling the connected watch
     * accepts: short keys when [peerAcceptsShortKeys] says so, long keys otherwise.
     * Reads the live HELLO state at call time, so callers refresh the watch's HELLO
     * first and encode once afterwards.
     */
    fun encodeWatchFaceLayout(payload: WatchFaceLayoutPayload): EncodedWatchFaceLayout =
        ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = peerAcceptsShortKeys())

    /**
     * Send already-encoded WATCHFACE_LAYOUT bytes (from [encodeWatchFaceLayout]) to the
     * watch. The single path for layout pushes: the bytes the caller preflighted are the
     * bytes handed to the transport.
     */
    suspend fun sendWatchFaceLayoutBytes(
        transport: BleTransport,
        msgId: Int,
        bytes: ByteArray,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): AppResult<Unit> {
        val r = transport.send(Protocol.Type.WATCHFACE_LAYOUT.wire, flags = 0, msgId = msgId, payload = bytes, onProgress = onProgress)
        if (r is AppResult.Ok) {
            logCallback("phone->watch", Protocol.Type.WATCHFACE_LAYOUT.name, msgId, "${bytes.size} bytes")
        }
        return r
    }

    /**
     * Send a state update to the watch.
     * Includes [StateUpdatePayload.ha_offline] and [StateUpdatePayload.data_stale] flags
     * derived from [StateManager] so the watch can display a stale-data indicator.
     */
    suspend fun sendStateUpdate(transport: BleTransport, msgId: Int, items: List<StateItem>) {
        if (items.isEmpty()) return
        sendToWatch(
            transport,
            Protocol.Type.STATE_UPDATE,
            msgId,
            StateUpdatePayload(
                items = items,
                ha_offline = stateManager.isHaOffline(),
                data_stale = stateManager.isDataStale(),
            ),
        )
    }

    /**
     * The [StateUpdatePayload.ha_offline] and [StateUpdatePayload.data_stale] flags alone, for
     * when they change with no state to send: during an HA outage nothing else goes out, and the
     * watch keeps the flags of the last update it got.
     */
    suspend fun sendStateFlags(transport: BleTransport) {
        sendToWatch(
            transport,
            Protocol.Type.STATE_UPDATE,
            nextMsgId(),
            StateUpdatePayload(
                items = emptyList(),
                ha_offline = stateManager.isHaOffline(),
                data_stale = stateManager.isDataStale(),
            ),
        )
    }

    /**
     * Send TIME_SYNC to the watch with the current UTC epoch, timezone offset (minutes), and DST flag.
     *
     * If the watch supports TIME_SYNC (feature flag [Protocol.Features.TIME_SYNC]) this waits up
     * to 5 seconds for a TIME_SYNC_ACK. On timeout the send is retried once; a status of 1 (the
     * watch clock was already within tolerance) counts as success.
     * Older firmware without the feature flag receives the message and no ACK is expected.
     */
    suspend fun sendTimeSync(transport: BleTransport) {
        val calendar = java.util.Calendar.getInstance()
        val timestampSeconds = System.currentTimeMillis() / 1000
        // getOffset() returns the effective UTC offset including active DST.
        //
        // SECONDS, not minutes. TimeSyncPayload.timezoneOffset is documented as
        // "Timezone offset in seconds from UTC" and the firmware adds it
        // straight onto an epoch in seconds:
        //     int64_t localTimestamp = timestamp + timezoneOffset;
        // This previously sent minutes, so a UTC-5 watch was handed -300
        // instead of -18000 and landed ~4h55m off its true local time.
        val timezoneOffsetMs = calendar.timeZone.getOffset(calendar.timeInMillis)
        val timezoneOffsetSeconds = timezoneOffsetMs / 1_000
        val isDst = calendar.timeZone.inDaylightTime(calendar.time)

        val payload = TimeSyncPayload(
            timestamp = timestampSeconds,
            timezoneOffset = timezoneOffsetSeconds,
            isDst = isDst,
        )

        val expectAck = peerSupports(Protocol.Features.TIME_SYNC)

        suspend fun trySend(): Boolean {
            // Register before sending: a SharedFlow has no replay, so subscribing
            // after the write can miss an ACK the watch has already sent.
            val pending = if (expectAck) {
                transport.expect { it.type == Protocol.Type.TIME_SYNC_ACK.wire }
            } else null

            // try/finally: sendToWatch throws on a failed write, which would
            // otherwise leak the waiter. cancelExpect is a no-op once completed.
            val ack = try {
                sendToWatch(transport, Protocol.Type.TIME_SYNC, nextMsgId(), payload)
                if (pending == null) return true
                withTimeoutOrNull(TIME_SYNC_ACK_TIMEOUT_MS) { pending.await() }
            } finally {
                pending?.let { transport.cancelExpect(it) }
            }
            if (ack == null) return false // timeout

            return try {
                val ackPayload = ProtocolCbor.decode(TimeSyncAckPayload.serializer(), ack.payload)
                logCallback("watch->phone", "TIME_SYNC_ACK", ack.msgId,
                    "status=${ackPayload.status}, delta=${ackPayload.appliedDeltaSeconds}s, watchEpoch=${ackPayload.watchUtcEpoch}")
                ackPayload.status == TIME_SYNC_APPLIED || ackPayload.status == TIME_SYNC_IN_TOLERANCE
            } catch (e: Exception) {
                logger.w(tag, "Failed to decode TIME_SYNC_ACK: ${e.message}")
                false
            }
        }

        if (!trySend()) {
            logger.w(tag, "TIME_SYNC not acknowledged, retrying once")
            trySend()
        }
    }

    /**
     * Encode a payload to CBOR bytes.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> encodePayload(payload: T): ByteArray {
        return when (payload) {
            is HelloPayload -> ProtocolCbor.encode(HelloPayload.serializer(), payload)
            is ConfigPushPayload -> ProtocolCbor.encode(ConfigPushPayload.serializer(), payload)
            is ConfigAckPayload -> ProtocolCbor.encode(ConfigAckPayload.serializer(), payload)
            is StateUpdatePayload -> ProtocolCbor.encode(StateUpdatePayload.serializer(), payload)
            is StateRefreshRequestPayload -> ProtocolCbor.encode(StateRefreshRequestPayload.serializer(), payload)
            is ActionInvokePayload -> ProtocolCbor.encode(ActionInvokePayload.serializer(), payload)
            is ActionResultPayload -> ProtocolCbor.encode(ActionResultPayload.serializer(), payload)
            is NotifyPayload -> ProtocolCbor.encode(NotifyPayload.serializer(), payload)
            is NotifyAckPayload -> ProtocolCbor.encode(NotifyAckPayload.serializer(), payload)
            is NotifyRemovePayload -> ProtocolCbor.encode(NotifyRemovePayload.serializer(), payload)
            is SensorReportPayload -> ProtocolCbor.encode(SensorReportPayload.serializer(), payload)
            is HapticFeedbackPayload -> ProtocolCbor.encode(HapticFeedbackPayload.serializer(), payload)
            is WatchFaceLayoutPayload -> throw IllegalArgumentException(
                "WatchFaceLayoutPayload is sent as bytes: encodeWatchFaceLayout, then sendWatchFaceLayoutBytes")
            is TimeSyncPayload -> ProtocolCbor.encode(TimeSyncPayload.serializer(), payload)
            is TimeSyncAckPayload -> ProtocolCbor.encode(TimeSyncAckPayload.serializer(), payload)
            is HelloAckPayload -> ProtocolCbor.encode(HelloAckPayload.serializer(), payload)
            is VersionMismatchPayload -> ProtocolCbor.encode(VersionMismatchPayload.serializer(), payload)
            is LogbookUpdatePayload -> ProtocolCbor.encode(LogbookUpdatePayload.serializer(), payload)
            is EmptyPayload -> ProtocolCbor.encode(EmptyPayload.serializer(), payload)
            is PongPayload -> ProtocolCbor.encode(PongPayload.serializer(), payload)
            // Missing until 2.1.74: every FIND_WATCH threw here, inside the command
            // handler's catch-all, so the watch never received one.
            is FindWatchPayload -> ProtocolCbor.encode(FindWatchPayload.serializer(), payload)
            // Missing until 2.1.96, as FIND_WATCH was: every calendar and home-control update
            // threw here, so the watch's calendar and home-control apps never received data.
            is CalendarUpdatePayload -> ProtocolCbor.encode(CalendarUpdatePayload.serializer(), payload)
            is HomeControlUpdatePayload -> ProtocolCbor.encode(HomeControlUpdatePayload.serializer(), payload)
            is DashboardStatePayload -> ProtocolCbor.encode(DashboardStatePayload.serializer(), payload)
            else -> throw IllegalArgumentException("Unsupported payload: ${payload!!::class}")
        }
    }

    /** Send FIND_WATCH to the watch (phone → watch). */
    suspend fun sendFindWatch(transport: BleTransport, durationSeconds: Int = 30) {
        sendToWatch(transport, Protocol.Type.FIND_WATCH, nextMsgId(), FindWatchPayload(duration = durationSeconds))
    }

    /**
     * Ask the watch why it last reset and for the crash context its previous boot left
     * (CRASH_DUMP_REQUEST, answered with the same type; see [CrashDumpPayload]). A delivered
     * context is confirmed with CRASH_DUMP_ACK, after which the watch stops offering it.
     *
     * Sends nothing unless the watch advertises [Protocol.Features.CRASH_REPORT]. Returns null
     * then, and on a timeout, a failed write or an undecodable reply; nothing is confirmed in
     * those cases, so the watch offers the context again on the next connection.
     */
    suspend fun requestCrashReport(transport: BleTransport): CrashDumpPayload? {
        if (!peerSupports(Protocol.Features.CRASH_REPORT)) return null
        // Registered before the write: see sendTimeSync.
        val pending = transport.expect { it.type == Protocol.Type.CRASH_DUMP_REQUEST.wire }
        val reply = try {
            val sent = sendToWatchSafe(transport, Protocol.Type.CRASH_DUMP_REQUEST, nextMsgId(), EmptyPayload())
            if (sent is AppResult.Err) {
                logger.w(tag, "CRASH_DUMP_REQUEST not sent: ${sent.error.message}")
                return null
            }
            withTimeoutOrNull(CRASH_REPORT_TIMEOUT_MS) { pending.await() }
        } finally {
            transport.cancelExpect(pending)
        }
        if (reply == null) {
            logger.w(tag, "CRASH_DUMP_REQUEST not answered within $CRASH_REPORT_TIMEOUT_MS ms")
            return null
        }
        val report = try {
            ProtocolCbor.decode(CrashDumpPayload.serializer(), reply.payload)
        } catch (e: Exception) {
            logger.w(tag, "Failed to decode the CRASH_DUMP_REQUEST reply: ${e.message}")
            return null
        }
        val ctx = report.crashContext
        val crash = crashSummary(report)
        logCallback(
            "watch->phone", "CRASH_REPORT", reply.msgId,
            "reset=${report.resetReason ?: "?"} context=${ctx?.let(::crashContextSummary) ?: "none"}" +
                if (crash != null) " crash=$crash" else "",
        )
        if (ctx != null || report.box != null) {
            if (crash != null) logger.w(tag, "Watch reported a crash in the previous boot: $crash, reset=${report.resetReason}")
            val acked = sendToWatchSafe(transport, Protocol.Type.CRASH_DUMP_ACK, nextMsgId(), EmptyPayload())
            if (acked is AppResult.Err) logger.w(tag, "CRASH_DUMP_ACK not sent: ${acked.error.message}")
        }
        return report
    }

    /** One line for a crash context, for the log, the Debug screen and the debug bundle. */
    fun crashContextSummary(ctx: CrashContextPayload): String =
        "${breadcrumbName(ctx.breadcrumb)} msg=0x${ctx.lastMsgType.toString(16)} id=${ctx.lastMsgId}" +
            " heap=${ctx.freeHeap} up=${ctx.uptimeTicks}"

    /**
     * The crash a report describes, as one line, or null when the previous boot ended in a planned
     * restart (an update, a reboot from settings): every such reset also leaves a context, the
     * bridge's last activity, which is not a crash. The black box's record decides when the watch
     * sends one (2.4.90+); before it, only a watchdog or lockup reset counts.
     */
    fun crashSummary(report: CrashDumpPayload): String? {
        report.box?.let { return bootBoxSummary(it) }
        val ctx = report.crashContext ?: return null
        return if (report.resetReason in UNEXPECTED_RESETS) crashContextSummary(ctx) else null
    }

    /** One line for the black box's record: what, where, when, and the registers. */
    fun bootBoxSummary(box: BootBoxCrashPayload): String {
        val kind = when (box.kind) {
            1 -> "FAULT"
            2 -> "WATCHDOG"
            3 -> "SDK-ERROR"
            else -> "RESET"
        }
        val where = when (box.context) {
            1 -> "display"
            2 -> "system"
            3 -> "ble"
            4 -> "ll"
            5 -> "timer"
            6 -> "heartrate"
            7 -> "task"
            8 -> "irq"
            9 -> "boot"
            10 -> "idle"
            else -> "?"
        }
        fun hex(v: Long) = "%08X".format(v)
        val seconds = "%.1f".format(java.util.Locale.ROOT, box.uptimeTicks / 1024.0)
        val calls = if (box.calls.isEmpty()) "" else " calls=" + box.calls.joinToString(",") { hex(it) }
        return "$kind in $where at ${seconds}s stage=${box.stage} pc=${hex(box.pc)} lr=${hex(box.lr)}" +
            " cfsr=${hex(box.cfsr)} addr=${hex(box.address)} minHeap=${box.minHeap}$calls"
    }

    /**
     * Handle an incoming message from the watch.
     * Delegates to specific handlers based on message type.
     */
    suspend fun handleWatchMessage(
        m: FrameCodec.CompleteMessage,
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
        onVoiceAssistInvoke: suspend (String) -> Unit,
        onSensorReport: suspend (SensorReportPayload) -> Unit,
        onCalendarNav: suspend (CalendarNavPayload) -> Unit = {},
        onHomeControlCommand: suspend (entityId: String) -> Unit = {},
        onNotifyDismiss: (nid: Int) -> Unit = {},
        onPong: ((msgId: Int) -> Unit)? = null,
        onHelloReceived: ((HelloPayload) -> Unit)? = null,
        onVersionMismatch: ((requiredVersion: String, currentVersion: String) -> Unit)? = null,
    ) {
        val type = Protocol.Type.values().firstOrNull { it.wire == m.type }
        // Universal inbound-frame visibility (HA-restore debugging): record EVERY
        // complete frame that reaches this dispatcher — type, msgId, payload size —
        // BEFORE the type-specific dispatch. Note that replies claimed by a
        // transport.expect() waiter (HELLO, CONFIG_ACK, WATCHFACE_ACK,
        // TIME_SYNC_ACK) are consumed there and do not reach this point; those
        // sites log their own arrival.
        logCallback(
            "watch->phone",
            type?.name ?: "UNKNOWN(0x%02X)".format(m.type),
            m.msgId,
            "rx ${m.payload.size}B",
        )
        if (type == null) return
        when (type) {
            Protocol.Type.HELLO -> {
                val hello = handleHello(m)
                if (hello != null) {
                    onHelloReceived?.invoke(hello)
                    // FR-BLE-6: Send HELLO_ACK with our semantic version so the watch can
                    // check major version compatibility on its side.
                    // REQ-057: include crashContextAck=true when a crash context was present
                    // in the watch HELLO, confirming successful ingestion so the watch can
                    // clear hasCrashContext_ and stop re-broadcasting the stale report.
                    sendToWatch(
                        transport,
                        Protocol.Type.HELLO_ACK,
                        nextMsgId(),
                        HelloAckPayload(
                            protocolVersion = Protocol.PROTOCOL_VERSION,
                            supportedFeatures = Protocol.Features.WATCHFACE_LAYOUT or
                                Protocol.Features.LOGBOOK or
                                Protocol.Features.HAPTIC_FEEDBACK or
                                Protocol.Features.TIME_SYNC or
                                Protocol.Features.VOICE_ASSIST or
                                Protocol.Features.WATCHFACE_ACK or
                                Protocol.Features.CALENDAR or
                                Protocol.Features.HOME_CONTROL,
                            crashContextAck = hello.crashContext != null,
                        ),
                    )
                    // FR-BLE-6: Check major version compatibility when the watch sends a version string.
                    val watchVersionStr = hello.protoVersionString
                    if (watchVersionStr != null) {
                        val watchMajor = watchVersionStr.substringBefore('.').toIntOrNull() ?: 0
                        val ourMajor = Protocol.PROTOCOL_VERSION.substringBefore('.').toIntOrNull() ?: 1
                        if (watchMajor != ourMajor) {
                            logger.w(tag, "Protocol major version mismatch: watch=$watchVersionStr, phone=${Protocol.PROTOCOL_VERSION}")
                            onVersionMismatch?.invoke(watchVersionStr, Protocol.PROTOCOL_VERSION)
                        }
                    }
                }
            }
            Protocol.Type.VERSION_MISMATCH -> {
                handleVersionMismatch(m, onVersionMismatch)
            }
            Protocol.Type.TIME_SYNC_ACK -> {
                // Consumed by sendTimeSync() via transport.expect(); log any
                // unexpected arrivals that arrive outside the wait window.
                try {
                    val ackPayload = ProtocolCbor.decode(TimeSyncAckPayload.serializer(), m.payload)
                    logCallback("watch->phone", "TIME_SYNC_ACK", m.msgId,
                        "status=${ackPayload.status}, delta=${ackPayload.appliedDeltaSeconds}s")
                } catch (e: Exception) {
                    logger.w(tag, "Failed to decode TIME_SYNC_ACK: ${e.message}")
                }
            }
            Protocol.Type.ACTION_INVOKE -> {
                handleActionInvoke(m, ha, transport, sessionScope, onVoiceAssistInvoke, onHomeControlCommand)
            }
            Protocol.Type.NOTIFY_ACK -> {
                handleNotifyAck(m)
            }
            Protocol.Type.NOTIFY_DISMISS -> {
                handleNotifyDismiss(m, onNotifyDismiss)
            }
            Protocol.Type.PING -> {
                sendToWatch(transport, Protocol.Type.PONG, nextMsgId(), EmptyPayload())
                logCallback("watch->phone", "PING", m.msgId, "")
            }
            Protocol.Type.PONG -> {
                // The echoed request id lives in the payload, not the header —
                // the watch stamps its own sequential id on every frame it
                // sends, so m.msgId never equals the PING's id.
                var echoedId: Int? = null
                // FR-BLE-3: decode optional diagnostic fields piggybacked on PONG
                val summary = if (m.payload.isNotEmpty()) {
                    try {
                        val pong = ProtocolCbor.decode(PongPayload.serializer(), m.payload)
                        echoedId = pong.requestMsgId
                        pong.enc?.let { peerEnc = it }
                        buildString {
                            pong.rssi?.let { append("rssi=${it}dBm ") }
                            pong.queueDepth?.let { append("qd=${it} ") }
                            pong.errorCount?.let { append("ec=${it}") }
                        }.trim().ifEmpty { "" }
                    } catch (_: Exception) {
                        ""
                    }
                } else ""
                logCallback("watch->phone", "PONG", echoedId ?: m.msgId, summary)
                // Report the sentinel rather than the header id when nothing
                // was echoed: the header id is the watch's own sequence number
                // and would never match the PING, so passing it through would
                // look like a miss on every round.
                onPong?.invoke(echoedId ?: Protocol.PONG_NO_ECHO)
            }
            Protocol.Type.SENSOR_REPORT -> {
                handleSensorReport(m, onSensorReport)
            }
            Protocol.Type.CALENDAR_NAV -> {
                try {
                    val payload = ProtocolCbor.decode(CalendarNavPayload.serializer(), m.payload)
                    logCallback("watch->phone", "CALENDAR_NAV", m.msgId, "offset=${payload.dayOffset}")
                    onCalendarNav(payload)
                } catch (e: Exception) {
                    logger.w(tag, "Failed to decode CALENDAR_NAV: ${e.message}")
                }
            }
            else -> Unit
        }
    }

    /**
     * Applies a HELLO from the watch, then calls [onHelloSeen]: an empty or undecodable HELLO
     * counts as one (see [helloSeen]), so the hook runs for every HELLO seen, not only the ones
     * that decode.
     */
    fun handleHello(m: FrameCodec.CompleteMessage): HelloPayload? {
        val wasPredating = watchPredatesConfigKey
        val hello = applyHello(m)
        onHelloSeen?.invoke()
        if (wasPredating && !watchPredatesConfigKey) onConfigKeySupportGained?.invoke()
        return hello
    }

    private fun applyHello(m: FrameCodec.CompleteMessage): HelloPayload? {
        helloSeen = true
        if (m.payload.isEmpty()) {
            peerFeatures = 0
            peerHelloDiagRev = 0
            peerEnc = null
            logCallback("watch->phone", "HELLO", m.msgId, "(empty)")
            return null
        }
        try {
            val payload = ProtocolCbor.decode(HelloPayload.serializer(), m.payload)
            peerFeatures = payload.features
            peerFreeHeap = payload.freeHeap ?: peerFreeHeap
            peerHelloDiagRev = payload.helloDiagRev ?: 0
            peerEnc = payload.enc
            val summary = buildString {
                append("fw=${payload.fwVersion ?: "?"}")
                if (payload.features != 0) append(" features=0x${payload.features.toString(16)}")
                payload.resetReason?.let { append(" reset=$it") }
                payload.freeHeap?.let { append(" heap=$it") }
                payload.minFreeHeap?.let { append(" minHeap=$it") }
                payload.mallocFails?.let { if (it > 0) append(" mallocFails=$it") }
                payload.stackOverflows?.let { if (it > 0) append(" stackOverflows=$it") }
                payload.mbufFailures?.let { if (it > 0) append(" mbufFails=$it") }
                payload.notifyFailures?.let { if (it > 0) append(" notifyFails=$it") }
                payload.oversizeNotifyDrops?.let { if (it > 0) append(" oversizeDrops=$it") }
                payload.negotiatedMtu?.let { append(" watchMtu=$it") }
                payload.safeFaceTrips?.let { if (it > 0) append(" safeFaceTrips=$it") }
                // Uptime is always shown: a value that went backwards between two HELLOs
                // is a watch reset, which is what the hardware gate checks for.
                payload.uptimeTicks?.let { append(" up=$it") }
                payload.layoutRefusedCount()?.let { if (it > 0) append(" layoutRefused=$it") }
                payload.layoutWriteFailedCount()?.let { if (it > 0) append(" layoutWriteFailed=$it") }
                // The screen-wake telemetry (2.4.104), as the five numbers: HealthSummary reads it back.
                payload.wakeDiag()?.let { append(" ${HealthSummary.WK_KEY}${it.wakes},${it.notifyWakes},${it.notifyStored},${it.screenOnSec},${it.settings}") }
                payload.crashContext?.let { ctx ->
                    append(" CRASH[bc=${ctx.breadcrumb},msg=0x${ctx.lastMsgType.toString(16)}")
                    append(",id=${ctx.lastMsgId},heap=${ctx.freeHeap},tick=${ctx.uptimeTicks}")
                    // 2.4.41+ only: the faulting PC is the whole point of the capture.
                    if (ctx.hasFaultRegisters) {
                        append(",pc=0x${ctx.pc.toString(16)},lr=0x${ctx.lr.toString(16)}")
                    }
                    append("]")
                }
            }
            logCallback("watch->phone", "HELLO", m.msgId, summary)

            // Log crash context as a warning if present
            val ctx = payload.crashContext
            if (ctx != null) {
                val bc = breadcrumbName(ctx.breadcrumb)
                logger.w(tag, "Watch reported crash context from previous boot: " +
                    "breadcrumb=$bc (${ctx.breadcrumb}), " +
                    "lastMsgType=0x${ctx.lastMsgType.toString(16)}, " +
                    "lastMsgId=${ctx.lastMsgId}, " +
                    "freeHeap=${ctx.freeHeap}, " +
                    "uptimeTicks=${ctx.uptimeTicks}")
                // Logged separately so the fault line can be copied on its own and
                // fed straight to addr2line. Absent on pre-2.4.41 firmware.
                ctx.faultSummary()?.let { logger.w(tag, "Watch fault registers: $it") }
            }
            return payload
        } catch (e: Exception) {
            // Backward-compatible: old firmware sends empty HELLO
            peerFeatures = 0
            peerHelloDiagRev = 0
            peerEnc = null
            logCallback("watch->phone", "HELLO", m.msgId, "(no diagnostics)")
            return null
        }
    }

    private fun handleVersionMismatch(
        m: FrameCodec.CompleteMessage,
        onVersionMismatch: ((requiredVersion: String, currentVersion: String) -> Unit)?,
    ) {
        try {
            val payload = ProtocolCbor.decode(VersionMismatchPayload.serializer(), m.payload)
            logger.w(tag, "Watch reported VERSION_MISMATCH: required=${payload.requiredVersion}, current=${payload.currentVersion}")
            logCallback("watch->phone", "VERSION_MISMATCH", m.msgId,
                "required=${payload.requiredVersion}, current=${payload.currentVersion}")
            onVersionMismatch?.invoke(payload.requiredVersion, payload.currentVersion)
        } catch (e: Exception) {
            logger.w(tag, "Failed to decode VERSION_MISMATCH: ${e.message}")
        }
    }

    /** Map breadcrumb code to human-readable name. */
    private fun breadcrumbName(code: Int): String = when (code) {
        0 -> "IDLE"
        1 -> "BLE_RX"
        2 -> "FRAME_DECODE"
        3 -> "CBOR_DECODE"
        4 -> "CONFIG_APPLY"
        5 -> "SCREEN_TRANSITION"
        6 -> "WATCHFACE_APPLY"
        7 -> "SENSOR_REPORT"
        8 -> "BLE_TX"
        9 -> "CBOR_ENCODE"
        else -> "UNKNOWN($code)"
    }

    private suspend fun handleActionInvoke(
        m: FrameCodec.CompleteMessage,
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
        onVoiceAssistInvoke: suspend (String) -> Unit,
        onHomeControlCommand: suspend (entityId: String) -> Unit = {},
    ) {
        try {
            val payload = ProtocolCbor.decode(ActionInvokePayload.serializer(), m.payload)
            logCallback("watch->phone", "ACTION_INVOKE", m.msgId, "actionId=${payload.actionId} gesture=${payload.gesture} entityId=${payload.entityId}")

            // Check if this is the voice assist reserved action
            if (payload.actionId == Protocol.VOICE_ASSIST_ACTION_ID) {
                onVoiceAssistInvoke(payload.gesture)
                return
            }

            // Check if this is a Home Control app tap (entity toggle via dedicated app)
            if (payload.actionId == Protocol.HOME_CONTROL_ACTION_ID) {
                logCallback("watch->phone", "HOME_CONTROL_COMMAND", m.msgId, "entityId=${payload.entityId}")
                onHomeControlCommand(payload.entityId ?: "")
                return
            }

            // Check if this is a tile tap (entity toggle)
            if (payload.actionId == Protocol.TILE_ACTION_ID && !payload.entityId.isNullOrEmpty()) {
                logger.d(tag, "Tile tap: toggling entity ${payload.entityId}")
                sessionScope.launch {
                    try {
                        // device: prefix means this is an HA device_id, not an entity_id
                        // Use HA target to address the device directly
                        val eid = payload.entityId!!
                        val result = if (eid.startsWith("device:")) {
                            val deviceId = eid.removePrefix("device:")
                            logger.d(tag, "Tile tap: using device_id target: $deviceId")
                            ha.callService(
                                domain = "homeassistant",
                                service = "toggle",
                                serviceData = JsonObject(emptyMap()),
                                target = JsonObject(mapOf("device_id" to JsonPrimitive(deviceId)))
                            )
                        } else {
                            ha.callService(
                                domain = "homeassistant",
                                service = "toggle",
                                serviceData = JsonObject(emptyMap()),
                                target = JsonObject(mapOf("entity_id" to JsonPrimitive(eid)))
                            )
                        }
                        val ok = result is AppResult.Ok
                        val errMsg = (result as? AppResult.Err)?.error?.message
                        logger.d(tag, "Tile toggle result: ok=$ok entity=$eid err=$errMsg")
                        sendToWatch(
                            transport,
                            Protocol.Type.ACTION_RESULT,
                            nextMsgId(),
                            ActionResultPayload(actionId = payload.actionId, ok = ok, message = errMsg)
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logger.e(tag, "Tile toggle failed: ${e.message}", e)
                    }
                }
                return
            }
        } catch (e: Exception) {
            logger.w(tag, "Failed to decode ACTION_INVOKE: ${e.message}")
        }
    }

    private fun handleNotifyAck(m: FrameCodec.CompleteMessage) {
        try {
            val payload = ProtocolCbor.decode(NotifyAckPayload.serializer(), m.payload)
            stateManager.removePendingNotification(payload.msgId)
            logCallback("watch->phone", "NOTIFY_ACK", m.msgId, "ack for ${payload.msgId}")
        } catch (e: Exception) {
            logger.w(tag, "Failed to decode NOTIFY_ACK: ${e.message}")
        }
    }

    /** NOTIFY_DISMISS: the user dismissed phone notification `nid` on the watch. */
    private fun handleNotifyDismiss(m: FrameCodec.CompleteMessage, onNotifyDismiss: (nid: Int) -> Unit) {
        // Only a watch that advertised bit 15 sends this; the retired REQ-071 meaning shared the type.
        if (!peerSupports(Protocol.Features.PHONE_NOTIFY_SYNC)) {
            logCallback("watch->phone", "NOTIFY_DISMISS", m.msgId, "dropped: watch did not advertise PHONE_NOTIFY_SYNC")
            return
        }
        try {
            val payload = ProtocolCbor.decode(NotifyDismissPayload.serializer(), m.payload)
            logCallback("watch->phone", "NOTIFY_DISMISS", m.msgId, "nid=${payload.nid}")
            onNotifyDismiss(payload.nid)
        } catch (e: Exception) {
            logger.w(tag, "Failed to decode NOTIFY_DISMISS: ${e.message}")
        }
    }

    private suspend fun handleSensorReport(
        m: FrameCodec.CompleteMessage,
        onSensorReport: suspend (SensorReportPayload) -> Unit,
    ) {
        try {
            val payload = ProtocolCbor.decode(SensorReportPayload.serializer(), m.payload)
            logCallback("watch->phone", "SENSOR_REPORT", m.msgId, sensorReportSummary(payload))
            onSensorReport(payload)
        } catch (e: Exception) {
            logger.w(tag, "Failed to decode SENSOR_REPORT: ${e.message}")
        }
    }

    /**
     * The SENSOR_REPORT line of the diagnostic log. HealthSummary reads its name=number pairs
     * back for the bundle's watch counters, so keep them in that form. With a voltage the line also
     * says whether the watch was charging (`bc=1`) and, only when it is, that power is present
     * (`pp=1`: also true when the battery is full, which `bc` is not), which the battery trend
     * needs to tell a drain from a charge. A line without `pp` is a watch off power.
     */
    fun sensorReportSummary(payload: SensorReportPayload): String = buildString {
        append("steps=${payload.steps}, hr=${payload.heartRate}, batt=${payload.batteryPercent}%")
        payload.batteryMillivolts?.let { append(" bmv=$it bc=${if (payload.batteryCharging) 1 else 0}") }
        if (payload.powerPresent == true) append(" pp=1")
        payload.freeHeap?.let { append(" heap=$it") }
        payload.minFreeHeap?.let { append(" minHeap=$it") }
        payload.stackHighWater?.let { append(" stackHW=$it") }
        payload.dashStateOk?.let { append(" dsOk=$it") }
        payload.dashStateRefused?.let { append(" dsBad=$it") }
        payload.fingerprintMisses?.let { append(" fpMis=$it") }
        payload.dashStateAgeSec?.let { append(" dsAge=$it") }
    }

    /**
     * The states of [entities], from [stateCache]. Right after HA connects, and after the watched
     * entities change, the cache's first answer is waited for, a few seconds at most. An entity HA
     * does not have, or that is not watched, is left out.
     */
    suspend fun fetchSelectedStates(ha: HaWebSocketClient, entities: List<EntityConfig>): AppResult<List<StateItem>> {
        return try {
            if (entities.isEmpty()) return AppResult.Ok(emptyList())
            if (!stateCache.awaitSnapshot(ha)) return AppResult.Err(IllegalStateException("no states from HA"))
            val byId = stateCache.states(entities.map { it.entityId })
            // In the order asked for, not HA's: the watch keeps the first 16 states it is sent,
            // and the callers put the tile entities, the only ones it shows, first.
            val selected = entities.map { it.entityId }.distinct().mapNotNull { byId[it] }
            val items = selected.map { s ->
                val unit = s.attributes["unit_of_measurement"]?.jsonPrimitive?.contentOrNull
                StateItem(entityId = s.entityId, state = s.state, unit = unit, ts = System.currentTimeMillis())
            }
            AppResult.Ok(items)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(tag, "fetchSelectedStates failed: ${e.message}", e)
            AppResult.Err(e)
        }
    }
}

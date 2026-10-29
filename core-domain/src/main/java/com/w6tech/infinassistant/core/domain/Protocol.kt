package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

object Protocol {
    /**
     * Current protocol version. Sent in the HELLO handshake.
     *
     * Bump VERSION when adding new message types or changing existing ones.
     */
    const val VERSION: Int = 1

    /**
     * Semantic protocol version string for FR-BLE-6 version negotiation.
     * Sent in HELLO_ACK after receiving a watch HELLO. Major version must match
     * for the connection to proceed; minor/patch differences are tolerated.
     */
    const val PROTOCOL_VERSION: String = "1.0.0"

    /**
     * Major version as an integer, sent in HELLO_ACK as "pmaj".
     *
     * The watch compares this against its own kProtocolMajor and answers
     * VERSION_MISMATCH when they differ. It reads the short key "pmaj" as an
     * unsigned integer and nothing else — it cannot parse PROTOCOL_VERSION, which
     * is a string. Omitting pmaj leaves the watch's peerMajor at 0, so every
     * HELLO_ACK drew a VERSION_MISMATCH and real negotiation never worked.
     */
    const val PROTOCOL_MAJOR: Int = 1

    /**
     * Sentinel reported for a PONG that carried no echoed request id, i.e.
     * firmware older than 2.4.35. The liveness check accepts it so an older
     * watch is not reported unresponsive.
     */
    const val PONG_NO_ECHO: Int = -1

    /** Reserved action ID for voice assistant trigger. */
    const val VOICE_ASSIST_ACTION_ID: Int = 0xFFFE

    /** Reserved action ID for tile entity toggle (watch face). */
    const val TILE_ACTION_ID: Int = 0xFFFD

    /** Reserved action ID for Home Control app tile tap. */
    const val HOME_CONTROL_ACTION_ID: Int = 0xFFFC

    /** Protocol feature flags negotiated during HELLO handshake. */
    object Features {
        const val WATCHFACE_LAYOUT = 1 shl 0   // 0x01 - Watch face layout support
        const val LOGBOOK = 1 shl 1             // 0x02 - Logbook support
        const val HAPTIC_FEEDBACK = 1 shl 2     // 0x04 - Haptic feedback support
        const val TIME_SYNC = 1 shl 3           // 0x08 - Time sync support
        const val VOICE_ASSIST = 1 shl 4        // 0x10 - Voice assistant support
        const val WATCHFACE_ACK = 1 shl 5       // 0x20 - Watch face ACK support
        const val CALENDAR     = 1 shl 6        // 0x40 - Calendar app support
        const val HOME_CONTROL = 1 shl 7        // 0x80 - Home control app support
        // Bits 8+ must stay in step with kFeature* in the firmware's
        // ProtocolMessages.h — the watch advertises these in HELLO.
        const val PROTOCOL_NEGOTIATION = 1 shl 8   // 0x100 - HELLO_ACK / VERSION_MISMATCH
        const val FIND_DEVICE = 1 shl 9            // 0x200 - FIND_PHONE / FIND_WATCH
        const val NOTIFY_DISMISS_SNOOZE = 1 shl 10 // 0x400 - NOTIFY_DISMISS / NOTIFY_SNOOZE
        const val SHORT_KEYS = 1 shl 11            // 0x800 - WATCHFACE_LAYOUT decoder accepts short keys (REQ009-FR-C3)
        const val CRASH_REPORT = 1 shl 12          // 0x1000 - answers CRASH_DUMP_REQUEST (see CrashDumpPayload)
        const val DASHBOARD_STATE = 1 shl 13       // 0x2000 - takes DASHBOARD_STATE (see DashboardStatePayload)
        const val SIX_SCREENS = 1 shl 14           // 0x4000 - holds 6 screens in a layout, not 4 (fw 2.4.100)
        // Bit 10 was NOTIFY_DISMISS_SNOOZE (REQ-071), which firmware before 2.4.95 still sets: do not reuse it.
        const val PHONE_NOTIFY_SYNC = 1 shl 15     // 0x8000 - NOTIFY nid, NOTIFY_REMOVE, and NOTIFY_DISMISS back (fw 2.4.101)
        // The word is a 32-bit Int from here on: 0x1FBFF is 5 bytes as a CBOR unsigned int.
        const val CONFIG_KEY_V2 = 1 shl 16         // 0x10000 - CONFIG_PUSH decoder reads the key "infinassistant" (fw 2.4.103)
    }

    /** Message type values as defined in the spec. */
    enum class Type(val wire: Int) {
        HELLO(0x01),
        CONFIG_PUSH(0x02),
        CONFIG_ACK(0x03),
        STATE_UPDATE(0x04),
        STATE_REFRESH_REQUEST(0x05),
        ACTION_INVOKE(0x06),
        ACTION_RESULT(0x07),
        NOTIFY(0x08),
        NOTIFY_ACK(0x09),
        PING(0x0A),
        PONG(0x0B),
        SENSOR_REPORT(0x0C),
        HAPTIC_FEEDBACK(0x0D),
        WATCHFACE_LAYOUT(0x0E),
        TIME_SYNC(0x0F),
        LOGBOOK_REQUEST(0x10),
        LOGBOOK_UPDATE(0x11),
        WATCHFACE_ACK(0x12),
        CALENDAR_UPDATE(0x13),
        CALENDAR_NAV(0x14),
        HOME_CONTROL_UPDATE(0x15),
        TIME_SYNC_ACK(0x16),
        HELLO_ACK(0x17),
        VERSION_MISMATCH(0x18),
        CONFIG_PUSH_ACK(0x19),
        CRASH_DUMP_REQUEST(0x1A),
        CRASH_DUMP_ACK(0x1B),
        // REQ-072 — find-device round-trip (bridge_protocol_rev = 2).
        FIND_PHONE(0x1C),    // watch → phone: user requests phone alert
        FIND_WATCH(0x1D),    // phone → watch: vibrate + alert 30 s
        // Phone notification sync (fw 2.4.101): NOTIFY_DISMISS {nid} and NOTIFY_REMOVE {nid} carry a phone notification's nid.
        NOTIFY_DISMISS(0x1E),  // watch → phone: user dismissed a nid notification (retired REQ-071 meaning)
        NOTIFY_SNOOZE(0x1F),   // watch → phone: user long-pressed to snooze a notification
        // Dashboard v7 — the dashboard's live state (firmware 2.4.96, ha_bridge/DashboardState.h).
        DASHBOARD_STATE(0x20), // phone → watch: doors, lights, sensors, weather and events as they are now
        NOTIFY_REMOVE(0x21),   // phone → watch: the phone removed notification nid; no popup, no ACK
    }
}

@Serializable
data class Capabilities(
    val cbor: Boolean = true,
    val maxActions: Int = 20,
    val supportsDoubleTap: Boolean = true,
    val supportsNotifyDedupe: Boolean = true,
)

@Serializable
data class HelloPayload(
    val device: String, // "watch" | "phone"
    val proto: Int = Protocol.VERSION,
    val capabilities: Capabilities = Capabilities(),
    /** Bitmask of supported protocol features (see [Protocol.Features]). Default 0 for backward compat. */
    val features: Int = 0,
    /**
     * Semantic version string for FR-BLE-6 protocol negotiation (e.g. "1.0.0").
     * Null on older firmware that does not support version negotiation.
     * Android sends this in its own HELLO so the watch can compare major versions.
     */
    val protoVersionString: String? = null,
    // Firmware diagnostics (only present in watch HELLO)
    val fwVersion: String? = null,
    val resetReason: String? = null,
    val freeHeap: Long? = null,
    val minFreeHeap: Long? = null,
    val mallocFails: Int? = null,
    val stackOverflows: Int? = null,
    val mbufFailures: Int? = null,
    /**
     * ble_gattc_notify_custom returned non-zero this many times. Non-zero means the
     * watch built notifications the stack refused — which is how WATCHFACE_ACK was
     * lost on every layout apply while mbufFailures stayed 0. 2.4.61+.
     */
    val notifyFailures: Int? = null,
    /**
     * Frames SendSingleFrameImpl refused to send because they exceeded the MTU it
     * believes is negotiated, paired with that MTU. Both have been on the wire since
     * REQ-012 and neither was parsed, which left the only remaining explanation for a
     * missing WATCHFACE_ACK unobservable once mbufFailures and notifyFailures read 0.
     */
    val oversizeNotifyDrops: Int? = null,
    val negotiatedMtu: Int? = null,
    val uptimeTicks: Long? = null,
    val crashContext: CrashContextPayload? = null,
    /**
     * REQ-034 / REQ-056 — HELLO diagnostics protocol revision. Null when
     * watch firmware predates the revisioned protocol; ≥ 1 means the watch
     * supports the modern WATCHFACE_ACK contract (ok=true required, not
     * inferred from CONFIG_ACK). Bumped to 2 when REQ-056 lands.
     */
    val helloDiagRev: Int? = null,
    /**
     * REQ-067 — cumulative count of false→true safe-face mode transitions on
     * the watch (i.e. how many times the layout was too heavy for the LVGL
     * pool). Null on firmware that predates REQ-064/REQ-067. Android uses an
     * increase in this counter to post a preflight notification.
     */
    val safeFaceTrips: Int? = null,
    /**
     * REQ009-OBS-1 — layout pushes the watch refused before decode (heap guard,
     * failed allocation, a push already pending, or a materialization holding the
     * heap phase). Introduced at `helloDiagRev` 3. Read it through
     * [layoutRefusedCount], which returns null on older firmware.
     */
    val layoutRefused: Long? = null,
    /**
     * Layout pushes the watch accepted (its WATCHFACE_ACK said ok) and then could not
     * write to its store, so the previous layout stands. Introduced at `helloDiagRev` 4
     * (firmware 2.4.64). Read it through [layoutWriteFailedCount].
     */
    val layoutWriteFailed: Long? = null,
    /**
     * Whether the link was encrypted when this HELLO was sent (firmware 2.4.101+); null from older
     * firmware. Phone notifications are forwarded only over a bonded link and, when this is
     * reported, only while it is true.
     */
    val enc: Boolean? = null,
    /**
     * The screen-wake telemetry, the compact HELLO's optional `wk` array (firmware 2.4.104,
     * `helloDiagRev` 5). Absent when the watch did not send it: read it through [wakeDiag].
     */
    val wk: List<Long>? = null,
) {
    /**
     * The decoded screen-wake telemetry, or null when the HELLO did not carry it (an older watch, or
     * a HELLO too full for the key) or it is malformed. Null means "not reported", never zero.
     */
    fun wakeDiag(): WakeDiag? = if ((helloDiagRev ?: 0) >= WAKE_DIAG_MIN_REV) WakeDiag.from(wk) else null

    /**
     * The refused-push count, or null when this HELLO predates the field. Gated on
     * `helloDiagRev` so an older watch never reads as zero refusals.
     */
    fun layoutRefusedCount(): Long? =
        if ((helloDiagRev ?: 0) >= LAYOUT_REFUSED_MIN_REV) layoutRefused else null

    /**
     * The failed-write count, or null when this HELLO predates the field. Gated on
     * `helloDiagRev` so an older watch never reads as zero failed writes.
     */
    fun layoutWriteFailedCount(): Long? =
        if ((helloDiagRev ?: 0) >= LAYOUT_WRITE_FAILED_MIN_REV) layoutWriteFailed else null

    companion object {
        /** `hello_diag_rev` that introduced [layoutRefused]. */
        const val LAYOUT_REFUSED_MIN_REV = 3

        /** `hello_diag_rev` that introduced [layoutWriteFailed]. */
        const val LAYOUT_WRITE_FAILED_MIN_REV = 4

        /** `hello_diag_rev` that introduced [wk]. */
        const val WAKE_DIAG_MIN_REV = 5
    }
}

/**
 * Crash breadcrumb data from watch's previous boot (.noinit RAM).
 *
 * The first five fields are the base context, always sent. The rest arrive only
 * from firmware built with HA_CRASH_CONTEXT_EXTENDED=1 (2.4.41+), which installs
 * the ARM fault handlers; every one defaults to 0/"" so older firmware still
 * decodes. ProtocolCbor sets ignoreUnknownKeys, so the reverse (new firmware,
 * old app) is also safe — the extra keys are simply dropped.
 *
 * `pc` is the instruction that faulted: decode it with
 *   arm-none-eabi-addr2line -e pinetime-mcuboot-app-<ver>.out -f -C -i <pc>
 */
@Serializable
data class CrashContextPayload(
    val breadcrumb: Int = 0,
    val lastMsgType: Int = 0,
    val lastMsgId: Int = 0,
    val freeHeap: Long = 0,
    val uptimeTicks: Long = 0,
    // --- ARM Cortex-M fault registers (HA_CRASH_CONTEXT_EXTENDED) ---
    val pc: Long = 0,
    val lr: Long = 0,
    val xpsr: Long = 0,
    val cfsr: Long = 0,
    val hfsr: Long = 0,
    val mmfar: Long = 0,
    val bfar: Long = 0,
    // --- Task / scheduler + LVGL snapshot ---
    val taskName: String = "",
    val displayStackHwm: Long = 0,
    val lvFreeSize: Long = 0,
    val lvFreeBiggest: Long = 0,
    // --- Layout fingerprint at the time of the fault ---
    val activeScreenId: Int = 0,
    val lastResetReason: Int = 0,
    val layoutGen: Long = 0,
    val layoutCrc: Long = 0,
) {
    /** True when the watch actually captured ARM fault registers (2.4.41+). */
    val hasFaultRegisters: Boolean
        get() = pc != 0L || lr != 0L || cfsr != 0L || hfsr != 0L

    /**
     * One-line, copy-pasteable fault summary for the debug bundle and logs.
     * Returns null when the firmware did not capture fault registers.
     */
    fun faultSummary(): String? {
        if (!hasFaultRegisters) return null
        return buildString {
            append("pc=0x${pc.toString(16)}")
            append(" lr=0x${lr.toString(16)}")
            append(" cfsr=0x${cfsr.toString(16)}")
            append(" hfsr=0x${hfsr.toString(16)}")
            if (mmfar != 0L) append(" mmfar=0x${mmfar.toString(16)}")
            if (bfar != 0L) append(" bfar=0x${bfar.toString(16)}")
            if (taskName.isNotBlank()) append(" task=$taskName")
            if (displayStackHwm != 0L) append(" dispStackHwm=$displayStackHwm")
            if (layoutCrc != 0L) append(" layoutCrc=0x${layoutCrc.toString(16)}")
            if (layoutGen != 0L) append(" layoutGen=$layoutGen")
        }
    }
}

/**
 * The watch's reply to CRASH_DUMP_REQUEST. It answers with the same type byte, so the
 * reply is CRASH_DUMP_REQUEST in the watch → phone direction.
 *
 * Firmware that advertises [Protocol.Features.CRASH_REPORT] (2.4.65+) keeps one slot, the
 * context left by the previous boot: `{"empty": true}` when there is none, else `slotIndex` 0
 * and the five base fields of [CrashContextPayload]. Both forms carry [resetReason], the
 * watch's reason for its last reset ("Watchdog", "Soft reset", "Reset pin", ...). Neither
 * reached the phone before: the rich HELLO that carries them never fits one frame.
 *
 * A context is left by any reset that keeps RAM (watchdog, soft reset, reset pin), not only
 * by a crash: the breadcrumb says what the watch was doing, IDLE for nothing in particular.
 * Firmware 2.4.90+ adds [box], the boot black box's record, when the previous boot ended badly;
 * that, not the context, says whether it crashed. The phone confirms a delivered context or
 * record with CRASH_DUMP_ACK, and the watch then answers `empty` until it resets again.
 */
@Serializable
data class CrashDumpPayload(
    val resetReason: String? = null,
    val empty: Boolean = false,
    val slotIndex: Int? = null,
    val breadcrumb: Int = 0,
    val lastMsgType: Int = 0,
    val lastMsgId: Int = 0,
    val freeHeap: Long = 0,
    val uptimeTicks: Long = 0,
    val box: BootBoxCrashPayload? = null,
) {
    /** The context this reply delivers, or null when the watch had none. */
    val crashContext: CrashContextPayload?
        get() = if (empty || slotIndex == null) null else CrashContextPayload(
            breadcrumb = breadcrumb,
            lastMsgType = lastMsgType,
            lastMsgId = lastMsgId,
            freeHeap = freeHeap,
            uptimeTicks = uptimeTicks,
        )
}

/**
 * The boot black box's record of how the previous boot ended (firmware 2.4.90+, the "box" map of
 * a CRASH_DUMP reply; BridgeController::HandleCrashDumpRequest). Sent only when that boot ended
 * badly, with what the watch shows on its screen for a minute afterwards.
 */
@Serializable
data class BootBoxCrashPayload(
    /** 1 HardFault, 2 watchdog, 3 SDK error; 0 a lockup or watchdog reset that left no record. */
    @kotlinx.serialization.SerialName("k") val kind: Int = 0,
    /** Where it hit: 1 display task, 2 SystemTask, 3 BLE host, 4 BLE link layer, 5 timer daemon, 6 heart rate, 7 other task, 8 interrupt, 9 before the scheduler, 10 idle. */
    @kotlinx.serialization.SerialName("c") val context: Int = 0,
    /** Furthest SystemTask start-up stage (12 = running). */
    @kotlinx.serialization.SerialName("s") val stage: Int = 0,
    /** Uptime at the end, in ticks (1024 per second). */
    @kotlinx.serialization.SerialName("u") val uptimeTicks: Long = 0,
    @kotlinx.serialization.SerialName("pc") val pc: Long = 0,
    @kotlinx.serialization.SerialName("lr") val lr: Long = 0,
    /** Fault: CFSR; SDK error: the error code. */
    @kotlinx.serialization.SerialName("cf") val cfsr: Long = 0,
    /** Fault: BFAR or MMFAR when valid. */
    @kotlinx.serialization.SerialName("ad") val address: Long = 0,
    /** Lowest free heap of that boot, bytes. */
    @kotlinx.serialization.SerialName("h") val minHeap: Long = 0,
    /** The innermost return addresses above the fault frame. */
    @kotlinx.serialization.SerialName("cl") val calls: List<Long> = emptyList(),
)

@Serializable
data class InfinassistantConfig(
    val enabled: Boolean = false,
    val sensorReportIntervalSeconds: Int = 60,
)

@Serializable
data class ConfigPushPayload(
    val configVersion: Int,
    @SerialName("infinassistant") val infinassistant: InfinassistantConfig = InfinassistantConfig(),
    /**
     * Voice assist is switched on in the app. The watch (firmware 2.4.99) takes a side-button
     * double-click for voice only when its last CONFIG_PUSH said true; otherwise the double-click
     * opens Notifications. False is the default and so is not written.
     */
    val voiceAssist: Boolean = false,
)

@Serializable
data class ConfigAckPayload(
    val ok: Boolean,
    val appliedVersion: Int,
)

@Serializable
data class StateItem(
    val entityId: String,
    val state: String,
    val unit: String? = null,
    val ts: Long,
)

@Serializable
data class StateUpdatePayload(
    val items: List<StateItem>,
    /** True when the HA WebSocket has been down for over a minute (StateManager.isHaOffline). */
    val ha_offline: Boolean = false,
    /** True before the first state snapshot, or when HA has been down for over 5 minutes (StateManager.isDataStale). */
    val data_stale: Boolean = false,
)

@Serializable
data class StateRefreshRequestPayload(
    val entityIds: List<String>? = null,
    val all: Boolean? = null,
)

@Serializable
data class ActionInvokePayload(
    val actionId: Int,
    val gesture: String, // "tap" | "long" | "double"
    val entityId: String? = null, // For tile taps: HA entity to toggle
)

@Serializable
data class ActionResultPayload(
    val actionId: Int,
    val ok: Boolean,
    val message: String? = null,
)

@Serializable
data class NotifyPayload(
    val title: String,
    val body: String,
    val severity: Severity = Severity.INFO,
    val tag: String? = null,
    val ts: Long,
    /**
     * A phone notification's id on the watch, 1 to 65535 (see PhoneNotificationRegistry); null for
     * Home Assistant notifications. A NOTIFY whose nid is already on the watch replaces that entry.
     * Firmware before 2.4.101 skips the key.
     */
    val nid: Int? = null,
) {
    // A phone notification's title and body are the user's private text: not for logs or bundles.
    override fun toString(): String = "NotifyPayload(severity=$severity, tag=$tag, ts=$ts, nid=$nid)"
}

/** NOTIFY_REMOVE (phone to watch): the phone removed the notification [nid]. */
@Serializable
data class NotifyRemovePayload(val nid: Int)

/** NOTIFY_DISMISS (watch to phone): the user dismissed the notification [nid] on the watch. */
@Serializable
data class NotifyDismissPayload(val nid: Int)

@Serializable
data class NotifyAckPayload(
    val tag: String? = null,
    val msgId: Int,
    val displayed: Boolean = true,
)

@Serializable
data class SensorReportPayload(
    @kotlinx.serialization.SerialName("s") val steps: Long,
    @kotlinx.serialization.SerialName("hr") val heartRate: Int,
    @kotlinx.serialization.SerialName("hs") val heartRateState: Int,  // 0=Stopped, 1=NotEnough, 2=NoTouch, 3=Running
    @kotlinx.serialization.SerialName("b") val batteryPercent: Int,
    @kotlinx.serialization.SerialName("bc") val batteryCharging: Boolean,
    @kotlinx.serialization.SerialName("mx") val motionX: Int,
    @kotlinx.serialization.SerialName("my") val motionY: Int,
    @kotlinx.serialization.SerialName("mz") val motionZ: Int,
    @kotlinx.serialization.SerialName("ts") val timestamp: Long,
    // Diagnostic fields piggybacked on periodic sensor reports
    @kotlinx.serialization.SerialName("fh") val freeHeap: Long? = null,
    @kotlinx.serialization.SerialName("mh") val minFreeHeap: Long? = null,
    @kotlinx.serialization.SerialName("sw") val stackHighWater: Int? = null,
    // DASHBOARD_STATE health since the watch booted; null from firmware that does not send it.
    // dsOk/dsBad/fpMis are 16-bit counts that wrap: states decoded and published, states the
    // decoder refused, and states drawn against a layout of another fingerprint (once per state).
    @kotlinx.serialization.SerialName("dsOk") val dashStateOk: Int? = null,
    @kotlinx.serialization.SerialName("dsBad") val dashStateRefused: Int? = null,
    @kotlinx.serialization.SerialName("fpMis") val fingerprintMisses: Int? = null,
    // Seconds since the watch published its last DASHBOARD_STATE; 0 when none since boot.
    @kotlinx.serialization.SerialName("dsAge") val dashStateAgeSec: Long? = null,
    // The battery's voltage in millivolts (firmware 2.4.105); null from firmware that does not send it.
    @kotlinx.serialization.SerialName("bmv") val batteryMillivolts: Int? = null,
    // Power is present (a charger is connected), also when the battery is full and not charging;
    // firmware 2.4.105 sends the key only while it is true. Absent means not on power.
    @kotlinx.serialization.SerialName("pp") val powerPresent: Boolean? = null,
)

@Serializable
class EmptyPayload

@Serializable
data class HapticFeedbackPayload(
    val pattern: String, // "short", "long", "double", "recording_start", "recording_stop"
)

/**
 * Watch face layout protocol payloads - optimized for BLE transmission.
 * These structures are more compact than the import JSON format.
 */

/**
 * Watch face layout configuration payload.
 * Sent via WATCHFACE_LAYOUT message to configure the watch face display.
 */
@Serializable
data class WatchFaceLayoutPayload(
    /** Configuration version. */
    val version: Int,
    /** Color theme. Omitted from the wire when it equals the empty default (REQ009-FR-B4; see WatchFaceWire). */
    val theme: WatchTheme = WatchTheme(background = "", accent = ""),
    /** Header configuration. */
    val header: WatchHeader,
    /** Multi-screen support (max 6 screens). */
    val screens: List<WatchScreen> = emptyList(),
    /** Information tiles (max 8) - DEPRECATED: use screens instead. */
    val tiles: List<WatchTile> = emptyList(),
    /** Status bar items. Omitted from the wire when all flags are false (REQ009-FR-B4; see WatchFaceWire). */
    val statusBar: WatchStatusBar = WatchStatusBar(
        battery = false, steps = false, heartRate = false, notifications = false, bluetooth = false,
    ),
    /** Global active color for entity-state coloring (hex). Default: gold. */
    val defaultActiveColor: String = "#FFD700",
    /** Global inactive color for entity-state coloring (hex). Default: purple. */
    val defaultInactiveColor: String = "#800080",
    /** Logbook app config (phone-side only, not sent to watch). */
    val logbookApp: WatchLogbookAppConfig? = null,
    /** Home control app config (phone-side only, not sent to watch). */
    val homeControlConfig: WatchHomeControlConfig? = null,
    /** Calendar entity IDs (phone-side only, extracted from events config). */
    val calendarEntityIds: List<String> = emptyList(),
)

/**
 * Watch face theme (protocol version).
 */
@Serializable
data class WatchTheme(
    /** Background color/style. */
    val background: String,
    /** Accent color (hex string). */
    val accent: String,
)

/**
 * Watch face header (protocol version).
 */
@Serializable
data class WatchHeader(
    /** Time format: "HH:mm" or "hh:mm". */
    val timeFormat: String,
    /** Time alignment: "left", "center", "right". Blank is omitted from the wire (REQ009-FR-B4). */
    val timeAlign: String = "",
    /** Presence icons. Null is omitted from the wire (REQ009-FR-B4); an empty list is written as before. */
    val presenceIcons: List<WatchPresenceIcon>? = null,
)

/**
 * Presence icon (protocol version).
 */
@Serializable
data class WatchPresenceIcon(
    /** Icon type. */
    val type: String,
    /** Icon color (hex). */
    val color: String,
    /** Optional entity binding. */
    val entity: String? = null,
)

/**
 * Information tile (protocol version).
 * Note: "value" field omitted - values are fetched via STATE_UPDATE messages.
 */
@Serializable
data class WatchTile(
    /** Display label. */
    val label: String,
    /**
     * Entity ID for state binding. Phone-side only — WatchFaceOrchestrator
     * blanks it before every BLE write and the firmware's decoder skips the key
     * entirely. Defaulted so kotlinx OMITS it when blank rather than spending
     * ~10 bytes per glyph on a key plus an empty string; across 38 glyph/sensor
     * occurrences that was ~380 B of a 2532 B payload.
     */
    val entityId: String = "",
    /** Tile color (hex). */
    val color: String,
    /** Whether dynamic entity-state coloring is enabled for this tile. */
    val colorEnabled: Boolean = true,
    /** Icon identifier (e.g., "garage", "light", "door"). */
    val icon: String = "",
    /** Display mode: 0 = text label (default), 1 = icon glyph. */
    val displayMode: Int = 0,
    /** Show confirmation dialog before toggling (for locks, covers). */
    val confirm: Boolean = false,
)

/**
 * Status bar configuration (protocol version).
 */
@Serializable
data class WatchStatusBar(
    val battery: Boolean,
    val steps: Boolean,
    val heartRate: Boolean,
    val notifications: Boolean,
    val bluetooth: Boolean,
)

/**
 * Multi-screen watch face support.
 */

/**
 * Watch face screen definition.
 * Supports both dashboard (read-only) and tile/action (interactive) screens.
 */
@Serializable
data class WatchScreen(
    /** Screen type: 0 = dashboard, 1 = tiles. */
    val screenType: Int,
    /** Tile grid layout: always 4 (2x2 grid). Kept for wire compatibility. */
    val tileLayout: Int = 4,
    /** Tiles (used if screenType == 1). */
    val tiles: List<WatchTile> = emptyList(),
    /** Dashboard configuration (used if screenType == 0). */
    val dashboard: WatchDashboard? = null,
    /** Logbook configuration (used if screenType == 2). */
    val logbook: WatchLogbook? = null,
)

/**
 * Dashboard screen configuration.
 */
@Serializable
data class WatchDashboard(
    /** Status groups (doors, garage, motion, lights, media). */
    val statusGroups: List<WatchStatusGroup> = emptyList(),
    /** Sensor groups (thermostats, freezers, beacons). */
    val sensorGroups: List<WatchSensorGroup> = emptyList(),
    /** Weather widget. */
    val weather: WatchWeather? = null,
    /** Calendar events (max 2). */
    val events: List<WatchEvent> = emptyList(),
    /** Notifications (max 2). */
    val notifications: List<WatchNotification> = emptyList(),
    /** Epoch seconds when dashboard data was last enriched with live HA state. */
    @SerialName("lea") val lastEnrichedAt: Long = 0,
    /** Configurable value label (0-11 chars). Omitted when not configured. */
    @SerialName("cvl") val configurableValueLabel: String? = null,
    /** Configurable value resolved state (0-19 chars). Omitted when not configured. */
    @SerialName("cvv") val configurableValueValue: String? = null,
    /** Inline metric slot 0 label (left of clock). Omitted when slot is not configured. */
    @SerialName("im0l") val inlineMetric0Label: String? = null,
    /** Inline metric slot 0 value (left of clock, ≤3 chars). Omitted when slot is not configured. */
    @SerialName("im0v") val inlineMetric0Value: String? = null,
    /** Inline metric slot 1 label (right of clock). Omitted when slot is not configured. */
    @SerialName("im1l") val inlineMetric1Label: String? = null,
    /** Inline metric slot 1 value (right of clock, ≤3 chars). Omitted when slot is not configured. */
    @SerialName("im1v") val inlineMetric1Value: String? = null,
    // Phone-side-only fields — excluded from CBOR serialization.
    /** HA entity ID for the configurable value row. Never sent to watch. */
    @kotlinx.serialization.Transient val configurableValueEntityId: String = "",
    /** HA entity IDs for inline metric slots [0] and [1]. Never sent to watch. */
    @kotlinx.serialization.Transient val inlineMetricEntityIds: List<String> = emptyList(),
)

/**
 * Status group (e.g., doors, garage, motion).
 */
@Serializable
data class WatchStatusGroup(
    /** Group type: "doors", "garage", "motion", "lights", "media". */
    val type: String = "",
    /** Glyphs in this group (max 12). */
    val glyphs: List<WatchGlyph> = emptyList(),
)

/**
 * Status glyph (symbol + entity binding).
 */
@Serializable
data class WatchGlyph(
    /** Display symbol (single character). */
    val symbol: String,
    /** Entity ID for state binding. Phone-side only; null is omitted from the wire (REQ009-FR-B4). */
    val entityId: String? = null,
    /** Current active state (computed at send time). */
    val active: Boolean = false,
)

/**
 * Sensor group (e.g., thermostats, freezers, beacons).
 */
@Serializable
data class WatchSensorGroup(
    /** Group type: "thermostats", "freezers", "beacons". */
    val type: String = "",
    /** Sensors in this group (max 7). */
    val sensors: List<WatchSensor> = emptyList(),
)

/**
 * Sensor definition.
 */
@Serializable
data class WatchSensor(
    /** Entity ID. Phone-side only; blanked before send — see WatchGlyph.entityId. */
    val entityId: String = "",
    /**
     * Display type: "temperature", "presence". Phone-side only — the firmware
     * renders sensor values numerically and never reads this key.
     */
    val displayType: String = "",
    /** Current value (computed at send time). */
    val value: Int = 0,
)

/**
 * Weather widget configuration.
 */
@Serializable
data class WatchWeather(
    /** Weather entity ID. Blank is omitted from the wire (REQ009-FR-B4). */
    val entityId: String = "",
    /** Current condition. */
    val condition: String = "",
    /** Current temperature. */
    val temp: Int = 0,
    /** High temperature. */
    val hi: Int = 0,
    /** Low temperature. */
    val lo: Int = 0,
)

/**
 * Calendar event.
 */
@Serializable
data class WatchEvent(
    /** Event time string. */
    val time: String,
    /** Event title. */
    val title: String,
    /** UTC epoch seconds of event start. Zero when not available (old Android builds). */
    @SerialName("ese") val startEpoch: Long = 0,
)

/**
 * Dashboard notification.
 */
@Serializable
data class WatchNotification(
    /** Notification source. */
    val source: String,
    /** Notification text. */
    val text: String,
)

/**
 * Time synchronization payload.
 * Sent from phone to watch to synchronize time.
 */
@Serializable
data class TimeSyncPayload(
    /** Unix timestamp in seconds. */
    val timestamp: Long,
    /** Timezone offset in seconds from UTC. */
    val timezoneOffset: Int,
    /** Whether DST is currently in effect. */
    val isDst: Boolean = false,
)

/**
 * ACK sent by watch after applying TIME_SYNC (watch → phone).
 * status: 0 = OK, 1 = REJECTED (e.g. timestamp out of sane range).
 * appliedDeltaSeconds: how many seconds the watch clock was adjusted (signed).
 * watchUtcEpoch: the watch's UTC epoch after adjustment.
 *
 * The watch writes one-letter keys (`BridgeController::HandleTimeSync`:
 * `{"s": status, "d": appliedDelta, "t": watchUtcEpoch}`). With the long property
 * names every real ACK failed to decode, so `sendTimeSync` sent each TIME_SYNC twice.
 * That stayed hidden until firmware 2.4.64, the first to show the phone its TIME_SYNC
 * feature bit, which is what makes the phone wait for the ACK at all.
 */
@Serializable
data class TimeSyncAckPayload(
    @SerialName("s") val status: Int,
    @SerialName("d") val appliedDeltaSeconds: Int,
    @SerialName("t") val watchUtcEpoch: Long,
)

/**
 * Sent by Android to the watch after receiving HELLO (FR-BLE-6).
 * Carries Android's semantic protocol version so the watch can verify compatibility.
 * supportedFeatures mirrors the bitmask from [Protocol.Features].
 *
 * REQ-057: crashContextAck is true when the phone successfully decoded a crashContext
 * from the preceding watch HELLO.  The watch clears hasCrashContext_ only on receipt
 * of this flag, preventing the crash report from being silently dropped on a lossy
 * BLE notification.  Older phone firmware omits the field (null); the watch treats
 * absence as "not acknowledged" and re-broadcasts on the next HELLO.
 */
@Serializable
data class HelloAckPayload(
    val protocolVersion: String,
    val supportedFeatures: Int,
    val crashContextAck: Boolean? = null,
    /**
     * Major version the watch actually reads; see [Protocol.PROTOCOL_MAJOR].
     *
     * @EncodeDefault is required, not decorative. This serializer does not encode
     * defaults, so a property holding its declared default is skipped — pmaj was
     * added in 2.1.65 and stayed off the wire, HELLO_ACK unchanged at 61 bytes,
     * with the watch still answering VERSION_MISMATCH on every handshake.
     */
    @EncodeDefault
    val pmaj: Int = Protocol.PROTOCOL_MAJOR,
)

/**
 * Sent by the watch when it detects a major version mismatch (watch → phone).
 * The phone should surface this to the user and consider refusing the connection.
 */
@Serializable
data class VersionMismatchPayload(
    val requiredVersion: String,
    val currentVersion: String,
)

/**
 * Logbook screen configuration (stored in WatchScreen, phone-side only).
 * Entity IDs are NOT sent to the watch — the phone uses them to fetch logbook data.
 */
@Serializable
data class WatchLogbook(
    /** Entity IDs to fetch logbook events for. */
    val entityIds: List<String> = emptyList(),
    /** Maximum entries to send to the watch. */
    val maxEntries: Int = 15,
    /** How many hours back to query. */
    val hoursBack: Int = 12,
)

/**
 * Single logbook entry (phone → watch), as BridgeController::HandleLogbookUpdate reads it; the
 * HaLogbook app shows "time state subject". The watch stores char[8], char[8] and char[32] and
 * drops a string that does not fit, so LogbookOrchestrator cuts them to 7, 7 and 31 bytes. Until
 * 2.1.97 the text went out as "x", which the watch does not read, and with no "s" every entry was
 * dropped.
 */
@Serializable
data class LogbookEntry(
    /** Time "HH:MM". Required. */
    @kotlinx.serialization.SerialName("t") val time: String,
    /** Short state: "ON", "OFF", "OPN", "CLSD", "LCKD", "ULKD", "HOME", "AWAY", ... Required. */
    @kotlinx.serialization.SerialName("s") val state: String,
    /** Whose state: the entity's friendly name. */
    @kotlinx.serialization.SerialName("n") val subject: String,
)

/**
 * Logbook update payload (phone → watch). The watch reads "entries" only (at most 4, most recent
 * first) and skips "screenIndex".
 */
@Serializable
data class LogbookUpdatePayload(
    /** Index of the logbook screen to update; 0xFF for the HaLogbook app. */
    val screenIndex: Int,
    /** Logbook entries (most recent first). */
    val entries: List<LogbookEntry>,
)

/**
 * Watch face ACK payload (watch -> phone).
 * Sent by the watch after processing a WATCHFACE_LAYOUT message.
 */
@Serializable
data class WatchFaceAckPayload(
    /** Whether the layout was applied successfully. */
    val ok: Boolean,
    /** The layout version that was applied. */
    val appliedVersion: Int,
    /** Failure reason (only present when ok=false). */
    val reason: String? = null,
)

/**
 * PONG response payload (watch -> phone).
 * Carries optional diagnostic fields piggybacked on PING/PONG exchange (FR-BLE-3).
 * All fields are nullable for backward compatibility with firmware that sends an empty PONG.
 */
@Serializable
data class PongPayload(
    /** BLE RSSI in dBm at the time the PING was received. Null if unavailable. */
    @kotlinx.serialization.SerialName("rssi") val rssi: Int? = null,
    /** Number of items currently waiting in the BLE processing queue (0–8). */
    @kotlinx.serialization.SerialName("qd") val queueDepth: Int? = null,
    /** Cumulative BLE error count since last connection. */
    @kotlinx.serialization.SerialName("ec") val errorCount: Int? = null,
    /**
     * msgId of the PING this PONG answers.
     *
     * The frame header cannot carry it: the watch assigns every outgoing frame
     * its own sequential id, so the header id is unrelated to the request.
     * Null from firmware older than 2.4.35, which echoed nothing.
     */
    @kotlinx.serialization.SerialName("rid") val requestMsgId: Int? = null,
    /** Whether the link was encrypted when the watch answered (firmware 2.4.101+); null from older firmware. */
    @kotlinx.serialization.SerialName("enc") val enc: Boolean? = null,
)

/**
 * Calendar navigation payload (watch->phone): the day the user swiped to, 0 today, 1 tomorrow,
 * -1 yesterday (BridgeController::SendCalendarNav writes {"day": int}).
 */
@Serializable
data class CalendarNavPayload(@kotlinx.serialization.SerialName("day") val dayOffset: Int = 0)

/**
 * Single calendar entry for watch display. The watch stores char[6] and char[48] and drops a
 * string that does not fit (the entry then shows blank), so the sender cuts both to 5 and 47 bytes
 * of UTF-8 (CalendarOrchestrator).
 */
@Serializable
data class CalendarEntryPayload(
    @kotlinx.serialization.SerialName("t") val time: String,    // "HH:MM", or "All" for an all-day event
    @kotlinx.serialization.SerialName("n") val title: String,   // event title
)

/**
 * Calendar update payload (phone->watch), as BridgeController::HandleCalendarUpdate reads it:
 * {"day": int, "entries": [{"t", "n"}]}, at most 3 entries. The watch shows [day] as the header
 * ("Today", "Tomorrow", ...), so a reply to CALENDAR_NAV echoes the requested day.
 */
@Serializable
data class CalendarUpdatePayload(
    /** The day the entries are for, 0 today. Left off the wire at 0, which the watch reads as 0. */
    @kotlinx.serialization.SerialName("day") val day: Int = 0,
    @kotlinx.serialization.SerialName("entries") val entries: List<CalendarEntryPayload>,
)

/**
 * Single home control device state for watch display. The watch stores char[20], char[64] and
 * char[16] and drops a string that does not fit (HomeControlOrchestrator cuts them to size).
 */
@Serializable
data class HomeControlDevicePayload(
    @kotlinx.serialization.SerialName("l") val label: String,    // display name, at most 19 bytes
    @kotlinx.serialization.SerialName("e") val entityId: String, // HA entity ID, at most 63 bytes
    @kotlinx.serialization.SerialName("s") val state: String,    // state string, at most 15 bytes
    @kotlinx.serialization.SerialName("a") val active: Boolean,  // is entity active/on
)

/**
 * Home control update payload (phone->watch), as BridgeController::HandleHomeControlUpdate reads
 * it: {"devices": [{"l", "e", "s", "a"}]}, at most 2 devices.
 */
@Serializable
data class HomeControlUpdatePayload(
    @kotlinx.serialization.SerialName("devices") val devices: List<HomeControlDevicePayload>,
)

/**
 * DASHBOARD_STATE (0x20, phone → watch, firmware 2.4.96): what the dashboard shows that changes.
 * The layout record keeps the letters, the group order and the fallbacks; this message carries the
 * state, and goes out on every change of a dashboard entity (DashboardStateOrchestrator). The watch
 * keeps the last one in RAM (DashboardState.h) and decodes it whole or not at all. One frame: the
 * phone keeps it at most DashboardStateBuilder.MAX_BYTES.
 */
@Serializable
data class DashboardStatePayload(
    /**
     * The status groups this state is for, as the watch stored them (DashboardStateBuilder.fingerprint).
     * The watch draws the status rows only when its layout gives the same value.
     */
    @SerialName("f") val fingerprint: Long,
    /** Epoch seconds the state was read at. The watch greys the home status 20 min later. */
    @SerialName("t") val computedAt: Long,
    @SerialName("g") val groups: List<DashStateGroup> = emptyList(),
    @SerialName("s") val sensors: List<DashStateSensors> = emptyList(),
    @SerialName("w") val weather: DashStateWeather? = null,
    @SerialName("e") val events: List<DashStateEvent> = emptyList(),
)

/** A status group: its type (DashboardStateBuilder.groupType) and a bit per glyph that is on/open. */
@Serializable
data class DashStateGroup(
    @SerialName("t") val type: Int,
    @SerialName("b") val bits: Int,
)

/**
 * A sensor group: its type (DashboardStateBuilder.sensorType) and one value per sensor, null when
 * there is no reading. Beacons read 1 at home and 0 away; [alerts] has a bit per freezer in alarm.
 */
@Serializable
data class DashStateSensors(
    @SerialName("t") val type: Int,
    @SerialName("v") val values: List<Int?>,
    @SerialName("a") val alerts: Int = 0,
)

/** The weather: a WeatherCode (DashboardState.h), the temperature, and today's high and low when known. */
@Serializable
data class DashStateWeather(
    @SerialName("c") val code: Int,
    @SerialName("t") val temp: Int,
    @SerialName("h") val hi: Int? = null,
    @SerialName("l") val lo: Int? = null,
)

/** An upcoming event: its start (epoch seconds) and title, at most 24 bytes of UTF-8. */
@Serializable
data class DashStateEvent(
    @SerialName("s") val start: Long,
    @SerialName("n") val title: String,
)

/**
 * Logbook app-level config (phone-side only, not sent to watch).
 * Used when the watch requests logbook data via the standalone Logbook app (screenIndex=0xFF).
 * With no entities, the phone logs the face's motion sensors (LogbookOrchestrator.appLogbook).
 */
@Serializable
data class WatchLogbookAppConfig(
    val entityIds: List<String> = emptyList(),
    val maxEntries: Int = 10,
    val hoursBack: Int = 12,
)

/**
 * REQ-072 — FIND_WATCH payload (phone → watch).
 * Optional duration override; watch defaults to 30 s when field is absent.
 * Firmware caps at 60 s regardless of what the phone sends.
 */
@Serializable
data class FindWatchPayload(
    /** Duration in seconds the watch should alert (1–60). Default 30. */
    val duration: Int = 30,
)

/** A single home control device reference for the watch face config. */
@Serializable
data class WatchHomeControlDevice(
    val entityId: String,
    val name: String? = null,
    val domain: String? = null,
)

/**
 * Home control app config (phone-side only, not sent to watch).
 * Defines which HA entities are shown in the watch Home Control app.
 */
@Serializable
data class WatchHomeControlConfig(
    val devices: List<WatchHomeControlDevice> = emptyList(),
)

package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.domain.SensorReportPayload
import com.w6tech.infinassistant.core.domain.WakeDiag
import com.w6tech.infinassistant.core.ha.HaConnectionState

data class BridgeStatus(
    val connectedModeEnabled: Boolean,
    val haState: HaConnectionState,
    val bleState: BleConnectionState,
    val lastError: String? = null,
    val watchResponsive: Boolean = true,
    val watchBatteryPercent: Int? = null,
    val watchBatteryCharging: Boolean? = null,
    val watchFirmwareVersion: String? = null,
    val lastSyncTime: Long? = null,
    val rssi: Int? = null,
    val pingLatencyMs: Long? = null,
    val lastConfigAckOk: Boolean? = null,
    // REQ-066: diagnostic fields surfaced by the Debug screen (populated from watch HELLO).
    val lastResetReason: String? = null,
    val lastFreeHeap: Long? = null,
    val lastMinFreeHeap: Long? = null,
    val lastCrashBreadcrumb: Int? = null,
    // ARM fault registers from the watch's last hard fault, pre-formatted for
    // display and for the debug bundle. Null on firmware older than 2.4.41,
    // which did not install the fault handlers (see CrashContextPayload).
    val lastCrashFault: String? = null,
    // The last crash the watch reported (CRASH_DUMP_REQUEST, firmware 2.4.65+), as one line:
    // the black box's record from 2.4.90 (kind, where, when, registers), or before it the
    // bridge's context of a watchdog or lockup reset (ProtocolHandler.crashSummary). Kept
    // across connections; the watch delivers each report once.
    val lastCrashContext: String? = null,
    // The bridge's last activity before the previous restart, when that restart was not a
    // crash (an update, a reboot from settings): breadcrumb, last message, heap, uptime.
    val lastRestartActivity: String? = null,
    /**
     * Last failure from infinassistant.get_watchface, verbatim from Home
     * Assistant. Recorded because the user hit this repeatedly and worked
     * around it by applying a cached layout instead — and the error only
     * existed on a transient import screen, so it never reached a debug
     * bundle and could not be diagnosed without asking them to retype it.
     */
    val lastWatchFaceFetchError: String? = null,
    val lastHelloDiagRev: Int? = null,
    // The screen-wake telemetry of the last HELLO (firmware 2.4.104+, the optional "wk" key). Replaced
    // on every HELLO, so null means "not reported", never zero.
    val lastWakeDiag: WakeDiag? = null,
    // REQ-033 / REQ-066: acknowledged-write diagnostic mode (toggled from the Debug screen).
    val diagnosticWriteTypeEnabled: Boolean = false,
    // REQ-067: cumulative safe-face trip count from watch HELLO. Used to detect increments
    // and post a preflight notification when the layout causes safe-mode trips.
    val lastSafeFaceTrips: Int? = null,
    // REQ009-OBS-1: layout pushes the watch refused, from HELLO diag rev >= 3. Null on
    // older firmware; it is replaced (not retained) on every HELLO so a stale count from
    // a different firmware is never shown.
    val lastLayoutRefused: Long? = null,
    // Layout pushes the watch ACKed and then failed to write, from HELLO diag rev >= 4
    // (firmware 2.4.64). Null on older firmware; replaced on every HELLO.
    val lastLayoutWriteFailed: Long? = null,
    // When the running connected-mode session started (wall clock); null while none runs.
    val sessionStartedAtMs: Long? = null,
    // The latest SENSOR_REPORT, received at lastSyncTime: the watch's heap and its DASHBOARD_STATE
    // counters (null fields from firmware that does not send them).
    val lastSensorReport: SensorReportPayload? = null,
    // Whether the bridge's foreground service runs with the microphone type, which Android needs
    // for the phone to record while the app is in the background. False after a boot start, until
    // the app is opened (BridgeForegroundService).
    val serviceMicrophone: Boolean = false,
    // Whether the watch's HELLO advertised voice assist (Protocol.Features.VOICE_ASSIST); null until
    // a HELLO has arrived this run.
    val watchSupportsVoice: Boolean? = null,
    // The watch's HELLO lacks Protocol.Features.CONFIG_KEY_V2 (firmware before 2.4.103): its CONFIG
    // decoder does not read the renamed key, so the app sends it no CONFIG_PUSH and asks for a
    // firmware update. Set from every HELLO, so it clears once the updated watch reconnects.
    val watchUpdateNeeded: Boolean = false,
)

package com.w6tech.infinassistant.ui.debug

import android.content.Context
import android.os.Build
import com.w6tech.infinassistant.core.common.Redactor
import com.w6tech.infinassistant.core.data.BridgeStatus
import com.w6tech.infinassistant.core.data.DiagnosticLogger
import com.w6tech.infinassistant.core.data.HealthSummary
import com.w6tech.infinassistant.core.data.ProtocolLogEntry
import com.w6tech.infinassistant.core.domain.FirmwareLimits
import com.w6tech.infinassistant.core.domain.WatchFaceLayoutPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Assembles a human-readable diagnostic bundle for SMB upload.
 * All sections are delimited so a developer can quickly grep for what matters.
 * Missing / null fields print as "<null>" — never throws.
 */
object DebugBundleBuilder {

    fun build(
        context: Context,
        status: BridgeStatus,
        protocolLog: List<ProtocolLogEntry>,
        lastWatchFaceLayout: WatchFaceLayoutPayload?,
        debugSharePath: String,
        diagnosticLogger: DiagnosticLogger,
    ): ByteArray {
        val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date())

        val appVersion = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.versionCode})"
        } catch (_: Exception) { "<null>" }

        // The diagnostic log is read first: the summary at the top is computed from it.
        val fwVersion = status.watchFirmwareVersion
        val exportFile = try {
            val appVer = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }
            diagnosticLogger.exportForSharing(appVer, fwVersion)
        } catch (_: Exception) { null }
        val logLines: List<String>? = try {
            exportFile?.takeIf { it.exists() && it.length() > 0L }?.readLines(Charsets.UTF_8)
        } catch (_: Exception) { null }

        val text = buildString {

            // ── HEALTH SUMMARY ────────────────────────────────────────────────
            // The answers first: reconnects and their causes, phone sleep, the dashboard's
            // sends and the watch's own counters, computed from the log below.
            appendLine("=== HEALTH SUMMARY ===")
            try {
                HealthSummary.compute(logLines.orEmpty().mapNotNull { DiagnosticLogger.parseLine(it) })
                    .render()
                    .forEach { appendLine(it) }
            } catch (_: Exception) {
                appendLine("<error computing the summary>")
            }
            appendLine()

            // ── METADATA ──────────────────────────────────────────────────────
            appendLine("=== METADATA ===")
            appendLine("timestamp        : $ts")
            appendLine("app_version      : $appVersion")
            appendLine("watch_firmware   : ${status.watchFirmwareVersion ?: "<null>"}")
            appendLine("android_sdk      : ${Build.VERSION.SDK_INT}")
            appendLine("device           : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("debug_share_path : $debugSharePath")
            appendLine()

            // ── WATCH STATUS SNAPSHOT ─────────────────────────────────────────
            appendLine("=== WATCH STATUS SNAPSHOT ===")
            appendLine("connectedModeEnabled       : ${status.connectedModeEnabled}")
            appendLine("haState                    : ${status.haState}")
            appendLine("bleState                   : ${status.bleState}")
            appendLine("watchFirmwareVersion       : ${status.watchFirmwareVersion ?: "<null>"}")
            appendLine("lastResetReason            : ${status.lastResetReason ?: "<null>"}")
            appendLine("lastFreeHeap               : ${status.lastFreeHeap?.let { "$it B" } ?: "<null>"}")
            appendLine("lastMinFreeHeap            : ${status.lastMinFreeHeap?.let { "$it B" } ?: "<null>"}")
            appendLine("lastCrashBreadcrumb        : ${status.lastCrashBreadcrumb?.toString() ?: "<null>"}")
            appendLine("lastCrashFault             : ${status.lastCrashFault ?: "<null>"}")
            appendLine("lastCrashContext           : ${status.lastCrashContext ?: "<null>"}")
            appendLine("lastRestartActivity        : ${status.lastRestartActivity ?: "<null>"}")
            appendLine("lastWatchFaceFetchError    : ${status.lastWatchFaceFetchError ?: "<null>"}")
            // Doze suspends the bridge silently — a watch stale for hours with
            // no reconnect attempts looks identical to a BLE fault in a bundle
            // unless this is recorded alongside it.
            appendLine(
                "batteryOptimization        : " +
                    when (
                        (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                            ?.isIgnoringBatteryOptimizations(context.packageName)
                    ) {
                        true -> "exempt"
                        false -> "ACTIVE (can suspend the bridge)"
                        null -> "<unknown>"
                    }
            )
            appendLine("lastHelloDiagRev           : ${status.lastHelloDiagRev?.toString() ?: "<null>"}")
            // The optional "wk" key of the HELLO (firmware 2.4.104+): the numbers as sent, then in words.
            appendLine(
                "lastWakeDiag (wk)          : " +
                    (status.lastWakeDiag?.let { "${it.raw()} = ${it.decoded()}" } ?: "not reported"),
            )
            appendLine("lastSafeFaceTrips          : ${status.lastSafeFaceTrips?.toString() ?: "<null>"}")
            appendLine("lastLayoutRefused          : ${status.lastLayoutRefused?.toString() ?: "<null>"}")
            appendLine("lastLayoutWriteFailed      : ${status.lastLayoutWriteFailed?.toString() ?: "<null>"}")
            appendLine("rssi                       : ${status.rssi?.let { "$it dBm" } ?: "<null>"}")
            appendLine("pingLatencyMs              : ${status.pingLatencyMs?.let { "${it}ms" } ?: "<null>"}")
            appendLine("lastConfigAckOk            : ${status.lastConfigAckOk?.toString() ?: "<null>"}")
            appendLine("watchUpdateNeeded          : ${status.watchUpdateNeeded}")
            appendLine("diagnosticWriteTypeEnabled : ${status.diagnosticWriteTypeEnabled}")
            appendLine("sessionStartedAtMs         : ${status.sessionStartedAtMs?.toString() ?: "<null>"}")
            val report = status.lastSensorReport
            val reportText = report?.let { r ->
                "heap=${r.freeHeap} minHeap=${r.minFreeHeap} stackHW=${r.stackHighWater} dsOk=${r.dashStateOk}" +
                    " dsBad=${r.dashStateRefused} fpMis=${r.fingerprintMisses} dsAge=${r.dashStateAgeSec}"
            }
            appendLine("lastSensorReport           : ${reportText ?: "<null>"}")
            appendLine("lastError                  : ${status.lastError ?: "<null>"}")
            appendLine()

            // ── FIRMWARE LIMITS ───────────────────────────────────────────────
            appendLine("=== FIRMWARE LIMITS ===")
            appendLine("BRIDGE_PROTOCOL_REV      : ${FirmwareLimits.BRIDGE_PROTOCOL_REV}")
            appendLine("DASHBOARD_SCHEMA_REV     : ${FirmwareLimits.DASHBOARD_SCHEMA_REV}")
            appendLine("HELLO_DIAG_REV           : ${FirmwareLimits.HELLO_DIAG_REV}")
            appendLine("MAX_REASSEMBLY_SIZE      : ${FirmwareLimits.MAX_REASSEMBLY_SIZE}")
            appendLine("REASSEMBLY_TIMEOUT_MS    : ${FirmwareLimits.REASSEMBLY_TIMEOUT_MS}")
            appendLine("MAX_STATUS_GROUPS        : ${FirmwareLimits.MAX_STATUS_GROUPS}")
            appendLine("MAX_SENSOR_GROUPS        : ${FirmwareLimits.MAX_SENSOR_GROUPS}")
            appendLine("MAX_GLYPHS_PER_GROUP     : ${FirmwareLimits.MAX_GLYPHS_PER_GROUP}")
            appendLine("MAX_SENSORS_PER_GROUP    : ${FirmwareLimits.MAX_SENSORS_PER_GROUP}")
            appendLine("ACK_TIMEOUT_MS           : ${FirmwareLimits.ACK_TIMEOUT_MS}")
            appendLine("LVGL_POOL_BYTES          : ${FirmwareLimits.LVGL_POOL_BYTES}")
            appendLine("LVGL_BYTES_PER_OBJECT    : ${FirmwareLimits.LVGL_BYTES_PER_OBJECT}")
            appendLine("LVGL_BYTES_PER_STYLE     : ${FirmwareLimits.LVGL_BYTES_PER_STYLE}")
            appendLine()

            // ── LAST WATCHFACE LAYOUT ─────────────────────────────────────────
            appendLine("=== LAST WATCHFACE LAYOUT ===")
            if (lastWatchFaceLayout == null) {
                appendLine("<null>")
            } else {
                appendLine("screens_count : ${lastWatchFaceLayout.screens.size}")
                lastWatchFaceLayout.screens.forEachIndexed { i, screen ->
                    appendLine("  screen[$i] type=${screen.screenType} tiles=${screen.tiles.size}")
                }
            }
            appendLine()

            // ── RECENT PROTOCOL LOG ───────────────────────────────────────────
            appendLine("=== RECENT PROTOCOL LOG ===")
            appendLine("entries: ${protocolLog.size}")
            protocolLog.forEach { e ->
                appendLine("${e.ts} ${e.direction} ${e.type} msgId=${e.msgId ?: "-"} ${e.summary}")
            }
            appendLine()

            // ── FULL DIAGNOSTIC LOG (JSONL) ───────────────────────────────────
            appendLine("=== FULL DIAGNOSTIC LOG (JSONL) ===")
            when {
                exportFile == null || !exportFile.exists() || exportFile.length() == 0L -> appendLine("<no diagnostic log>")
                logLines == null -> appendLine("<error reading diagnostic log>")
                else -> logLines.forEach { appendLine(it) }
            }
        }

        // Once more over the whole bundle: the log lines were redacted when written, but the status,
        // the last error and the protocol log come from memory, and a section added later is covered too.
        return Redactor.redact(text).toByteArray(Charsets.UTF_8)
    }
}

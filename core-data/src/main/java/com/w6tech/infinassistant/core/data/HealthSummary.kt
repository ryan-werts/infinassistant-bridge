package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.data.DiagnosticLogger.LogEntry
import com.w6tech.infinassistant.core.domain.WakeDiag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The headline numbers of a diagnostic log: the top of a debug bundle, and the Health screen. A pure
 * function of the log's entries ([compute]), so the two agree and the counting is tested.
 *
 * It reads lines the bridge writes: BridgeEngine's HA lines, [SessionDiagnostics]' session lines,
 * DashboardStateOrchestrator's sends, ProtocolHandler's SENSOR_REPORT line, and the HA socket's
 * warnings and errors that [DiagnosticTeeLogger] passes on. A count that [DiagnosticTeeLogger]
 * folded into one "repeated N more times" line counts N.
 */
data class HealthSummary(
    val entries: Int,
    val fromMs: Long?,
    val toMs: Long?,
    val sessions: Int,
    val haLost: Int,
    /** "HA lost" by cause, most frequent first: the socket's close or failure just before it when there was one. */
    val haLostByCause: List<Pair<String, Int>>,
    val haReconnected: Int,
    val haLongestOutageSec: Long?,
    /** Failed HA connects: the socket's ("connect failed"), and those refused before one (no URL, no token, breaker open). */
    val haConnectFailures: Int,
    val bleLinkLost: Int,
    val bleConnectsDirect: Int,
    val bleConnectsBackground: Int,
    val bleFailedAttempts: Int,
    val bleLongestWaitSec: Long?,
    /** Deep-sleep gaps of [SessionDiagnostics.SLEEP_LINE_MS] or more. */
    val sleepGaps: Int,
    val longestSleepSec: Long?,
    val totalSleepSec: Long,
    val dashboardSends: Int,
    /** DASHBOARD_STATE sends by reason (connect, change, heartbeat, ...), most frequent first. */
    val dashboardSendsByReason: List<Pair<String, Int>>,
    val lastDashboardSendMs: Long?,
    val lastSensorReportMs: Long?,
    /** The last SENSOR_REPORT's watch counters and heap (batt, heap, minHeap, stackHW, dsOk, dsBad, fpMis, dsAge). */
    val watchCounters: Map<String, Long>,
    /** The last [LAST_ERRORS] ERROR entries, oldest first. */
    val lastErrors: List<LogEntry>,
    /** HA connects by the URL they chose ([HA_URL] lines, app 2.1.113 on). */
    val haUrlLocal: Int = 0,
    val haUrlRemote: Int = 0,
    /** The last [HA_URL] line without its prefix: the URL, and why it was chosen. */
    val lastHaUrl: String? = null,
    /** NOTIFYs queued from a Home Assistant event, and from the phone's notifications ([ForwardDiagnostics]' lines). */
    val notifyFromHa: Int = 0,
    val notifyFromPhone: Int = 0,
    /** Phone forwards by the app's package name, most frequent first (all of them; the render shows [NOTIFY_TOP_PACKAGES]). */
    val phoneForwardsByPackage: List<Pair<String, Int>> = emptyList(),
    /** Phone notifications not forwarded, by fixed reason word, most frequent first. */
    val phoneSkipsByReason: List<Pair<String, Int>> = emptyList(),
    /** The last watch HELLO's screen-wake telemetry (firmware 2.4.104+), null when it did not carry it. */
    val wakeDiag: WakeDiag? = null,
    /** That HELLO's uptime in seconds, for the screen-on share. */
    val helloUptimeSec: Long? = null,
    /** The battery voltage across the log's SENSOR_REPORTs (firmware 2.4.105+), null when none carried it. */
    val batteryTrend: BatteryTrend? = null,
) {
    /**
     * The first and last battery voltage with their times, and the slope in mV per hour over the
     * stretches between two readings that were both off power, neither charging (`bc`) nor with power
     * present (`pp`) ([dischargeHours] in all). A pair more than [TREND_MAX_GAP_MS] apart, or whose
     * voltage rose more than [TREND_MAX_RISE_MV], is left out too: an unseen charge in a gap
     * would read as a rise. The slope is null when the stretches add up to less than [TREND_MIN_MS].
     */
    data class BatteryTrend(
        val readings: Int,
        val firstMv: Int,
        val firstMs: Long,
        val lastMv: Int,
        val lastMs: Long,
        val dischargeMvPerHour: Double?,
        val dischargeHours: Double,
    )

    companion object {
        // Written elsewhere: BridgeEngine (tag "ha"), DashboardStateOrchestrator (tag "dashboard"),
        // ProtocolHandler (tag "watch->phone"), HaWebSocketClient (through the tee logger).
        const val HA_TAG = "ha"
        const val HA_LOST = "HA lost"
        const val HA_RECONNECTED = "HA reconnected"
        const val DASHBOARD_TAG = "dashboard"
        const val DASHBOARD_SENT = "DASHBOARD_STATE sent"
        const val WATCH_TAG = "watch->phone"
        const val SENSOR_REPORT = "SENSOR_REPORT"
        const val HA_SOCKET_TAG = "HaWebSocket"
        const val CONNECTION_TAG = "ConnectionManager"
        /** ConnectionManager's line for each HA connect: "HA URL local|remote <url> (<source>[; local test failed: ...])". */
        const val HA_URL = "HA URL"
        /** [ForwardDiagnostics]' lines: where each NOTIFY came from, and the phone notifications skipped. */
        const val NOTIFY_TAG = "notify"
        const val NOTIFY_HA = "NOTIFY from HA"
        const val NOTIFY_PHONE = "NOTIFY from phone"   // extra "pkg=<package name>"
        const val NOTIFY_SKIPS = "NOTIFY skips"        // extra "<reason>=<n> <reason>=<n>"
        /** The watch's HELLO line (ProtocolHandler, tag [WATCH_TAG]) carries " wk=<wakes>,<notifyWakes>,<stored>,<screenOnSec>,<settings>" and " up=<ticks>". */
        const val WK_KEY = "wk="
        /** The watch's uptime ticks per second (configTICK_RATE_HZ). */
        const val UPTIME_TICKS_PER_SEC = 1024L
        /** How many apps the render lists. */
        const val NOTIFY_TOP_PACKAGES = 8

        /** App 2.1.109's line for a background connect, before [SessionDiagnostics.BLE_CONNECTED]. */
        const val LEGACY_BLE_BACKGROUND = "BLE connected in the background"

        /** A socket close or failure this close before "HA lost" is taken as its cause. */
        const val CAUSE_WINDOW_MS = 120_000L
        const val LAST_ERRORS = 5
        /** The SENSOR_REPORT fields shown, in this order. */
        val WATCH_FIELDS = listOf("batt", "bmv", "heap", "minHeap", "stackHW", "dsOk", "dsBad", "fpMis", "dsAge")
        /** Off-charger time a battery slope needs: shorter stretches say nothing but noise. */
        const val TREND_MIN_MS = 600_000L
        /** The longest gap between two readings that count as one stretch: a longer one may hide a charge. */
        const val TREND_MAX_GAP_MS = 2 * 3_600_000L
        /** A rise this big between two off-power readings is a charge nobody reported, not a drain. */
        const val TREND_MAX_RISE_MV = 50

        private val NUMBER_PAIR = Regex("""(\w+)=(-?\d+)""")
        private val BATTERY_MV = Regex("""(?:^| )bmv=(\d+)(?: |$)""")
        private val BATTERY_CHARGING = Regex("""(?:^| )bc=([01])(?: |$)""")
        private val BATTERY_POWER = Regex("""(?:^| )pp=1(?: |$)""")
        private val SECONDS_AFTER = Regex("""after (\d+) s""")
        private val SLEPT_SECONDS = Regex("""^${SessionDiagnostics.SLEPT} (\d+) s""")
        private val FAILED_ATTEMPTS = Regex("""(\d+) failed""")
        private val SOCKET_CAUSE = Regex("""(?i)clos|fail|invalid|error""")
        private val CLASS_NAME = Regex("""^[A-Za-z_$][\w$.]*$""")
        private val HELLO_WK = Regex("""(?:^| )wk=(\d+),(\d+),(\d+),(\d+),(\d+)(?: |$)""")
        private val HELLO_UP = Regex("""(?:^| )up=(\d+)(?: |$)""")
        private val PACKAGE_PAIR = Regex("""^pkg=(\S+)""")
        private val SKIP_PAIR = Regex("""([A-Za-z0-9._-]+)=(\d+)""")

        fun compute(log: List<LogEntry>): HealthSummary {
            val entries = log.sortedBy { it.ts }
            var sessions = 0
            var haLost = 0
            val haCauses = LinkedHashMap<String, Int>()
            var haReconnected = 0
            var haLongest: Long? = null
            var haConnectFailures = 0
            var lastSocketCause: Pair<Long, String>? = null
            var bleLost = 0
            var bleDirect = 0
            var bleBackground = 0
            var bleFailed = 0
            var openOutageFailed = 0
            var bleLongestWait: Long? = null
            var sleepGaps = 0
            var longestSleep: Long? = null
            var totalSleep = 0L
            var dashboardSends = 0
            val dashboardReasons = LinkedHashMap<String, Int>()
            var lastDashboardMs: Long? = null
            var lastReport: LogEntry? = null
            val errors = ArrayDeque<LogEntry>()
            var haUrlLocal = 0
            var haUrlRemote = 0
            var lastHaUrl: String? = null
            var lastHello: LogEntry? = null
            val batteryReadings = ArrayList<BatteryReading>()
            var notifyFromHa = 0
            var notifyFromPhone = 0
            val phonePackages = LinkedHashMap<String, Int>()
            val phoneSkips = LinkedHashMap<String, Int>()

            for (e in entries) {
                val extra = e.extra.orEmpty()
                val n = DiagnosticTeeLogger.occurrences(e.extra)
                when (e.tag) {
                    SessionDiagnostics.TAG -> when {
                        e.msg == SessionDiagnostics.STARTED -> sessions++
                        e.msg.startsWith(SessionDiagnostics.SLEPT) -> {
                            val s = SLEPT_SECONDS.find(e.msg)?.groupValues?.get(1)?.toLongOrNull()
                            if (s != null) {
                                sleepGaps++
                                totalSleep += s
                                longestSleep = maxOf(longestSleep ?: 0L, s)
                            }
                        }
                        e.msg == SessionDiagnostics.BLE_LOST -> bleLost++
                        e.msg == SessionDiagnostics.BLE_CONNECTED -> {
                            if (extra.startsWith("background")) bleBackground++ else bleDirect++
                            bleFailed += FAILED_ATTEMPTS.find(extra)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            openOutageFailed = 0
                        }
                        e.msg == SessionDiagnostics.BLE_ATTEMPTS ->
                            openOutageFailed = FAILED_ATTEMPTS.find(extra)?.groupValues?.get(1)?.toIntOrNull() ?: openOutageFailed
                        e.msg == SessionDiagnostics.BLE_WAIT_ENDED ->
                            SECONDS_AFTER.find(extra)?.groupValues?.get(1)?.toLongOrNull()?.let {
                                bleLongestWait = maxOf(bleLongestWait ?: 0L, it)
                            }
                    }
                    HA_TAG -> when {
                        e.msg.startsWith(HA_LOST) -> {
                            haLost++
                            val recent = lastSocketCause?.takeIf { e.ts - it.first in 0..CAUSE_WINDOW_MS }?.second
                            val cause = recent ?: extra.substringBefore(';').trim().ifEmpty { "unknown" }
                            haCauses[cause] = (haCauses[cause] ?: 0) + 1
                        }
                        e.msg.startsWith(HA_RECONNECTED) -> {
                            haReconnected++
                            SECONDS_AFTER.find(extra)?.groupValues?.get(1)?.toLongOrNull()?.let {
                                haLongest = maxOf(haLongest ?: 0L, it)
                            }
                        }
                    }
                    HA_SOCKET_TAG -> if (e.level == "WARN" || e.level == "ERROR") {
                        if (e.msg.startsWith("connect failed")) haConnectFailures += n
                        if (SOCKET_CAUSE.containsMatchIn(e.msg) && !e.msg.contains("replaced socket")) {
                            lastSocketCause = e.ts to causeOf(e)
                        }
                    }
                    CONNECTION_TAG -> when {
                        e.msg.startsWith("HA connect failed") || e.msg.startsWith("HA circuit breaker OPEN") ->
                            haConnectFailures += n
                        e.msg.startsWith("$HA_URL ") -> {
                            val chosen = e.msg.removePrefix("$HA_URL ")
                            if (chosen.startsWith("local ")) haUrlLocal += n else haUrlRemote += n
                            lastHaUrl = chosen
                        }
                    }
                    DASHBOARD_TAG -> if (e.msg == DASHBOARD_SENT) {
                        dashboardSends++
                        val reason = extra.substringBefore(':').trim().ifEmpty { "?" }
                        dashboardReasons[reason] = (dashboardReasons[reason] ?: 0) + 1
                        lastDashboardMs = e.ts
                    }
                    NOTIFY_TAG -> when (e.msg) {
                        NOTIFY_HA -> notifyFromHa += n
                        NOTIFY_PHONE -> {
                            notifyFromPhone += n
                            val pkg = PACKAGE_PAIR.find(extra)?.groupValues?.get(1) ?: "?"
                            phonePackages[pkg] = (phonePackages[pkg] ?: 0) + n
                        }
                        NOTIFY_SKIPS -> for (m in SKIP_PAIR.findAll(extra)) {
                            val count = m.groupValues[2].toIntOrNull() ?: continue
                            phoneSkips[m.groupValues[1]] = (phoneSkips[m.groupValues[1]] ?: 0) + count
                        }
                    }
                    WATCH_TAG -> {
                        if (e.msg == SENSOR_REPORT && extra.contains("steps=")) {
                            lastReport = e
                            BATTERY_MV.find(extra)?.groupValues?.get(1)?.toIntOrNull()?.let { mv ->
                                // Charging (bc) is false for a full battery on the charger: power present (pp) is not.
                                val onPower = BATTERY_CHARGING.find(extra)?.groupValues?.get(1) == "1" || BATTERY_POWER.containsMatchIn(extra)
                                batteryReadings.add(BatteryReading(e.ts, mv, onPower))
                            }
                        }
                        if (e.msg == "HELLO" && extra.startsWith("fw=")) lastHello = e
                    }
                    "engine" -> if (e.msg == LEGACY_BLE_BACKGROUND) bleBackground++
                }
                if (e.level == "ERROR") {
                    errors.addLast(e)
                    if (errors.size > LAST_ERRORS) errors.removeFirst()
                }
            }

            val counters = lastReport?.extra?.let { report ->
                // A number past Long (a garbled line) is skipped: one such would end the whole summary.
                val all = NUMBER_PAIR.findAll(report)
                    .mapNotNull { m -> m.groupValues[2].toLongOrNull()?.let { m.groupValues[1] to it } }
                    .toMap()
                WATCH_FIELDS.mapNotNull { k -> all[k]?.let { k to it } }.toMap()
            } ?: emptyMap()

            return HealthSummary(
                entries = entries.size,
                fromMs = entries.firstOrNull()?.ts,
                toMs = entries.lastOrNull()?.ts,
                sessions = sessions,
                haLost = haLost,
                haLostByCause = haCauses.byCount(),
                haReconnected = haReconnected,
                haLongestOutageSec = haLongest,
                haConnectFailures = haConnectFailures,
                bleLinkLost = bleLost,
                bleConnectsDirect = bleDirect,
                bleConnectsBackground = bleBackground,
                bleFailedAttempts = bleFailed + openOutageFailed,
                bleLongestWaitSec = bleLongestWait,
                sleepGaps = sleepGaps,
                longestSleepSec = longestSleep,
                totalSleepSec = totalSleep,
                dashboardSends = dashboardSends,
                dashboardSendsByReason = dashboardReasons.byCount(),
                lastDashboardSendMs = lastDashboardMs,
                lastSensorReportMs = lastReport?.ts,
                watchCounters = counters,
                lastErrors = errors.toList(),
                haUrlLocal = haUrlLocal,
                haUrlRemote = haUrlRemote,
                lastHaUrl = lastHaUrl,
                notifyFromHa = notifyFromHa,
                notifyFromPhone = notifyFromPhone,
                phoneForwardsByPackage = phonePackages.byCount(),
                phoneSkipsByReason = phoneSkips.byCount(),
                wakeDiag = lastHello?.extra?.let(::wakeOf),
                helloUptimeSec = lastHello?.extra?.let { HELLO_UP.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.div(UPTIME_TICKS_PER_SEC),
                batteryTrend = trendOf(batteryReadings),
            )
        }

        private class BatteryReading(val ts: Long, val mv: Int, val onPower: Boolean)

        private fun trendOf(readings: List<BatteryReading>): BatteryTrend? {
            val first = readings.firstOrNull() ?: return null
            val last = readings.last()
            var dropMv = 0L
            var offChargerMs = 0L
            for ((a, b) in readings.zipWithNext()) {
                if (a.onPower || b.onPower || b.ts <= a.ts) continue
                if (b.ts - a.ts > TREND_MAX_GAP_MS || b.mv - a.mv > TREND_MAX_RISE_MV) continue
                dropMv += b.mv - a.mv
                offChargerMs += b.ts - a.ts
            }
            return BatteryTrend(
                readings = readings.size,
                firstMv = first.mv, firstMs = first.ts,
                lastMv = last.mv, lastMs = last.ts,
                dischargeMvPerHour = if (offChargerMs >= TREND_MIN_MS) dropMv * 3_600_000.0 / offChargerMs else null,
                dischargeHours = offChargerMs / 3_600_000.0,
            )
        }

        private fun wakeOf(helloExtra: String): WakeDiag? =
            HELLO_WK.find(helloExtra)?.groupValues?.drop(1)?.map { it.toLongOrNull() ?: return null }?.let(WakeDiag::from)

        /** "onFailure timeout [SocketTimeoutException]": the message, and the exception's class when the tee logged one. */
        private fun causeOf(e: LogEntry): String {
            val text = e.msg.removeSuffix(" (a replaced socket)").take(80)
            val thrown = e.extra
                ?.let { if (it.startsWith(DiagnosticTeeLogger.REPEATED)) it.substringAfter("; ", "") else it }
                ?.substringBefore(':')?.trim()
                ?.takeIf { it.isNotEmpty() && CLASS_NAME.matches(it) }
            return if (thrown != null) "$text [$thrown]" else text
        }

        private fun Map<String, Int>.byCount(): List<Pair<String, Int>> =
            entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** The summary as the lines of a debug bundle's HEALTH SUMMARY section. */
    fun render(zone: TimeZone = TimeZone.getDefault()): List<String> {
        if (entries == 0) return listOf("<no diagnostic log>")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
        fun at(ms: Long?) = ms?.let { time.format(Date(it)) } ?: "<none>"
        fun counts(list: List<Pair<String, Int>>) = list.joinToString(" | ") { (k, v) -> "$v $k" }
        val spanHours = if (fromMs != null && toMs != null) "%.1f h".format(Locale.US, (toMs - fromMs) / 3_600_000.0) else "?"
        return buildList {
            add("span            : ${at(fromMs)} .. ${at(toMs)} ${zone.id} ($spanHours, $entries entries, $sessions sessions started)")
            add(
                "HA              : lost $haLost, reconnected $haReconnected" +
                    (haLongestOutageSec?.let { ", longest outage $it s" } ?: "") +
                    ", failed connects $haConnectFailures",
            )
            if (haLostByCause.isNotEmpty()) add("  lost by cause : ${counts(haLostByCause)}")
            if (lastHaUrl != null) {
                add("  URL chosen    : local $haUrlLocal, remote $haUrlRemote; last: ${lastHaUrl.take(200)}")
            }
            add(
                "BLE             : link lost $bleLinkLost, connected ${bleConnectsDirect + bleConnectsBackground}" +
                    " (direct $bleConnectsDirect, background $bleConnectsBackground), failed attempts $bleFailedAttempts" +
                    (bleLongestWaitSec?.let { ", longest background wait $it s" } ?: ""),
            )
            add(
                "phone sleep     : $sleepGaps gaps of ${SessionDiagnostics.SLEEP_LINE_MS / 1000} s or more" +
                    (longestSleepSec?.let { ", longest $it s" } ?: "") + ", $totalSleepSec s in all",
            )
            add(
                "DASHBOARD_STATE : $dashboardSends sent" +
                    (if (dashboardSendsByReason.isEmpty()) "" else " (${counts(dashboardSendsByReason)})") +
                    ", last ${at(lastDashboardSendMs)}",
            )
            add(
                "watch           : " + if (lastSensorReportMs == null) {
                    "<no SENSOR_REPORT>"
                } else {
                    "${watchCounters.entries.joinToString(" ") { (k, v) -> "$k=$v" }} (SENSOR_REPORT ${at(lastSensorReportMs)})"
                },
            )
            add(
                "battery trend   : " + if (batteryTrend == null) {
                    "no voltage reported"
                } else {
                    with(batteryTrend) {
                        "$firstMv mV at ${at(firstMs)} -> $lastMv mV at ${at(lastMs)} ($readings readings), off charger " +
                            if (dischargeMvPerHour == null) {
                                "too short for a slope (%.1f h)".format(Locale.US, dischargeHours)
                            } else {
                                "%+.1f mV/h over %.1f h".format(Locale.US, dischargeMvPerHour, dischargeHours)
                            }
                    }
                },
            )
            add("screen time     : ${wakeDiag?.summaryLine(helloUptimeSec) ?: "not reported"}")
            if (notifyFromHa + notifyFromPhone > 0 || phoneSkipsByReason.isNotEmpty()) {
                add("NOTIFY          : ${notifyFromHa + notifyFromPhone} queued (HA $notifyFromHa, phone forwards $notifyFromPhone)")
                if (phoneForwardsByPackage.isNotEmpty()) {
                    add("  phone by app  : ${counts(phoneForwardsByPackage.take(NOTIFY_TOP_PACKAGES))}")
                }
                if (phoneSkipsByReason.isNotEmpty()) add("  phone skipped : ${counts(phoneSkipsByReason)}")
            }
            add("last errors     : ${if (lastErrors.isEmpty()) "<none>" else ""}")
            for (e in lastErrors) {
                add("  ${at(e.ts)} ${e.tag} ${e.msg.take(160)}${e.extra?.let { " (${it.take(160)})" } ?: ""}")
            }
        }
    }
}

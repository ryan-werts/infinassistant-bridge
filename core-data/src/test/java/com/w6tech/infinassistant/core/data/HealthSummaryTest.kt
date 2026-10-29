package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.domain.WakeDiag
import com.w6tech.infinassistant.core.data.DiagnosticLogger.LogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The bundle's HEALTH SUMMARY: counted from diagnostic-log entries as the bridge writes them. */
class HealthSummaryTest {

    private var ts = 1_791_900_000_000L  // 2026-10-13, UTC

    private fun entry(tag: String, msg: String, extra: String? = null, level: String = "DEBUG", afterMs: Long = 1_000): LogEntry {
        ts += afterMs
        return LogEntry(ts = ts, level = level, tag = tag, msg = msg, extra = extra)
    }

    private fun session(msg: String, extra: String? = null, afterMs: Long = 1_000) =
        entry(SessionDiagnostics.TAG, msg, extra, afterMs = afterMs)

    /** A day of a bridge: HA dropping, the watch out of range, the phone asleep, the dashboard sending. */
    private fun day(): List<LogEntry> = listOf(
        session(SessionDiagnostics.STARTED, "phone up 3600 s since boot, 600 s of it asleep"),
        session(SessionDiagnostics.BLE_CONNECTED, "direct; down 3 s, 0 failed attempts"),
        entry("dashboard", "DASHBOARD_STATE sent", "connect: fp=19826696 bits=1:2 sensors= wx=- events=0"),
        // HA drops: the socket says why just before.
        entry("HaWebSocket", "onClosing code=1001 reason=going away", level = "WARN", afterMs = 60_000),
        entry("ha", "HA lost", "Disconnected; reconnecting, watch link kept", afterMs = 2_000),
        entry("ha", "HA reconnected", "after 12 s; resyncing states", afterMs = 12_000),
        // A socket line from a replaced socket is not a cause.
        entry("HaWebSocket", "onFailure null (a replaced socket)", level = "ERROR", afterMs = 1_000),
        // Ten minutes on, HA drops with nothing from the socket: the engine's own cause.
        entry("ha", "HA lost", "failed: null; reconnecting, watch link kept", afterMs = 600_000),
        entry("HaWebSocket", "connect failed: timeout", "SocketTimeoutException: timeout", level = "ERROR"),
        entry("HaWebSocket", "connect failed: timeout", "repeated 5 more times in 50 s, the last at 10:00:00; SocketTimeoutException: timeout", level = "ERROR", afterMs = 60_000),
        entry("ConnectionManager", "HA connect failed: No network", level = "WARN"),
        entry("ha", "HA reconnected", "after 300 s; resyncing states", afterMs = 240_000),
        entry("HaWebSocket", "onFailure timeout", "SocketTimeoutException: timeout", level = "ERROR", afterMs = 30_000),
        entry("ha", "HA lost", "failed: timeout; reconnecting, watch link kept", afterMs = 1_000),
        // The watch goes out of range while the phone sleeps.
        session(SessionDiagnostics.BLE_LOST, "up 900 s; ha=Connected"),
        session(SessionDiagnostics.BLE_WAIT_STARTED, "after: GATT connect timed out"),
        session("${SessionDiagnostics.SLEPT} 18000 s", "during ble-background-wait; ble=Connected ha=Connected; 18000 s asleep this session", afterMs = 18_005_000),
        session(SessionDiagnostics.BLE_WAIT_ENDED, "connected after 18005 s"),
        session(SessionDiagnostics.BLE_CONNECTED, "background; down 18006 s, 1 failed attempt (GATT connect timed out x1)"),
        entry("engine", "BLE connected in the background", "after 30 s"),  // app 2.1.109's line
        session("${SessionDiagnostics.SLEPT} 90 s", "during connected; ble=Connected ha=Connected; 18090 s asleep this session", afterMs = 90_000),
        entry("dashboard", "DASHBOARD_STATE sent", "change: fp=19826696 bits=1:3"),
        entry("dashboard", "DASHBOARD_STATE sent", "change: fp=19826696 bits=1:2"),
        entry("dashboard", "DASHBOARD_STATE sent", "heartbeat: fp=19826696 bits=1:2"),
        entry("dashboard", "DASHBOARD_STATE sent", "change: fp=19826696 bits=1:0"),
        entry("watch->phone", "SENSOR_REPORT", "rx 121B"),
        entry("watch->phone", "SENSOR_REPORT", "steps=8000, hr=70, batt=86% bmv=3990 bc=0 heap=4100 minHeap=3150 stackHW=121 dsOk=40 dsBad=2 fpMis=1 dsAge=93"),
        entry("watch->phone", "SENSOR_REPORT", "rx 121B", afterMs = 60_000),
        entry("watch->phone", "SENSOR_REPORT", "steps=8432, hr=72, batt=85% bmv=3950 bc=0 heap=4012 minHeap=3100 stackHW=120 dsOk=41 dsBad=2 fpMis=1 dsAge=33"),
        // Out of range again, still down: its attempts so far.
        session(SessionDiagnostics.BLE_LOST, "up 120 s; ha=Connected"),
        session(SessionDiagnostics.BLE_ATTEMPTS, "121 failed in 600 s (Bluetooth is off x121)", afterMs = 600_000),
    )


    @Test
    fun `counts reconnects by cause, the watch link, sleep, dashboard sends and the watch's counters`() {
        val s = HealthSummary.compute(day())

        assertEquals(1, s.sessions)
        assertEquals(3, s.haLost)
        assertEquals(
            listOf(
                "onClosing code=1001 reason=going away" to 1,
                "failed: null" to 1,
                "onFailure timeout [SocketTimeoutException]" to 1,
            ),
            s.haLostByCause,
        )
        assertEquals(2, s.haReconnected)
        assertEquals(300L, s.haLongestOutageSec)
        assertEquals("the socket's line and its 5 repeats, and one refused before a socket", 7, s.haConnectFailures)

        assertEquals(2, s.bleLinkLost)
        assertEquals(1, s.bleConnectsDirect)
        assertEquals("the session's line and 2.1.109's", 2, s.bleConnectsBackground)
        assertEquals("1 before the background connect, 121 in the open outage", 122, s.bleFailedAttempts)
        assertEquals(18005L, s.bleLongestWaitSec)

        assertEquals(2, s.sleepGaps)
        assertEquals(18000L, s.longestSleepSec)
        assertEquals(18090L, s.totalSleepSec)

        assertEquals(5, s.dashboardSends)
        assertEquals(listOf("change" to 3, "connect" to 1, "heartbeat" to 1), s.dashboardSendsByReason)

        assertEquals(
            mapOf("batt" to 85L, "bmv" to 3950L, "heap" to 4012L, "minHeap" to 3100L, "stackHW" to 120L, "dsOk" to 41L, "dsBad" to 2L, "fpMis" to 1L, "dsAge" to 33L),
            s.watchCounters,
        )
        assertEquals(HealthSummary.WATCH_FIELDS, s.watchCounters.keys.toList())
    }

    @Test
    fun `the last five errors, oldest first`() {
        val errors = (1..7).map { entry("BleTransport", "error $it", level = "ERROR") }
        val s = HealthSummary.compute(listOf(entry("ha", "HA lost", "x")) + errors)
        assertEquals(listOf("error 3", "error 4", "error 5", "error 6", "error 7"), s.lastErrors.map { it.msg })
    }

    @Test
    fun `entries out of order are counted in time order`() {
        val inOrder = day()
        val s = HealthSummary.compute(inOrder.reversed())
        assertEquals(HealthSummary.compute(inOrder), s)
        assertEquals(inOrder.first().ts, s.fromMs)
        assertEquals(inOrder.last().ts, s.toMs)
    }

    @Test
    fun `older firmware's report has no dashboard counters`() {
        val s = HealthSummary.compute(
            listOf(entry("watch->phone", "SENSOR_REPORT", "steps=1, hr=0, batt=90% heap=5000 minHeap=4000 stackHW=100")),
        )
        assertEquals(mapOf("batt" to 90L, "heap" to 5000L, "minHeap" to 4000L, "stackHW" to 100L), s.watchCounters)
    }

    @Test
    fun `an empty log summarises to nothing`() {
        val s = HealthSummary.compute(emptyList())
        assertEquals(0, s.entries)
        assertNull(s.fromMs)
        assertNull(s.lastSensorReportMs)
        assertEquals(listOf("<no diagnostic log>"), s.render(TimeZone.getTimeZone("UTC")))
    }

    @Test
    fun `renders the bundle's lines`() {
        val text = HealthSummary.compute(day()).render(TimeZone.getTimeZone("UTC"))
        assertTrue(text.joinToString("\n"), text.any { it == "HA              : lost 3, reconnected 2, longest outage 300 s, failed connects 7" })
        assertTrue(text.any { it.startsWith("  lost by cause : 1 onClosing code=1001 reason=going away | 1 failed: null") })
        assertTrue(text.any { it == "BLE             : link lost 2, connected 3 (direct 1, background 2), failed attempts 122, longest background wait 18005 s" })
        assertTrue(text.any { it == "phone sleep     : 2 gaps of 60 s or more, longest 18000 s, 18090 s in all" })
        assertTrue(text.any { it.startsWith("DASHBOARD_STATE : 5 sent (3 change | 1 connect | 1 heartbeat), last 2026-10-") })
        assertTrue(text.any { it.startsWith("watch           : batt=85 bmv=3950 heap=4012 minHeap=3100 stackHW=120 dsOk=41 dsBad=2 fpMis=1 dsAge=33 (SENSOR_REPORT 2026-10-") })
        assertTrue(text.any { it.startsWith("  ") && it.contains("HaWebSocket onFailure timeout (SocketTimeoutException: timeout)") })
    }

    @Test
    fun `parses the log's own lines`() {
        val line = """{"ts":1791900000000,"level":"WARN","tag":"HaWebSocket","msg":"onClosing code=1001 reason=","extra":null,"future":1}"""
        val e = DiagnosticLogger.parseLine(line)
        assertEquals(LogEntry(1791900000000, "WARN", "HaWebSocket", "onClosing code=1001 reason="), e)
        assertNull(DiagnosticLogger.parseLine("""{"ts":17919"""))
        assertNull(DiagnosticLogger.parseLine(""))
    }

    @Test
    fun `the URL each HA connect chose is counted, repeats included, with the last one`() {
        val remote = "HA URL remote https://ha.example.com:8443 (FALLBACK; local test failed: refused)"
        val s = HealthSummary.compute(
            listOf(
                entry("ConnectionManager", remote, level = "INFO"),
                entry("ConnectionManager", remote, "repeated 4 more times in 50 s, the last at 10:00:00", level = "INFO"),
                entry("ConnectionManager", "HA URL local http://192.168.1.5:8123 (CONNECTION_TEST)", level = "INFO"),
            ),
        )
        assertEquals(1, s.haUrlLocal)
        assertEquals(5, s.haUrlRemote)
        assertEquals("local http://192.168.1.5:8123 (CONNECTION_TEST)", s.lastHaUrl)
        val text = s.render(TimeZone.getTimeZone("UTC"))
        assertTrue(text.joinToString("\n"), text.contains("  URL chosen    : local 1, remote 5; last: local http://192.168.1.5:8123 (CONNECTION_TEST)"))
        // Before app 2.1.113 there are no such lines, and no line for them.
        assertFalse(HealthSummary.compute(day()).render(TimeZone.getTimeZone("UTC")).any { it.contains("URL chosen") })
    }

    @Test
    fun `NOTIFYs are counted by origin, phone forwards by package and skips by reason`() {
        val log = buildList {
            repeat(3) { add(entry(HealthSummary.NOTIFY_TAG, HealthSummary.NOTIFY_HA)) }
            // Ten apps, com.app0 the most: only the top 8 are listed.
            for (app in 0 until 10) repeat(10 - app) { add(entry(HealthSummary.NOTIFY_TAG, HealthSummary.NOTIFY_PHONE, "pkg=com.app$app")) }
            add(entry(HealthSummary.NOTIFY_TAG, HealthSummary.NOTIFY_SKIPS, "ongoing=40 silent=2"))
            add(entry(HealthSummary.NOTIFY_TAG, HealthSummary.NOTIFY_SKIPS, "ongoing=10 do-not-disturb=5"))
        }
        val s = HealthSummary.compute(log)
        assertEquals(3, s.notifyFromHa)
        assertEquals(55, s.notifyFromPhone)
        assertEquals("com.app0" to 10, s.phoneForwardsByPackage.first())
        assertEquals(10, s.phoneForwardsByPackage.size)
        assertEquals(listOf("ongoing" to 50, "do-not-disturb" to 5, "silent" to 2), s.phoneSkipsByReason)
        val text = s.render(TimeZone.getTimeZone("UTC"))
        assertTrue(text.joinToString("\n"), text.contains("NOTIFY          : 58 queued (HA 3, phone forwards 55)"))
        val byApp = text.single { it.startsWith("  phone by app  : ") }
        assertTrue(byApp, byApp.endsWith("3 com.app7"))
        assertFalse(byApp, byApp.contains("com.app8"))
        assertTrue(text.contains("  phone skipped : 50 ongoing | 5 do-not-disturb | 2 silent"))
    }

    @Test
    fun `a bundle without those lines has no NOTIFY line`() {
        assertFalse(HealthSummary.compute(day()).render(TimeZone.getTimeZone("UTC")).any { it.contains("NOTIFY") })
    }
    @Test
    fun `the screen-time line comes from the last watch HELLO, with its uptime`() {
        val older = "fw=2.4.104 up=1024 wk=1,0,0,5,0"
        val last = "fw=2.4.104 heap=5744 up=24576000 wk=300,60,20,1234,31508"
        val s = HealthSummary.compute(
            listOf(
                entry("watch->phone", "HELLO", older),
                entry("phone->watch", "HELLO", "diagnostic refresh"),
                entry("watch->phone", "HELLO", last),
            ),
        )
        assertEquals(WakeDiag(300, 60, 20, 1234, 0x7B14), s.wakeDiag)
        assertEquals(24_000L, s.helloUptimeSec)
        val text = s.render(TimeZone.getTimeZone("UTC"))
        assertTrue(
            text.joinToString("\n"),
            text.contains(
                "screen time     : screen on 20 min since boot (5% of uptime), wakes 300 (by notification 60, stored quietly 20), " +
                    "timeout 15 s, brightness Medium, Always On off, wake modes raise wrist + lower wrist, notifications On",
            ),
        )
    }

    @Test
    fun `the screen-time line names brightness code 5 as B`() {
        // 0x7D14 = 32020: raise + lower wrist, notifications On, brightness code 5, 15 s.
        val s = HealthSummary.compute(listOf(entry("watch->phone", "HELLO", "fw=2.4.105 up=24576000 wk=300,60,20,1234,32020")))
        val line = s.render(TimeZone.getTimeZone("UTC")).single { it.startsWith("screen time") }
        assertTrue(line, line.contains("brightness B (button), Always On off"))
    }

    /** A SENSOR_REPORT line as 2.4.105 firmware gives it: `pp=1` only while power is present. */
    private fun report(mv: Int, charging: Boolean, afterMs: Long, power: Boolean = false) = entry(
        "watch->phone", "SENSOR_REPORT",
        "steps=1, hr=0, batt=80% bmv=$mv bc=${if (charging) 1 else 0}${if (power) " pp=1" else ""} heap=5000 minHeap=4000 stackHW=100",
        afterMs = afterMs,
    )

    private val hour = 3_600_000L

    @Test
    fun `the battery trend gives first and last voltage and the slope off the charger`() {
        // Two hours off the charger, an hour on it (the pairs touching a charging reading are left out), then two hours off.
        val s = HealthSummary.compute(
            listOf(
                report(4000, false, afterMs = 0),
                report(3970, false, afterMs = hour),
                report(3940, false, afterMs = hour),
                report(3945, true, afterMs = hour),
                report(4100, true, afterMs = 1_000),
                report(4090, false, afterMs = 1_000),
                report(3990, false, afterMs = 1 * hour),
                report(3900, false, afterMs = 1 * hour),
            ),
        )
        val t = s.batteryTrend!!
        assertEquals(8, t.readings)
        assertEquals(4000, t.firstMv)
        assertEquals(3900, t.lastMv)
        // Off-charger pairs: 4000->3970 (1 h, -30), 3970->3940 (1 h, -30), 4090->3990 (1 h, -100), 3990->3900 (1 h, -90): -250 mV in 4 h.
        assertEquals(4.0, t.dischargeHours, 0.001)
        assertEquals(-62.5, t.dischargeMvPerHour!!, 0.001)
        val line = s.render(TimeZone.getTimeZone("UTC")).single { it.startsWith("battery trend") }
        assertTrue(line, line.startsWith("battery trend   : 4000 mV at 2026-10-"))
        assertTrue(line, line.contains(" -> 3900 mV at 2026-10-"))
        assertTrue(line, line.endsWith("(8 readings), off charger -62.5 mV/h over 4.0 h"))
    }

    @Test
    fun `power present counts as on the charger even when the full battery is not charging`() {
        // A full battery on the charger reads bc=0 pp=1: the readings at 4150 mV are not a drain or a rise.
        val s = HealthSummary.compute(
            listOf(
                report(4000, false, afterMs = 0),
                report(3990, false, afterMs = hour),
                report(4150, false, afterMs = hour, power = true),
                report(4150, false, afterMs = hour, power = true),
                report(4100, false, afterMs = hour),
                report(4050, false, afterMs = hour),
            ),
        )
        val t = s.batteryTrend!!
        // Only 4000 -> 3990 and 4100 -> 4050 are off power: -60 mV in 2 h.
        assertEquals(2.0, t.dischargeHours, 0.001)
        assertEquals(-30.0, t.dischargeMvPerHour!!, 0.001)
        assertEquals(4000, t.firstMv)
        assertEquals(4050, t.lastMv)
    }

    @Test
    fun `a long gap is not a stretch, and neither is a rise nobody explained`() {
        val s = HealthSummary.compute(
            listOf(
                report(4000, false, afterMs = 0),
                report(3980, false, afterMs = hour),          // kept: -20 in 1 h
                report(3950, false, afterMs = 2 * hour),      // kept: exactly 2 h is still one stretch, -30
                report(4250, false, afterMs = 2 * hour + 1),  // left out: over 2 h apart (a charge in the gap)
                report(4200, false, afterMs = hour),          // kept: -50 in 1 h
                report(4260, false, afterMs = hour),          // left out: +60 mV with no power reported
                report(4250, false, afterMs = hour),          // kept: -10 in 1 h
                report(4300, false, afterMs = hour),          // kept: +50 is allowed (noise), +50 in 1 h
            ),
        )
        val t = s.batteryTrend!!
        assertEquals(8, t.readings)
        // Kept pairs: -20/1 h, -30/2 h, -50/1 h, -10/1 h, +50/1 h = -60 mV in 6 h.
        assertEquals(6.0, t.dischargeHours, 0.001)
        assertEquals(-10.0, t.dischargeMvPerHour!!, 0.001)
        // First and last still span the whole log.
        assertEquals(4000, t.firstMv)
        assertEquals(4300, t.lastMv)
    }

    @Test
    fun `a gap over two hours alone leaves no slope`() {
        val s = HealthSummary.compute(listOf(report(4000, false, 0), report(3500, false, 3 * hour)))
        assertNull(s.batteryTrend!!.dischargeMvPerHour)
        assertEquals(0.0, s.batteryTrend!!.dischargeHours, 0.0)
    }

    @Test
    fun `a charging stretch gives no slope, and a short one is too short for one`() {
        val charging = HealthSummary.compute(listOf(report(3900, true, 0), report(4100, true, hour)))
        assertNull(charging.batteryTrend!!.dischargeMvPerHour)
        assertEquals(0.0, charging.batteryTrend!!.dischargeHours, 0.0)
        assertTrue(
            charging.render(TimeZone.getTimeZone("UTC")).single { it.startsWith("battery trend") }.endsWith("off charger too short for a slope (0.0 h)"),
        )
        // Two minutes off the charger: not enough to call a slope.
        val brief = HealthSummary.compute(listOf(report(4000, false, 0), report(3990, false, 120_000)))
        assertNull(brief.batteryTrend!!.dischargeMvPerHour)
        // One reading is a first and a last and nothing between.
        val one = HealthSummary.compute(listOf(report(4000, false, 0))).batteryTrend!!
        assertEquals(one.firstMv, one.lastMv)
        assertNull(one.dischargeMvPerHour)
    }

    @Test
    fun `older firmware without voltage says so`() {
        val s = HealthSummary.compute(
            listOf(entry("watch->phone", "SENSOR_REPORT", "steps=1, hr=0, batt=90% heap=5000 minHeap=4000 stackHW=100")),
        )
        assertNull(s.batteryTrend)
        assertTrue(s.render(TimeZone.getTimeZone("UTC")).contains("battery trend   : no voltage reported"))
        assertTrue(HealthSummary.compute(day()).batteryTrend != null)
    }

    @Test
    fun `a last HELLO without wk, or no HELLO, prints not reported`() {
        val noWk = HealthSummary.compute(
            listOf(
                entry("watch->phone", "HELLO", "fw=2.4.104 up=1024 wk=1,0,0,5,0"),
                entry("watch->phone", "HELLO", "fw=2.4.104 up=2048"),
            ),
        )
        assertNull(noWk.wakeDiag)
        assertTrue(noWk.render(TimeZone.getTimeZone("UTC")).contains("screen time     : not reported"))
        assertTrue(HealthSummary.compute(day()).render(TimeZone.getTimeZone("UTC")).contains("screen time     : not reported"))
    }
}

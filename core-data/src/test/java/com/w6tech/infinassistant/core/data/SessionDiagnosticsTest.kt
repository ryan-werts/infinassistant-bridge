package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.ha.HaConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deep-sleep detection with injected clocks (elapsedRealtime runs on in deep sleep, uptime does
 * not), and the BLE wait and attempt lines: counted, never one line per retry.
 */
class SessionDiagnosticsTest {

    private val lines = mutableListOf<Pair<String, String>>()
    private var elapsedMs = 10_000_000L
    private var uptimeMs = 5_000_000L

    private val diag = SessionDiagnostics(
        log = { msg, extra -> lines += msg to extra },
        elapsedRealtimeMs = { elapsedMs },
        uptimeMs = { uptimeMs },
    )

    private val bleDown = BleConnectionState.Disconnected
    private val bleUp = BleConnectionState.Connected(247)
    private val haUp = HaConnectionState.Connected

    private fun awake(ms: Long) {
        elapsedMs += ms
        uptimeMs += ms
    }

    private fun asleep(ms: Long) {
        elapsedMs += ms
    }

    private fun messages() = lines.map { it.first }

    @Before
    fun startSession() {
        diag.start()
    }

    @Test
    fun `a session start records how long the phone has been up and asleep`() {
        assertEquals(SessionDiagnostics.STARTED to "phone up 10000 s since boot, 5000 s of it asleep", lines.single())
    }

    @Test
    fun `a deep sleep of a minute or more gets one line with the links' states`() {
        awake(1_000)
        diag.checkSleep("connected", bleUp, haUp)
        asleep(5 * 3600_000L)
        awake(1_000)
        diag.checkSleep("connected", bleDown, haUp)

        assertEquals(2, lines.size)
        assertEquals("${SessionDiagnostics.SLEPT} 18000 s" to "during connected; ble=Disconnected ha=Connected; 18000 s asleep this session", lines[1])
    }

    @Test
    fun `time awake, however long, is no sleep`() {
        awake(2 * 3600_000L)
        diag.checkSleep("connected", bleUp, haUp)
        assertEquals(listOf(SessionDiagnostics.STARTED), messages())
    }

    @Test
    fun `a short sleep gets no line but counts toward the session's total`() {
        asleep(30_000)
        awake(1_000)
        diag.checkSleep("connected", bleUp, haUp)
        assertEquals(1, lines.size)

        asleep(90_000)
        diag.checkSleep("ble-retry-wait", bleDown, HaConnectionState.Disconnected)
        assertEquals(
            "${SessionDiagnostics.SLEPT} 90 s" to "during ble-retry-wait; ble=Disconnected ha=Disconnected; 120 s asleep this session",
            lines.last(),
        )
    }

    @Test
    fun `each gap is measured from the last check, not from the session start`() {
        asleep(70_000)
        diag.checkSleep("connected", bleUp, haUp)
        awake(1_000)
        diag.checkSleep("connected", bleUp, haUp)
        asleep(65_000)
        diag.checkSleep("connected", bleUp, haUp)
        assertEquals(
            listOf(SessionDiagnostics.STARTED, "${SessionDiagnostics.SLEPT} 70 s", "${SessionDiagnostics.SLEPT} 65 s"),
            messages(),
        )
    }

    @Test
    fun `a background wait logs its start and end, the sleep in it, and the connect it ended in`() {
        diag.bleConnected()
        lines.clear()
        awake(60_000)
        diag.bleLost(haUp)
        diag.bleWaitStarted(RuntimeException("GATT connect timed out"))
        asleep(2 * 3600_000L)
        awake(5_000)
        diag.bleWaitEnded(null, bleUp, haUp)
        diag.bleConnected()

        assertEquals(
            listOf(
                SessionDiagnostics.BLE_LOST to "up 60 s; ha=Connected",
                SessionDiagnostics.BLE_WAIT_STARTED to "after: GATT connect timed out",
                "${SessionDiagnostics.SLEPT} 7200 s" to "during ble-background-wait; ble=Connected ha=Connected; 7200 s asleep this session",
                SessionDiagnostics.BLE_WAIT_ENDED to "connected after 7205 s",
                SessionDiagnostics.BLE_CONNECTED to "background; down 7205 s, 1 failed attempt (GATT connect timed out x1)",
            ),
            lines,
        )
    }

    @Test
    fun `retries that fail at once are counted, not logged one by one`() {
        val off = IllegalStateException("Bluetooth is off")
        repeat(70) {
            diag.bleWaitStarted(off)
            diag.bleWaitEnded(off, bleDown, haUp)
            awake(10_000)  // the retry delay
        }
        diag.bleConnected()

        assertEquals(
            listOf(
                SessionDiagnostics.STARTED,
                SessionDiagnostics.BLE_WAIT_STARTED,
                SessionDiagnostics.BLE_WAIT_ENDED,
                SessionDiagnostics.BLE_ATTEMPTS,
                SessionDiagnostics.BLE_CONNECTED,
            ),
            messages(),
        )
        assertEquals("121 failed in 600 s (Bluetooth is off x121)", lines[3].second)
        assertEquals("direct; down 700 s, 140 failed attempts (Bluetooth is off x140)", lines[4].second)
    }

    @Test
    fun `a long background wait that failed gets its lines, and the next wait too`() {
        val off = IllegalStateException("Bluetooth is off")
        diag.bleWaitStarted(RuntimeException("GATT connect timed out"))
        awake(5_000)
        diag.bleWaitEnded(off, bleDown, haUp)        // quick: paired with its logged start
        diag.bleWaitStarted(RuntimeException("GATT connect timed out"))
        awake(3_600_000)
        diag.bleWaitEnded(off, bleDown, haUp)        // an hour: logged although its start was not
        diag.bleWaitStarted(RuntimeException("GATT connect timed out"))

        val waits = lines.filter { it.first.startsWith("BLE background wait") }
        assertEquals(4, waits.size)
        assertEquals(SessionDiagnostics.BLE_WAIT_ENDED to "failed after 3600 s: Bluetooth is off", waits[2])
        assertEquals(SessionDiagnostics.BLE_WAIT_STARTED, waits[3].first)
    }

    @Test
    fun `reasons are listed by count, the top three`() {
        val reasons = listOf("a", "b", "b", "c", "c", "c", "d", "d", "d", "d")
        for (r in reasons) {
            diag.bleWaitStarted(RuntimeException(r))
            diag.bleWaitEnded(RuntimeException("gatt"), bleDown, haUp)
        }
        diag.bleConnected()
        val connected = lines.last()
        assertEquals(SessionDiagnostics.BLE_CONNECTED, connected.first)
        assertTrue(connected.second, connected.second.endsWith("20 failed attempts (gatt x10, d x4, c x3, ...)"))
    }
}

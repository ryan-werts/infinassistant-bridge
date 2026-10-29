package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.TimeZone

/**
 * The tee logger: what reaches the diagnostic log (levels and the listed events), that a flood
 * collapses into counts without losing an occurrence, and that secrets never reach the file.
 */
class DiagnosticTeeLoggerTest {

    private data class Line(val level: String, val tag: String, val msg: String, val extra: String?)

    private val lines = mutableListOf<Line>()
    private val logcat = mutableListOf<String>()
    private var nowMs = 1_800_000_000_000L

    private val delegate = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) { logcat += "D/$tag: $msg" }
        override fun i(tag: String, msg: String, tr: Throwable?) { logcat += "I/$tag: $msg" }
        override fun w(tag: String, msg: String, tr: Throwable?) { logcat += "W/$tag: $msg" }
        override fun e(tag: String, msg: String, tr: Throwable?) { logcat += "E/$tag: $msg" }
    }

    private fun tee(tagBudget: Int = 120) = DiagnosticTeeLogger(
        delegate = delegate,
        sink = DiagnosticTeeLogger.Sink { level, tag, msg, extra -> lines += Line(level, tag, msg, extra) },
        clockMs = { nowMs },
        tagBudget = tagBudget,
        timeZone = TimeZone.getTimeZone("UTC"),
    )

    private fun advance(ms: Long) { nowMs += ms }

    /** What the file stands for: each line once, a repeat count's line N times. */
    private fun occurrencesOf(msgPrefix: String) =
        lines.filter { it.msg.startsWith(msgPrefix) }.sumOf { DiagnosticTeeLogger.occurrences(it.extra) }

    @Test
    fun `every call still reaches logcat unchanged`() {
        val log = tee()
        log.d("ConnectionManager", "BLE reconnect delay: 2000ms")
        log.i("BleGattClient", "GATT acked-write completed")
        log.w("HaApi", "slow")
        log.e("HaWebSocket", "connect failed: timeout")
        assertEquals(
            listOf(
                "D/ConnectionManager: BLE reconnect delay: 2000ms",
                "I/BleGattClient: GATT acked-write completed",
                "W/HaApi: slow",
                "E/HaWebSocket: connect failed: timeout",
            ),
            logcat,
        )
    }

    @Test
    fun `warnings and errors reach the file, debug and info only the listed events`() {
        val log = tee()
        log.d("ConnectionManager", "Selected URL: http://ha.local:8123")
        log.i("BleGattClient", "GATT acked-write completed")
        log.i("HaWebSocket", "WebSocket opened")
        log.d("HaWebSocket", "auth_required received, sending token (len=183)")
        log.i("BleGattClient", "GATT connected in the background")
        log.i("BleGattClient", "effective MTU = 247")
        log.i("BleGattClient", "Closing GATT (connect failed)")
        log.w("HaWebSocket", "onClosing code=1001 reason=going away")
        log.e("BleTransport", "write failed")

        assertEquals(
            listOf(
                Line("INFO", "HaWebSocket", "WebSocket opened", null),
                Line("DEBUG", "HaWebSocket", "auth_required received, sending token (len=183)", null),
                Line("INFO", "BleGattClient", "GATT connected in the background", null),
                Line("INFO", "BleGattClient", "effective MTU = 247", null),
                Line("INFO", "BleGattClient", "Closing GATT (connect failed)", null),
                Line("WARN", "HaWebSocket", "onClosing code=1001 reason=going away", null),
                Line("ERROR", "BleTransport", "write failed", null),
            ),
            lines,
        )
    }

    @Test
    fun `a flood is written a few times, then counted in one line when its window closes`() {
        val log = tee()
        repeat(50) { log.w("BleGattClient", "GATT write FAILED status=133 (acked=false)") }
        assertEquals("the first three, then counted", 3, lines.size)

        advance(61_000)
        log.w("HaApi", "something else")  // any later message closes the window

        val count = lines.single { it.extra?.startsWith(DiagnosticTeeLogger.REPEATED) == true }
        assertEquals("GATT write FAILED status=133 (acked=false)", count.msg)
        assertEquals("WARN", count.level)
        assertEquals(47, DiagnosticTeeLogger.occurrences(count.extra))
        assertEquals(50, occurrencesOf("GATT write FAILED"))
    }

    @Test
    fun `numbers in a message do not split its key`() {
        val log = tee()
        for (id in 1..20) log.w("HaWebSocket", "Request $id timed out after 15000ms")
        log.flush()
        assertEquals(4, lines.size)  // three written, one count
        assertEquals("Request 20 timed out after 15000ms", lines.last().msg)
        assertEquals(20, occurrencesOf("Request"))
    }

    @Test
    fun `a message that comes back now and then is never collapsed`() {
        val log = tee()
        repeat(10) {
            log.w("HaWebSocket", "onClosing code=1000 reason=")
            advance(30_000)  // the 30-s drop cycle: two a minute, under the burst
        }
        assertEquals(10, lines.size)
        assertTrue(lines.none { it.extra?.startsWith(DiagnosticTeeLogger.REPEATED) == true })
    }

    @Test
    fun `a flood that lasts settles at a few lines an hour and loses no occurrence`() {
        val log = tee()
        val seconds = 3 * 3600
        repeat(seconds) {
            log.w("BleTransport", "notify failed rc=6")
            advance(1_000)
        }
        log.flush()
        val flood = lines.filter { it.msg.startsWith("notify failed") }
        assertTrue("${flood.size} lines for a 3-h flood", flood.size < 40)
        assertEquals(seconds, occurrencesOf("notify failed"))
        // The last hour is in the longest window: at most a burst and a count.
        val lastHour = flood.takeLast(4)
        assertEquals(1, lastHour.count { it.extra?.startsWith(DiagnosticTeeLogger.REPEATED) == true })
    }

    @Test
    fun `a tag over its budget is held, and the lines it lost are counted`() {
        val log = tee(tagBudget = 5)
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel")
        for (w in words) log.e("EventBroker", "failed to route $w event")
        assertEquals(5, lines.size)
        log.flush()
        val dropped = lines.last()
        assertEquals("EventBroker", dropped.tag)
        assertEquals("rate limit: 3 more lines not written", dropped.msg)
    }

    @Test
    fun `flush writes the counts still open`() {
        val log = tee()
        repeat(5) { log.e("HaWebSocket", "connect failed: timeout") }
        assertEquals(3, lines.size)
        log.flush()
        assertEquals(4, lines.size)
        assertEquals(2, DiagnosticTeeLogger.occurrences(lines.last().extra))
        log.flush()
        assertEquals("nothing more to write", 4, lines.size)
    }

    @Test
    fun `secrets in the message are redacted`() {
        val log = tee()
        log.w(
            "HaApi",
            "POST https://ha.example/api/webhook/abcdef1234567890 failed: Bearer eyJabc.def-ghi " +
                "url=https://ha.example/auth?access_token=sekrit123&x=1 body={\"refresh_token\": \"r3fr3sh\"}",
        )
        val msg = lines.single().msg
        for (secret in listOf("abcdef1234567890", "eyJabc.def-ghi", "sekrit123", "r3fr3sh")) {
            assertFalse("$secret leaked: $msg", msg.contains(secret))
        }
        assertTrue(msg.contains("REDACTED"))
    }

    @Test
    fun `a throwable goes in as class and message with its causes, redacted, never its stack`() {
        val log = tee()
        val inner = IOException("token=\"t0k3n\"")
        log.e("HaWebSocket", "onFailure null", IllegalStateException("socket closed", inner))
        val extra = lines.single().extra
        assertNotNull(extra)
        assertTrue(extra!!.startsWith("IllegalStateException: socket closed <- IOException: "))
        assertFalse("token leaked: $extra", extra.contains("t0k3n"))
        assertFalse("no stack frames: $extra", extra.contains("at com."))
    }

    @Test
    fun `the notification listener's decisions reach the log, and its text-free lines only`() {
        val t = tee()
        // The listener logs at debug level; until app 2.1.125 none of its lines were written.
        t.d("NotificationForwarder", "pkg=com.example.chat nid=7 decision=forward")
        t.d("NotificationForwarder", "pkg=com.example.chat decision=skip reason=silent")
        t.d("NotificationForwarder", "nid=7 decision=keep reason=gone")
        t.d("NotificationForwarder", "Service bound")
        assertEquals(
            listOf(
                "pkg=com.example.chat nid=7 decision=forward",
                "pkg=com.example.chat decision=skip reason=silent",
                "nid=7 decision=keep reason=gone",
            ),
            lines.filter { it.tag == "NotificationForwarder" }.map { it.msg },
        )
    }
}

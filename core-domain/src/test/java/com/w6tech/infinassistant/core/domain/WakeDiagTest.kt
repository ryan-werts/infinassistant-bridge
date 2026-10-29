package com.w6tech.infinassistant.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words for the watch's screen-wake telemetry (the decoding of the wire bytes is in HelloCompactMapTest). */
class WakeDiagTest {

    // 300 wakes, 60 by a notification, 20 stored, 1234 s; settings as PackWakeSettings packs raise + lower wrist,
    // notifications On, brightness Medium, 15 s: 0x14 | (3 << 8) | (15 << 11) = 0x7B14 (test_pack_settings).
    private val example = WakeDiag(300, 60, 20, 1234, 0x7B14)

    @Test
    fun `the example in words, with the share of uptime`() {
        assertEquals(
            "screen on 20 min since boot (5% of uptime), wakes 300 (by notification 60, stored quietly 20), " +
                "timeout 15 s, brightness Medium, Always On off, wake modes raise wrist + lower wrist, notifications On",
            // 1234 s of an uptime of 24 000 s.
            example.summaryLine(uptimeSec = 24_000),
        )
        assertEquals("[300, 60, 20, 1234, 0x7b14]", example.raw())
    }

    @Test
    fun `without an uptime the share is left out, and a short time reads in seconds`() {
        assertEquals(
            "screen on 45 s since boot, wakes 2 (by notification 0, stored quietly 0), timeout 0 s, " +
                "brightness Off, Always On off, wake modes none, notifications On",
            WakeDiag(2, 0, 0, 45, 0).summaryLine(uptimeSec = null),
        )
        // An uptime of zero gives no share either (no division by it).
        assertEquals("screen on 0 s since boot, wakes 0", WakeDiag(0, 0, 0, 0, 0).summaryLine(0).substringBefore(" ("))
    }

    @Test
    fun `a capped counter reads as at least the cap, and the share never passes 100`() {
        assertEquals(
            "screen on 1092+ min since boot (100% of uptime), wakes 65535+ (by notification 1, stored quietly 2), " +
                "timeout 30 s, brightness High, Always On on, " +
                "wake modes single tap + double tap + raise wrist + shake + lower wrist, notifications Sleep",
            WakeDiag(65535, 1, 2, 65535, 0xF4BF).summaryLine(uptimeSec = 3_600),
        )
    }

    @Test
    fun `a value the app does not know is named, not guessed`() {
        // Notification status 3 and brightness 7 are not in the firmware's enums.
        val odd = WakeDiag(0, 0, 0, 0, (3 shl 6) or (7 shl 8))
        assertEquals("status 3", odd.notificationStatusWords())
        assertEquals("level 7", odd.brightnessWords())
    }

    @Test
    fun `brightness code 5 reads as B, in every line that names the brightness`() {
        // Raise wrist + lower wrist, notifications On, brightness code 5, 15 s: 0x14 | (5 << 8) | (15 << 11) = 0x7D14.
        val b = WakeDiag(300, 60, 20, 1234, 0x7D14)
        assertEquals(5, b.brightness)
        assertEquals("B (button)", b.brightnessWords())
        assertEquals(
            "screen on 20 min since boot (5% of uptime), wakes 300 (by notification 60, stored quietly 20), " +
                "timeout 15 s, brightness B (button), Always On off, wake modes raise wrist + lower wrist, notifications On",
            b.summaryLine(uptimeSec = 24_000),
        )
        assertEquals(
            "wakes 300 (by notification 60, stored quietly 20), screen on 1234 s, timeout 15 s, " +
                "brightness B (button), Always On off, wake modes raise wrist + lower wrist, notifications On",
            b.decoded(),
        )
    }

    @Test
    fun `codes after B still decode safely`() {
        assertEquals("level 6", WakeDiag(0, 0, 0, 0, 6 shl 8).brightnessWords())
        assertEquals("level 7", WakeDiag(0, 0, 0, 0, 7 shl 8).brightnessWords())
    }

    @Test
    fun `only five integers of 16 bits make a reading`() {
        assertNull(WakeDiag.from(null))
        assertNull(WakeDiag.from(listOf(1, 2, 3, 4)))
        assertNull(WakeDiag.from(listOf(1, 2, 3, 4, 5, 6)))
        assertNull(WakeDiag.from(listOf(1, 2, 3, 4, 65536)))
        assertNull(WakeDiag.from(listOf(-1, 2, 3, 4, 5)))
        assertEquals(WakeDiag(1, 2, 3, 4, 5), WakeDiag.from(listOf(1, 2, 3, 4, 5)))
    }
}

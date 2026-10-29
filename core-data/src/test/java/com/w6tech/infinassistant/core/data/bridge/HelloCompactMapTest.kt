package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.domain.WakeDiag
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.HelloPayload
import com.w6tech.infinassistant.core.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The HELLO the watch really sends.
 *
 * At a 256-byte MTU the rich HELLO map never fits one frame, so the watch answers with its
 * compact map (`BridgeController::HandleHello`, the `SendFromBle` fallback). Up to firmware
 * 2.4.63 that map had no `features` word and no layout counters, so on hardware the phone
 * never saw the short-key bit. From 2.4.64 (`helloDiagRev` 4) it carries them.
 *
 * The fixtures are hand-encoded CBOR maps with the keys, order and value widths the firmware
 * writes, so the test covers the wire shape, including the 244-byte payload limit of a single
 * frame (256-byte frame buffer and MTU, less the ATT and frame headers).
 */
class HelloCompactMapTest {

    private lateinit var handler: ProtocolHandler

    private val fakeLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private val summaries = mutableListOf<String>()

    @Before
    fun setup() {
        summaries.clear()
        handler = ProtocolHandler(
            logger = fakeLogger,
            stateManager = StateManager(),
            logCallback = { _, type, _, summary -> if (type == "HELLO") summaries += summary },
        )
    }

    private fun ByteArrayOutputStream.head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> write(m or value.toInt())
            value <= 0xFF -> { write(m or 24); write(value.toInt()) }
            value <= 0xFFFF -> { write(m or 25); write((value shr 8).toInt()); write(value.toInt()) }
            else -> {
                write(m or 26)
                for (shift in intArrayOf(24, 16, 8, 0)) write((value shr shift).toInt())
            }
        }
    }

    private fun ByteArrayOutputStream.text(s: String) {
        head(3, s.length.toLong())
        write(s.toByteArray(Charsets.US_ASCII))
    }

    /** `kSupportedFeatures` of the firmware: bits 0 through 11 without VOICE_ASSIST (bit 4). */
    private val watchFeatures = 0xFEFL

    /** The 14-field compact map of firmware 2.4.64, in the firmware's order. */
    private fun compactMap264(
        fwVersion: String = "2.4.64",
        freeHeap: Long = 5744,
        minFreeHeap: Long = 5200,
        mallocFails: Long = 0,
        stackOverflows: Long = 0,
        uptimeTicks: Long = 3_600_000,
        lvCreateFailures: Long = 0,
        safeFaceTrips: Long = 0,
        layoutRefused: Long = 0,
        layoutWriteFailed: Long = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.head(5, 14)
        out.text("device"); out.text("watch")
        out.text("features"); out.head(0, watchFeatures)
        out.text("fwVersion"); out.text(fwVersion)
        out.text("bridgeProtocolRev"); out.head(0, 2)
        out.text("helloDiagRev"); out.head(0, 4)
        out.text("freeHeap"); out.head(0, freeHeap)
        out.text("minFreeHeap"); out.head(0, minFreeHeap)
        out.text("mallocFails"); out.head(0, mallocFails)
        out.text("stackOverflows"); out.head(0, stackOverflows)
        out.text("uptimeTicks"); out.head(0, uptimeTicks)
        out.text("lvCreateFailures"); out.head(0, lvCreateFailures)
        out.text("safeFaceTrips"); out.head(0, safeFaceTrips)
        out.text("layoutRefused"); out.head(0, layoutRefused)
        out.text("layoutWriteFailed"); out.head(0, layoutWriteFailed)
        return out.toByteArray()
    }

    /** The 12-field compact map of firmware 2.4.62 and 2.4.63: no feature word, no layout counters. */
    private fun compactMap263(): ByteArray {
        val out = ByteArrayOutputStream()
        out.head(5, 12)
        out.text("device"); out.text("watch")
        out.text("fwVersion"); out.text("2.4.63")
        out.text("bridgeProtocolRev"); out.head(0, 2)
        out.text("helloDiagRev"); out.head(0, 3)
        out.text("freeHeap"); out.head(0, 5744)
        out.text("minFreeHeap"); out.head(0, 5200)
        out.text("mallocFails"); out.head(0, 0)
        out.text("lvCreateFailures"); out.head(0, 0)
        out.text("safeFaceTrips"); out.head(0, 0)
        out.text("negotiatedMtu"); out.head(0, 256)
        out.text("oversizeNotifyDrops"); out.head(0, 0)
        out.text("helloEncodeTruncated"); out.write(0xF5)  // CBOR true
        return out.toByteArray()
    }

    private fun hello(payload: ByteArray): HelloPayload {
        val decoded = handler.handleHello(
            FrameCodec.CompleteMessage(
                version = FrameCodec.VERSION,
                type = Protocol.Type.HELLO.wire,
                flags = 0,
                msgId = 1,
                payload = payload,
            )
        )
        assertNotNull("HELLO did not decode", decoded)
        return decoded!!
    }

    @Test
    fun `the 2_4_64 compact map carries the feature word, so short keys need no inference`() {
        val h = hello(compactMap264())
        assertEquals(watchFeatures.toInt(), h.features)
        assertTrue(handler.peerSupports(Protocol.Features.SHORT_KEYS))
        assertTrue(handler.peerAcceptsShortKeys())
        assertEquals(4, h.helloDiagRev)
    }

    @Test
    fun `the 2_4_64 compact map carries the counters the hardware gate scores`() {
        val h = hello(compactMap264(mallocFails = 1, stackOverflows = 2, uptimeTicks = 123_456, layoutRefused = 3, layoutWriteFailed = 4))
        assertEquals(1, h.mallocFails)
        assertEquals(2, h.stackOverflows)
        assertEquals(123_456L, h.uptimeTicks)
        assertEquals(3L, h.layoutRefusedCount())
        assertEquals(4L, h.layoutWriteFailedCount())
        assertEquals(5744L, h.freeHeap)
        assertEquals(5200L, h.minFreeHeap)

        val summary = summaries.single()
        assertTrue(summary, summary.contains(" up=123456"))
        assertTrue(summary, summary.contains(" stackOverflows=2"))
        assertTrue(summary, summary.contains(" layoutRefused=3"))
        assertTrue(summary, summary.contains(" layoutWriteFailed=4"))
    }

    @Test
    fun `zero failed writes read as zero, not absent, at rev 4`() {
        val h = hello(compactMap264())
        assertEquals(0L, h.layoutWriteFailedCount())
        assertEquals(0L, h.layoutRefusedCount())
    }

    @Test
    fun `the failed-write count is absent below the revision that introduced it`() {
        val h = hello(compactMap263())
        assertNull(h.layoutWriteFailed)
        assertNull(h.layoutWriteFailedCount())
        assertNull(h.layoutRefusedCount())
    }

    @Test
    fun `the 2_4_63 compact map has no feature word and relies on the revision for short keys`() {
        val h = hello(compactMap263())
        assertEquals(0, h.features)
        assertFalse(handler.peerSupports(Protocol.Features.SHORT_KEYS))
        assertTrue(handler.peerAcceptsShortKeys())
    }

    @Test
    fun `the compact map fits one frame with every counter at its widest`() {
        // 256-byte frame buffer and MTU: 256 - 3 (ATT) - 9 (frame header) = 244 payload bytes.
        val typical = compactMap264()
        val widest = compactMap264(
            fwVersion = "2.4.100",
            freeHeap = 65_535,
            minFreeHeap = 65_535,
            mallocFails = Int.MAX_VALUE.toLong(),
            stackOverflows = Int.MAX_VALUE.toLong(),
            uptimeTicks = 0xFFFF_FFFFL,
            lvCreateFailures = 65_535,
            safeFaceTrips = Int.MAX_VALUE.toLong(),
            layoutRefused = 0xFFFF_FFFFL,
            layoutWriteFailed = 0xFFFF_FFFFL,
        )
        assertTrue("typical ${typical.size} B", typical.size <= 244)
        assertTrue("widest ${widest.size} B", widest.size <= 244)
        assertNotNull(hello(widest))
    }
    // --- firmware 2.4.104: the optional "wk" key (screen-wake telemetry) ---

    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /**
     * The 2.4.104 compact map: the fields of 2.4.64 with helloDiagRev 5 and `enc`, then [wkEntry]
     * (the key and its array, exactly as the firmware encodes them) when the watch had room for it.
     * 15 keys without it, 16 with it.
     */
    private fun compactMap2104(wkEntry: ByteArray?, uptimeTicks: Long = 3_600_000, diagRev: Long = 5): ByteArray {
        val out = ByteArrayOutputStream()
        out.head(5, if (wkEntry != null) 16 else 15)
        out.text("device"); out.text("watch")
        out.text("features"); out.head(0, watchFeatures)
        out.text("fwVersion"); out.text("2.4.104")
        out.text("bridgeProtocolRev"); out.head(0, 2)
        out.text("helloDiagRev"); out.head(0, diagRev)
        out.text("freeHeap"); out.head(0, 5744)
        out.text("minFreeHeap"); out.head(0, 5200)
        out.text("mallocFails"); out.head(0, 0)
        out.text("stackOverflows"); out.head(0, 0)
        out.text("uptimeTicks"); out.head(0, uptimeTicks)
        out.text("lvCreateFailures"); out.head(0, 0)
        out.text("safeFaceTrips"); out.head(0, 0)
        out.text("layoutRefused"); out.head(0, 0)
        out.text("layoutWriteFailed"); out.head(0, 0)
        out.text("enc"); out.write(0xF5)
        if (wkEntry != null) out.write(wkEntry)
        return out.toByteArray()
    }

    /** The firmware's own example (docs/reference/ble-protocol-spec.md; test_wk_bytes in test_wake_telemetry.cpp): a map holding wk. */
    private val firmwareExample = hex("A1 62 77 6B 85 19 01 2C 18 3C 14 19 04 D2 19 78 14")

    /** The key and array of the example, without its one-entry map header. */
    private val firmwareExampleEntry = firmwareExample.copyOfRange(1, firmwareExample.size)

    /**
     * What EncodeWakeDiag writes (WakeDiag.h), line by line: "wk", an array of 5, each of the four
     * counters capped at 65535 (WakeCap16), then the packed settings.
     */
    private fun firmwareEncode(wakes: Long, notifyWakes: Long, notifyStored: Long, screenOnSec: Long, settings: Long): ByteArray {
        fun cap16(v: Long) = if (v > 0xFFFF) 0xFFFFL else v
        val out = ByteArrayOutputStream()
        out.text("wk")
        out.head(4, 5)
        for (v in listOf(cap16(wakes), cap16(notifyWakes), cap16(notifyStored), cap16(screenOnSec), cap16(settings))) out.head(0, v)
        return out.toByteArray()
    }

    /** PackWakeSettings (WakeTelemetry.h): wake modes 0-4, Always On 5, notification status 6-7, brightness 8-10, timeout seconds 11-15. */
    private fun firmwarePack(wakeModes: Int, alwaysOn: Boolean, notifyStatus: Int, brightness: Int, timeoutMs: Int): Long {
        val secs = minOf(31, (timeoutMs + 500) / 1000)
        return ((wakeModes and 0x1F) or ((if (alwaysOn) 1 else 0) shl 5) or ((notifyStatus and 3) shl 6) or
            ((brightness and 7) shl 8) or (secs shl 11)).toLong()
    }

    @Test
    fun `the firmware's own wk example decodes to its documented values`() {
        assertEquals(17, firmwareExample.size)
        val h = hello(compactMap2104(firmwareExampleEntry))
        assertEquals(5, h.helloDiagRev)
        val wk = h.wakeDiag()!!
        assertEquals(WakeDiag(300, 60, 20, 1234, 0x7814), wk)
        // 0x7814 by the layout of PackWakeSettings: raise and lower wrist wake the screen, Always On off,
        // notifications On, timeout 15 s, and brightness bits 8-10 = 0, which is Off. The spec's prose for
        // this example says "brightness Medium", but Medium (3) packs as 0x7B14; the bytes are what count.
        assertEquals(0x14, wk.wakeModes)
        assertEquals("raise wrist + lower wrist", wk.wakeModeWords())
        assertFalse(wk.alwaysOn)
        assertEquals("On", wk.notificationStatusWords())
        assertEquals("Off", wk.brightnessWords())
        assertEquals(15, wk.timeoutSec)
    }

    @Test
    fun `settings packed with brightness Medium decode as Medium`() {
        val settings = firmwarePack(wakeModes = 0x14, alwaysOn = false, notifyStatus = 0, brightness = 3, timeoutMs = 15_000)
        assertEquals(0x7B14L, settings)   // test_pack_settings: 0x14 | (3 << 8) | (15 << 11)
        val wk = hello(compactMap2104(firmwareEncode(300, 60, 20, 1234, settings))).wakeDiag()!!
        assertEquals("Medium", wk.brightnessWords())
        assertEquals(15, wk.timeoutSec)
    }

    @Test
    fun `wk built the way WakeDiag h encodes it decodes, caps and all`() {
        val settings = firmwarePack(wakeModes = 0x1F, alwaysOn = true, notifyStatus = 2, brightness = 4, timeoutMs = 30_000)
        assertEquals(0xF4BFL, settings)
        val entry = firmwareEncode(wakes = 70_000, notifyWakes = 0, notifyStored = 24, screenOnSec = 100_000, settings = settings)
        // The bytes the C++ writes: "wk", array(5), 65535 (capped), 0, 24, 65535 (capped), 0xF4BF.
        assertEquals(hex("62 77 6B 85 19 FF FF 00 18 18 19 FF FF 19 F4 BF").toList(), entry.toList())
        val wk = hello(compactMap2104(entry)).wakeDiag()!!
        assertEquals(WakeDiag(65535, 0, 24, 65535, 0xF4BF), wk)
        assertEquals("single tap + double tap + raise wrist + shake + lower wrist", wk.wakeModeWords())
        assertTrue(wk.alwaysOn)
        assertEquals("Sleep", wk.notificationStatusWords())
        assertEquals("High", wk.brightnessWords())
        assertEquals(30, wk.timeoutSec)
    }

    @Test
    fun `a HELLO without wk reads as not reported, never zero`() {
        val h = hello(compactMap2104(null))
        assertEquals(5, h.helloDiagRev)
        assertNull(h.wakeDiag())
        // A watch below the revision that introduced the key does not report it, whatever it carries.
        assertNull(hello(compactMap2104(firmwareExampleEntry, diagRev = 4)).wakeDiag())
        assertNull(hello(compactMap264()).wakeDiag())
    }

    @Test
    fun `a wk array that is not five 16-bit integers is not reported`() {
        for (bad in listOf(
            hex("62 77 6B 84 01 02 03 04"),                      // four items
            hex("62 77 6B 86 01 02 03 04 05 06"),                // six items
            hex("62 77 6B 85 01 02 03 04 1A 00 01 00 00"),       // a settings word above 16 bits
        )) {
            assertNull(hello(compactMap2104(bad)).wakeDiag())
        }
    }

    @Test
    fun `the HELLO line in the log carries wk and the uptime`() {
        hello(compactMap2104(firmwareExampleEntry, uptimeTicks = 7_372_800))
        val summary = summaries.single()
        assertTrue(summary, summary.contains(" wk=300,60,20,1234,30740"))
        assertTrue(summary, summary.contains(" up=7372800"))
        summaries.clear()
        hello(compactMap2104(null))
        assertFalse(summaries.single(), summaries.single().contains("wk="))
    }

    @Test
    fun `the 2_4_104 HELLO with wk fits the 244-byte frame`() {
        val withWk = compactMap2104(firmwareExampleEntry, uptimeTicks = 0xFFFF_FFFFL)
        assertTrue("${withWk.size} bytes", withWk.size <= 244)
    }
}

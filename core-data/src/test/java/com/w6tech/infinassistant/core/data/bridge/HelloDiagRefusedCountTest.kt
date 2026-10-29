package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.HelloPayload
import com.w6tech.infinassistant.core.domain.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * REQ009-OBS-1: the watch reports how many layout pushes it refused as
 * `layoutRefused` on the HELLO diag map, from `helloDiagRev` 3.
 *
 * The fixtures are hand-encoded CBOR maps in the order and with the key names the
 * firmware writes (`BridgeController::HandleHello`), so the test covers the wire
 * spelling and not only the Kotlin property name. The count must read as absent,
 * not zero, from firmware that predates it: zero refusals is a result, a missing
 * field is not.
 */
class HelloDiagRefusedCountTest {

    private lateinit var handler: ProtocolHandler

    private val fakeLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    @Before
    fun setup() {
        handler = ProtocolHandler(
            logger = fakeLogger,
            stateManager = StateManager(),
            logCallback = { _, _, _, _ -> },
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

    /** A watch HELLO map: `fields` are (key, unsigned value) pairs after the device key. */
    private fun watchHello(vararg fields: Pair<String, Long>): FrameCodec.CompleteMessage {
        val out = ByteArrayOutputStream()
        out.head(5, (fields.size + 2).toLong())
        out.text("device"); out.text("watch")
        out.text("fwVersion"); out.text("2.4.62")
        for ((k, v) in fields) { out.text(k); out.head(0, v) }
        return FrameCodec.CompleteMessage(
            version = FrameCodec.VERSION,
            type = Protocol.Type.HELLO.wire,
            flags = 0,
            msgId = 1,
            payload = out.toByteArray(),
        )
    }

    private fun decode(m: FrameCodec.CompleteMessage): HelloPayload {
        val hello = handler.handleHello(m)
        assertNotNull("HELLO did not decode", hello)
        return hello!!
    }

    @Test
    fun `rev 3 HELLO decodes the refused count`() {
        val hello = decode(watchHello("helloDiagRev" to 3, "notifyFailures" to 0, "layoutRefused" to 7))
        assertEquals(7L, hello.layoutRefused)
        assertEquals(7L, hello.layoutRefusedCount())
    }

    @Test
    fun `rev 3 HELLO with no refusals reports zero, not absent`() {
        val hello = decode(watchHello("helloDiagRev" to 3, "layoutRefused" to 0))
        assertEquals(0L, hello.layoutRefusedCount())
    }

    @Test
    fun `a count past 16 bits survives the 32-bit CBOR encoding`() {
        val hello = decode(watchHello("helloDiagRev" to 3, "layoutRefused" to 70_000))
        assertEquals(70_000L, hello.layoutRefusedCount())
    }

    @Test
    fun `rev 2 HELLO leaves the count absent, not zero`() {
        val hello = decode(watchHello("helloDiagRev" to 2, "notifyFailures" to 0))
        assertNull(hello.layoutRefused)
        assertNull(hello.layoutRefusedCount())
    }

    @Test
    fun `a HELLO with no helloDiagRev leaves the count absent`() {
        val hello = decode(watchHello("freeHeap" to 12_000))
        assertNull(hello.layoutRefusedCount())
    }

    @Test
    fun `the field is ignored below the revision that introduced it`() {
        val hello = decode(watchHello("helloDiagRev" to 2, "layoutRefused" to 9))
        assertEquals(9L, hello.layoutRefused)
        assertNull("gated on helloDiagRev >= 3", hello.layoutRefusedCount())
    }
}

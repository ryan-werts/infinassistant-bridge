package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.domain.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The CRASH_DUMP_REQUEST exchange with firmware 2.4.65+ (feature bit CRASH_REPORT), and the
 * FIND_WATCH send path. The watch's replies are built byte for byte the way the firmware's
 * TinyCBOR encoder writes them (BridgeController::HandleCrashDumpRequest): definite-length
 * maps, shortest-form integers, keys in that order.
 */
class CrashReportTest {

    private val logs = mutableListOf<String>()
    private val silentLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private lateinit var handler: ProtocolHandler

    @Before
    fun setup() {
        logs.clear()
        handler = ProtocolHandler(
            logger = silentLogger,
            stateManager = StateManager(),
            logCallback = { _, type, _, summary -> logs += "$type $summary" },
        )
    }

    // ── The firmware's bytes ──

    private class Cbor {
        val out = ByteArrayOutputStream()
        private fun head(major: Int, value: Long) {
            val m = major shl 5
            when {
                value < 24 -> out.write(m or value.toInt())
                value < 0x100 -> { out.write(m or 24); out.write(value.toInt()) }
                value < 0x10000 -> { out.write(m or 25); out.write((value shr 8).toInt() and 0xFF); out.write(value.toInt() and 0xFF) }
                else -> { out.write(m or 26); for (s in intArrayOf(24, 16, 8, 0)) out.write((value shr s).toInt() and 0xFF) }
            }
        }
        fun map(size: Int) = apply { head(5, size.toLong()) }
        fun text(s: String) = apply { val b = s.toByteArray(); head(3, b.size.toLong()); out.write(b) }
        fun uint(v: Long) = apply { head(0, v) }
        fun bool(v: Boolean) = apply { out.write(if (v) 0xF5 else 0xF4) }
        fun array(size: Int) = apply { head(4, size.toLong()) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    /** What 2.4.65 sends when the previous boot left a context. */
    private val contextReply = Cbor().map(7)
        .text("resetReason").text("Watchdog")
        .text("slotIndex").uint(0)
        .text("breadcrumb").uint(6)
        .text("lastMsgType").uint(0x0E)
        .text("lastMsgId").uint(812)
        .text("freeHeap").uint(4012)
        .text("uptimeTicks").uint(81234)
        .bytes()

    /** What it sends when there is none, or after CRASH_DUMP_ACK. */
    private val emptyReply = Cbor().map(2)
        .text("resetReason").text("Soft reset")
        .text("empty").bool(true)
        .bytes()

    @Test
    fun `a reply with a context decodes from the firmware's bytes`() {
        val report = ProtocolCbor.decode(CrashDumpPayload.serializer(), contextReply)
        assertEquals("Watchdog", report.resetReason)
        val ctx = assertNotNullAndGet(report.crashContext)
        assertEquals(6, ctx.breadcrumb)
        assertEquals(0x0E, ctx.lastMsgType)
        assertEquals(812, ctx.lastMsgId)
        assertEquals(4012L, ctx.freeHeap)
        assertEquals(81234L, ctx.uptimeTicks)
        assertEquals("WATCHFACE_APPLY msg=0xe id=812 heap=4012 up=81234", handler.crashContextSummary(ctx))
    }

    @Test
    fun `an empty reply decodes with the reset reason and no context`() {
        val report = ProtocolCbor.decode(CrashDumpPayload.serializer(), emptyReply)
        assertEquals("Soft reset", report.resetReason)
        assertNull(report.crashContext)
    }

    // ── The exchange ──

    /** A transport that plays the watch: answers CRASH_DUMP_REQUEST with [reply], or stays silent. */
    private class FakeWatch(private val reply: ByteArray?) {
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        private val waiters = mutableListOf<Pair<(FrameCodec.CompleteMessage) -> Boolean, CompletableDeferred<FrameCodec.CompleteMessage>>>()
        val transport: BleTransport = mockk()

        init {
            every { transport.expect(any()) } answers {
                val predicate = firstArg<(FrameCodec.CompleteMessage) -> Boolean>()
                CompletableDeferred<FrameCodec.CompleteMessage>().also { waiters += predicate to it }
            }
            every { transport.cancelExpect(any()) } answers {
                val d = firstArg<CompletableDeferred<FrameCodec.CompleteMessage>>()
                waiters.removeAll { it.second === d }
                Unit
            }
            coEvery { transport.send(any(), any(), any(), any(), any()) } coAnswers {
                val type = firstArg<Int>()
                sent += type to arg<ByteArray>(3)
                if (type == Protocol.Type.CRASH_DUMP_REQUEST.wire && reply != null) {
                    val m = FrameCodec.CompleteMessage(1, type, 0, 4242, reply)
                    waiters.firstOrNull { it.first(m) && !it.second.isCompleted }?.second?.complete(m)
                }
                AppResult.Ok(Unit)
            }
        }

        val types get() = sent.map { it.first }
    }

    private fun helloWithFeatures(features: Int) {
        val hello = ProtocolCbor.encode(HelloPayload.serializer(), HelloPayload(device = "watch", features = features))
        handler.handleHello(FrameCodec.CompleteMessage(1, Protocol.Type.HELLO.wire, 0, 1, hello))
    }

    @Test
    fun `a delivered context is confirmed with CRASH_DUMP_ACK`() = runTest {
        helloWithFeatures(0x1FEF)
        val watch = FakeWatch(contextReply)

        val report = handler.requestCrashReport(watch.transport)

        assertEquals("Watchdog", report?.resetReason)
        assertEquals(6, report?.crashContext?.breadcrumb)
        assertEquals(listOf(Protocol.Type.CRASH_DUMP_REQUEST.wire, Protocol.Type.CRASH_DUMP_ACK.wire), watch.types)
        assertTrue(logs.any { it.startsWith("CRASH_REPORT reset=Watchdog context=WATCHFACE_APPLY") })
    }

    @Test
    fun `an empty reply is not confirmed`() = runTest {
        helloWithFeatures(0x1FEF)
        val watch = FakeWatch(emptyReply)

        val report = handler.requestCrashReport(watch.transport)

        assertEquals("Soft reset", report?.resetReason)
        assertNull(report?.crashContext)
        assertEquals(listOf(Protocol.Type.CRASH_DUMP_REQUEST.wire), watch.types)
        assertTrue(logs.any { it == "CRASH_REPORT reset=Soft reset context=none" })
    }

    @Test
    fun `nothing is sent to a watch without the feature bit`() = runTest {
        helloWithFeatures(0xFEF)   // 2.4.64
        val watch = FakeWatch(contextReply)

        assertNull(handler.requestCrashReport(watch.transport))
        assertTrue(watch.sent.isEmpty())
    }

    @Test
    fun `a silent watch times out and nothing is confirmed`() = runTest {
        helloWithFeatures(0x1FEF)
        val watch = FakeWatch(reply = null)

        assertNull(handler.requestCrashReport(watch.transport))
        assertEquals(listOf(Protocol.Type.CRASH_DUMP_REQUEST.wire), watch.types)
    }

    // ── FIND_WATCH ──

    @Test
    fun `FIND_WATCH encodes through the send path`() = runTest {
        // Before 2.1.74 encodePayload had no branch for FindWatchPayload and threw, so the
        // command never reached the watch.
        val watch = FakeWatch(reply = null)

        handler.sendFindWatch(watch.transport, durationSeconds = 30)

        val (type, bytes) = watch.sent.single()
        assertEquals(Protocol.Type.FIND_WATCH.wire, type)
        assertEquals(30, ProtocolCbor.decode(FindWatchPayload.serializer(), bytes).duration)
    }

    // ── The black box's record (2.4.90) ──

    /** What 2.4.90 sends after a HardFault: no bridge context worth the name, the record in "box". */
    private val boxReply = Cbor().map(3)
        .text("resetReason").text("Watchdog")
        .text("empty").bool(true)
        .text("box").map(10)
        .text("k").uint(1)
        .text("c").uint(1)
        .text("s").uint(12)
        .text("u").uint(2560)
        .text("pc").uint(0x0003E61E)
        .text("lr").uint(0x00041109)
        .text("cf").uint(0x00008200)
        .text("ad").uint(0x200106B4)
        .text("h").uint(7736)
        .text("cl").array(2).uint(0x0003EAC9).uint(0x0003E8F5)
        .bytes()

    @Test
    fun `the black box's record decodes and reads as a crash`() {
        val report = ProtocolCbor.decode(CrashDumpPayload.serializer(), boxReply)
        assertNull(report.crashContext)
        val box = assertNotNullAndGet(report.box)
        assertEquals(1, box.kind)
        assertEquals(0x200106B4L, box.address)
        assertEquals(listOf(0x0003EAC9L, 0x0003E8F5L), box.calls)
        assertEquals(
            "FAULT in display at 2.5s stage=12 pc=0003E61E lr=00041109 cfsr=00008200 addr=200106B4 minHeap=7736 calls=0003EAC9,0003E8F5",
            handler.crashSummary(report),
        )
    }

    @Test
    fun `a record alone is confirmed with CRASH_DUMP_ACK`() = runTest {
        helloWithFeatures(0x1FEF)
        val watch = FakeWatch(boxReply)

        val report = handler.requestCrashReport(watch.transport)

        assertEquals(1, report?.box?.kind)
        assertEquals(listOf(Protocol.Type.CRASH_DUMP_REQUEST.wire, Protocol.Type.CRASH_DUMP_ACK.wire), watch.types)
        assertTrue(logs.any { it.contains("crash=FAULT in display") })
    }

    @Test
    fun `a soft reset's context is the last activity, not a crash`() {
        // An update or a reboot from settings: the bridge's breadcrumb is all the watch has.
        val softReset = Cbor().map(7)
            .text("resetReason").text("Soft reset")
            .text("slotIndex").uint(0)
            .text("breadcrumb").uint(8)
            .text("lastMsgType").uint(0x0C)
            .text("lastMsgId").uint(0)
            .text("freeHeap").uint(4920)
            .text("uptimeTicks").uint(54498689)
            .bytes()
        val report = ProtocolCbor.decode(CrashDumpPayload.serializer(), softReset)
        assertNotNull(report.crashContext)
        assertNull(handler.crashSummary(report))
    }

    @Test
    fun `before 2_4_90 a watchdog reset's context still counts as a crash`() {
        val report = ProtocolCbor.decode(CrashDumpPayload.serializer(), contextReply)
        assertEquals("WATCHFACE_APPLY msg=0xe id=812 heap=4012 up=81234", handler.crashSummary(report))
    }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}

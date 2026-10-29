package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator.WatchFaceLayoutResult
import com.w6tech.infinassistant.core.domain.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * REQ009-FR-C3 / FR-C4 / NFR-7: WATCHFACE_LAYOUT through the real send path.
 *
 * Drives `WatchFaceOrchestrator.handleWatchFaceLayoutCommand` against a fake
 * transport that plays the watch: it answers the refresh HELLO (or stays silent)
 * and acks the layout. The key spelling comes from the live `peerFeatures` read
 * after that refresh, and the bytes that are preflighted are the bytes sent.
 */
class WatchFaceSendPathTest {

    private val logs = mutableListOf<String>()
    private val fakeLogger = object : Logger {
        override fun d(tag: String, msg: String, tr: Throwable?) {}
        override fun i(tag: String, msg: String, tr: Throwable?) {}
        override fun w(tag: String, msg: String, tr: Throwable?) {}
        override fun e(tag: String, msg: String, tr: Throwable?) {}
    }

    private data class Sent(val type: Int, val msgId: Int, val payload: ByteArray)

    /** A transport that plays the watch. */
    private inner class FakeWatch {
        val sent = mutableListOf<Sent>()
        private val waiters = mutableListOf<Pair<(FrameCodec.CompleteMessage) -> Boolean, CompletableDeferred<FrameCodec.CompleteMessage>>>()

        /** Payload of the watch HELLO answering a phone HELLO, or null for no answer. */
        var helloReply: ByteArray? = null
        var ackLayout = true
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
                val msgId = thirdArg<Int>()
                sent += Sent(type, msgId, arg(3))
                when (type) {
                    Protocol.Type.HELLO.wire -> helloReply?.let { deliver(FrameCodec.CompleteMessage(1, type, 0, 9000, it)) }
                    Protocol.Type.WATCHFACE_LAYOUT.wire -> if (ackLayout) {
                        val ack = ProtocolCbor.encode(WatchFaceAckPayload.serializer(), WatchFaceAckPayload(ok = true, appliedVersion = 1))
                        deliver(FrameCodec.CompleteMessage(1, Protocol.Type.WATCHFACE_ACK.wire, 0, msgId, ack))
                    }
                }
                AppResult.Ok(Unit)
            }
        }

        private fun deliver(m: FrameCodec.CompleteMessage) {
            val hit = waiters.firstOrNull { it.first(m) && !it.second.isCompleted } ?: return
            hit.second.complete(m)
        }

        val layoutFrames get() = sent.filter { it.type == Protocol.Type.WATCHFACE_LAYOUT.wire }
        val helloFrames get() = sent.filter { it.type == Protocol.Type.HELLO.wire }
    }

    private lateinit var protocolHandler: ProtocolHandler
    private lateinit var orchestrator: WatchFaceOrchestrator
    private lateinit var watch: FakeWatch

    @Before
    fun setup() {
        logs.clear()
        watch = FakeWatch()
        val stateManager = StateManager()
        protocolHandler = ProtocolHandler(
            logger = fakeLogger,
            stateManager = stateManager,
            logCallback = { _, _, _, _ -> },
        )
        orchestrator = WatchFaceOrchestrator(
            logger = fakeLogger,
            haApi = mockk(relaxed = true),
            settingsRepo = mockk(relaxed = true),
            protocolHandler = protocolHandler,
            stateManager = stateManager,
            eventBroker = EventBroker(
                logger = fakeLogger,
                stateManager = stateManager,
                protocolHandler = protocolHandler,
                logCallback = { _, _, _, _ -> },
            ),
            logCallback = { dir, type, _, summary -> logs.add("$dir|$type|$summary") },
        )
    }

    // ---- helpers -----------------------------------------------------------------

    /**
     * A layout the orchestrator sends unchanged: already stripped of the fields the watch
     * never renders (WatchFaceWire), with nothing else for its strip step to remove (no
     * logbook, no phone-side config) and nothing for HA enrichment to look up, so the
     * encoded bytes can be predicted from this object alone.
     */
    private fun layout() = WatchFaceWire.stripNonRendered(WatchFaceLayoutPayload(
        version = 1,
        theme = WatchTheme(background = "#000000", accent = "#FF8800"),
        header = WatchHeader(timeFormat = "HH:mm", timeAlign = "center", presenceIcons = emptyList()),
        screens = listOf(
            WatchScreen(
                screenType = 0,
                dashboard = WatchDashboard(
                    statusGroups = listOf(WatchStatusGroup(type = "doors", glyphs = listOf(WatchGlyph(symbol = "D", entityId = "", active = true)))),
                    sensorGroups = listOf(WatchSensorGroup(type = "thermostats", sensors = listOf(WatchSensor(value = 71)))),
                    events = listOf(WatchEvent(time = "09:30", title = "Standup", startEpoch = 1_790_000_000L)),
                ),
            ),
            WatchScreen(screenType = 1, tiles = listOf(WatchTile(label = "Lamp", color = "#FFFFFF"))),
        ),
        statusBar = WatchStatusBar(battery = true, steps = false, heartRate = true, notifications = false, bluetooth = true),
    ))

    private fun longBytes() = ProtocolCbor.encodeWatchFaceLayout(layout(), shortKeys = false).bytes
    private fun shortBytes() = ProtocolCbor.encodeWatchFaceLayout(layout(), shortKeys = true).bytes

    private fun hello(features: Int, freeHeap: Long? = 20_000L): ByteArray =
        ProtocolCbor.encode(
            HelloPayload.serializer(),
            HelloPayload(device = "watch", features = features, freeHeap = freeHeap, helloDiagRev = 2),
        )

    /** Apply a HELLO to the handler the way the connect handshake does. */
    private fun connectHello(features: Int, freeHeap: Long? = 20_000L) {
        protocolHandler.handleHello(FrameCodec.CompleteMessage(1, Protocol.Type.HELLO.wire, 0, 1, hello(features, freeHeap)))
    }

    /** The watch answered with an empty HELLO (a reset watch): features go back to 0, heap is kept. */
    private fun resetHello() {
        protocolHandler.handleHello(FrameCodec.CompleteMessage(1, Protocol.Type.HELLO.wire, 0, 2, ByteArray(0)))
    }

    private suspend fun push(peerFeaturesParameter: Int = 0, payload: WatchFaceLayoutPayload = layout()): WatchFaceLayoutResult =
        orchestrator.handleWatchFaceLayoutCommand(
            payload = payload,
            ha = mockk(relaxed = true),
            transport = watch.transport,
            onProgress = null,
            peerFeatures = peerFeaturesParameter,
            peerHelloDiagRev = 2,
        )

    private val featuresWithoutShortKeys = Protocol.Features.WATCHFACE_LAYOUT or Protocol.Features.WATCHFACE_ACK or Protocol.Features.NOTIFY_DISMISS_SNOOZE
    private val featuresWithShortKeys = featuresWithoutShortKeys or Protocol.Features.SHORT_KEYS

    private fun assertSentLayout(expected: ByteArray) {
        assertEquals("exactly one layout frame", 1, watch.layoutFrames.size)
        assertArrayEquals(expected, watch.layoutFrames.single().payload)
    }

    // ---- the fixtures differ, so the assertions below can tell the spellings apart --

    @Test
    fun `the two spellings of the test layout differ in size`() {
        assertTrue(shortBytes().size < longBytes().size)
    }

    // ---- FR-C3: the four cases ---------------------------------------------------

    @Test
    fun `refresh HELLO with bit 11 set sends short keys`() = runTest {
        watch.helloReply = hello(featuresWithShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(shortBytes())
    }

    @Test
    fun `refresh HELLO without bit 11 sends long keys`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(longBytes())
    }

    @Test
    fun `refresh HELLO with features 0 sends long keys`() = runTest {
        watch.helloReply = hello(0)
        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(longBytes())
    }

    @Test
    fun `no HELLO ever received sends long keys`() = runTest {
        watch.helloReply = null
        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(longBytes())
    }

    // ---- refresh outcomes --------------------------------------------------------

    @Test
    fun `a refresh that clears bit 11 sends long keys`() = runTest {
        connectHello(featuresWithShortKeys)
        watch.helloReply = hello(featuresWithoutShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(longBytes())
    }

    @Test
    fun `a refresh timeout after a reset sends long keys against the retained heap`() = runTest {
        // Connect-time HELLO: bit 11 and a heap reading. Then the watch resets and answers
        // with an empty HELLO (features 0, heap kept), and the refresh HELLO gets no answer.
        val retainedHeap = longBytes().size + WatchFaceOrchestrator.FW_REASSEMBLY_HEADROOM_BYTES.toLong()
        connectHello(featuresWithShortKeys, freeHeap = retainedHeap)
        resetHello()
        assertEquals(retainedHeap, protocolHandler.peerFreeHeap)
        watch.helloReply = null

        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(longBytes())
    }

    @Test
    fun `a refresh timeout after a reset still refuses on the retained heap`() = runTest {
        val retainedHeap = longBytes().size + WatchFaceOrchestrator.FW_REASSEMBLY_HEADROOM_BYTES.toLong() - 1
        connectHello(featuresWithShortKeys, freeHeap = retainedHeap)
        resetHello()
        watch.helloReply = null

        val result = push()
        assertTrue(result is WatchFaceLayoutResult.PreflightFailed)
        result as WatchFaceLayoutResult.PreflightFailed
        assertEquals("freeHeap", result.offendingField)
        assertTrue(result.reason, result.reason.contains("needs ${longBytes().size} B"))
        assertTrue("nothing sent", watch.layoutFrames.isEmpty())
    }

    @Test
    fun `the refresh HELLO goes out before the layout`() = runTest {
        watch.helloReply = hello(featuresWithShortKeys)
        push()
        assertEquals(listOf(Protocol.Type.HELLO.wire, Protocol.Type.WATCHFACE_LAYOUT.wire), watch.sent.map { it.type })
    }

    // ---- mid-command bit flip: the live value wins -------------------------------

    @Test
    fun `a stale parameter without bit 11 loses to a refresh that sets it`() = runTest {
        watch.helloReply = hello(featuresWithShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push(peerFeaturesParameter = featuresWithoutShortKeys))
        assertSentLayout(shortBytes())
    }

    @Test
    fun `a stale parameter with bit 11 loses to a refresh that clears it`() = runTest {
        connectHello(featuresWithShortKeys)
        watch.helloReply = hello(featuresWithoutShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push(peerFeaturesParameter = featuresWithShortKeys))
        assertSentLayout(longBytes())
    }

    // ---- FR-C4: the preflighted count is the sent size ---------------------------

    private suspend fun assertFitBoundary(featuresInRefresh: Int, expected: ByteArray) {
        val size = expected.size
        val headroom = WatchFaceOrchestrator.FW_REASSEMBLY_HEADROOM_BYTES

        // size + H passes and sends exactly `size` bytes.
        watch.helloReply = hello(featuresInRefresh, freeHeap = (size + headroom).toLong())
        assertEquals(WatchFaceLayoutResult.Success, push())
        assertSentLayout(expected)
        assertEquals(size, watch.layoutFrames.single().payload.size)

        // One byte less is refused, names the same size, and sends no layout frame.
        setup()
        watch.helloReply = hello(featuresInRefresh, freeHeap = (size + headroom - 1).toLong())
        val result = push()
        assertTrue("expected PreflightFailed but got $result", result is WatchFaceLayoutResult.PreflightFailed)
        result as WatchFaceLayoutResult.PreflightFailed
        assertEquals("freeHeap", result.offendingField)
        assertTrue(result.reason, result.reason.contains("needs $size B plus $headroom B"))
        assertTrue("nothing sent", watch.layoutFrames.isEmpty())
        assertEquals("only the refresh HELLO was sent", 1, watch.sent.size)
    }

    @Test
    fun `long spelling preflight fit is size plus headroom`() = runTest {
        assertFitBoundary(featuresWithoutShortKeys, longBytes())
    }

    @Test
    fun `short spelling preflight fit is size plus headroom`() = runTest {
        assertFitBoundary(featuresWithShortKeys, shortBytes())
    }

    @Test
    fun `the byte cap is judged on the spelling that is sent`() = runTest {
        // Pad one tile label until the long spelling is just over MAX_REASSEMBLY_SIZE. The short
        // spelling of the same layout is smaller, so it fits the cap and the long one does not.
        fun padded(n: Int): WatchFaceLayoutPayload {
            val base = layout()
            return base.copy(screens = base.screens.map { s ->
                if (s.tiles.isEmpty()) s else s.copy(tiles = listOf(WatchTile(label = "X".repeat(n), color = "#FFFFFF")))
            })
        }
        var n = 0
        while (ProtocolCbor.encodeWatchFaceLayout(padded(n), shortKeys = false).bytes.size <= FirmwareLimits.MAX_REASSEMBLY_SIZE) n++
        val payload = padded(n)
        val longSize = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = false).bytes.size
        val shortBytes = ProtocolCbor.encodeWatchFaceLayout(payload, shortKeys = true).bytes
        assertTrue("long is over the cap", longSize > FirmwareLimits.MAX_REASSEMBLY_SIZE)
        assertTrue("short fits the cap", shortBytes.size <= FirmwareLimits.MAX_REASSEMBLY_SIZE)

        watch.helloReply = hello(featuresWithShortKeys, freeHeap = 100_000L)
        assertEquals(WatchFaceLayoutResult.Success, push(payload = payload))
        assertSentLayout(shortBytes)

        setup()
        watch.helloReply = hello(featuresWithoutShortKeys, freeHeap = 100_000L)
        val refused = push(payload = payload)
        assertTrue("expected PreflightFailed but got $refused", refused is WatchFaceLayoutResult.PreflightFailed)
        refused as WatchFaceLayoutResult.PreflightFailed
        assertEquals("blePayload", refused.offendingField)
        assertTrue(refused.reason, refused.reason.contains("byteSize $longSize"))
        assertTrue("nothing sent", watch.layoutFrames.isEmpty())
    }

    @Test
    fun `an over-cap payload is refused before any layout frame`() = runTest {
        val oversized = layout().copy(
            screens = layout().screens.map { s ->
                s.copy(tiles = listOf(WatchTile(label = "X".repeat(FirmwareLimits.MAX_REASSEMBLY_SIZE), color = "#FFFFFF")))
            },
        )
        watch.helloReply = hello(featuresWithShortKeys, freeHeap = 100_000L)
        val result = orchestrator.handleWatchFaceLayoutCommand(
            payload = oversized,
            ha = mockk(relaxed = true),
            transport = watch.transport,
            peerHelloDiagRev = 2,
        )
        assertTrue("expected PreflightFailed but got $result", result is WatchFaceLayoutResult.PreflightFailed)
        assertEquals("blePayload", (result as WatchFaceLayoutResult.PreflightFailed).offendingField)
        assertTrue(watch.layoutFrames.isEmpty())
    }

    @Test
    fun `the layout is encoded once, after the refresh`() = runTest {
        watch.helloReply = hello(featuresWithShortKeys)
        push()
        val encodedLogs = logs.filter { it.contains("WATCHFACE_LAYOUT push") }
        assertEquals(1, encodedLogs.size)
        assertTrue(encodedLogs.single(), encodedLogs.single().contains("keys=short"))
        assertFalse(logs.any { it.contains("keys=long") })
    }
}

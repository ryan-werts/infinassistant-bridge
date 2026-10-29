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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * REQ009-OBS-4 and REQ009-FR-D4 / N6: the log entry per WATCHFACE_LAYOUT push attempt, and the
 * one bounded retry after a "pending" refusal. Drives `handleWatchFaceLayoutCommand` against a
 * fake transport that plays the watch, on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchFacePushLogTest {

    private data class LogEntry(val direction: String, val type: String, val summary: String)

    private val logs = mutableListOf<LogEntry>()
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

        /** HELLO payload the watch answers with; changed by tests between attempts. */
        var helloReply: ByteArray? = null

        /** One reply per layout frame, in order; a null entry is silence. Once empty, every layout is acked ok. */
        val layoutReplies = ArrayDeque<WatchFaceAckPayload?>()

        /** Called after each layout frame is received, with the number of layout frames so far. */
        var onLayout: (Int) -> Unit = {}

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
                    Protocol.Type.WATCHFACE_LAYOUT.wire -> {
                        val reply = if (layoutReplies.isEmpty()) {
                            WatchFaceAckPayload(ok = true, appliedVersion = 1)
                        } else {
                            layoutReplies.removeFirst()
                        }
                        if (reply != null) {
                            val ack = ProtocolCbor.encode(WatchFaceAckPayload.serializer(), reply)
                            deliver(FrameCodec.CompleteMessage(1, Protocol.Type.WATCHFACE_ACK.wire, 0, msgId, ack))
                        }
                        onLayout(layoutFrames.size)
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

    private lateinit var orchestrator: WatchFaceOrchestrator
    private lateinit var watch: FakeWatch

    @Before
    fun setup() {
        logs.clear()
        watch = FakeWatch()
        val stateManager = StateManager()
        val protocolHandler = ProtocolHandler(
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
            logCallback = { dir, type, _, summary -> logs += LogEntry(dir, type, summary) },
        )
    }

    // ---- helpers -----------------------------------------------------------------

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

    /**
     * The HELLO real firmware sends at a 256-byte MTU. The rich one does not fit a single
     * frame, so the watch falls back to a compact map that has NO `features` field.
     */
    private fun compactHello(helloDiagRev: Int): ByteArray =
        ProtocolCbor.encode(
            HelloPayload.serializer(),
            HelloPayload(device = "watch", freeHeap = 20_000L, helloDiagRev = helloDiagRev),
        )

    /** One byte short of what the long-spelling push needs (its size plus the reassembly headroom). */
    private fun tightHeap(): Long =
        (longBytes().size + WatchFaceOrchestrator.FW_REASSEMBLY_HEADROOM_BYTES - 1).toLong()

    private val featuresWithoutShortKeys = Protocol.Features.WATCHFACE_LAYOUT or Protocol.Features.WATCHFACE_ACK or Protocol.Features.NOTIFY_DISMISS_SNOOZE
    private val featuresWithShortKeys = featuresWithoutShortKeys or Protocol.Features.SHORT_KEYS

    private suspend fun push(payload: WatchFaceLayoutPayload = layout(), peerFeatures: Int = 0): WatchFaceLayoutResult =
        orchestrator.handleWatchFaceLayoutCommand(
            payload = payload,
            ha = mockk(relaxed = true),
            transport = watch.transport,
            onProgress = null,
            peerFeatures = peerFeatures,
            peerHelloDiagRev = 2,
        )

    /** The layout with [total] screens: its dashboard and tile screen, then more tile screens. */
    private fun layoutOfScreens(total: Int): WatchFaceLayoutPayload {
        val base = layout()
        val more = List(total - base.screens.size) { i ->
            WatchScreen(screenType = 1, tiles = listOf(WatchTile(label = "Extra${i + 1}", color = "#FFFFFF")))
        }
        return base.copy(screens = base.screens + more)
    }

    private fun sentScreens(): Int =
        ProtocolCbor.decode(WatchFaceLayoutPayload.serializer(), watch.layoutFrames.single().payload).screens.size

    private fun pushEntries() = logs.filter { it.type == "WATCHFACE_LAYOUT push" }
    private fun retryEntries() = logs.filter { it.type == "WATCHFACE_LAYOUT retry" }
    private fun pending() = WatchFaceAckPayload(ok = false, appliedVersion = 0, reason = WatchFaceOrchestrator.PENDING_PUSH_REASON)
    private fun rejected(reason: String?) = WatchFaceAckPayload(ok = false, appliedVersion = 0, reason = reason)

    // ---- OBS-4: one entry per attempt --------------------------------------------

    @Test
    fun `a passing push logs one entry with the byte count, spelling and pass`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push())

        val entry = pushEntries().single()
        assertEquals("watchface", entry.direction)
        assertTrue(entry.summary, entry.summary.startsWith("attempt=1 keys=long bytes=${longBytes().size} preflight=pass"))
        assertEquals(longBytes().size, watch.layoutFrames.single().payload.size)
        assertTrue("no retry entry", retryEntries().isEmpty())
    }

    @Test
    fun `a short-key push logs the short spelling and its own byte count`() = runTest {
        watch.helloReply = hello(featuresWithShortKeys)
        assertEquals(WatchFaceLayoutResult.Success, push())

        val entry = pushEntries().single()
        assertTrue(entry.summary, entry.summary.startsWith("attempt=1 keys=short bytes=${shortBytes().size} preflight=pass"))
        assertTrue(shortBytes().size < longBytes().size)
    }

    // Seen on hardware (firmware 2.4.63, app 2.1.69): "keys=long bytes=1509 peerFeatures=0x0".
    // The compact HELLO carries no feature word, so the short-key bit was never seen.
    @Test
    fun `a compact HELLO at diag rev 3 gets short keys although it has no feature word`() = runTest {
        watch.helloReply = compactHello(helloDiagRev = 3)
        assertEquals(WatchFaceLayoutResult.Success, push())

        val entry = pushEntries().single()
        assertTrue(entry.summary, entry.summary.startsWith("attempt=1 keys=short bytes=${shortBytes().size} preflight=pass"))
        assertTrue(entry.summary, entry.summary.endsWith("peerFeatures=0x0"))
        assertEquals(shortBytes().size, watch.layoutFrames.single().payload.size)
    }

    @Test
    fun `a compact HELLO below diag rev 3 keeps long keys`() = runTest {
        watch.helloReply = compactHello(helloDiagRev = 2)
        assertEquals(WatchFaceLayoutResult.Success, push())

        val entry = pushEntries().single()
        assertTrue(entry.summary, entry.summary.startsWith("attempt=1 keys=long bytes=${longBytes().size} preflight=pass"))
        assertEquals(longBytes().size, watch.layoutFrames.single().payload.size)
    }

    @Test
    fun `a heap refusal logs one entry with PreflightFailed and the free-heap reading`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys, freeHeap = tightHeap())
        val result = push()

        assertTrue(result is WatchFaceLayoutResult.PreflightFailed)
        val entry = pushEntries().single()
        assertTrue(
            entry.summary,
            entry.summary.startsWith(
                "attempt=1 keys=long bytes=${longBytes().size} preflight=PreflightFailed freeHeap=${tightHeap()} field=freeHeap",
            ),
        )
        assertTrue("nothing was sent", watch.layoutFrames.isEmpty())
    }

    @Test
    fun `a refusal with no heap reading says unknown`() = runTest {
        // No HELLO reply and no connect-time reading, but a layout over the byte cap is still refused.
        val oversized = layout().copy(
            screens = layout().screens.map { s ->
                s.copy(tiles = listOf(WatchTile(label = "X".repeat(FirmwareLimits.MAX_REASSEMBLY_SIZE), color = "#FFFFFF")))
            },
        )
        val result = orchestrator.handleWatchFaceLayoutCommand(
            payload = oversized,
            ha = mockk(relaxed = true),
            transport = watch.transport,
            onProgress = null,
            peerFeatures = 0,
            peerHelloDiagRev = 2,
        )

        assertTrue(result is WatchFaceLayoutResult.PreflightFailed)
        val entry = pushEntries().single()
        assertTrue(entry.summary, entry.summary.contains("preflight=PreflightFailed freeHeap=unknown field=blePayload"))
        assertTrue(watch.layoutFrames.isEmpty())
    }

    // ---- FR-7: the screen cap follows the watch's HELLO ---------------------------

    @Test
    fun `the cap is 6 with the six-screen bit and 4 without it`() {
        assertEquals(6, WatchFaceOrchestrator.screenCap(Protocol.Features.SIX_SCREENS))
        assertEquals(6, WatchFaceOrchestrator.screenCap(featuresWithShortKeys or Protocol.Features.SIX_SCREENS))
        assertEquals(4, WatchFaceOrchestrator.screenCap(featuresWithShortKeys))
        assertEquals("a HELLO with no feature word is a watch of 4", 4, WatchFaceOrchestrator.screenCap(0))
        assertEquals(0x4000, Protocol.Features.SIX_SCREENS)
    }

    @Test
    fun `a six-screen watch is sent all six screens and no warning`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)

        val result = push(layoutOfScreens(6), featuresWithoutShortKeys or Protocol.Features.SIX_SCREENS)

        assertEquals(WatchFaceLayoutResult.Success, result)
        assertEquals(6, sentScreens())
        assertTrue(logs.none { it.type == "WATCHFACE_LAYOUT screens trimmed" })
    }

    @Test
    fun `a four-screen watch is sent four screens, and the success says which were left out`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)

        val result = push(layoutOfScreens(6), featuresWithoutShortKeys)

        assertEquals(4, sentScreens())
        val trimmed = result as? WatchFaceLayoutResult.SuccessTrimmed ?: throw AssertionError("expected SuccessTrimmed: $result")
        assertTrue(trimmed.warning, trimmed.warning.contains("6 screens") && trimmed.warning.contains("holds 4") && trimmed.warning.contains("5-6"))
        val entry = logs.single { it.type == "WATCHFACE_LAYOUT screens trimmed" }
        assertEquals("watchface", entry.direction)
        assertEquals(trimmed.warning, entry.summary)
    }

    @Test
    fun `a layout that fits the cap is not reported as trimmed`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)

        assertEquals(WatchFaceLayoutResult.Success, push(layoutOfScreens(4), featuresWithoutShortKeys))
        assertEquals(4, sentScreens())
        assertTrue(logs.none { it.type == "WATCHFACE_LAYOUT screens trimmed" })
    }

    // ---- N6: one bounded retry after a pending refusal ---------------------------

    @Test
    fun `a pending refusal is retried once after the completion bound and then succeeds`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)
        watch.layoutReplies += pending()

        val job = async { push() }
        runCurrent()
        assertEquals("first attempt sent", 1, watch.layoutFrames.size)
        assertEquals(1, pushEntries().size)

        advanceTimeBy(WatchFaceOrchestrator.PENDING_PUSH_RETRY_DELAY_MS - 1)
        runCurrent()
        assertEquals("no retry before the bound", 1, watch.layoutFrames.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals("retry at the bound", 2, watch.layoutFrames.size)

        assertEquals(WatchFaceLayoutResult.Success, job.await())
        assertEquals(WatchFaceOrchestrator.PENDING_PUSH_RETRY_DELAY_MS, currentTime)
        assertEquals(35_000L, WatchFaceOrchestrator.PENDING_PUSH_RETRY_DELAY_MS)
        assertEquals(listOf("attempt=1", "attempt=2"), pushEntries().map { it.summary.substringBefore(' ') })
        assertEquals(1, retryEntries().size)
    }

    @Test
    fun `a second pending refusal is returned and there is no third attempt`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)
        repeat(5) { watch.layoutReplies += pending() }

        val result = push()

        assertTrue(result.toString(), result is WatchFaceLayoutResult.AckFailed)
        assertTrue((result as WatchFaceLayoutResult.AckFailed).message.contains(WatchFaceOrchestrator.PENDING_PUSH_REASON))
        assertEquals("exactly one retry", 2, watch.layoutFrames.size)
        assertEquals(2, pushEntries().size)
        assertEquals(1, retryEntries().size)
        assertEquals("one refresh HELLO per attempt", 2, watch.helloFrames.size)
    }

    @Test
    fun `the retry runs the whole send path again with the refreshed bit and heap`() = runTest {
        // Attempt 1 goes out long; the watch then advertises the bit, so attempt 2 must go out short.
        watch.helloReply = hello(featuresWithoutShortKeys)
        watch.layoutReplies += pending()
        watch.onLayout = { n -> if (n == 1) watch.helloReply = hello(featuresWithShortKeys) }

        assertEquals(WatchFaceLayoutResult.Success, push())

        assertEquals(2, watch.helloFrames.size)
        assertEquals(longBytes().size, watch.layoutFrames[0].payload.size)
        assertEquals(shortBytes().size, watch.layoutFrames[1].payload.size)
        assertTrue(pushEntries()[0].summary.contains("keys=long"))
        assertTrue(pushEntries()[1].summary.contains("keys=short"))
    }

    @Test
    fun `the retry is preflighted again and a heap that has shrunk refuses it`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)
        watch.layoutReplies += pending()
        watch.onLayout = { n -> if (n == 1) watch.helloReply = hello(featuresWithoutShortKeys, freeHeap = tightHeap()) }

        val result = push()

        assertTrue(result.toString(), result is WatchFaceLayoutResult.PreflightFailed)
        assertEquals("the refused retry sends nothing", 1, watch.layoutFrames.size)
        val entries = pushEntries()
        assertEquals(2, entries.size)
        assertTrue(entries[0].summary.contains("preflight=pass"))
        assertTrue(entries[1].summary.contains("attempt=2"))
        assertTrue(entries[1].summary.contains("preflight=PreflightFailed freeHeap=${tightHeap()}"))
    }

    // ---- no retry for anything else ----------------------------------------------

    private fun assertNoRetry(result: WatchFaceLayoutResult) {
        assertTrue(result.toString(), result is WatchFaceLayoutResult.AckFailed)
        assertEquals("no second layout frame", 1, watch.layoutFrames.size)
        assertEquals(1, pushEntries().size)
        assertTrue(retryEntries().isEmpty())
    }

    @Test
    fun `genuine rejections are not retried`() {
        for (reason in listOf("validation_error", "decode_error", "oom", "", null, "Pending", "PENDING", "pending ", "pending_write")) {
            setup()
            runTest {
                watch.helloReply = hello(featuresWithoutShortKeys)
                watch.layoutReplies += rejected(reason)
                val result = push()
                assertTrue("reason '$reason': $result", result is WatchFaceLayoutResult.AckFailed)
                assertEquals("reason '$reason': no second layout frame", 1, watch.layoutFrames.size)
                assertEquals("reason '$reason': one entry", 1, pushEntries().size)
                assertTrue("reason '$reason': no retry entry", retryEntries().isEmpty())
                assertEquals("reason '$reason': no waiting", 0L, currentTime)
            }
        }
    }

    @Test
    fun `an ACK timeout is not retried`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys)
        watch.layoutReplies += null

        val result = push()

        assertNoRetry(result)
        assertEquals("WATCHFACE_ACK timeout", (result as WatchFaceLayoutResult.AckFailed).message)
        assertEquals(WatchFaceOrchestrator.WATCHFACE_ACK_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `a preflight refusal is not retried`() = runTest {
        watch.helloReply = hello(featuresWithoutShortKeys, freeHeap = tightHeap())

        val result = push()

        assertTrue(result is WatchFaceLayoutResult.PreflightFailed)
        assertEquals(1, pushEntries().size)
        assertTrue(retryEntries().isEmpty())
        assertEquals(1, watch.helloFrames.size)
        assertEquals(0L, currentTime)
    }
}

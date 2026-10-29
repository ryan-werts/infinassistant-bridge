package com.w6tech.infinassistant.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The liveness check (app 2.1.125): a PING only after the watch has been silent, a dead link found
 * within three minutes, and no PING at all while frames keep coming. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivenessMonitorTest {

    private fun TestScope.monitor() = LivenessMonitor({ currentTime })

    /** What a run did: the times PINGs went out, the events, and how and when it ended. */
    private class Run {
        val pingTimes = mutableListOf<Long>()
        val events = mutableListOf<LivenessMonitor.Event>()
        var verdict: LivenessMonitor.Verdict? = null
        var verdictAt = -1L
    }

    private fun TestScope.start(
        m: LivenessMonitor,
        send: suspend () -> LivenessMonitor.Send = { LivenessMonitor.Send.Sent },
        pong: suspend (Long) -> Boolean,
    ): Run {
        val r = Run()
        launch {
            r.verdict = m.run(
                sendPing = { r.pingTimes += currentTime; send() },
                awaitPong = pong,
                onEvent = { r.events += it },
            )
            r.verdictAt = currentTime
        }
        return r
    }

    @Test
    fun `no PING goes out while the watch keeps sending frames`() = runTest {
        val m = monitor()
        val r = start(m) { true }
        // Ten minutes, a frame every 30 s: the old fixed timer would have sent 20 PINGs.
        repeat(20) {
            delay(30_000)
            m.onInbound()
        }
        runCurrent()
        assertTrue(r.pingTimes.isEmpty())
        assertNull(r.verdict)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a PING goes out after the silence and not before`() = runTest {
        val m = monitor()
        val r = start(m) { true }
        delay(BridgeConfig.LIVENESS_SILENCE_MS - 1)
        runCurrent()
        assertTrue(r.pingTimes.isEmpty())
        delay(1)
        runCurrent()
        assertEquals(listOf(BridgeConfig.LIVENESS_SILENCE_MS), r.pingTimes)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a frame restarts the silence`() = runTest {
        val m = monitor()
        val r = start(m) { true }
        delay(60_000)
        m.onInbound()
        delay(60_000)   // 120 s since the start, 60 s since the frame
        runCurrent()
        assertTrue(r.pingTimes.isEmpty())
        delay(30_000)   // 90 s since the frame
        runCurrent()
        assertEquals(listOf(150_000L), r.pingTimes)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a PONG ends the round and the next PING waits a whole silence`() = runTest {
        val m = monitor()
        val r = start(m) {
            delay(200)
            m.onInbound()   // the PONG is a frame like any other
            true
        }
        delay(3 * BridgeConfig.LIVENESS_SILENCE_MS)
        runCurrent()
        assertEquals(listOf(90_000L, 180_200L), r.pingTimes)
        assertEquals(LivenessMonitor.Event.Alive(200L), r.events.first { it is LivenessMonitor.Event.Alive })
        assertNull(r.verdict)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `three missed PONGs end the run with a reconnect inside three minutes`() = runTest {
        val m = monitor()
        val r = start(m) { timeout -> delay(timeout); false }
        delay(200_000)
        runCurrent()
        assertEquals(LivenessMonitor.Verdict.Unresponsive(writeFailed = false), r.verdict)
        // 90 s of silence, then PING / 10 s wait, 15 s, PING / 10 s, 15 s, PING / 10 s.
        assertEquals(listOf(90_000L, 115_000L, 140_000L), r.pingTimes)
        assertEquals(150_000L, r.verdictAt)
        assertTrue(r.verdictAt <= 180_000L)
        assertEquals(
            listOf(1, 2, 3),
            r.events.filterIsInstance<LivenessMonitor.Event.PongMissed>().map { it.misses },
        )
    }

    @Test
    fun `the configured worst case is under three minutes`() {
        assertEquals(150_000L, BridgeConfig.LIVENESS_DETECTION_WORST_CASE_MS)
        assertTrue(BridgeConfig.LIVENESS_DETECTION_WORST_CASE_MS <= 180_000L)
    }

    @Test
    fun `any frame during the PONG wait is a sign of life, not a miss`() = runTest {
        val m = monitor()
        val r = start(m) { timeout ->
            delay(5_000)
            m.onInbound()   // a NOTIFY_ACK, not the PONG
            delay(timeout - 5_000)
            false
        }
        delay(150_000)
        runCurrent()
        assertEquals(listOf(90_000L), r.pingTimes)
        assertEquals(LivenessMonitor.Event.Alive(null), r.events.last())
        assertTrue(r.events.none { it is LivenessMonitor.Event.PongMissed })
        assertNull(r.verdict)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a frame after a miss ends the run of misses and says so`() = runTest {
        val m = monitor()
        assertFalse(m.onInbound())   // nothing was missed
        val r = start(m) { timeout -> delay(timeout); false }
        delay(101_000)
        runCurrent()
        assertEquals(1, r.events.count { it is LivenessMonitor.Event.PongMissed })
        assertTrue(m.onInbound())    // the watch spoke: responsive again
        assertFalse(m.onInbound())
        // Two more misses are not enough now: the count started over.
        delay(60_000)
        runCurrent()
        assertNull(r.verdict)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a link the stack reports lost ends the run at once`() = runTest {
        val m = monitor()
        val r = start(m, send = { LivenessMonitor.Send.LinkLost(9) }) { true }
        delay(100_000)
        runCurrent()
        assertEquals(LivenessMonitor.Verdict.LinkLost(9), r.verdict)
        assertEquals(1, r.pingTimes.size)
    }

    @Test
    fun `three failed writes end the run as a write failure`() = runTest {
        val m = monitor()
        val r = start(m, send = { LivenessMonitor.Send.Failed("busy") }) { true }
        delay(200_000)
        runCurrent()
        assertEquals(LivenessMonitor.Verdict.Unresponsive(writeFailed = true), r.verdict)
        assertEquals(listOf(90_000L, 105_000L, 120_000L), r.pingTimes)
    }
}

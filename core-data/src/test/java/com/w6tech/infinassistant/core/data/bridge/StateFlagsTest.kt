package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.domain.Protocol
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The STATE_UPDATE flags behind the watch's "HA offline" banner. Until 2.1.103 data_stale was the
 * age of the last state snapshot, taken only on connect and after an HA reconnect, so every update
 * sent more than 5 min later said stale while live events kept the data current.
 */
class StateFlagsTest {

    private var now = 1_791_900_000_000L
    private val stateManager = StateManager { now }

    @Test
    fun `before the first snapshot the data is stale`() {
        assertTrue(stateManager.isDataStale())
        assertFalse(stateManager.isHaOffline())
    }

    @Test
    fun `with the HA socket up the data stays fresh however old the snapshot`() {
        stateManager.recordHaFetch()
        now += 6 * 3_600_000L
        assertFalse(stateManager.isDataStale())
        assertFalse(stateManager.isHaOffline())
    }

    @Test
    fun `an outage is offline after a minute, stale after five, and clean once HA is back`() {
        stateManager.recordHaFetch()
        stateManager.setHaOffline(true)
        now += 59_000
        assertFalse("a blip is not reported", stateManager.isHaOffline())
        now += 2_000
        assertTrue(stateManager.isHaOffline())
        assertFalse(stateManager.isDataStale())
        now += StateManager.STALE_THRESHOLD_MS
        stateManager.setHaOffline(true)   // a repeated report keeps the outage's start
        assertTrue(stateManager.isDataStale())

        stateManager.setHaOffline(false)
        assertFalse(stateManager.isHaOffline())
        assertFalse(stateManager.isDataStale())
    }

    @Test
    fun `the flags go out alone when there is no state to send`() = runTest {
        val handler = ProtocolHandler(WireTestSupport.silentLogger, stateManager) { _, _, _, _ -> }
        val watch = WireTestSupport.Recorder()
        stateManager.recordHaFetch()
        stateManager.setHaOffline(true)
        now += 61_000

        handler.sendStateFlags(watch.transport)
        stateManager.setHaOffline(false)
        handler.sendStateFlags(watch.transport)

        val (type, offline) = watch.sent[0]
        assertEquals(Protocol.Type.STATE_UPDATE.wire, type)
        assertEquals(mapOf("items" to emptyList<Any?>(), "ha_offline" to true), WireTestSupport.decodeMap(offline))
        // Clean flags are left at their defaults, which the watch reads as false: the banner clears.
        assertEquals(mapOf("items" to emptyList<Any?>()), WireTestSupport.decodeMap(watch.sent[1].second))
    }
}

package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.common.AppResult
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BLE retries while Bluetooth is off. In one bundle (app 2.1.105) Bluetooth was off for 47 min:
 * every attempt read "Device not bonded", and when Bluetooth came back the next attempt was up to
 * 2 min away. Bluetooth coming on now ends the wait and starts the retries over.
 *
 * And the background connection that waits for a watch out of range (app 2.1.109): Bluetooth
 * going off ends it, since the stack drops the request with the adapter.
 */
class BluetoothRetryTest {

    private val manager = ConnectionManager(
        appContext = mockk(relaxed = true),
        logger = WireTestSupport.silentLogger,
        tokenStore = mockk(relaxed = true),
        urlSelector = mockk(relaxed = true),
        stateCache = HaStateCache(WireTestSupport.silentLogger),
    )

    @Test
    fun `Bluetooth coming on ends a retry's wait and starts the retries over`() = runTest {
        repeat(12) { manager.bleBackoff.nextDelayMs() }  // a long outage: the interval is at its cap
        var waited = false
        val retry = launch {
            manager.bleReconnectDelay()
            waited = true
        }
        advanceTimeBy(5_000)
        assertFalse("still waiting out the backoff", waited)

        manager.onBluetoothState(true)
        advanceTimeBy(1)

        assertTrue("the retry goes at once", waited)
        assertEquals(5_001L, currentTime)
        assertEquals("the next wait is the shortest again", 0, manager.bleBackoff.retryCount)
        retry.join()
    }

    @Test
    fun `Bluetooth going off does not end the wait`() = runTest {
        var waited = false
        repeat(12) { manager.bleBackoff.nextDelayMs() }
        val retry = launch {
            manager.bleReconnectDelay()
            waited = true
        }
        advanceTimeBy(5_000)
        manager.onBluetoothState(false)
        advanceTimeBy(1)
        assertFalse(waited)
        retry.cancel()
    }

    @Test
    fun `Bluetooth going off ends a background connection's wait and withdraws it`() = runTest {
        var withdrawn = false
        val wait = async {
            manager.untilBluetoothOff {
                try {
                    awaitCancellation()
                } finally {
                    withdrawn = true
                }
            }
        }
        advanceTimeBy(3_600_000)
        assertFalse("an hour on, still waiting for the watch", wait.isCompleted)

        manager.onBluetoothState(false)
        advanceTimeBy(1)
        val result = wait.await()
        assertEquals("Bluetooth is off", (result as AppResult.Err).error.message)
        assertTrue("the request is withdrawn", withdrawn)
    }

    @Test
    fun `a background connection that is made is the result`() = runTest {
        val result = manager.untilBluetoothOff {
            delay(5 * 3_600_000L)
            AppResult.Ok(Unit)
        }
        assertTrue(result is AppResult.Ok)
    }

    @Test
    fun `Bluetooth that went off before the wait does not end it`() = runTest {
        manager.onBluetoothState(false)
        val result = manager.untilBluetoothOff {
            delay(1_000)
            AppResult.Ok(Unit)
        }
        assertTrue(result is AppResult.Ok)
    }

    @Test
    fun `both changes reach the session's log callback`() {
        val seen = mutableListOf<Boolean>()
        manager.onBluetoothState(false) { seen += it }
        manager.onBluetoothState(true) { seen += it }
        assertEquals(listOf(false, true), seen)
    }
}

package com.w6tech.infinassistant.core.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.data.WatchAlertTracker.Action
import com.w6tech.infinassistant.core.domain.NotificationPrefs
import com.w6tech.infinassistant.core.ha.HaConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-AF-1/2: the watch battery low and watch disconnected alerts. */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchAlertTrackerTest {

    private val prefs = NotificationPrefs()   // battery 10 %, disconnected 5 min, both on
    private val minute = 60_000L

    private fun status(
        ble: BleConnectionState = BleConnectionState.Connected(256),
        battery: Int? = 80,
        charging: Boolean? = false,
        connectedMode: Boolean = true,
    ) = BridgeStatus(
        connectedModeEnabled = connectedMode,
        haState = HaConnectionState.Connected,
        bleState = ble,
        watchBatteryPercent = battery,
        watchBatteryCharging = charging,
    )

    @Test
    fun `battery low shows once, at the threshold, and clears when the watch charges`() {
        val t = WatchAlertTracker()
        assertEquals(emptyList<Action>(), t.update(status(battery = 11), prefs, 0))
        assertEquals(listOf(Action.ShowBatteryLow(10)), t.update(status(battery = 10), prefs, 0))
        assertEquals(emptyList<Action>(), t.update(status(battery = 7), prefs, 0))   // once per episode
        assertEquals(listOf(Action.ClearBatteryLow), t.update(status(battery = 7, charging = true), prefs, 0))
        assertEquals(emptyList<Action>(), t.update(status(battery = 9, charging = true), prefs, 0))   // charging: no alert
        assertEquals(listOf(Action.ShowBatteryLow(9)), t.update(status(battery = 9), prefs, 0))
    }

    @Test
    fun `battery low re-arms only well above the threshold, and never when turned off`() {
        val t = WatchAlertTracker()
        t.update(status(battery = 10), prefs, 0)
        assertEquals(emptyList<Action>(), t.update(status(battery = 12), prefs, 0))   // a jitter above 10
        assertEquals(listOf(Action.ClearBatteryLow), t.update(status(battery = 15), prefs, 0))

        val off = prefs.copy(watchBatteryAlert = false)
        assertEquals(emptyList<Action>(), WatchAlertTracker().update(status(battery = 3), off, 0))
    }

    @Test
    fun `disconnected shows after the threshold and clears when the watch is back`() {
        val t = WatchAlertTracker()
        assertEquals(emptyList<Action>(), t.update(status(ble = BleConnectionState.Disconnected), prefs, 0))
        assertEquals(emptyList<Action>(), t.update(status(ble = BleConnectionState.Connecting), prefs, 4 * minute))
        assertEquals(listOf(Action.ShowDisconnected(0)), t.update(status(ble = BleConnectionState.Disconnected), prefs, 5 * minute))
        assertEquals(emptyList<Action>(), t.update(status(ble = BleConnectionState.Disconnected), prefs, 9 * minute))
        assertEquals(listOf(Action.ClearDisconnected), t.update(status(), prefs, 10 * minute))
        // A new episode starts its own clock.
        assertEquals(emptyList<Action>(), t.update(status(ble = BleConnectionState.Disconnected), prefs, 11 * minute))
        assertEquals(listOf(Action.ShowDisconnected(11 * minute)), t.update(status(ble = BleConnectionState.Disconnected), prefs, 16 * minute))
    }

    @Test
    fun `no disconnected alert with Connected Mode off or the alert turned off`() {
        val t = WatchAlertTracker()
        t.update(status(ble = BleConnectionState.Disconnected, connectedMode = false), prefs, 0)
        assertEquals(emptyList<Action>(), t.update(status(ble = BleConnectionState.Disconnected, connectedMode = false), prefs, 60 * minute))

        val muted = prefs.copy(watchDisconnectAlert = false)
        val u = WatchAlertTracker()
        u.update(status(ble = BleConnectionState.Disconnected), muted, 0)
        assertEquals(emptyList<Action>(), u.update(status(ble = BleConnectionState.Disconnected), muted, 60 * minute))

        // Turned off while showing: it is taken away.
        val v = WatchAlertTracker()
        v.update(status(ble = BleConnectionState.Disconnected), prefs, 0)
        v.update(status(ble = BleConnectionState.Disconnected), prefs, 6 * minute)
        assertEquals(listOf(Action.ClearDisconnected), v.update(status(ble = BleConnectionState.Disconnected), muted, 7 * minute))
    }

    @Test
    fun `the clock ticks only while Connected Mode is on and the watch is away`() = runTest {
        val st = MutableStateFlow(status())
        val ticks = mutableListOf<Long>()
        val job = launch { WatchAlertTracker.ticksWhileAway(st, 60_000).collect { ticks += currentTime } }
        delay(10 * minute)
        runCurrent()
        assertEquals("connected: one tick to start, then none", listOf(0L), ticks)

        st.value = status(ble = BleConnectionState.Disconnected)
        runCurrent()
        delay(3 * minute)
        runCurrent()
        assertEquals(listOf(0L, 10 * minute, 11 * minute, 12 * minute, 13 * minute), ticks)

        st.value = status()   // back: the tick that clears the alert, then quiet
        runCurrent()
        assertEquals(listOf(0L, 10 * minute, 11 * minute, 12 * minute, 13 * minute, 13 * minute), ticks)
        delay(10 * minute)
        runCurrent()
        assertEquals(6, ticks.size)

        st.value = status(ble = BleConnectionState.Disconnected, connectedMode = false)   // Connected Mode off: nothing to time
        runCurrent()
        val off = ticks.size
        delay(10 * minute)
        runCurrent()
        assertEquals(off, ticks.size)
        job.cancel()
    }
}

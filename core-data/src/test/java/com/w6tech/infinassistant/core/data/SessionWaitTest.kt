package com.w6tech.infinassistant.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The session loop's wait: one wake-up in the timeout while the links hold, an immediate one on a change. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionWaitTest {

    @Test
    fun `nothing changing, the wait lasts the whole timeout`() = runTest {
        val ble = MutableStateFlow("up")
        val ha = MutableStateFlow("up")
        awaitChange(ble, ha, "up", "up", 60_000)
        assertEquals(60_000L, currentTime)
    }

    @Test
    fun `a change of either link ends the wait at once`() = runTest {
        val ble = MutableStateFlow("up")
        val ha = MutableStateFlow("up")
        var woke = -1L
        launch {
            awaitChange(ble, ha, "up", "up", 60_000)
            woke = currentTime
        }
        delay(7_000)
        ha.value = "down"
        runCurrent()
        assertEquals(7_000L, woke)
    }

    @Test
    fun `a change made since the loop last looked is not missed`() = runTest {
        val ble = MutableStateFlow("up")
        val ha = MutableStateFlow("up")
        ble.value = "down"   // after the loop's check, before its wait
        awaitChange(ble, ha, "up", "up", 60_000)
        assertEquals(0L, currentTime)
    }
}

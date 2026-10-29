package com.w6tech.infinassistant.core.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failure status on a connection-state change: the link dropping when it was up, a failed
 * connect when it was not (app 2.1.118; bundle 20261021-213409, status 147 at 19:45:55).
 */
class LinkLostStateTest {

    @Test
    fun `the constants are what the rule assumes`() {
        assertEquals(0, BluetoothGatt.GATT_SUCCESS)
        assertEquals(0, BluetoothProfile.STATE_DISCONNECTED)
    }

    @Test
    fun `a drop with a failure status on a link that was up is a lost link`() {
        for (status in listOf(147, 8, 19, 133)) {
            assertTrue("status $status", BleGattClient.isLinkLost(established = true, status = status, newState = 0))
        }
    }

    @Test
    fun `a failed connect, a clean disconnect and a connect are not`() {
        assertFalse(BleGattClient.isLinkLost(established = false, status = 147, newState = 0))
        assertFalse(BleGattClient.isLinkLost(established = true, status = 0, newState = 0))
        assertFalse(BleGattClient.isLinkLost(established = true, status = 147, newState = 2))
    }
}

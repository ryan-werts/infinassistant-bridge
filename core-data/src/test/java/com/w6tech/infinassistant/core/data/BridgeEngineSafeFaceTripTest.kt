package com.w6tech.infinassistant.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-067 — gate for the "layout too heavy" notification.
 *
 * The watch's safeFaceTrips is a cumulative counter that only resets on reboot;
 * the phone's per-connection baseline resets to null on every reconnect. These
 * tests pin the rule that a null/equal/backwards count never notifies, so a
 * historical trip can no longer re-fire the notification after a reconnect.
 */
class BridgeEngineSafeFaceTripTest {

    @Test
    fun `null baseline seeds silently on first HELLO of a connection`() {
        // The false positive this fix removes: a historical trip (>=1) seen on the
        // first HELLO after a reconnect must NOT notify.
        assertFalse(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = null, newTrips = 1))
        assertFalse(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = null, newTrips = 7))
    }

    @Test
    fun `null baseline with zero trips does not notify`() {
        assertFalse(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = null, newTrips = 0))
    }

    @Test
    fun `genuine in-session increase notifies`() {
        assertTrue(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = 1, newTrips = 2))
        assertTrue(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = 0, newTrips = 1))
    }

    @Test
    fun `unchanged count does not notify`() {
        assertFalse(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = 3, newTrips = 3))
    }

    @Test
    fun `backwards count from watch reboot reset does not notify`() {
        assertFalse(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = 5, newTrips = 1))
        assertFalse(BridgeEngine.shouldNotifySafeFaceTrip(previousTrips = 2, newTrips = 0))
    }
}

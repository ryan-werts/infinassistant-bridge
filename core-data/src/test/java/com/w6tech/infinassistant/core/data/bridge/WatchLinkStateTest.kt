package com.w6tech.infinassistant.core.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-20: phone notifications need a bonded watch whose HELLO has been heard and whose link is not reported unencrypted. */
class WatchLinkStateTest {

    @Test
    fun `no bond is not paired, whatever the watch says`() {
        assertEquals(WatchLinkState.NOT_PAIRED, WatchLinkState.of(bonded = false, helloSeen = true, enc = true))
        assertEquals(WatchLinkState.NOT_PAIRED, WatchLinkState.of(bonded = false, helloSeen = false, enc = null))
    }

    @Test
    fun `until this connection's HELLO arrives the link is only being checked`() {
        assertEquals(WatchLinkState.CHECKING, WatchLinkState.of(bonded = true, helloSeen = false, enc = null))
        // A leftover enc from an earlier connection does not count before the new HELLO.
        assertEquals(WatchLinkState.CHECKING, WatchLinkState.of(bonded = true, helloSeen = false, enc = true))
        assertEquals(WatchLinkState.CHECKING, WatchLinkState.of(bonded = true, helloSeen = false, enc = false))
    }

    @Test
    fun `a bonded watch that reports an unencrypted link is not yet encrypted`() {
        assertEquals(WatchLinkState.PAIRED_NOT_ENCRYPTED, WatchLinkState.of(bonded = true, helloSeen = true, enc = false))
    }

    @Test
    fun `a bonded watch that reports an encrypted link is encrypted`() {
        assertEquals(WatchLinkState.ENCRYPTED, WatchLinkState.of(bonded = true, helloSeen = true, enc = true))
    }

    @Test
    fun `after a HELLO with no enc the bond alone gates, as for firmware 2_4_100`() {
        assertEquals(WatchLinkState.PAIRED, WatchLinkState.of(bonded = true, helloSeen = true, enc = null))
    }

    @Test
    fun `only paired and encrypted links let notifications through`() {
        assertTrue(WatchLinkState.ENCRYPTED.allowsPhoneNotifications)
        assertTrue(WatchLinkState.PAIRED.allowsPhoneNotifications)
        assertFalse(WatchLinkState.CHECKING.allowsPhoneNotifications)
        assertFalse(WatchLinkState.PAIRED_NOT_ENCRYPTED.allowsPhoneNotifications)
        assertFalse(WatchLinkState.MISMATCH.allowsPhoneNotifications)
        assertFalse(WatchLinkState.NOT_PAIRED.allowsPhoneNotifications)
    }

    // ── The grace period ──

    private var now = 1_000_000L
    private val tracker = WatchLinkTracker { now }

    @Test
    fun `an unencrypted link is a mismatch after the grace period`() {
        assertEquals(WatchLinkState.PAIRED_NOT_ENCRYPTED, tracker.update(true, true, false))
        now += WatchLinkTracker.GRACE_MS - 1
        assertEquals(WatchLinkState.PAIRED_NOT_ENCRYPTED, tracker.update(true, true, false))
        now += 1
        assertEquals(WatchLinkState.MISMATCH, tracker.update(true, true, false))
        now += 5_000
        assertEquals(WatchLinkState.MISMATCH, tracker.update(true, true, false))
    }

    @Test
    fun `a link that becomes encrypted within the grace period is never a mismatch`() {
        tracker.update(true, true, false)
        now += 30_000
        assertEquals(WatchLinkState.ENCRYPTED, tracker.update(true, true, true))
        // And the clock starts over if it falls back.
        now += 40_000
        assertEquals(WatchLinkState.PAIRED_NOT_ENCRYPTED, tracker.update(true, true, false))
    }

    @Test
    fun `a mismatch ends when the pairing is forgotten or the link encrypts`() {
        tracker.update(true, true, false)
        now += WatchLinkTracker.GRACE_MS
        assertEquals(WatchLinkState.MISMATCH, tracker.update(true, true, false))
        assertEquals(WatchLinkState.NOT_PAIRED, tracker.update(false, true, false))
        assertEquals(WatchLinkState.PAIRED_NOT_ENCRYPTED, tracker.update(true, true, false))
    }
}

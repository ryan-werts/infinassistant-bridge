package com.w6tech.infinassistant.core.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-11: key to nid, the content fingerprint, wrapping and the 64-key limit. */
class PhoneNotificationRegistryTest {

    private val registry = PhoneNotificationRegistry()

    @Test
    fun `nids count up from 1 and a key keeps its nid`() {
        assertEquals(1, registry.register("a", 10))
        assertEquals(2, registry.register("b", 20))
        assertEquals(1, registry.register("a", 11))
        assertEquals("a", registry.keyFor(1))
        assertEquals("b", registry.keyFor(2))
    }

    @Test
    fun `the fingerprint follows the last forward`() {
        assertNull(registry.lastFingerprint("a"))
        registry.register("a", 10)
        assertEquals(10, registry.lastFingerprint("a"))
        registry.register("a", 11)
        assertEquals(11, registry.lastFingerprint("a"))
    }

    @Test
    fun `nids wrap from 65535 to 1 and never reach 0`() {
        // Walk the counter to the top with one key that is re-registered after each release.
        var last = 0
        repeat(PhoneNotificationRegistry.MAX_NID) {
            last = registry.register("k", 0)
            registry.release("k")
        }
        assertEquals(65535, last)
        assertEquals(1, registry.register("k", 0))
    }

    @Test
    fun `a nid still held is skipped on wrap`() {
        assertEquals(1, registry.register("old", 0))
        repeat(PhoneNotificationRegistry.MAX_NID - 1) {
            registry.register("k", 0)
            registry.release("k")
        }
        // The counter is at 65535; the next one wraps to 1, which "old" holds.
        assertEquals(2, registry.register("new", 0))
        assertEquals("old", registry.keyFor(1))
    }

    @Test
    fun `holds 64 keys and drops the least recently forwarded`() {
        for (i in 1..64) registry.register("k$i", i)
        assertEquals(1, registry.keyFor(1)?.let { registry.lastFingerprint(it) })

        // k1 is forwarded again: k2 becomes the oldest.
        registry.register("k1", 100)
        registry.register("k65", 65)

        assertNull(registry.lastFingerprint("k2"))
        assertNull(registry.keyFor(2))
        assertEquals(100, registry.lastFingerprint("k1"))
        assertEquals(65, registry.lastFingerprint("k65"))
        for (i in 3..64) assertEquals(i, registry.lastFingerprint("k$i"))
    }

    @Test
    fun `an evicted key is reported to the caller by its nid`() {
        val evicted = mutableListOf<Int>()
        for (i in 1..64) registry.register("k$i", i) { evicted += it }
        assertTrue(evicted.isEmpty())
        registry.register("k65", 65) { evicted += it }
        assertEquals(listOf(1), evicted)
    }

    @Test
    fun `keys lists what is registered and clear forgets it`() {
        registry.register("a", 1)
        registry.register("b", 2)
        assertEquals(setOf("a", "b"), registry.keys())
        registry.clear()
        assertTrue(registry.keys().isEmpty())
        assertNull(registry.keyFor(1))
        // The counter carries on: a nid is not handed out again at once.
        assertEquals(3, registry.register("c", 3))
    }

    @Test
    fun `an evicted key is new when it comes back`() {
        for (i in 1..65) registry.register("k$i", i)
        assertNull(registry.lastFingerprint("k1"))
        val nid = registry.register("k1", 1)
        assertNotEquals(1, nid) // nid 1 is free again, but the counter has moved on
        assertEquals("k1", registry.keyFor(nid))
    }

    @Test
    fun `release forgets a key and reports its nid`() {
        val nid = registry.register("a", 1)
        assertEquals(PhoneNotificationRegistry.Released(nid, false), registry.release("a"))
        assertNull(registry.keyFor(nid))
        assertNull(registry.lastFingerprint("a"))
        assertNull(registry.release("a"))
    }

    @Test
    fun `a watch dismissal is remembered until the key is released`() {
        val nid = registry.register("a", 1)
        assertEquals("a", registry.markWatchDismissed(nid))
        assertEquals("a", registry.keyFor(nid))
        assertEquals(PhoneNotificationRegistry.Released(nid, true), registry.release("a"))
    }

    @Test
    fun `forwarding again clears the watch dismissal`() {
        val nid = registry.register("a", 1)
        registry.markWatchDismissed(nid)
        registry.register("a", 2)
        assertEquals(PhoneNotificationRegistry.Released(nid, false), registry.release("a"))
    }

    @Test
    fun `a nid that is not registered dismisses nothing`() {
        assertNull(registry.markWatchDismissed(7))
        assertTrue(registry.keyFor(7) == null)
    }
}

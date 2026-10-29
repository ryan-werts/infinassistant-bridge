package com.w6tech.infinassistant.core.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reply-correlation path.
 *
 * The bug these guard against: `incomingMessages` used to be a single-consumer
 * `Channel.receiveAsFlow()`, which *distributes* elements rather than
 * broadcasting them. A temporary `first { ... }` ACK waiter overlapping the
 * long-lived dispatch loop consumed frames that the dispatch loop then never
 * saw. Replacing it with a SharedFlow fixes the fan-out, but introduces a second
 * hazard: a SharedFlow has no replay, so a reply that arrives between the write
 * and the subscribe is lost. Registering the waiter before the write closes that
 * window, which is what MessageCorrelator exists to do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageCorrelatorTest {

    private data class Msg(val type: Int, val id: Int = 0)

    @Test
    fun `waiter registered before delivery receives the message`() = runBlocking {
        val correlator = MessageCorrelator<Msg>()

        // Register first — this is the ordering the transport relies on.
        val pending = correlator.register { it.type == 0x17 }
        assertEquals(1, correlator.pendingCount)

        // Reply arrives afterwards, as it would from the BLE pump.
        assertTrue(correlator.deliver(Msg(type = 0x17, id = 42)))

        val got = withTimeoutOrNull(1_000) { pending.await() }
        assertEquals(Msg(0x17, 42), got)
        assertEquals(0, correlator.pendingCount)
    }

    @Test
    fun `non-matching messages leave the waiter pending`() = runBlocking {
        val correlator = MessageCorrelator<Msg>()
        val pending = correlator.register { it.type == 0x12 }

        assertFalse(correlator.deliver(Msg(type = 0x04)))
        assertFalse(correlator.deliver(Msg(type = 0x0B)))
        assertEquals(1, correlator.pendingCount)

        assertNull(withTimeoutOrNull(50) { pending.await() })

        assertTrue(correlator.deliver(Msg(type = 0x12)))
        assertEquals(Msg(0x12), withTimeoutOrNull(1_000) { pending.await() })
    }

    @Test
    fun `concurrent waiters for different types are satisfied independently`() = runBlocking {
        val correlator = MessageCorrelator<Msg>()

        // This is the case the old Channel could not serve: two overlapping
        // waiters plus the dispatch loop all needing the same stream.
        val hello = correlator.register { it.type == 0x01 }
        val configAck = correlator.register { it.type == 0x03 }
        assertEquals(2, correlator.pendingCount)

        correlator.deliver(Msg(type = 0x03, id = 7))
        assertEquals(Msg(0x03, 7), withTimeoutOrNull(1_000) { configAck.await() })
        // The HELLO waiter must be untouched by an unrelated message.
        assertEquals(1, correlator.pendingCount)
        assertNull(withTimeoutOrNull(50) { hello.await() })

        correlator.deliver(Msg(type = 0x01, id = 8))
        assertEquals(Msg(0x01, 8), withTimeoutOrNull(1_000) { hello.await() })
        assertEquals(0, correlator.pendingCount)
    }

    @Test
    fun `cancelled waiter is removed and does not consume a later message`() = runBlocking {
        val correlator = MessageCorrelator<Msg>()
        val timedOut = correlator.register { it.type == 0x12 }

        correlator.cancel(timedOut)
        assertEquals(0, correlator.pendingCount)
        assertTrue(timedOut.isCancelled)

        // A late reply must not be swallowed by the abandoned waiter.
        assertFalse(correlator.deliver(Msg(type = 0x12)))
    }

    @Test
    fun `cancelAll fails every outstanding waiter`() = runBlocking {
        val correlator = MessageCorrelator<Msg>()
        val a = correlator.register { it.type == 0x01 }
        val b = correlator.register { it.type == 0x02 }

        correlator.cancelAll()

        assertEquals(0, correlator.pendingCount)
        assertTrue(a.isCancelled)
        assertTrue(b.isCancelled)
    }

    @Test
    fun `delivery with no waiters is a no-op`() {
        val correlator = MessageCorrelator<Msg>()
        assertFalse(correlator.deliver(Msg(type = 0x04)))
        assertEquals(0, correlator.pendingCount)
    }
}

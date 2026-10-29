package com.w6tech.infinassistant.core.ble

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One-shot reply correlation for a request/response transport.
 *
 * Callers register a waiter *before* writing a request, then await the returned
 * deferred. Registration is synchronous, which is the whole point: the reply can
 * arrive before the caller would otherwise have subscribed, and a broadcast flow
 * with no replay buffer would drop it.
 *
 * Extracted from [BleTransport] so this logic is unit-testable without an
 * Android BluetoothGatt.
 *
 * Thread-safe: [register] and [deliver] may be called from different threads.
 */
class MessageCorrelator<T> {

    private class Waiter<T>(
        val predicate: (T) -> Boolean,
        val deferred: CompletableDeferred<T>,
    )

    private val waiters = CopyOnWriteArrayList<Waiter<T>>()

    /** Number of waiters still outstanding. Exposed for tests and diagnostics. */
    val pendingCount: Int get() = waiters.size

    /**
     * Register interest in the next message satisfying [predicate].
     * Call this before issuing the request it corresponds to.
     */
    fun register(predicate: (T) -> Boolean): CompletableDeferred<T> {
        val deferred = CompletableDeferred<T>()
        waiters.add(Waiter(predicate, deferred))
        return deferred
    }

    /**
     * Offer [message] to the outstanding waiters.
     *
     * Every matching waiter is completed and removed. Returns true when at least
     * one waiter matched — callers still broadcast the message either way, so a
     * correlated reply remains visible to the general dispatch path.
     */
    fun deliver(message: T): Boolean {
        if (waiters.isEmpty()) return false
        val matched = waiters.filter { it.predicate(message) }
        if (matched.isEmpty()) return false
        waiters.removeAll(matched)
        matched.forEach { it.deferred.complete(message) }
        return true
    }

    /** Drop a waiter that timed out or is no longer wanted. */
    fun cancel(deferred: CompletableDeferred<T>) {
        waiters.removeAll { it.deferred === deferred }
        deferred.cancel()
    }

    /** Fail every outstanding waiter. Called by BleTransport when the frame
     *  flow ends (disconnect or session cancellation). */
    fun cancelAll(cause: Throwable? = null) {
        val snapshot = waiters.toList()
        waiters.clear()
        snapshot.forEach { it.deferred.cancel(kotlinx.coroutines.CancellationException(cause?.message ?: "transport closed")) }
    }
}

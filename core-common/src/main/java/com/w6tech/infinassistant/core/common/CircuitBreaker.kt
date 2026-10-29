package com.w6tech.infinassistant.core.common

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Circuit breaker for Home Assistant API calls.
 *
 * States:
 * - CLOSED  : Normal operation; failures are counted.
 * - OPEN    : Too many failures; calls are rejected immediately with exponential back-off
 *             before transitioning to HALF_OPEN.
 * - HALF_OPEN: One probe request is let through. Success → CLOSED; failure → OPEN (reset timer).
 *
 * Opens after [failureThreshold] failures within [failureWindowMs].
 * Back-off sequence when OPEN: 15 s, 30 s, 60 s, 120 s (capped).
 */
class CircuitBreaker(
    private val failureThreshold: Int = 5,
    private val failureWindowMs: Long = 60_000L,
) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private val _state = AtomicReference(State.CLOSED)
    val state: State get() = _state.get()

    /** Timestamps of recent failures (epoch ms), used for sliding-window counting. */
    private val failureTimes = ArrayDeque<Long>(failureThreshold + 1)
    private val failureCountLock = Any()

    /** How many times the breaker has been opened (used to compute back-off step). */
    private val openCount = AtomicInteger(0)

    /** Epoch-ms when the OPEN → HALF_OPEN transition should occur. */
    private val nextProbeAt = AtomicLong(0L)

    /** Back-off delays (ms) indexed by open count (0-based), capped at last entry. */
    private val backoffStepsMs = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L)

    /**
     * Returns `true` when the call should be allowed through.
     *
     * CLOSED  → always allow.
     * HALF_OPEN → allow (exactly one probe per cycle — subsequent callers are blocked).
     * OPEN    → block unless it is time for a probe; if so, transition to HALF_OPEN and allow.
     */
    fun allowRequest(): Boolean {
        return when (_state.get()) {
            State.CLOSED -> true
            State.HALF_OPEN -> false   // Probe slot already claimed; others wait.
            State.OPEN -> {
                val now = System.currentTimeMillis()
                if (now >= nextProbeAt.get()) {
                    // Attempt transition OPEN → HALF_OPEN (CAS ensures only one caller wins).
                    if (_state.compareAndSet(State.OPEN, State.HALF_OPEN)) true else false
                } else {
                    false
                }
            }
        }
    }

    /**
     * Record a successful call.
     * HALF_OPEN → CLOSED; CLOSED stays CLOSED.
     */
    fun recordSuccess() {
        if (_state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            openCount.set(0)
            synchronized(failureCountLock) { failureTimes.clear() }
        }
    }

    /**
     * Record a failed call.
     * HALF_OPEN → OPEN (probe failed).
     * CLOSED: count failure; if threshold reached within window → OPEN.
     */
    fun recordFailure() {
        when (_state.get()) {
            State.HALF_OPEN -> {
                // Probe failed — reopen with the next back-off step.
                trip()
            }
            State.CLOSED -> {
                val now = System.currentTimeMillis()
                val windowStart = now - failureWindowMs
                val recentCount = synchronized(failureCountLock) {
                    failureTimes.addLast(now)
                    // Evict failures outside the window.
                    while (failureTimes.isNotEmpty() && failureTimes.first() < windowStart) {
                        failureTimes.removeFirst()
                    }
                    failureTimes.size
                }
                if (recentCount >= failureThreshold) {
                    trip()
                }
            }
            State.OPEN -> Unit   // Already open; no additional action needed.
        }
    }

    /** Force the breaker to CLOSED state (e.g., after user-initiated reconnect). */
    fun reset() {
        _state.set(State.CLOSED)
        openCount.set(0)
        nextProbeAt.set(0L)
        synchronized(failureCountLock) { failureTimes.clear() }
    }

    // --- Internal ---

    private fun trip() {
        val count = openCount.getAndIncrement()
        val step = count.coerceAtMost(backoffStepsMs.size - 1)
        nextProbeAt.set(System.currentTimeMillis() + backoffStepsMs[step])
        _state.set(State.OPEN)
    }
}

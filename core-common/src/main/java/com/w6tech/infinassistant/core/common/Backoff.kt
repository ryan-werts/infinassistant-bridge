package com.w6tech.infinassistant.core.common

import kotlin.math.min
import kotlin.random.Random

/**
 * Exponential backoff with jitter for connection retry loops.
 *
 * @param initialMs  First retry delay in milliseconds.
 * @param maxMs      Maximum delay cap (default 120 s for BLE reliability).
 * @param multiplier Multiplicative growth factor per attempt.
 * @param jitterFraction  Proportion of current delay used as random jitter band.
 */
class ExponentialBackoff(
    private val initialMs: Long = 500,
    private val maxMs: Long = 120_000,
    private val multiplier: Double = 1.7,
    private val jitterFraction: Double = 0.25,
) {
    private var current: Long = 0

    /** Number of retries consumed so far (since last [reset]). */
    var retryCount: Int = 0
        private set

    fun reset() {
        current = 0
        retryCount = 0
    }

    fun nextDelayMs(): Long {
        retryCount++
        current = if (current == 0L) initialMs else min(maxMs.toDouble(), current * multiplier).toLong()
        val jitter = (current * jitterFraction).toLong()
        val delta = Random.nextLong(-jitter, jitter + 1)
        return (current + delta).coerceAtLeast(0)
    }
}

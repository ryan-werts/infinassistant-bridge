package com.w6tech.infinassistant.core.data

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suspends until [a] or [b] differs from [seenA] / [seenB], the values the caller last acted on, or
 * [timeoutMs] has passed. The session loop waits on its links this way while both are up, so a quiet
 * bridge wakes the phone once in [timeoutMs] instead of once a second, and a change is still seen at
 * once. Comparing with what the caller saw, not with the values at the call, means a change made
 * between its last look and this wait ends the wait at once instead of being missed.
 */
internal suspend fun <A, B> awaitChange(a: StateFlow<A>, b: StateFlow<B>, seenA: A, seenB: B, timeoutMs: Long) {
    withTimeoutOrNull(timeoutMs) {
        combine(a, b) { x, y -> x != seenA || y != seenB }.first { it }
    }
}

package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.domain.NotifyPayload
import com.w6tech.infinassistant.core.domain.StateItem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages entity state caching and pending notification tracking for the bridge.
 *
 * Thread-safe: All maps use ConcurrentHashMap for safe concurrent access
 * from multiple coroutines.
 *
 * @param clockMs epoch milliseconds, for the HA staleness tracking.
 */
class StateManager(private val clockMs: () -> Long = System::currentTimeMillis) {

    /**
     * Cache of the latest state for each tracked entity.
     * Used for reconnect replay and change detection during polling.
     */
    private val lastStateByEntity = ConcurrentHashMap<String, StateItem>()

    /**
     * Pending notifications awaiting acknowledgment from the watch.
     * Key is the message ID, value is the notification payload.
     * Notifications are removed when NOTIFY_ACK is received.
     */
    private val pendingNotifyByMsgId = ConcurrentHashMap<Int, PendingNotify>()

    /** A pending notification and its place in the order they were queued. */
    private class PendingNotify(val seq: Long, val payload: NotifyPayload)

    private val pendingSeq = AtomicLong(0L)

    // --- Entity State Management ---

    /**
     * Update the cached state for an entity.
     */
    fun updateEntityState(entityId: String, state: StateItem) {
        lastStateByEntity[entityId] = state
    }

    /**
     * Update cached states for multiple entities.
     */
    fun updateEntityStates(states: List<StateItem>) {
        states.forEach { lastStateByEntity[it.entityId] = it }
    }

    /**
     * Check if a state has changed from the cached version.
     * Returns true if the entity is new or if state/unit has changed.
     */
    fun hasStateChanged(state: StateItem): Boolean {
        val prev = lastStateByEntity[state.entityId]
        return prev == null || prev.state != state.state || prev.unit != state.unit
    }

    /**
     * Filter a list of states to only those that have changed.
     * Also updates the cache for changed states.
     */
    fun filterAndUpdateChangedStates(states: List<StateItem>): List<StateItem> {
        val changed = states.filter { hasStateChanged(it) }
        changed.forEach { lastStateByEntity[it.entityId] = it }
        return changed
    }

    // --- Pending Notification Management ---

    /**
     * Add a notification to the pending queue.
     */
    fun addPendingNotification(msgId: Int, payload: NotifyPayload) {
        pendingNotifyByMsgId[msgId] = PendingNotify(pendingSeq.incrementAndGet(), payload)
    }

    /**
     * Remove a notification from the pending queue (called when ACK received).
     * Returns the payload if it was pending, null otherwise.
     */
    fun removePendingNotification(msgId: Int): NotifyPayload? {
        return pendingNotifyByMsgId.remove(msgId)?.payload
    }

    /** The pending notification [msgId], or null once it was ACKed or dropped. */
    fun getPendingNotification(msgId: Int): NotifyPayload? = pendingNotifyByMsgId[msgId]?.payload

    /**
     * Drop every pending notification for the phone notification [nid]: it was removed or
     * dismissed, so resending it would show it again.
     */
    fun removePendingByNid(nid: Int) {
        pendingNotifyByMsgId.entries.removeIf { it.value.payload.nid == nid }
    }

    /** Whether a notification for the phone notification [nid] is pending: sent or not, un-ACKed. */
    fun hasPendingForNid(nid: Int): Boolean = pendingNotifyByMsgId.values.any { it.payload.nid == nid }

    /**
     * Get all pending notifications (for resend on reconnect), the oldest first.
     */
    fun getAllPendingNotifications(): Map<Int, NotifyPayload> =
        pendingNotifyByMsgId.entries
            .sortedBy { it.value.seq }
            .associateTo(LinkedHashMap()) { it.key to it.value.payload }

    /**
     * Check if there are any pending notifications.
     */
    fun hasPendingNotifications(): Boolean = pendingNotifyByMsgId.isNotEmpty()

    // --- HA connectivity / staleness tracking ---
    //
    // These are the STATE_UPDATE flags; the watch shows "HA offline" while either is set. While
    // the HA WebSocket is up the data is live: every state change arrives as an event, and the
    // client pings HA every 30 s and reconnects on a missed pong. Until 2.1.103 data_stale was the
    // age of the last state snapshot, which is taken only on connect and after an HA reconnect, so
    // every update sent more than 5 min later said stale and the watch showed the banner for good.

    /** Epoch-ms since when the HA WebSocket has been down; 0 while it is up. */
    private val _haDownSinceMs = AtomicLong(0L)

    /** Epoch-ms of the last successful HA data fetch. 0 means never fetched. */
    private val _lastHaFetchMs = AtomicLong(0L)

    /** Mark the HA WebSocket down (it failed or closed) or up again. */
    fun setHaOffline(offline: Boolean) {
        if (offline) _haDownSinceMs.compareAndSet(0L, clockMs()) else _haDownSinceMs.set(0L)
    }

    /** Record a successful HA data fetch: the state snapshot on connect and after an HA reconnect. */
    fun recordHaFetch() {
        _lastHaFetchMs.set(clockMs())
    }

    /** How long the HA WebSocket has been down; 0 while it is up. */
    private fun haDownForMs(): Long {
        val since = _haDownSinceMs.get()
        return if (since == 0L) 0L else clockMs() - since
    }

    /** `true` once the HA WebSocket has been down for [OFFLINE_GRACE_MS]; a blip does not count. */
    fun isHaOffline(): Boolean = haDownForMs() > OFFLINE_GRACE_MS

    /** `true` before the first snapshot, and once HA has been unreachable for [STALE_THRESHOLD_MS]. */
    fun isDataStale(): Boolean = _lastHaFetchMs.get() == 0L || haDownForMs() > STALE_THRESHOLD_MS

    companion object {
        /** HA outages shorter than this (it usually reconnects within a second) are not reported. */
        const val OFFLINE_GRACE_MS = 60_000L
        const val STALE_THRESHOLD_MS = 5 * 60_000L
    }
}

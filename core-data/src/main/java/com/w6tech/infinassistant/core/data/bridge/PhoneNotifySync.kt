package com.w6tech.infinassistant.core.data.bridge

import android.app.NotificationManager
import com.w6tech.infinassistant.core.domain.NotifyPayload
import com.w6tech.infinassistant.core.domain.Severity

/**
 * The rules for phone notifications on the watch, apart from the wire: what is forwarded
 * ([PhoneNotificationFilter] and the link gate), which pending forwards survive until the next
 * connection, which removals wait for it, and what a watch dismissal asks of the phone.
 * [BridgeEngine] drives it and does the sending.
 *
 * Pending forwards live in the [StateManager], with the Home Assistant ones, so a NOTIFY_ACK
 * clears them all alike.
 */
class PhoneNotifySync(
    private val registry: PhoneNotificationRegistry,
    private val stateManager: StateManager,
    private val nextMsgId: () -> Int,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface Posted {
        /** Queued as pending forward [msgId] for the watch's notification [nid]. */
        data class Forwarded(val msgId: Int, val nid: Int) : Posted

        /** Not sent; [reason] is a short fixed word, safe to log. */
        data class Skipped(val reason: String) : Posted
    }

    sealed interface Removed {
        /** The key was never forwarded, or has left the registry. */
        data object NotForwarded : Removed

        /** The watch dismissed it, which is why the phone dropped it: nothing to tell the watch. */
        data class WatchDismissed(val nid: Int) : Removed

        /** A NOTIFY_REMOVE for [nid] is queued. */
        data class Queued(val nid: Int) : Removed
    }

    /**
     * The user dismissed the watch's notification [nid]: cancel the phone notification [key].
     * [crossedForward] is set when a forward for [nid] was still pending, which may have replaced
     * the entry that was dismissed: the watch then still holds one, so the cancel is not treated as
     * the watch's own and a NOTIFY_REMOVE follows.
     */
    data class DismissRequest(val key: String, val nid: Int, val crossedForward: Boolean)

    /** The nids to send a NOTIFY_REMOVE for, oldest first. */
    private val removes = ArrayDeque<Int>()

    /** Pending phone forwards not yet put on the wire this connection, by msgId. */
    private val unsent = LinkedHashSet<Int>()

    /**
     * A notification was posted or updated on the phone. When it passes the filter it becomes a
     * pending forward (replacing the watch's entry for the same key). It is sent when [link]
     * allows it, which the caller checks ([takeUnsent]): while the link is only being checked, or
     * is reported unencrypted, it waits. With the watch not paired at all it is skipped, not queued.
     */
    @Synchronized
    fun onPosted(s: PhoneNotificationSnapshot, switchOn: Boolean, link: WatchLinkState): Posted {
        if (switchOn && link == WatchLinkState.NOT_PAIRED) return Posted.Skipped("link-not-encrypted")
        val decision = PhoneNotificationFilter.decide(s, switchOn, registry.lastFingerprint(s.key))
        if (decision !is PhoneNotificationFilter.Decision.Forward) {
            return Posted.Skipped((decision as PhoneNotificationFilter.Decision.Skip).reason)
        }
        val nid = registry.register(s.key, decision.fingerprint) { evicted ->
            // The registry can no longer vouch for it, so it is not resent.
            stateManager.removePendingByNid(evicted)
        }
        removes.remove(nid)
        // The newer forward replaces the watch's entry, so an older one still pending is moot.
        stateManager.removePendingByNid(nid)
        val msgId = nextMsgId()
        stateManager.addPendingNotification(
            msgId,
            NotifyPayload(
                title = decision.content.title,
                body = decision.content.body,
                severity = if (s.importance >= NotificationManager.IMPORTANCE_HIGH) Severity.CRITICAL else Severity.WARN,
                tag = nid.toString(),
                ts = clockMs(),
                nid = nid,
            ),
        )
        unsent.add(msgId)
        trimPendingPhone()
        return Posted.Forwarded(msgId, nid)
    }

    /** Keeps the newest [MAX_PENDING_PHONE] phone forwards pending; a watch away for long does not get a flood. */
    private fun trimPendingPhone() {
        val phone = stateManager.getAllPendingNotifications().entries.filter { it.value.nid != null }
        phone.take((phone.size - MAX_PENDING_PHONE).coerceAtLeast(0)).forEach { stateManager.removePendingNotification(it.key) }
        unsent.retainAll(stateManager.getAllPendingNotifications().keys)
    }

    /**
     * The phone removed [key]. Its pending forward is dropped, and a NOTIFY_REMOVE is queued,
     * unless the watch dismissed it first. The queue keeps the newest [MAX_QUEUED_REMOVES].
     */
    @Synchronized
    fun onRemoved(key: String): Removed {
        val released = registry.release(key) ?: return Removed.NotForwarded
        stateManager.removePendingByNid(released.nid)
        if (released.watchDismissed) return Removed.WatchDismissed(released.nid)
        removes.remove(released.nid)
        removes.addLast(released.nid)
        while (removes.size > MAX_QUEUED_REMOVES) removes.removeFirst()
        return Removed.Queued(released.nid)
    }

    /**
     * The user dismissed the watch's notification [nid]: what to cancel on the phone, or null when
     * it is not one the phone forwarded. Nothing is sent back. The key is marked as the watch's
     * only when the cancel runs ([onCancelStarting]).
     */
    @Synchronized
    fun onWatchDismissed(nid: Int): DismissRequest? {
        val key = registry.keyFor(nid) ?: return null
        return DismissRequest(key, nid, crossedForward = stateManager.hasPendingForNid(nid))
    }

    /**
     * The phone is about to cancel [request]'s notification: the removal that follows is the
     * watch's own dismissal coming back, so it is not echoed (unless a newer forward crossed it).
     */
    @Synchronized
    fun onCancelStarting(request: DismissRequest) {
        if (!request.crossedForward) registry.markWatchDismissed(request.nid)
    }

    /**
     * The listener (re)connected and the phone's notifications are [activeKeys]: a forwarded key
     * that is no longer among them was removed while nobody was listening, and is handled as a removal.
     */
    @Synchronized
    fun reconcile(activeKeys: Set<String>): List<Removed> =
        registry.keys().filter { it !in activeKeys }.map { onRemoved(it) }

    /** The switch went off: forget what was forwarded and what waits to be, on either side. */
    @Synchronized
    fun clear() {
        registry.clear()
        removes.clear()
        unsent.clear()
        stateManager.getAllPendingNotifications().entries
            .filter { it.value.nid != null }
            .forEach { stateManager.removePendingNotification(it.key) }
    }

    /**
     * The queued removals, emptied. They wait while this connection has had no HELLO
     * ([helloSeen] false): whether the watch takes them is not known yet. After a HELLO without
     * [Protocol.Features.PHONE_NOTIFY_SYNC] ([watchSupportsSync] false) the watch would not know the
     * message, so they are dropped.
     */
    @Synchronized
    fun takeRemoves(helloSeen: Boolean, watchSupportsSync: Boolean): List<Int> {
        if (!helloSeen) return emptyList()
        val taken = removes.toList()
        removes.clear()
        return if (watchSupportsSync) taken else emptyList()
    }

    /**
     * The phone forwards queued and not yet sent, oldest first, now to be sent, when the link
     * allows it ([linkOpen]). While it does not they stay queued, pending and unsent, for when it
     * does. One whose notification has left the phone meanwhile is dropped.
     */
    @Synchronized
    fun takeUnsent(linkOpen: Boolean): List<Pair<Int, NotifyPayload>> {
        if (!linkOpen) return emptyList()
        val out = unsent.mapNotNull { mid -> stateManager.getPendingNotification(mid)?.let { mid to it } }
        unsent.clear()
        val (active, gone) = out.partition { registry.keyFor(it.second.nid!!) != null }
        gone.forEach { stateManager.removePendingNotification(it.first) }
        return active
    }

    /** Sends what [takeUnsent] gives, through [send]. */
    suspend fun flushForwards(linkOpen: Boolean, send: suspend (msgId: Int, payload: NotifyPayload) -> Unit) {
        for ((mid, payload) in takeUnsent(linkOpen)) send(mid, payload)
    }

    /** Sends what [takeRemoves] gives, through [send]. */
    suspend fun flushRemoves(helloSeen: Boolean, watchSupportsSync: Boolean, send: suspend (nid: Int) -> Unit) {
        for (nid in takeRemoves(helloSeen, watchSupportsSync)) send(nid)
    }

    /**
     * What to send again after a reconnect, oldest first: the Home Assistant notifications still
     * pending, and of the phone's only those still on the phone, the newest [MAX_PENDING_PHONE],
     * and only if the link allows ([linkOpen]). The phone ones left out are dropped from the
     * pending set; the ones returned count as sent. While the link does not allow it the phone
     * ones are kept pending and queued as unsent, for [takeUnsent] to send once it does.
     */
    @Synchronized
    fun pendingForResend(linkOpen: Boolean): List<Pair<Int, NotifyPayload>> {
        val all = stateManager.getAllPendingNotifications().entries.map { it.key to it.value }
        val phone = all.filter { it.second.nid != null }
        if (!linkOpen) {
            unsent.addAll(phone.map { it.first })
            return all.filter { it.second.nid == null }
        }
        val keep = phone
            .filter { registry.keyFor(it.second.nid!!) != null }
            .takeLast(MAX_PENDING_PHONE)
            .map { it.first }
            .toSet()
        phone.filter { it.first !in keep }.forEach { stateManager.removePendingNotification(it.first) }
        unsent.clear()
        return all.filter { it.second.nid == null || it.first in keep }
    }

    companion object {
        const val MAX_QUEUED_REMOVES = 16
        const val MAX_PENDING_PHONE = 5
    }
}

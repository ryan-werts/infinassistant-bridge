package com.w6tech.infinassistant.core.data.bridge

import android.app.NotificationManager
import com.w6tech.infinassistant.core.domain.NotifyPayload
import com.w6tech.infinassistant.core.domain.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-11 to FR-14 and FR-20: forwarding, removal, watch dismissal, the link gate and the resend rules. */
class PhoneNotifySyncTest {

    private val registry = PhoneNotificationRegistry()
    private val state = StateManager()
    private var msgId = 100
    private val sync = PhoneNotifySync(registry, state, { ++msgId }, { 5_000L })

    private fun snap(key: String, text: String = "hello", importance: Int = NotificationManager.IMPORTANCE_HIGH) =
        PhoneNotificationSnapshot(
            key = key,
            packageName = "com.chat",
            appLabel = "Chat",
            flags = 0,
            importance = importance,
            matchesInterruptionFilter = true,
            title = "Alice",
            text = text,
        )

    private fun post(key: String, text: String = "hello", link: WatchLinkState = WatchLinkState.ENCRYPTED) =
        sync.onPosted(snap(key, text), true, link)

    private fun forwarded(key: String, text: String = "hello") =
        post(key, text) as PhoneNotifySync.Posted.Forwarded

    /** The watch ACKed the forward: nothing pending any more. */
    private fun ack(f: PhoneNotifySync.Posted.Forwarded) = state.removePendingNotification(f.msgId)

    private fun takeRemoves(hello: Boolean = true, sync: Boolean = true) = this.sync.takeRemoves(hello, sync)

    // ── Forwarding ──

    @Test
    fun `a forward carries the nid as nid and as tag, and no key`() {
        val f = forwarded("k1")
        val payload = state.getPendingNotification(f.msgId)!!
        assertEquals(f.nid, payload.nid)
        assertEquals(f.nid.toString(), payload.tag)
        assertEquals("Alice", payload.title)
        assertEquals("hello", payload.body)
        assertEquals(5_000L, payload.ts)
        assertEquals(Severity.CRITICAL, payload.severity)
    }

    @Test
    fun `default importance is a warning, not critical`() {
        val f = sync.onPosted(snap("k1", importance = NotificationManager.IMPORTANCE_DEFAULT), true, WatchLinkState.ENCRYPTED)
            as PhoneNotifySync.Posted.Forwarded
        assertEquals(Severity.WARN, state.getPendingNotification(f.msgId)!!.severity)
    }

    @Test
    fun `a skipped notification queues nothing`() {
        val skipped = sync.onPosted(snap("k1"), false, WatchLinkState.ENCRYPTED)
        assertEquals(PhoneNotifySync.Posted.Skipped("switch-off"), skipped)
        assertFalse(state.hasPendingNotifications())
    }

    @Test
    fun `a re-post with the same content is not forwarded again`() {
        forwarded("k1")
        assertEquals(PhoneNotifySync.Posted.Skipped("unchanged"), post("k1"))
        assertEquals(1, state.getAllPendingNotifications().size)
    }

    @Test
    fun `an update keeps the nid and replaces the older pending forward`() {
        val first = forwarded("k1", "one")
        val second = forwarded("k1", "two")
        assertEquals(first.nid, second.nid)
        assertNull(state.getPendingNotification(first.msgId))
        assertEquals("two", state.getPendingNotification(second.msgId)!!.body)
    }

    // ── The link gate (FR-20) ──

    @Test
    fun `a watch that is not paired skips the notification with its reason and queues nothing`() {
        assertEquals(PhoneNotifySync.Posted.Skipped("link-not-encrypted"), post("k1", link = WatchLinkState.NOT_PAIRED))
        assertFalse(state.hasPendingNotifications())
        assertTrue(registry.keys().isEmpty())
    }

    @Test
    fun `a notification skipped as not paired is forwarded once the watch is paired`() {
        post("k1", link = WatchLinkState.NOT_PAIRED)
        assertTrue(post("k1") is PhoneNotifySync.Posted.Forwarded)
    }

    @Test
    fun `with the switch off the reason is the switch, not the link`() {
        assertEquals(
            PhoneNotifySync.Posted.Skipped("switch-off"),
            sync.onPosted(snap("k1"), false, WatchLinkState.NOT_PAIRED),
        )
    }

    @Test
    fun `while the link is checked or unencrypted a forward waits pending and is not sent`() {
        for (link in listOf(WatchLinkState.CHECKING, WatchLinkState.PAIRED_NOT_ENCRYPTED, WatchLinkState.MISMATCH)) {
            val s = PhoneNotifySync(PhoneNotificationRegistry(), StateManager(), { ++msgId })
            val f = s.onPosted(snap("k1"), true, link) as PhoneNotifySync.Posted.Forwarded
            assertTrue("$link", s.takeUnsent(link.allowsPhoneNotifications).isEmpty())
            // Still queued: the link opening sends it.
            assertEquals("$link", listOf(f.msgId), s.takeUnsent(true).map { it.first })
        }
    }

    @Test
    fun `a forward held for the link stays pending, not dropped`() {
        val f = post("k1", link = WatchLinkState.PAIRED_NOT_ENCRYPTED) as PhoneNotifySync.Posted.Forwarded
        assertTrue(sync.takeUnsent(linkOpen = false).isEmpty())
        assertEquals("hello", state.getPendingNotification(f.msgId)!!.body)
    }

    @Test
    fun `forwards held for the link are still capped at 5`() {
        val sent = (1..7).map { post("k$it", link = WatchLinkState.CHECKING) as PhoneNotifySync.Posted.Forwarded }
        assertEquals(sent.takeLast(5).map { it.msgId }, state.getAllPendingNotifications().keys.toList())
        assertEquals(sent.takeLast(5).map { it.msgId }, sync.takeUnsent(true).map { it.first })
    }

    @Test
    fun `a held forward whose notification left the phone is dropped, not sent`() {
        val gone = post("k1", link = WatchLinkState.CHECKING) as PhoneNotifySync.Posted.Forwarded
        val kept = post("k2", link = WatchLinkState.CHECKING) as PhoneNotifySync.Posted.Forwarded
        sync.onRemoved("k1")
        assertEquals(listOf(kept.msgId), sync.takeUnsent(true).map { it.first })
        assertNull(state.getPendingNotification(gone.msgId))
    }

    @Test
    fun `a closed link resends no phone forward but keeps Home Assistant's, and holds the phone's`() {
        state.addPendingNotification(200, NotifyPayload(title = "HA", body = "b", ts = 1))
        val f = forwarded("k1")
        // A reconnect with the link not open yet: only Home Assistant's go out.
        assertEquals(listOf(200), sync.pendingForResend(linkOpen = false).map { it.first })
        assertEquals(2, state.getAllPendingNotifications().size)
        // The link opens: the held forward goes, as the resend would have.
        assertEquals(listOf(f.msgId), sync.takeUnsent(true).map { it.first })
    }

    @Test
    fun `a forward sent before the reconnect and still unacked is held too, then sent`() {
        val f = forwarded("k1")
        sync.takeUnsent(true) // sent on the old connection, never ACKed
        assertTrue(sync.pendingForResend(linkOpen = false).isEmpty())
        assertEquals(listOf(f.msgId), sync.takeUnsent(true).map { it.first })
    }

    @Test
    fun `flushing sends through the callback only while the link allows it`() = kotlinx.coroutines.test.runTest {
        val f = post("k1", link = WatchLinkState.CHECKING) as PhoneNotifySync.Posted.Forwarded
        val sent = mutableListOf<Int>()
        sync.flushForwards(linkOpen = false) { mid, _ -> sent += mid }
        assertTrue(sent.isEmpty())
        sync.flushForwards(linkOpen = true) { mid, _ -> sent += mid }
        assertEquals(listOf(f.msgId), sent)
    }

    // ── Pending forwards (FR-14) ──

    @Test
    fun `unsent forwards are taken once, oldest first`() {
        val a = forwarded("k1")
        val b = forwarded("k2")
        assertEquals(listOf(a.msgId, b.msgId), sync.takeUnsent(true).map { it.first })
        assertTrue(sync.takeUnsent(true).isEmpty())
        // Still pending until ACKed: a reconnect resends them.
        assertEquals(2, state.getAllPendingNotifications().size)
    }

    @Test
    fun `a forward sent by the resend is not unsent any more`() {
        forwarded("k1")
        sync.pendingForResend(true)
        assertTrue(sync.takeUnsent(true).isEmpty())
    }

    @Test
    fun `a removed or acked forward is not taken as unsent`() {
        val a = forwarded("k1")
        val b = forwarded("k2")
        sync.onRemoved("k1")
        ack(b)
        assertTrue(sync.takeUnsent(true).isEmpty())
        assertNull(state.getPendingNotification(a.msgId))
    }

    @Test
    fun `at most 5 phone forwards stay pending, the newest`() {
        val sent = (1..8).map { forwarded("k$it") }
        val pending = state.getAllPendingNotifications().keys.toList()
        assertEquals(sent.takeLast(5).map { it.msgId }, pending)
        assertEquals(sent.takeLast(5).map { it.msgId }, sync.takeUnsent(true).map { it.first })
    }

    @Test
    fun `the cap counts phone forwards only`() {
        (1..3).forEach { state.addPendingNotification(200 + it, NotifyPayload(title = "HA", body = "b", ts = 1)) }
        (1..6).forEach { forwarded("k$it") }
        assertEquals(3, state.getAllPendingNotifications().values.count { it.nid == null })
        assertEquals(5, state.getAllPendingNotifications().values.count { it.nid != null })
    }

    @Test
    fun `a key pushed out of the registry loses its pending forward`() {
        val first = forwarded("k1")
        // The rest are ACKed at once, so the cap never takes k1's forward: only the eviction does.
        (2..65).forEach { ack(forwarded("k$it")) }
        assertNull(registry.lastFingerprint("k1"))
        assertNull(state.getPendingNotification(first.msgId))
    }

    // ── Removal (FR-12) ──

    @Test
    fun `a removal drops the pending forward and queues a NOTIFY_REMOVE`() {
        val f = forwarded("k1")
        assertEquals(PhoneNotifySync.Removed.Queued(f.nid), sync.onRemoved("k1"))
        assertNull(state.getPendingNotification(f.msgId))
        assertEquals(listOf(f.nid), takeRemoves())
    }

    @Test
    fun `a key that was never forwarded queues nothing`() {
        assertEquals(PhoneNotifySync.Removed.NotForwarded, sync.onRemoved("never"))
        assertTrue(takeRemoves().isEmpty())
    }

    @Test
    fun `a key removed twice queues one remove`() {
        forwarded("k1")
        sync.onRemoved("k1")
        assertEquals(PhoneNotifySync.Removed.NotForwarded, sync.onRemoved("k1"))
        assertEquals(1, takeRemoves().size)
    }

    @Test
    fun `removes queued while disconnected are capped at 16, the newest kept`() {
        val nids = (1..20).map { forwarded("k$it").nid }
        (1..20).forEach { sync.onRemoved("k$it") }
        assertEquals(nids.takeLast(16), takeRemoves())
    }

    @Test
    fun `taking the removes empties the queue`() {
        forwarded("k1")
        sync.onRemoved("k1")
        assertEquals(1, takeRemoves().size)
        assertTrue(takeRemoves().isEmpty())
    }

    @Test
    fun `removes wait while this session has had no HELLO`() {
        val f = forwarded("k1")
        sync.onRemoved("k1")
        assertTrue(takeRemoves(hello = false, sync = false).isEmpty())
        assertEquals(listOf(f.nid), takeRemoves(hello = true, sync = true))
    }

    @Test
    fun `a HELLO without the sync feature drops the removes`() {
        forwarded("k1")
        sync.onRemoved("k1")
        assertTrue(takeRemoves(hello = true, sync = false).isEmpty())
        assertTrue(takeRemoves(hello = true, sync = true).isEmpty())
    }

    @Test
    fun `a key forwarded again after its remove gets a fresh nid and the queued remove stands`() {
        val f = forwarded("k1")
        sync.onRemoved("k1")
        // The registry released the key, so it comes back as a new notification.
        val again = forwarded("k1")
        assertEquals(f.nid + 1, again.nid)
        assertEquals(listOf(f.nid), takeRemoves())
    }

    // ── Watch dismissal (FR-13) ──

    @Test
    fun `a watch dismissal names the phone notification to cancel`() {
        val f = forwarded("k1")
        ack(f)
        assertEquals(PhoneNotifySync.DismissRequest("k1", f.nid, crossedForward = false), sync.onWatchDismissed(f.nid))
    }

    @Test
    fun `a dismissal of a nid the phone never forwarded is ignored`() {
        assertNull(sync.onWatchDismissed(99))
    }

    @Test
    fun `the cancel after a watch dismissal is not echoed back`() {
        val f = forwarded("k1")
        ack(f)
        val request = sync.onWatchDismissed(f.nid)!!
        sync.onCancelStarting(request)
        // The phone reports the cancel as a removal.
        assertEquals(PhoneNotifySync.Removed.WatchDismissed(f.nid), sync.onRemoved("k1"))
        assertTrue(takeRemoves().isEmpty())
    }

    @Test
    fun `a dismissal whose cancel never ran does not hide a later removal`() {
        val f = forwarded("k1")
        ack(f)
        sync.onWatchDismissed(f.nid) // the notification was not clearable: no cancel
        assertEquals(PhoneNotifySync.Removed.Queued(f.nid), sync.onRemoved("k1"))
    }

    @Test
    fun `a notification the watch dismissed and the phone kept is shown again when it changes`() {
        val f = forwarded("k1", "one")
        ack(f)
        sync.onCancelStarting(sync.onWatchDismissed(f.nid)!!)
        val again = forwarded("k1", "two")
        assertEquals(f.nid, again.nid)
        // Cleared on the phone later, it is the watch's to remove again.
        assertEquals(PhoneNotifySync.Removed.Queued(f.nid), sync.onRemoved("k1"))
    }

    @Test
    fun `a dismissal crossing a newer forward still removes the newer entry`() {
        val old = forwarded("k1", "one")
        ack(old)
        // The phone updated the notification; the watch's user dismissed the old entry meanwhile.
        val newer = forwarded("k1", "two")
        val request = sync.onWatchDismissed(old.nid)!!
        assertTrue(request.crossedForward)

        sync.onCancelStarting(request)
        assertEquals(PhoneNotifySync.Removed.Queued(old.nid), sync.onRemoved("k1"))
        assertEquals(listOf(old.nid), takeRemoves())
        assertNull(state.getPendingNotification(newer.msgId))
    }

    // ── Reconcile and clear ──

    @Test
    fun `a forwarded key no longer on the phone is removed from the watch`() {
        val gone = forwarded("k1")
        val kept = forwarded("k2")
        val removed = sync.reconcile(setOf("k2", "other"))
        assertEquals(listOf<PhoneNotifySync.Removed>(PhoneNotifySync.Removed.Queued(gone.nid)), removed)
        assertEquals(listOf(gone.nid), takeRemoves())
        assertNull(state.getPendingNotification(gone.msgId))
        assertEquals(kept.nid, registry.register("k2", 0))
    }

    @Test
    fun `clearing forgets the registry, the removes and the phone's pending, not Home Assistant's`() {
        state.addPendingNotification(200, NotifyPayload(title = "HA", body = "b", ts = 1))
        forwarded("k1")
        forwarded("k2")
        sync.onRemoved("k2")

        sync.clear()

        assertTrue(registry.keys().isEmpty())
        assertTrue(takeRemoves().isEmpty())
        assertTrue(sync.takeUnsent(true).isEmpty())
        assertEquals(listOf(200), state.getAllPendingNotifications().keys.toList())
    }

    // ── Resend (FR-14) ──

    @Test
    fun `an acked forward is not resent`() {
        ack(forwarded("k1"))
        assertTrue(sync.pendingForResend(true).isEmpty())
    }

    @Test
    fun `a removed notification is not resent`() {
        forwarded("k1")
        val keep = forwarded("k2")
        sync.onRemoved("k1")
        assertEquals(listOf(keep.msgId), sync.pendingForResend(true).map { it.first })
    }

    @Test
    fun `only the newest 5 phone forwards are resent, oldest first`() {
        val sent = (1..8).map { forwarded("k$it") }
        val resent = sync.pendingForResend(true).map { it.first }
        assertEquals(sent.takeLast(5).map { it.msgId }, resent)
        assertEquals(5, state.getAllPendingNotifications().size)
    }

    @Test
    fun `Home Assistant notifications keep their pending behaviour`() {
        val ha = (1..8).map { NotifyPayload(title = "HA $it", body = "b", ts = it.toLong()) }
        ha.forEachIndexed { i, p -> state.addPendingNotification(200 + i, p) }
        val phone = forwarded("k1")

        val resent = sync.pendingForResend(true)
        assertEquals((200..207).toList() + phone.msgId, resent.map { it.first })
        assertEquals(9, state.getAllPendingNotifications().size)
    }

    @Test
    fun `a forward with no link stays pending until a reconnect resends it`() {
        val f = forwarded("k1")
        assertEquals(listOf(f.msgId), sync.pendingForResend(true).map { it.first })
        assertEquals(listOf(f.msgId), sync.pendingForResend(true).map { it.first })
    }
}

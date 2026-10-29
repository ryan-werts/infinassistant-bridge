package com.w6tech.infinassistant.core.data.bridge

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-9: which phone notifications are forwarded. */
class PhoneNotificationFilterTest {

    private fun snap(
        flags: Int = 0,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        dnd: Boolean = true,
        title: String? = "Alice",
        text: String? = "Dinner at 7?",
        own: Boolean = false,
        test: Boolean = false,
    ) = PhoneNotificationSnapshot(
        key = "0|com.chat|1|null|10001",
        packageName = if (own) "com.w6tech.infinassistant" else "com.chat",
        appLabel = "Chat",
        flags = flags,
        importance = importance,
        matchesInterruptionFilter = dnd,
        title = title,
        text = text,
        isOwnPackage = own,
        isForwardTest = test,
    )

    private fun decide(s: PhoneNotificationSnapshot, on: Boolean = true, previous: Int? = null) =
        PhoneNotificationFilter.decide(s, on, previous)

    private fun assertSkip(reason: String, d: PhoneNotificationFilter.Decision) =
        assertEquals(PhoneNotificationFilter.Decision.Skip(reason), d)

    @Test
    fun `an ordinary notification is forwarded with its content`() {
        val d = decide(snap())
        assertTrue(d is PhoneNotificationFilter.Decision.Forward)
        d as PhoneNotificationFilter.Decision.Forward
        assertEquals("Alice", d.content.title)
        assertEquals("Dinner at 7?", d.content.body)
    }

    @Test
    fun `nothing is forwarded while the switch is off`() {
        assertSkip("switch-off", decide(snap(), on = false))
    }

    @Test
    fun `this app's own notifications are not forwarded, except the test`() {
        assertSkip("own-package", decide(snap(own = true)))
        assertTrue(decide(snap(own = true, test = true)) is PhoneNotificationFilter.Decision.Forward)
    }

    @Test
    fun `the test is still held to the switch`() {
        assertSkip("switch-off", decide(snap(own = true, test = true), on = false))
    }

    @Test
    fun `ongoing and foreground service notifications are skipped`() {
        assertSkip("ongoing", decide(snap(flags = Notification.FLAG_ONGOING_EVENT)))
        assertSkip("foreground-service", decide(snap(flags = Notification.FLAG_FOREGROUND_SERVICE)))
    }

    @Test
    fun `a group summary is skipped`() {
        assertSkip("group-summary", decide(snap(flags = Notification.FLAG_GROUP_SUMMARY)))
    }

    @Test
    fun `silent notifications are skipped, default and above pass`() {
        assertSkip("silent", decide(snap(importance = NotificationManager.IMPORTANCE_LOW)))
        assertSkip("silent", decide(snap(importance = NotificationManager.IMPORTANCE_MIN)))
        assertTrue(decide(snap(importance = NotificationManager.IMPORTANCE_DEFAULT)) is PhoneNotificationFilter.Decision.Forward)
    }

    @Test
    fun `a notification do not disturb hides is skipped`() {
        assertSkip("do-not-disturb", decide(snap(dnd = false)))
    }

    @Test
    fun `a notification with no title and no text is skipped`() {
        assertSkip("empty", decide(snap(title = null, text = null)))
        assertSkip("empty", decide(snap(title = "  ", text = "")))
    }

    @Test
    fun `a title alone or a text alone is enough`() {
        assertTrue(decide(snap(title = "Alice", text = null)) is PhoneNotificationFilter.Decision.Forward)
        val textOnly = decide(snap(title = null, text = "Hi")) as PhoneNotificationFilter.Decision.Forward
        assertEquals("Chat", textOnly.content.title)
    }

    @Test
    fun `a re-post with the same content is skipped`() {
        val first = decide(snap()) as PhoneNotificationFilter.Decision.Forward
        assertSkip("unchanged", decide(snap(), previous = first.fingerprint))
    }

    @Test
    fun `a changed title or text is forwarded again`() {
        val first = decide(snap()) as PhoneNotificationFilter.Decision.Forward
        assertTrue(decide(snap(text = "Dinner at 8?"), previous = first.fingerprint) is PhoneNotificationFilter.Decision.Forward)
        assertTrue(decide(snap(title = "Bob"), previous = first.fingerprint) is PhoneNotificationFilter.Decision.Forward)
    }

    @Test
    fun `a changed update that asks to alert once is skipped`() {
        val first = decide(snap()) as PhoneNotificationFilter.Decision.Forward
        assertSkip(
            "alert-once-update",
            decide(snap(text = "Dinner at 8?", flags = Notification.FLAG_ONLY_ALERT_ONCE), previous = first.fingerprint),
        )
    }

    @Test
    fun `alert once does not hold back the first post of a key`() {
        assertTrue(decide(snap(flags = Notification.FLAG_ONLY_ALERT_ONCE)) is PhoneNotificationFilter.Decision.Forward)
    }

    @Test
    fun `a second identical message is a new message`() {
        fun chat(sentAt: Long) = snap(title = null, text = null).copy(
            messaging = Messaging("Alice", false, Message("Alice", "ok", sentAt)),
        )
        val first = decide(chat(1_000)) as PhoneNotificationFilter.Decision.Forward
        assertSkip("unchanged", decide(chat(1_000), previous = first.fingerprint))
        assertTrue(decide(chat(2_000), previous = first.fingerprint) is PhoneNotificationFilter.Decision.Forward)
    }
}

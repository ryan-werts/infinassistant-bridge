package com.w6tech.infinassistant.core.data.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-10: the title and body the watch shows. */
class PhoneNotificationContentTest {

    private fun snap(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        messaging: Messaging? = null,
        appLabel: String = "Chat",
    ) = PhoneNotificationSnapshot(
        key = "k",
        packageName = "com.chat",
        appLabel = appLabel,
        flags = 0,
        importance = 4,
        matchesInterruptionFilter = true,
        title = title,
        text = text,
        bigText = bigText,
        messaging = messaging,
    )

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    // ── MessagingStyle ──

    @Test
    fun `a one to one conversation shows the sender and the newest message`() {
        val c = PhoneNotificationContent.build(
            snap(messaging = Messaging(null, false, Message("Alice", "Dinner at 7?", 1))),
        )!!
        assertEquals("Alice", c.title)
        assertEquals("Dinner at 7?", c.body)
    }

    @Test
    fun `a group conversation shows the conversation and the sender`() {
        val c = PhoneNotificationContent.build(
            snap(messaging = Messaging("Family", true, Message("Alice", "Dinner at 7?", 1))),
        )!!
        assertEquals("Family · Alice", c.title)
        assertEquals("Dinner at 7?", c.body)
    }

    @Test
    fun `a group message with no sender shows the conversation`() {
        val c = PhoneNotificationContent.build(
            snap(messaging = Messaging("Family", true, Message(null, "Hi", 1))),
        )!!
        assertEquals("Family", c.title)
    }

    @Test
    fun `a one to one message ignores a conversation title`() {
        val c = PhoneNotificationContent.build(
            snap(messaging = Messaging("Alice", false, Message("Alice B", "Hi", 1))),
        )!!
        assertEquals("Alice B", c.title)
    }

    @Test
    fun `a messaging notification with no messages is built like any other`() {
        val c = PhoneNotificationContent.build(
            snap(title = "Chat", text = "2 new messages", messaging = Messaging("Family", true, null)),
        )!!
        assertEquals("Chat", c.title)
        assertEquals("2 new messages", c.body)
    }

    // ── The rest ──

    @Test
    fun `the body is the big text when there is one, else the text`() {
        assertEquals("long", PhoneNotificationContent.build(snap(title = "T", text = "short", bigText = "long"))!!.body)
        assertEquals("short", PhoneNotificationContent.build(snap(title = "T", text = "short"))!!.body)
        assertEquals("short", PhoneNotificationContent.build(snap(title = "T", text = "short", bigText = "  "))!!.body)
    }

    @Test
    fun `a notification with no title is shown under the app's label`() {
        val c = PhoneNotificationContent.build(snap(text = "Your parcel is here"))!!
        assertEquals("Chat", c.title)
        assertEquals("Your parcel is here", c.body)
    }

    @Test
    fun `nothing to show gives no content, whatever the app's label`() {
        assertNull(PhoneNotificationContent.build(snap()))
        assertNull(PhoneNotificationContent.build(snap(title = " \n", text = "\t")))
    }

    // ── Whitespace and trimming ──

    @Test
    fun `whitespace runs collapse to one space`() {
        val c = PhoneNotificationContent.build(snap(title = "  Alice \n B ", text = "a\n\n b\t\tc d e"))!!
        assertEquals("Alice B", c.title)
        assertEquals("a b c d e", c.body)
    }

    @Test
    fun `ascii is cut at the buffer sizes`() {
        val c = PhoneNotificationContent.build(snap(title = "t".repeat(100), text = "b".repeat(200)))!!
        assertEquals(47, bytes(c.title))
        assertEquals(95, bytes(c.body))
    }

    @Test
    fun `text that fits is left alone`() {
        val c = PhoneNotificationContent.build(snap(title = "t".repeat(47), text = "b".repeat(95)))!!
        assertEquals("t".repeat(47), c.title)
        assertEquals("b".repeat(95), c.body)
    }

    @Test
    fun `a two byte character is never split`() {
        // 46 ASCII + one 2-byte letter = 48 B: the letter would straddle the 47 B limit.
        val title = "a".repeat(46) + "é" + "tail"
        val c = PhoneNotificationContent.build(snap(title = title, text = "x"))!!
        assertEquals("a".repeat(46), c.title)
        assertEquals(46, bytes(c.title))
    }

    @Test
    fun `a three byte character is cut whole or not at all`() {
        val body = "a".repeat(93) + "€€" // 93 + 3 = 96 B for one euro sign
        val c = PhoneNotificationContent.build(snap(title = "T", text = body))!!
        assertEquals("a".repeat(93), c.body)
        val fits = PhoneNotificationContent.build(snap(title = "T", text = "a".repeat(92) + "€€"))!!
        assertEquals("a".repeat(92) + "€", fits.body)
        assertEquals(95, bytes(fits.body))
    }

    @Test
    fun `an emoji surrogate pair is never split`() {
        val emoji = "😀" // 4 bytes
        val title = "a".repeat(44) + emoji + "z" // the emoji ends at 48 B
        val c = PhoneNotificationContent.build(snap(title = title, text = "x"))!!
        assertEquals("a".repeat(44), c.title)

        val fits = PhoneNotificationContent.build(snap(title = "a".repeat(43) + emoji + "z", text = "x"))!!
        assertEquals("a".repeat(43) + emoji, fits.title)
        assertEquals(47, bytes(fits.title))
        assertTrue(fits.title.codePoints().toArray().all { Character.isValidCodePoint(it) })
    }

    @Test
    fun `a body of only emoji is cut to whole emoji`() {
        val body = "😀".repeat(30) // 120 B
        val c = PhoneNotificationContent.build(snap(title = "T", text = body))!!
        assertEquals(23, c.body.codePointCount(0, c.body.length)) // 23 * 4 = 92 B; a 24th would be 96 B
        assertEquals(92, bytes(c.body))
    }

    @Test
    fun `multibyte text stays valid utf8 after the cut`() {
        val body = "日本語".repeat(20) // 3 bytes each
        val c = PhoneNotificationContent.build(snap(title = "T", text = body))!!
        assertEquals(31 * 3, bytes(c.body))
        assertEquals(c.body, String(c.body.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `a cut does not leave a trailing space`() {
        val c = PhoneNotificationContent.build(snap(title = "T", text = "a".repeat(94) + " bcd"))!!
        assertEquals("a".repeat(94), c.body)
    }

    @Test
    fun `a title taken from the app's label is cut too`() {
        val c = PhoneNotificationContent.build(snap(text = "x", appLabel = "L".repeat(80)))!!
        assertEquals(47, bytes(c.title))
    }

    @Test
    fun `a group title is cut to the buffer`() {
        val c = PhoneNotificationContent.build(
            snap(messaging = Messaging("C".repeat(60), true, Message("Alice", "Hi", 1))),
        )!!
        assertEquals(47, bytes(c.title))
    }
}

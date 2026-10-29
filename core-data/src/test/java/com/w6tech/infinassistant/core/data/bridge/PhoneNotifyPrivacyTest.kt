package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.domain.NotifyPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-15: what a notification says stays out of anything that is printed. */
class PhoneNotifyPrivacyTest {

    private val secrets = listOf("hunter2", "Dr Mallory", "Secret Club", "the biopsy result")

    private fun assertNoSecret(printed: String) =
        secrets.forEach { assertFalse("'$it' leaked into: $printed", printed.contains(it)) }

    @Test
    fun `a snapshot prints without its title and text`() {
        val s = PhoneNotificationSnapshot(
            key = "k",
            packageName = "com.chat",
            appLabel = "Chat",
            flags = 0,
            importance = 4,
            matchesInterruptionFilter = true,
            title = "Dr Mallory",
            text = "the biopsy result",
            bigText = "hunter2",
            messaging = Messaging("Secret Club", true, Message("Dr Mallory", "the biopsy result", 99)),
        )
        assertNoSecret(s.toString())
        assertNoSecret(s.messaging.toString())
        assertNoSecret(s.messaging!!.latest.toString())
        assertTrue(s.toString().contains("com.chat"))
    }

    @Test
    fun `a notify payload prints without its title and body`() {
        val payload = NotifyPayload(title = "Dr Mallory", body = "the biopsy result", tag = "7", ts = 1, nid = 7)
        assertNoSecret(payload.toString())
        assertTrue(payload.toString().contains("nid=7"))
    }
}

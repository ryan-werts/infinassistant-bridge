package com.w6tech.infinassistant.core.data.bridge

/**
 * The title and body the watch shows for a phone notification, built from a
 * [PhoneNotificationSnapshot]. The firmware's NOTIFY buffers are 47 B for the title and 95 B for
 * the body (`BridgeController::HandleNotify`: 48 and 96 with the terminator), so both are cut to
 * that many UTF-8 bytes, on a code point boundary.
 */
object PhoneNotificationContent {
    const val TITLE_MAX_BYTES = 47
    const val BODY_MAX_BYTES = 95

    /** A group conversation's title: the conversation, then who wrote. */
    private const val GROUP_SEPARATOR = " · "

    data class Content(val title: String, val body: String)

    /**
     * The content to show, or null when the notification has no title and no text (a title
     * borrowed from the app's label does not count).
     */
    fun build(s: PhoneNotificationSnapshot): Content? {
        val messaging = s.messaging
        val newest = messaging?.latest
        val title: String
        val body: String
        if (messaging != null && newest != null) {
            val sender = clean(newest.sender)
            val conversation = clean(messaging.conversationTitle)
            title = when {
                messaging.isGroup && conversation.isNotEmpty() && sender.isNotEmpty() ->
                    conversation + GROUP_SEPARATOR + sender
                messaging.isGroup && conversation.isNotEmpty() -> conversation
                sender.isNotEmpty() -> sender
                else -> clean(s.title)
            }
            body = clean(newest.text)
        } else {
            title = clean(s.title)
            body = clean(s.bigText).ifEmpty { clean(s.text) }
        }
        if (title.isEmpty() && body.isEmpty()) return null
        return Content(
            title = trimToUtf8Bytes(title.ifEmpty { clean(s.appLabel) }, TITLE_MAX_BYTES),
            body = trimToUtf8Bytes(body, BODY_MAX_BYTES),
        )
    }

    /**
     * A fingerprint of what the watch would show, for telling a re-post from a change. The newest
     * message's time is part of it: a second "ok" from the same sender is a new message with
     * the same words.
     */
    fun fingerprint(s: PhoneNotificationSnapshot, content: Content): Int {
        val sent = s.messaging?.latest?.timestamp ?: 0L
        return "${content.title}\u0000${content.body}\u0000$sent".hashCode()
    }

    /** [text] with every run of whitespace and control characters made one space, and trimmed. */
    fun clean(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        var pendingSpace = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /** The longest start of [text] that is at most [maxBytes] of UTF-8, never cutting a code point. */
    fun trimToUtf8Bytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = utf8Size(cp)
            if (bytes + size > maxBytes) break
            bytes += size
            i += Character.charCount(cp)
        }
        return if (i >= text.length) text else text.substring(0, i).trimEnd()
    }

    /** A lone surrogate counts as 3 so the cut errs on the short side whatever the encoder makes of it. */
    private fun utf8Size(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp in 0xD800..0xDFFF -> 3
        cp < 0x10000 -> 3
        else -> 4
    }
}

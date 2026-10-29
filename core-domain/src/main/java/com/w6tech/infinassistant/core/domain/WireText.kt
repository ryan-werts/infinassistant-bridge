package com.w6tech.infinassistant.core.domain

/**
 * Text for the watch's fixed-size char buffers. The firmware does not truncate a string that does
 * not fit its buffer: it drops it and leaves the field empty (LayoutDecoder's DecodeStringInto
 * rejects a length at or above the buffer size). Strings sent into those buffers are therefore cut
 * here to the buffer's budget in UTF-8 bytes, on a character boundary.
 */
object WireText {

    /** [text] cut to at most [maxBytes] bytes of UTF-8, never splitting a character. */
    fun fit(text: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val n = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) break
            bytes += n
            i += Character.charCount(codePoint)
        }
        return text.substring(0, i)
    }

    /** The length of [text] in UTF-8 bytes. */
    fun utf8Length(text: String): Int = text.encodeToByteArray().size
}

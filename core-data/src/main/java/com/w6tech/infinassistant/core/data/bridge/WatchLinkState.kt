package com.w6tech.infinassistant.core.data.bridge

/**
 * How well the link to the watch is protected. Phone notifications carry the user's private text,
 * so they go to the watch only over a bonded link that the watch does not say is unencrypted, and
 * only once this connection's HELLO has said what the watch reports.
 */
enum class WatchLinkState {
    /** The watch is not bonded with this phone. */
    NOT_PAIRED,

    /** Bonded, but this connection's HELLO has not arrived: what the watch reports is not known yet. */
    CHECKING,

    /** Bonded, and the watch reports the link is not encrypted (yet). */
    PAIRED_NOT_ENCRYPTED,

    /**
     * Bonded on the phone, yet the watch has reported an unencrypted link for longer than
     * [WatchLinkTracker.GRACE_MS]: the watch most likely holds no bond for this phone.
     */
    MISMATCH,

    /** Bonded, and the watch's HELLO has no `enc` (firmware before 2.4.101). */
    PAIRED,

    /** Bonded, and the watch reports the link encrypted. */
    ENCRYPTED;

    /** Whether phone notifications may be sent now. */
    val allowsPhoneNotifications: Boolean get() = this == PAIRED || this == ENCRYPTED

    companion object {
        /**
         * [helloSeen]: this connection's HELLO has arrived. [enc] is the watch's `enc` from that
         * HELLO or its latest PONG, null when it sends none.
         */
        fun of(bonded: Boolean, helloSeen: Boolean, enc: Boolean?): WatchLinkState = when {
            !bonded -> NOT_PAIRED
            !helloSeen -> CHECKING
            enc == false -> PAIRED_NOT_ENCRYPTED
            enc == true -> ENCRYPTED
            else -> PAIRED
        }
    }
}

/**
 * [WatchLinkState.of] plus the passage of time: a link reported unencrypted for [GRACE_MS] without
 * becoming encrypted is a [WatchLinkState.MISMATCH].
 */
class WatchLinkTracker(private val clockMs: () -> Long = System::currentTimeMillis) {
    private var notEncryptedSinceMs: Long? = null

    @Synchronized
    fun update(bonded: Boolean, helloSeen: Boolean, enc: Boolean?): WatchLinkState {
        val state = WatchLinkState.of(bonded, helloSeen, enc)
        if (state != WatchLinkState.PAIRED_NOT_ENCRYPTED) {
            notEncryptedSinceMs = null
            return state
        }
        val since = notEncryptedSinceMs ?: clockMs().also { notEncryptedSinceMs = it }
        return if (clockMs() - since >= GRACE_MS) WatchLinkState.MISMATCH else state
    }

    companion object {
        const val GRACE_MS = 60_000L
    }
}

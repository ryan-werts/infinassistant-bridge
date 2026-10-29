package com.w6tech.infinassistant.core.data.bridge

/**
 * What the forwarding rules read of one phone notification: the fields of its
 * `StatusBarNotification` and `Ranking`, copied out as plain values. The listener service fills
 * it in, so the filter ([PhoneNotificationFilter]) and the content builder
 * ([PhoneNotificationContent]) run without the framework.
 */
data class PhoneNotificationSnapshot(
    /** `StatusBarNotification.key`. */
    val key: String,
    val packageName: String,
    /** The app's label, for a notification that has no title. */
    val appLabel: String,
    /** `Notification.flags`. */
    val flags: Int,
    /** `Ranking.getImportance()`. */
    val importance: Int,
    /** `Ranking.matchesInterruptionFilter()`: the phone's Do Not Disturb would show it. */
    val matchesInterruptionFilter: Boolean,
    /** `EXTRA_TITLE`. */
    val title: String? = null,
    /** `EXTRA_TEXT`. */
    val text: String? = null,
    /** `EXTRA_BIG_TEXT`. */
    val bigText: String? = null,
    /** Set for a MessagingStyle notification. */
    val messaging: Messaging? = null,
    /** This app's own notification, from this app's package. */
    val isOwnPackage: Boolean = false,
    /** The "Send a test" notification: the one own notification that is forwarded. */
    val isForwardTest: Boolean = false,
) {
    // The title and text are the user's private words: not for logs or bundles.
    override fun toString(): String =
        "PhoneNotificationSnapshot(package=$packageName, flags=$flags, importance=$importance, " +
            "dnd=$matchesInterruptionFilter, messaging=${messaging != null}, own=$isOwnPackage, test=$isForwardTest)"
}

/** A MessagingStyle notification: the conversation and its newest message. */
data class Messaging(
    val conversationTitle: String?,
    val isGroup: Boolean,
    /** The newest message, or null for a style with none yet. */
    val latest: Message?,
) {
    override fun toString(): String = "Messaging(group=$isGroup, latest=$latest)"
}

data class Message(
    /** The sender's name. */
    val sender: String?,
    val text: String?,
    /** When the message was sent, epoch ms. */
    val timestamp: Long,
) {
    override fun toString(): String = "Message(timestamp=$timestamp)"
}

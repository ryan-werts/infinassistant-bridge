package com.w6tech.infinassistant.core.data.bridge

import android.app.Notification
import android.app.NotificationManager

/** Which phone notifications go to the watch. */
object PhoneNotificationFilter {

    sealed interface Decision {
        /** Send [content]; [fingerprint] is kept to tell a later re-post from a change. */
        data class Forward(val content: PhoneNotificationContent.Content, val fingerprint: Int) : Decision

        /** Do not send; [reason] is a short fixed word, safe to log. */
        data class Skip(val reason: String) : Decision
    }

    /**
     * Forward only when the switch is on, and the notification is from another app (or is the
     * test), is not ongoing, a foreground service's or a group summary, is not silent, is one the
     * phone's Do Not Disturb would show, and has a title or text. A key already forwarded
     * ([previousFingerprint]) is forwarded again only when what the watch would show has changed,
     * and not for an update that asks to alert once.
     */
    fun decide(s: PhoneNotificationSnapshot, switchOn: Boolean, previousFingerprint: Int?): Decision {
        if (!switchOn) return Decision.Skip("switch-off")
        if (s.isOwnPackage && !s.isForwardTest) return Decision.Skip("own-package")
        if (s.flags and Notification.FLAG_ONGOING_EVENT != 0) return Decision.Skip("ongoing")
        if (s.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return Decision.Skip("foreground-service")
        if (s.flags and Notification.FLAG_GROUP_SUMMARY != 0) return Decision.Skip("group-summary")
        if (s.importance < NotificationManager.IMPORTANCE_DEFAULT) return Decision.Skip("silent")
        if (!s.matchesInterruptionFilter) return Decision.Skip("do-not-disturb")
        val content = PhoneNotificationContent.build(s) ?: return Decision.Skip("empty")
        val fingerprint = PhoneNotificationContent.fingerprint(s, content)
        if (previousFingerprint != null) {
            if (previousFingerprint == fingerprint) return Decision.Skip("unchanged")
            if (s.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) return Decision.Skip("alert-once-update")
        }
        return Decision.Forward(content, fingerprint)
    }
}

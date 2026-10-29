package com.w6tech.infinassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The notification behind "Send a test" on the Notifications screen: a real notification from this
 * app, on a channel of its own with a fixed id and tag, so [NotificationForwarderService] can
 * recognise it and let that one through its own-package rule. It then travels the whole way: to the
 * watch, and, swiped away there, back off the phone.
 */
object ForwardTestNotification {
    private const val CHANNEL_ID = "phone_forward_test"
    private const val NOTIF_ID = 1005
    private const val NOTIF_TAG = "phone-forward-test"

    /** Posts the notification; false when the phone does not let this app post notifications. */
    fun post(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.phone_forward_test_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val text = context.getString(R.string.phone_forward_test_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(context.getString(R.string.phone_forward_test_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_TAG, NOTIF_ID, notification)
        return true
    }

    /** True for the notification [post] makes. */
    fun matches(sbn: StatusBarNotification, ownPackage: String): Boolean =
        sbn.packageName == ownPackage && sbn.id == NOTIF_ID && sbn.tag == NOTIF_TAG &&
            sbn.notification?.channelId == CHANNEL_ID
}

package com.w6tech.infinassistant.ui

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.w6tech.infinassistant.MainActivity
import no.nordicsemi.android.dfu.DfuBaseService

/**
 * Nordic DFU service implementation required by the DFU library.
 * This service handles the actual firmware update process.
 */
class DfuService : DfuBaseService() {

    companion object {
        private const val DFU_NOTIFICATION_CHANNEL_ID = "dfu_channel"
    }

    override fun onCreate() {
        super.onCreate()
        // Create notification channel for Android 8+ (required for foreground service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                DFU_NOTIFICATION_CHANNEL_ID,
                "Firmware Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of PineTime firmware updates"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun getNotificationTarget(): Class<out Activity> {
        // Return MainActivity so notification taps open the app
        return MainActivity::class.java
    }

    override fun isDebug(): Boolean {
        // Enable debug logs for better troubleshooting
        return true
    }
}

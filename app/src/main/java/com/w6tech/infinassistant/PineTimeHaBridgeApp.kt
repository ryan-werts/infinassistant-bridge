package com.w6tech.infinassistant

import dagger.hilt.android.HiltAndroidApp
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

@HiltAndroidApp
class PineTimeHaBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Create notification channel for DFU service (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Create DFU notification channel
            val dfuChannel = NotificationChannel(
                "dfu_channel",
                "Firmware Updates",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of PineTime firmware updates"
            }
            notificationManager.createNotificationChannel(dfuChannel)
        }
    }
}

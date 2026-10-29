package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Severity { INFO, WARN, CRITICAL }

@Serializable
enum class NotificationDeliveryMode { WATCH, HA_INBOX, BOTH }

@Serializable
data class QuietHours(
    /** 0-23 */ val startHour: Int,
    /** 0-23 */ val endHour: Int,
)

@Serializable
data class NotificationPrefs(
    val deliverToWatch: Boolean = true,
    val deliveryMode: NotificationDeliveryMode = NotificationDeliveryMode.WATCH,
    val dedupeByTag: Boolean = true,
    val quietHours: QuietHours? = null,
    /** Whether to forward native Android system notifications to watch. */
    val forwardSystemNotifications: Boolean = false,
    /** FR-AF-1: a phone notification when the watch battery is at [watchBatteryAlertPercent] or below. */
    val watchBatteryAlert: Boolean = true,
    val watchBatteryAlertPercent: Int = 10,
    /** FR-AF-2: a phone notification when the watch has been disconnected for [watchDisconnectAlertMinutes]. */
    val watchDisconnectAlert: Boolean = true,
    val watchDisconnectAlertMinutes: Int = 5,
)

@Serializable
data class EntityConfig(
    val entityId: String,
)

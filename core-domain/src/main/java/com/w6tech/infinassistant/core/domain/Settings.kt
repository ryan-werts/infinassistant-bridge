package com.w6tech.infinassistant.core.domain

import kotlinx.serialization.Serializable

@Serializable
data class HaConnectionSettings(
    val localUrl: String = "",
    val remoteUrl: String = "",
    /**
     * IPv4 subnets ("192.0.2.0/24") the phone must be on for an http local URL to be used: its
     * token goes out unencrypted, so only on the home networks. Empty: on any network.
     */
    val homeSubnets: List<String> = emptyList(),
    /** Token is stored in encrypted storage; this flag just indicates whether we have one. */
    val hasToken: Boolean = false,
)

@Serializable
data class InfinassistantSettings(
    val enabled: Boolean = false,
    val webhookId: String = "",
    val sensorReportIntervalSeconds: Int = 60,
    /**
     * URL-safe base64 HMAC secret issued by the integration's config flow
     * (the `hmac_secret` field of the setup QR payload). The integration
     * rejects unsigned webhook posts with HTTP 401, so this must be set for
     * sensor reporting to reach Home Assistant.
     */
    val hmacSecret: String = "",
)

/**
 * Voice assist from the watch: off until the owner turns it on, since a double-click then records
 * from the microphone.
 */
@Serializable
data class VoiceAssistSettings(
    val enabled: Boolean = false,
    /** The Assist pipeline to use; null is the one HA marks as preferred. */
    val pipelineId: String? = null,
)

@Serializable
data class WatchDevice(
    val address: String,
    val name: String = "",
    val isPrimary: Boolean = false,
)

@Serializable
data class BridgeSettings(
    val ha: HaConnectionSettings = HaConnectionSettings(),
    /** Legacy single-device field — use [associatedDevices] for multi-watch. */
    val associatedDeviceAddress: String? = null,
    val associatedDevices: List<WatchDevice> = emptyList(),
    val connectedModeEnabled: Boolean = false,
    val notificationPrefs: NotificationPrefs = NotificationPrefs(),
    /** HA custom event type used for watch notifications. */
    val notificationEventType: String = "pt_bridge_notify",
    val infinassistant: InfinassistantSettings = InfinassistantSettings(),
    val voiceAssist: VoiceAssistSettings = VoiceAssistSettings(),
    /** Last successfully pushed watch face JSON (for manual import tab). */
    val lastPushedWatchFaceJson: String? = null,
    /** Last resolved WatchFaceLayoutPayload serialized as JSON (for logbook lookup after app restart). */
    val lastResolvedWatchFaceLayoutJson: String? = null,
    /** UNC path to an SMB share used for one-tap debug bundle uploads. */
    val debugSharePath: String = "",
    /** Optional SMB username (blank = anonymous). */
    val debugShareUsername: String = "",
    /** Optional SMB password (used only if username is set). */
    val debugSharePassword: String = "",
) {
    /** Returns the primary device address, falling back to legacy single-device field. */
    val primaryDeviceAddress: String?
        get() = associatedDevices.firstOrNull { it.isPrimary }?.address
            ?: associatedDevices.firstOrNull()?.address
            ?: associatedDeviceAddress
}

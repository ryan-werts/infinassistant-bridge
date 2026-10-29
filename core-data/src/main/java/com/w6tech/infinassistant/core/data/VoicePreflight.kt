package com.w6tech.infinassistant.core.data

/**
 * The checks a voice trigger passes before the phone tells the watch it is listening. A trigger
 * that cannot work answers with a short reason, which the watch buzzes as a failure, instead of a
 * "listening" buzz that leads nowhere.
 */
object VoicePreflight {
    const val OFF = "Voice assist is off"
    const val NO_PERMISSION = "Microphone permission needed"

    /**
     * The bridge's foreground service started without the microphone type (after a reboot, Android
     * does not allow it), and gains it when the app is opened.
     */
    const val NEEDS_APP_OPEN = "Open the app once to enable voice"
    const val NO_TOKEN = "HA token not configured"
    const val TOKEN_REJECTED = VoiceAuthGuard.REJECTED_MESSAGE

    /** @return Why voice cannot start, or null when it can. The first failing check wins. */
    fun failure(
        enabled: Boolean,
        microphonePermission: Boolean,
        serviceMicrophone: Boolean,
        hasToken: Boolean,
        tokenRejected: Boolean = false,
    ): String? = when {
        !enabled -> OFF
        !microphonePermission -> NO_PERMISSION
        !serviceMicrophone -> NEEDS_APP_OPEN
        !hasToken -> NO_TOKEN
        tokenRejected -> TOKEN_REJECTED
        else -> null
    }
}

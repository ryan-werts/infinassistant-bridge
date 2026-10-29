package com.w6tech.infinassistant.bridge

import android.app.ForegroundServiceStartNotAllowedException
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Which foreground-service types the bridge service runs with. It always has `connectedDevice`
 * (the BLE link). It adds `microphone` so the phone can record for voice assist while the app is
 * in the background, but Android only lets a service enter that type from the foreground: the
 * microphone permission must be granted, and the start must not come from the background. A boot
 * (Android 15 forbids a microphone-type service from BOOT_COMPLETED), an update restart or the
 * system restarting a killed service is a background start; the service gains the microphone the
 * next time the app is opened.
 */
internal object ForegroundTypes {
    const val CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    /**
     * The types to enter the foreground with.
     *
     * @param recordAudioGranted Whether the RECORD_AUDIO permission is granted.
     * @param backgroundStart Whether this start comes from the background, where Android refuses
     *   the microphone type.
     */
    fun select(recordAudioGranted: Boolean, backgroundStart: Boolean): Int =
        if (recordAudioGranted && !backgroundStart) CONNECTED_DEVICE or MICROPHONE else CONNECTED_DEVICE

    fun hasMicrophone(types: Int): Boolean = (types and MICROPHONE) != 0

    /**
     * Whether [error] is Android refusing a type for this start (as opposed to a bug): a
     * permission or a state the type needs is missing. The service falls back to
     * `connectedDevice` for these.
     */
    fun isTypeRefusal(error: Throwable, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        error is SecurityException ||
            error is ForegroundServiceStartNotAllowedException ||
            (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isInvalidType(error))

    // Its own function so the class is only loaded where it exists (API 34).
    private fun isInvalidType(error: Throwable): Boolean =
        error is android.app.InvalidForegroundServiceTypeException
}

package com.w6tech.infinassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.w6tech.infinassistant.bridge.BridgeForegroundService
import com.w6tech.infinassistant.core.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Restarts [BridgeForegroundService] after device boot, and after the app is
 * updated in place, so the BLE bridge reconnects without user interaction
 * (FR-AR-6).
 *
 * This receiver existed but was never registered in the manifest, so it was
 * compiled and never instantiated — the bridge stayed down after a reboot
 * until the user opened the app.
 *
 * The service is only started when Connected Mode is actually enabled and a
 * watch is associated. [BridgeForegroundService.onStartCommand] does not check
 * either, so starting it unconditionally would post a persistent notification
 * for a bridge the user has turned off — and on API 31+ a background
 * foreground-service start is only permitted here because of the Companion
 * Device Manager association, which does not exist until a watch is paired.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val tag = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // Reading settings is asynchronous, so hold the broadcast open. The
        // window is short and bounded: a BroadcastReceiver is killed after
        // roughly 10 s, so give up well before that rather than risk an ANR.
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = withTimeoutOrNull(5_000) { settingsRepository.settings.first() }
                when {
                    settings == null ->
                        Log.w(tag, "Timed out reading settings; not starting bridge")
                    !settings.connectedModeEnabled ->
                        Log.i(tag, "Connected Mode disabled; not starting bridge")
                    settings.associatedDeviceAddress == null ->
                        Log.i(tag, "No watch associated; not starting bridge")
                    else -> {
                        // minSdk is 31, so startForegroundService is always the
                        // correct entry point.
                        // A boot or update start is a background start: the service must not
                        // ask for the microphone type, which Android refuses it there.
                        appContext.startForegroundService(
                            Intent(appContext, BridgeForegroundService::class.java)
                                .putExtra(BridgeForegroundService.EXTRA_BACKGROUND_START, true)
                        )
                        Log.i(tag, "Bridge restarted after ${intent.action}")
                    }
                }
            } catch (e: Exception) {
                // Never let a boot broadcast crash the app.
                Log.w(tag, "Failed to restart bridge: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }
}

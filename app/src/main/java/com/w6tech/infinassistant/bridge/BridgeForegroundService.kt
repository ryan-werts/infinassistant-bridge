package com.w6tech.infinassistant.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.w6tech.infinassistant.MainActivity
import com.w6tech.infinassistant.R
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.MdnsManager
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.data.WatchAlertTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class BridgeForegroundService : Service() {

    @Inject lateinit var engine: BridgeEngine
    @Inject lateinit var logger: Logger
    @Inject lateinit var mdnsManager: MdnsManager
    @Inject lateinit var settingsRepo: SettingsRepository

    private val tag = "BridgeForegroundService"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var registrationJob: Job? = null
    private val alertTracker = WatchAlertTracker()

    // The foreground types the service runs with now (ForegroundTypes), and whether a refusal of
    // the microphone type has been logged: once per service run is enough.
    private var activeTypes = 0
    private var micRefusalLogged = false

    // The first startForeground was refused: the service is stopping and does nothing more.
    private var startFailed = false

    override fun onCreate() {
        super.onCreate()

        // The first start is always connectedDevice, which no start state refuses, so the
        // five-second foreground deadline is met whatever happens next. onStartCommand then adds
        // the microphone when this start allows it (refreshForegroundTypes).
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ForegroundTypes.CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            if (!ForegroundTypes.isTypeRefusal(e)) throw e
            // Android would not let this start enter the foreground at all (a missing permission,
            // or a start from a state that is not allowed). Stop, rather than crash the app.
            logger.e(tag, "Could not enter the foreground; stopping the bridge service", e)
            startFailed = true
            stopSelf()
            return
        }
        activeTypes = ForegroundTypes.CONNECTED_DEVICE
        engine.setServiceMicrophone(false)

        try {
            engine.start()
        } catch (e: Exception) {
            logger.e(tag, "Engine start failed, stopping service", e)
            stopSelf()
            return
        }

        // Observe settings and manage mDNS registration. The registration depends on the webhook
        // URL and the watch's address only, so only a change of either registers again: a settings
        // write that changes neither (a slider, a switch) used to register anew each time, and
        // each registration that failed was logged and tried once more.
        serviceScope.launch {
            settingsRepo.settings
                .map { settings ->
                    val primaryAddr = settings.primaryDeviceAddress
                    val baseUrl = settings.ha.localUrl.ifBlank { settings.ha.remoteUrl }
                    if (settings.infinassistant.enabled &&
                        settings.infinassistant.webhookId.isNotBlank() &&
                        primaryAddr != null &&
                        baseUrl.isNotBlank()
                    ) {
                        "$baseUrl/api/webhook/${settings.infinassistant.webhookId}" to primaryAddr
                    } else {
                        null
                    }
                }
                .distinctUntilChanged()
                .collectLatest { registration ->
                    // Cancel any previous registration flow
                    registrationJob?.cancel()
                    registrationJob = null

                    if (registration == null) {
                        mdnsManager.unregisterService()
                    } else {
                        // A registration that fails ends its flow: it is not tried again until the URL
                        // or the watch changes.
                        registrationJob = mdnsManager.registerService(registration.first, registration.second)
                            .launchIn(serviceScope)
                    }
                }
        }

        // REQ-067 — observe safe-face trip events and post a system notification
        // directing the user to the preflight (WatchFaceImport) screen so they
        // can diagnose and reduce the layout weight.
        serviceScope.launch {
            engine.safeFaceTripEvent.collect { tripCount ->
                postSafeFaceTripNotification(tripCount)
            }
        }

        // FR-AF-1/2 — watch battery low and watch disconnected alerts. The tick lets the
        // disconnected alert fire while the status stays the same; it runs only while the watch is away.
        serviceScope.launch {
            val ticks = WatchAlertTracker.ticksWhileAway(engine.status, ALERT_TICK_MS)
            combine(engine.status, settingsRepo.settings.map { it.notificationPrefs }.distinctUntilChanged(), ticks) { status, prefs, _ ->
                status to prefs
            }.collect { (status, prefs) ->
                alertTracker.update(status, prefs, System.currentTimeMillis()).forEach(::applyWatchAlert)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startFailed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
            }
            ACTION_PING -> engine.pingWatch()
            ACTION_RESYNC -> engine.forceResyncStates()
            ACTION_TEST_NOTIFY -> engine.sendTestNotification()
            ACTION_MUTE_DISCONNECT_ALERT -> {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(DISCONNECT_NOTIF_ID)
                serviceScope.launch {
                    val prefs = settingsRepo.settings.first().notificationPrefs
                    settingsRepo.setNotificationPrefs(prefs.copy(watchDisconnectAlert = false))
                }
            }
            else -> Unit
        }
        // Only a start the app itself makes can enter the microphone type. A watch-alert action
        // from a notification, or the system restarting a killed service (a null intent), must not
        // try it, and a failed try must not take the type away from a service that has it.
        when (intent?.action) {
            ACTION_START, ACTION_REFRESH_TYPES, null ->
                refreshForegroundTypes(backgroundStart = intent == null || intent.getBooleanExtra(EXTRA_BACKGROUND_START, false))
            else -> Unit
        }
        return START_STICKY
    }

    /**
     * Enters the foreground with the types [ForegroundTypes.select] picks for this start, and tells
     * the engine whether the microphone is among them. Android refuses a type for a start it does
     * not allow it from; that leaves the service with the types it has (connectedDevice at the
     * least), is logged once, and voice answers a trigger with "Open the app once to enable voice".
     */
    private fun refreshForegroundTypes(backgroundStart: Boolean) {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val wanted = ForegroundTypes.select(granted, backgroundStart)
        // Never drop a type the service already has.
        val target = activeTypes or wanted
        if (target != activeTypes) {
            try {
                ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), target)
                activeTypes = target
            } catch (e: Exception) {
                if (!ForegroundTypes.isTypeRefusal(e)) throw e
                if (!micRefusalLogged) {
                    micRefusalLogged = true
                    logger.w(tag, "Foreground service type refused (microphone=${ForegroundTypes.hasMicrophone(target)}, " +
                        "background=$backgroundStart); staying at types=$activeTypes", e)
                }
            }
        }
        engine.setServiceMicrophone(ForegroundTypes.hasMicrophone(activeTypes))
    }

    /** FR-AF-1/2 — shows or takes away a watch alert ([WatchAlertTracker]). */
    private fun applyWatchAlert(action: WatchAlertTracker.Action) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            WatchAlertTracker.Action.ClearBatteryLow -> nm.cancel(BATTERY_NOTIF_ID)
            WatchAlertTracker.Action.ClearDisconnected -> nm.cancel(DISCONNECT_NOTIF_ID)
            is WatchAlertTracker.Action.ShowBatteryLow -> {
                ensureWatchAlertChannel(nm)
                val notif = NotificationCompat.Builder(this, WATCH_ALERTS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(getString(R.string.watch_battery_low_title))
                    .setContentText(getString(R.string.watch_battery_low_text, action.percent))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .build()
                nm.notify(BATTERY_NOTIF_ID, notif)
                logger.d(tag, "FR-AF-1: watch battery low (${action.percent}%)")
            }
            is WatchAlertTracker.Action.ShowDisconnected -> {
                ensureWatchAlertChannel(nm)
                val since = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(action.sinceMs))
                val text = getString(R.string.watch_disconnected_text, since)
                val mute = PendingIntent.getService(
                    this,
                    DISCONNECT_NOTIF_ID,
                    Intent(this, BridgeForegroundService::class.java).setAction(ACTION_MUTE_DISCONNECT_ALERT),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                val notif = NotificationCompat.Builder(this, WATCH_ALERTS_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(getString(R.string.watch_disconnected_title))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openAppIntent())
                    .addAction(0, getString(R.string.watch_alert_dont_remind), mute)
                    .build()
                nm.notify(DISCONNECT_NOTIF_ID, notif)
                logger.d(tag, "FR-AF-2: watch disconnected since $since")
            }
        }
    }

    private fun ensureWatchAlertChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(
                WATCH_ALERTS_CHANNEL_ID,
                getString(R.string.watch_alerts_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ))
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        super.onDestroy()
        mdnsManager.unregisterService()
        serviceScope.cancel()
        engine.setServiceMicrophone(false)
        engine.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "InfinAssistant Bridge", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.connected_mode_notif_title))
            .setContentText(getString(R.string.connected_mode_notif_text))
            .setOngoing(true)

        // Set foreground service behavior for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    /**
     * REQ-067 — post a dismissible system notification when the watch reports
     * a new safe-face trip. The notification deep-links into the layout preflight
     * screen (WatchFaceImport) so the user can reduce layout weight.
     */
    private fun postSafeFaceTripNotification(tripCount: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                SAFE_FACE_CHANNEL_ID,
                "Layout Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when the watch layout exceeds available memory"
            }
            nm.createNotificationChannel(ch)
        }

        // Intent opens the app and navigates to the WatchFaceImport (preflight) screen.
        // The SAFE_FACE_TRIP extra lets MainActivity route directly to the preflight route.
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_TO, ROUTE_WATCHFACE_IMPORT)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            SAFE_FACE_PENDING_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(this, SAFE_FACE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.safe_face_notif_title))
            .setContentText(getString(R.string.safe_face_notif_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(getString(R.string.safe_face_notif_text)))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(SAFE_FACE_NOTIF_ID, notif)
        logger.d(tag, "REQ-067: posted safe-face trip notification (trips=$tripCount)")
    }

    companion object {
        const val ACTION_START = "com.w6tech.infinassistant.action.START"
        const val ACTION_STOP = "com.w6tech.infinassistant.action.STOP"
        const val ACTION_PING = "com.w6tech.infinassistant.action.PING"
        const val ACTION_RESYNC = "com.w6tech.infinassistant.action.RESYNC"
        const val ACTION_TEST_NOTIFY = "com.w6tech.infinassistant.action.TEST_NOTIFY"
        /**
         * Sent by the app when it comes to the foreground (and after the microphone permission is
         * granted): a service that started in the background, after a reboot, gains the microphone
         * type now.
         */
        const val ACTION_REFRESH_TYPES = "com.w6tech.infinassistant.action.REFRESH_TYPES"
        /** Intent extra, true on a start from the boot or update receiver: the microphone type is not allowed there. */
        const val EXTRA_BACKGROUND_START = "background_start"
        /** "Don't remind me" on the disconnected alert: turns that alert off. */
        const val ACTION_MUTE_DISCONNECT_ALERT = "com.w6tech.infinassistant.action.MUTE_DISCONNECT_ALERT"

        // FR-AF-1/2 — watch alerts.
        const val WATCH_ALERTS_CHANNEL_ID = "bridge_alerts"
        private const val BATTERY_NOTIF_ID = 1003
        private const val DISCONNECT_NOTIF_ID = 1004
        private const val ALERT_TICK_MS = 60_000L

        private const val CHANNEL_ID = "connected_mode"
        private const val NOTIF_ID = 1001

        // REQ-067 — safe-face trip alert channel and notification constants.
        const val SAFE_FACE_CHANNEL_ID = "safe_face_alerts"
        private const val SAFE_FACE_NOTIF_ID = 1002
        private const val SAFE_FACE_PENDING_ID = 1002

        /** Intent extra: name of the in-app route to navigate to on tap. */
        const val EXTRA_NAVIGATE_TO = "navigate_to"

        /** Route value that maps to the WatchFaceImport / preflight screen. */
        const val ROUTE_WATCHFACE_IMPORT = "watchface_import"
    }
}

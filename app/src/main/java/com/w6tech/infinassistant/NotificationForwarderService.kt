package com.w6tech.infinassistant

import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.BridgeEngine
import com.w6tech.infinassistant.core.data.SettingsRepository
import com.w6tech.infinassistant.core.data.bridge.Message
import com.w6tech.infinassistant.core.data.bridge.Messaging
import com.w6tech.infinassistant.core.data.bridge.PhoneNotificationSnapshot
import com.w6tech.infinassistant.core.data.bridge.PhoneNotifySync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * NotificationListenerService that captures Android system notifications and forwards them to
 * the PineTime watch via BridgeEngine, and takes them off the phone when the watch dismisses them.
 *
 * What is forwarded, and the queueing, is [PhoneNotifySync]'s; this class reads the framework's
 * objects into a [PhoneNotificationSnapshot] and acts on the result. It never logs a
 * notification's title or text, nor the message of an exception that may quote them: the log
 * goes into debug bundles.
 */
@AndroidEntryPoint
class NotificationForwarderService : NotificationListenerService() {

    @Inject
    lateinit var bridgeEngine: BridgeEngine

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var logger: Logger

    private val tag = "NotificationForwarder"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectedJob: Job? = null

    // Kept current from the settings so the callbacks below can run in order, without a suspension
    // that would let a removal overtake the post it follows.
    @Volatile
    private var forwardingOn = false

    override fun onBind(intent: Intent?): IBinder? {
        logger.d(tag, "Service bound")
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        logger.d(tag, "Notification listener connected")
        // The first post is judged by the switch as it is now, not as it was left by a past connection.
        val seeded = runBlocking {
            withTimeoutOrNull(SEED_TIMEOUT_MS) {
                settingsRepository.settings.first().notificationPrefs.forwardSystemNotifications
            }
        }
        if (seeded == null) logger.w(tag, "Switch read timed out after $SEED_TIMEOUT_MS ms; forwarding stays off until it arrives")
        forwardingOn = seeded ?: false
        reconcileWithPhone()
        connectedJob?.cancel()
        connectedJob = serviceScope.launch {
            val firstValue = CompletableDeferred<Unit>()
            launch {
                settingsRepository.settings
                    .map { it.notificationPrefs.forwardSystemNotifications }
                    .distinctUntilChanged()
                    .collect {
                        forwardingOn = it
                        firstValue.complete(Unit)
                    }
            }
            launch {
                // Requests wait in the engine's queue until the switch is known: a late seed must not
                // make queued dismissals read as "switch off".
                firstValue.await()
                bridgeEngine.phoneDismissRequests.collect { request ->
                    if (forwardingOn) {
                        cancelOnPhone(request)
                    } else {
                        logger.d(tag, "nid=${request.nid} decision=keep reason=switch-off")
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        logger.d(tag, "Notification listener disconnected")
        connectedJob?.cancel()
        connectedJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        super.onNotificationPosted(sbn, rankingMap)
        try {
            val snapshot = snapshotOf(sbn, rankingMap)
            if (snapshot == null) {
                if (forwardingOn) logger.d(tag, "pkg=${sbn.packageName} decision=skip reason=no-ranking")
                return
            }
            when (val posted = bridgeEngine.onPhoneNotificationPosted(snapshot, forwardingOn)) {
                is PhoneNotifySync.Posted.Forwarded ->
                    logger.d(tag, "pkg=${sbn.packageName} nid=${posted.nid} decision=forward")
                is PhoneNotifySync.Posted.Skipped ->
                    if (forwardingOn) logger.d(tag, "pkg=${sbn.packageName} decision=skip reason=${posted.reason}")
            }
        } catch (e: Exception) {
            // The class only: an exception's message can quote the notification's text.
            logger.w(tag, "pkg=${sbn.packageName} decision=error reason=${e::class.java.simpleName}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        try {
            // This app's own cancel after a watch dismissal arrives here too (REASON_LISTENER_CANCEL),
            // and so does a cancel by another listener. The registry told them apart before: a key the
            // watch dismissed is already gone there and is not echoed back.
            when (val removed = bridgeEngine.onPhoneNotificationRemoved(sbn.key)) {
                PhoneNotifySync.Removed.NotForwarded -> Unit
                is PhoneNotifySync.Removed.Queued ->
                    logger.d(tag, "pkg=${sbn.packageName} nid=${removed.nid} reason=$reason decision=remove-watch")
                is PhoneNotifySync.Removed.WatchDismissed ->
                    logger.d(tag, "pkg=${sbn.packageName} nid=${removed.nid} reason=$reason decision=no-echo")
            }
        } catch (e: Exception) {
            logger.w(tag, "pkg=${sbn.packageName} decision=error reason=${e::class.java.simpleName}")
        }
    }

    /**
     * Notifications removed while nobody listened (the service was not bound) were never reported:
     * forwarded keys that are no longer on the phone are taken off the watch too.
     */
    private fun reconcileWithPhone() {
        try {
            val active = getActiveNotifications()?.map { it.key }?.toSet() ?: return
            val removed = bridgeEngine.reconcilePhoneNotifications(active)
            if (removed > 0) logger.d(tag, "reconcile: $removed forwarded notifications no longer active")
        } catch (e: Exception) {
            logger.w(tag, "reconcile failed reason=${e::class.java.simpleName}")
        }
    }

    /** The watch dismissed it: clear it off the phone, if it is still there and the phone allows it. */
    private fun cancelOnPhone(request: PhoneNotifySync.DismissRequest) {
        try {
            val sbn = getActiveNotifications(arrayOf(request.key))?.firstOrNull()
            when {
                sbn == null -> logger.d(tag, "nid=${request.nid} decision=keep reason=gone")
                !sbn.isClearable ->
                    logger.d(tag, "pkg=${sbn.packageName} nid=${request.nid} decision=keep reason=not-clearable")
                else -> {
                    // Marked now, not earlier: only a cancel that really runs comes back as a removal to not echo.
                    bridgeEngine.onPhoneCancelStarting(request)
                    cancelNotification(request.key)
                    logger.d(tag, "pkg=${sbn.packageName} nid=${request.nid} decision=cancel")
                }
            }
        } catch (e: Exception) {
            logger.w(tag, "nid=${request.nid} decision=error reason=${e::class.java.simpleName}")
        }
    }

    /** Null when the system has no ranking for the notification (it is gone already). */
    private fun snapshotOf(sbn: StatusBarNotification, rankingMap: RankingMap): PhoneNotificationSnapshot? {
        val notification = sbn.notification ?: return null
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return null
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val ownPackage = sbn.packageName == packageName
        return PhoneNotificationSnapshot(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            flags = notification.flags,
            importance = ranking.importance,
            matchesInterruptionFilter = ranking.matchesInterruptionFilter(),
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            messaging = style?.let {
                Messaging(
                    conversationTitle = it.conversationTitle?.toString(),
                    isGroup = it.isGroupConversation,
                    latest = it.messages.lastOrNull()?.let { m ->
                        Message(m.person?.name?.toString(), m.text?.toString(), m.timestamp)
                    },
                )
            },
            isOwnPackage = ownPackage,
            isForwardTest = ownPackage && ForwardTestNotification.matches(sbn, packageName),
        )
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        pkg
    }

    private companion object {
        /** The longest the first post waits for the switch to be read. */
        const val SEED_TIMEOUT_MS = 1_000L
    }
}

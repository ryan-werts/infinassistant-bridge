package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.CalendarEvent
import com.w6tech.infinassistant.core.data.HaApi
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the watch's dashboard current (DASHBOARD_STATE, firmware 2.4.96).
 *
 * Until 2.4.96 the dashboard showed the states a layout push carried, and the layout is pushed
 * only from the card or the app: the doors, lights, temperatures, weather and events stayed as
 * they were at that push, however long ago. The phone now sends the dashboard's state itself:
 *  - in full ([refresh]): on every watch connection, after a layout push and after HA reconnects;
 *    and every [HEARTBEAT_MS], or soon after the first upcoming event leaves the watch so the next
 *    one takes its place ([refreshDueAtMs]). A full send reads today's forecast and the upcoming
 *    events again;
 *  - after a state change of a dashboard entity ([onStateChange]): the first [DEBOUNCE_MS] after
 *    the change, at most one message per [MIN_INTERVAL_MS], so a burst of changes is one message;
 *  - when a warm freezer reaches its alarm ([FreezerAlertTracker]).
 * An update equal to the last one sent, its time aside, is not sent again; a full send always is,
 * which tells the watch the state is current (it greys the home status 20 min after the state's
 * time). Nothing goes out to a watch without the message (feature bit 13), or while HA is away.
 * The states are the bridge's state cache's ([HaStateCache]), which HA keeps current.
 *
 * The periodic refresh is checked on every message from the watch ([onWatchActivity]), which
 * sends a sensor report every minute, as well as on a timer ([runHeartbeat]): a coroutine delay
 * does not run while the phone sleeps, so a 15 min timer alone can come late enough for the
 * watch to grey a state that is still right.
 */
class DashboardStateOrchestrator(
    private val logger: Logger,
    private val haApi: HaApi,
    private val protocolHandler: ProtocolHandler,
    private val layoutProvider: () -> WatchFaceLayoutPayload?,
    private val logCallback: (direction: String, type: String, msgId: Int?, summary: String) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val tag = "DashboardStateOrchestrator"
    private val freezers = FreezerAlertTracker()
    private val stateCache get() = protocolHandler.stateCache
    @Volatile private var forecast: Pair<Int, Int>? = null
    @Volatile private var events: List<CalendarEvent> = emptyList()

    /** Serialises the sends, and guards [lastSent] and [lastSentAtMs]. */
    private val sendLock = Mutex()
    /** The last state sent, with computedAt 0, to tell a change from a repeat. */
    private var lastSent: DashboardStatePayload? = null
    @Volatile private var lastSentAtMs = 0L

    /** When the last full send went out, and when a failed one may be tried again. */
    @Volatile private var refreshedAtMs = 0L
    @Volatile private var retryAtMs = 0L

    @Volatile private var pendingJob: Job? = null
    @Volatile private var refreshJob: Job? = null
    @Volatile private var alarmJob: Job? = null
    @Volatile private var alarmAtMs = 0L
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var reconnectReadAtMs = 0L

    /** The entities of the dashboard last looked at, kept while the layout is the same. */
    @Volatile private var watched: Pair<WatchDashboard, Set<String>>? = null

    /** The screens the connected watch holds (its HELLO's six-screen bit). */
    private fun screenCap(): Int = WatchFaceOrchestrator.screenCap(protocolHandler.peerFeatures)

    /** Reads everything again and sends it ([refreshNow]) without holding up the caller. */
    fun refresh(reason: String, ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        refreshJob = sessionScope.launch { refreshNow(reason, ha, transport, sessionScope) }
    }

    /**
     * Reads today's forecast and the upcoming events, and sends the state whatever was sent before,
     * or, with [resendSame] false, only when it differs from what was sent last. Nothing is sent
     * when HA has not given the states (the cache's first answer is waited for, a few seconds at
     * most); the next message from the watch tries again a minute on ([RETRY_MS]).
     */
    suspend fun refreshNow(
        reason: String,
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
        resendSame: Boolean = true,
    ) {
        retryAtMs = clockMs() + RETRY_MS
        // The watch may not hold what was sent last (a new connection, a new layout, a watch that
        // restarted): until this send succeeds, no update is held back as a repeat.
        if (resendSame) sendLock.withLock { lastSent = null }
        if (!protocolHandler.peerSupports(Protocol.Features.DASHBOARD_STATE) || !ha.isConnected()) return
        val layout = layoutProvider() ?: return
        val dash = DashboardStateBuilder.dashboardOf(layout, screenCap()) ?: return
        try {
            if (!stateCache.awaitSnapshot(ha)) {
                logger.w(tag, "Dashboard refresh ($reason): no states from HA")
                return
            }
            dash.weather?.entityId?.takeIf { it.isNotBlank() }?.let { weatherId ->
                when (val f = haApi.fetchWeatherForecast(weatherId)) {
                    is AppResult.Ok -> forecast = f.value
                    // The last forecast is kept: today's high and low change little.
                    is AppResult.Err -> logger.w(tag, "Dashboard forecast for $weatherId failed: ${f.error.message}")
                }
            }
            // All at once: a face can have many calendars (16 on one), and read one after another a
            // slow one held up the whole state.
            val reads = coroutineScope {
                DashboardStateBuilder.calendarIds(dash, layout).map { calendarId ->
                    async { calendarId to haApi.fetchCalendarEvents(calendarId, maxEvents = CALENDAR_FETCH) }
                }.awaitAll()
            }
            val fetched = mutableListOf<CalendarEvent>()
            var calendarsRead = true
            for ((calendarId, e) in reads) {
                when (e) {
                    is AppResult.Ok -> fetched += e.value
                    is AppResult.Err -> {
                        calendarsRead = false
                        logger.w(tag, "Dashboard events of $calendarId failed: ${e.error.message}")
                    }
                }
            }
            // A calendar that did not answer leaves the events as they were, unless another gave some.
            if (calendarsRead || fetched.isNotEmpty()) events = fetched
            if (send(reason, ha, transport, sessionScope, force = resendSame)) refreshedAtMs = clockMs()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(tag, "Dashboard refresh ($reason) failed: ${e.message}", e)
        }
    }

    /**
     * HA is back: the dashboard is read again, at most once per [MIN_RECONNECT_READ_MS], and sent
     * when it differs from what the watch holds. The watch link was kept, so the watch still has the
     * last state; an identical one is not sent again (until app 2.1.113 it was, with a "change" send
     * of it just before, on every reconnect). The periodic refresh still renews the watch's copy
     * well inside its 30 min staleness. On a poor network HA can drop and return many times a
     * minute, and each read fetches the forecast and the events: the reads coalesce into one when
     * HA settles. (App 2.1.107 dropped HA itself with each read's forecast fetch, which made such a
     * loop: 773 reconnects and 521 reads in 7 h.)
     */
    fun onHaReconnected(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        if (reconnectJob?.isActive == true) return
        val wait = reconnectReadAtMs + MIN_RECONNECT_READ_MS - clockMs()
        reconnectJob = sessionScope.launch {
            if (wait > 0) delay(wait)
            reconnectReadAtMs = clockMs()
            refreshNow("ha-reconnect", ha, transport, sessionScope, resendSame = false)
        }
    }

    /** A message from the watch: the periodic refresh, when it is due ([refreshDueAtMs]). */
    fun onWatchActivity(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        if (clockMs() < refreshDueAtMs() || refreshJob?.isActive == true) return
        refresh("heartbeat", ha, transport, sessionScope)
    }

    /**
     * Runs for a watch connection (a listener job): the periodic refresh on a timer, checked at
     * least every [CHECK_MS]. It runs while the phone is awake; [onWatchActivity] covers the rest.
     */
    suspend fun runHeartbeat(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        while (currentCoroutineContext().isActive) {
            delay((refreshDueAtMs() - clockMs()).coerceIn(MIN_CHECK_MS, CHECK_MS))
            if (clockMs() >= refreshDueAtMs() && refreshJob?.isActive != true) {
                refresh("heartbeat", ha, transport, sessionScope)
                refreshJob?.join()
            }
        }
    }

    /**
     * When the next full refresh is due (wall-clock ms): [HEARTBEAT_MS] after the last one, or
     * sooner, just after the first upcoming event leaves the watch (its start plus the watch's
     * grace). Never before a failed refresh may be retried.
     */
    internal fun refreshDueAtMs(): Long {
        val heartbeat = refreshedAtMs + HEARTBEAT_MS
        val eventGone = events.asSequence().filter { it.startEpoch > 0 }
            .map { (it.startEpoch + DashboardStateBuilder.EVENT_GRACE_SEC) * 1000 + EVENT_GONE_SLACK_MS }
            .filter { it > refreshedAtMs }.minOrNull()
        val due = if (eventGone != null) minOf(eventGone, heartbeat) else heartbeat
        return maxOf(due, retryAtMs)
    }

    /** A watched entity's state changed (HaStateCache): a dashboard entity's goes out ([schedule]). */
    fun onStateChange(change: HaStateChange, ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        val dash = layoutProvider()?.let { DashboardStateBuilder.dashboardOf(it, screenCap()) } ?: return
        if (change.entityId !in entityIdsOf(dash)) return
        schedule(ha, transport, sessionScope)
    }

    /**
     * One update for a burst of changes: sent [DEBOUNCE_MS] after the first, and no sooner than
     * [MIN_INTERVAL_MS] after the last send. A change made while an update waits is in it: the
     * update reads the states when it is built, after it stops waiting.
     */
    private fun schedule(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        if (pendingJob?.isActive == true) return
        pendingJob = sessionScope.launch {
            delay(maxOf(DEBOUNCE_MS, lastSentAtMs + MIN_INTERVAL_MS - clockMs()))
            // From here a change schedules the next update: this one may be built already.
            pendingJob = null
            send("change", ha, transport, sessionScope, force = false)
        }
    }

    /** Builds the state and sends it; true when it went out. */
    private suspend fun send(
        reason: String,
        ha: HaWebSocketClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
        force: Boolean,
    ): Boolean {
        sendLock.withLock {
            if (!protocolHandler.peerSupports(Protocol.Features.DASHBOARD_STATE) || !ha.isConnected()) return false
            // Until the cache has HA's answer, the entities it lacks would read as off.
            if (!stateCache.isReady) return false
            val dash = layoutProvider()?.let { DashboardStateBuilder.dashboardOf(it, screenCap()) } ?: return false
            val nowMs = clockMs()
            val states = stateCache.states(entityIdsOf(dash))
            val payload = DashboardStateBuilder.build(dash, states, forecast, events, nowMs / 1000) { id, value, celsius ->
                freezers.alert(id, value, celsius, nowMs)
            }
            val key = payload.copy(computedAt = 0)
            if (!force && key == lastSent) return false
            try {
                protocolHandler.sendToWatch(transport, Protocol.Type.DASHBOARD_STATE, protocolHandler.nextMsgId(), payload)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.w(tag, "DASHBOARD_STATE ($reason) not sent: ${e.message}")
                return false
            }
            lastSent = key
            lastSentAtMs = nowMs
            logCallback("dashboard", "DASHBOARD_STATE sent", null, summary(reason, payload))
        }
        scheduleAlarm(ha, transport, sessionScope)
        return true
    }

    /** An update when the next warm freezer starts to alarm. */
    private fun scheduleAlarm(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        val at = freezers.nextAlarmAtMs(clockMs()) ?: return
        if (at == alarmAtMs && alarmJob?.isActive == true) return
        alarmJob?.cancel()
        alarmAtMs = at
        alarmJob = sessionScope.launch {
            delay((at - clockMs()).coerceAtLeast(0) + ALARM_SLACK_MS)
            send("freezer", ha, transport, sessionScope, force = false)
        }
    }

    private fun entityIdsOf(dash: WatchDashboard): Set<String> {
        watched?.let { (d, ids) -> if (d === dash) return ids }
        return DashboardStateBuilder.entityIds(dash).also { watched = dash to it }
    }

    private fun summary(reason: String, p: DashboardStatePayload): String = buildString {
        append(reason)
        append(": fp=%08x bits=".format(p.fingerprint))
        append(p.groups.joinToString(",") { "${it.type}:${Integer.toHexString(it.bits)}" })
        append(" sensors=")
        append(p.sensors.joinToString(";") { s -> "${s.type}:" + s.values.joinToString(",") { it?.toString() ?: "-" } + if (s.alerts != 0) "!${s.alerts}" else "" })
        append(" wx=${p.weather?.let { "${it.code}/${it.temp}" } ?: "-"} events=${p.events.size}")
    }

    companion object {
        const val DEBOUNCE_MS = 1_500L
        const val MIN_INTERVAL_MS = 3_000L
        const val HEARTBEAT_MS = 15 * 60_000L
        /** A refresh that did not go out is tried again no sooner than this. */
        const val RETRY_MS = 60_000L
        /**
         * The heartbeat timer looks at least this often, and no more often than [MIN_CHECK_MS]. The
         * due time only moves later (a refresh, a failed retry), so a long wait is safe; the watch's
         * own messages ([onWatchActivity]) cover a clock set back. Was 60 s: 15 wake-ups per heartbeat.
         */
        const val CHECK_MS = 5 * 60_000L
        const val MIN_CHECK_MS = 1_000L
        const val EVENT_GONE_SLACK_MS = 5_000L
        const val ALARM_SLACK_MS = 1_000L
        /** Events read per calendar: enough to reach the upcoming ones past any already under way. */
        const val CALENDAR_FETCH = 8
        /** The fewest ms between two reads after HA reconnects. */
        const val MIN_RECONNECT_READ_MS = 60_000L
    }
}

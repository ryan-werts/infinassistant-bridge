package com.w6tech.infinassistant.core.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.ble.BleGattClient
import com.w6tech.infinassistant.core.ble.BleLinkLostException
import com.w6tech.infinassistant.core.ble.BleTransport
import com.w6tech.infinassistant.core.ble.FrameCodec
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.bridge.ConnectionManager
import com.w6tech.infinassistant.core.data.bridge.EventBroker
import com.w6tech.infinassistant.core.data.bridge.ForwardDiagnostics
import com.w6tech.infinassistant.core.data.bridge.CalendarOrchestrator
import com.w6tech.infinassistant.core.data.bridge.DashboardStateOrchestrator
import com.w6tech.infinassistant.core.data.bridge.HaStateCache
import com.w6tech.infinassistant.core.data.bridge.HomeControlOrchestrator
import com.w6tech.infinassistant.core.data.bridge.LogbookOrchestrator
import com.w6tech.infinassistant.core.data.bridge.PhoneNotificationRegistry
import com.w6tech.infinassistant.core.data.bridge.PhoneNotificationSnapshot
import com.w6tech.infinassistant.core.data.bridge.PhoneNotifySync
import com.w6tech.infinassistant.core.data.bridge.ProtocolHandler
import com.w6tech.infinassistant.core.data.bridge.StateManager
import com.w6tech.infinassistant.core.data.bridge.WatchLinkState
import com.w6tech.infinassistant.core.data.bridge.WatchLinkTracker
import com.w6tech.infinassistant.core.data.bridge.WatchFaceOrchestrator
import com.w6tech.infinassistant.core.domain.*
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Convenience alias so BridgeEngine callers don't need to import WatchFaceOrchestrator. */
typealias WatchFaceLayoutResult = WatchFaceOrchestrator.WatchFaceLayoutResult

/**
 * Orchestrates the end-to-end bridge between Home Assistant and PineTime watch.
 *
 * This is the main coordinator that:
 * - Manages overall lifecycle (start/stop)
 * - Coordinates connection attempts via ConnectionManager
 * - Routes messages via ProtocolHandler and EventBroker
 * - Tracks state via StateManager
 * - Handles commands from UI (notifications, resync, ping, etc.)
 */
class BridgeEngine(
    private val appContext: Context,
    private val logger: Logger,
    private val settingsRepo: SettingsRepository,
    private val tokenStore: HaTokenStore,
    private val urlSelector: UrlSelector,
    private val webhookClient: InfinassistantWebhookClient,
    private val voiceAssistantManager: VoiceAssistantManager,
    private val diagnosticLogger: DiagnosticLogger,
    /** The phone notifications forwarded to the watch; the listener service reads the same one. */
    phoneNotificationRegistry: PhoneNotificationRegistry = PhoneNotificationRegistry(),
) {
    private val tag = "BridgeEngine"

    companion object {
        /** The debug-bundle line for a watch that cannot take the renamed CONFIG key. */
        const val WATCH_PREDATES_CONFIG_KEY_LOG = "watch firmware predates the renamed config key: update needed"

        /** A repeated connection failure is logged again after this long (see logConnectFailure). */
        const val CONNECT_FAILURE_LOG_INTERVAL_MS = 10 * 60 * 1_000L

        /**
         * Decides whether a watch-reported safe-face trip count warrants a user
         * notification.
         *
         * `safeFaceTrips` is a cumulative watch-side counter that only resets on
         * watch reboot, while the per-connection baseline ([previousTrips]) resets
         * to null on every reconnect. Notifying whenever the baseline was null
         * re-fired "layout too heavy" on the first HELLO after any reconnect — even
         * when the dashboard was healthy and the trip was historical. We therefore
         * seed the baseline silently and surface only a genuine in-session increase.
         * A backwards step (watch rebooted → counter reset) is not a new trip.
         *
         * @param previousTrips Last-seen cumulative count this connection, or null if unseen.
         * @param newTrips Cumulative count from the current HELLO.
         * @return true only when this is a real new trip worth surfacing.
         */
        fun shouldNotifySafeFaceTrip(previousTrips: Int?, newTrips: Int): Boolean =
            previousTrips != null && newTrips > previousTrips

        /**
         * The part of the settings a connected-mode session depends on: two settings with
         * the same key never warrant a session restart.
         *
         * The two saved copies of the watch-face layout are left out because a layout push
         * writes them itself: [WatchFaceOrchestrator] saves the resolved layout on success,
         * and the import screen saves the JSON it pushed. Restarting the session for those
         * writes cancelled the push's own command before it could deliver its result, so
         * the screen showed "Watch did not respond" after the watch had applied the layout,
         * and every push ended in a needless disconnect and re-handshake. A new session
         * still reads the latest saved layout when it starts.
         *
         * The voice assist settings are left out as well: a trigger reads them when it comes, and
         * switching voice on must not drop the watch link.
         */
        /** The CONFIG_PUSH for [settings]: the report settings, and whether voice assist is on. */
        fun configPushPayload(configVersion: Int, settings: BridgeSettings): ConfigPushPayload = ConfigPushPayload(
            configVersion = configVersion,
            infinassistant = InfinassistantConfig(
                enabled = settings.infinassistant.enabled,
                sensorReportIntervalSeconds = settings.infinassistant.sensorReportIntervalSeconds,
            ),
            voiceAssist = settings.voiceAssist.enabled,
        )

        fun sessionKey(settings: BridgeSettings): BridgeSettings =
            settings.copy(
                lastPushedWatchFaceJson = null,
                lastResolvedWatchFaceLayoutJson = null,
                voiceAssist = VoiceAssistSettings(),
            )
    }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Component instances
    // The states of the entities the watch needs, kept by one HA subscription: every consumer reads them here.
    private val haStateCache = HaStateCache(logger, ::log)
    private val connectionManager = ConnectionManager(appContext, logger, tokenStore, urlSelector, haStateCache)
    private val session = SessionDiagnostics({ msg, extra -> log(SessionDiagnostics.TAG, msg, null, extra) })
    private val stateManager = StateManager()
    private val haApi = HaApi(logger, settingsRepo, tokenStore, urlSelector)

    // Components that depend on the logging callback
    private val protocolHandler = ProtocolHandler(logger, stateManager, haStateCache, ::log).also { handler ->
        // Every HELLO seen, decoded or not, settles whether the watch needs the update.
        handler.onHelloSeen = {
            connectionManager.updateStatus { copy(watchUpdateNeeded = handler.watchPredatesConfigKey) }
        }
        // A later good HELLO after the CONFIG_PUSH was withheld: send it once now, not at the next reconnect.
        handler.onConfigKeySupportGained = {
            log("config", "Watch now reads the config key", null, "sending the withheld CONFIG_PUSH")
            commands.tryEmit(BridgeCommand.ForceConfigPush)
        }
    }
    private val phoneNotifySync = PhoneNotifySync(phoneNotificationRegistry, stateManager, protocolHandler::nextMsgId)
    private val forwardDiagnostics = ForwardDiagnostics({ msg, extra -> log(HealthSummary.NOTIFY_TAG, msg, null, extra) })
    private val eventBroker = EventBroker(logger, stateManager, protocolHandler, ::log)
    private val watchFaceOrchestrator by lazy {
        WatchFaceOrchestrator(logger, haApi, settingsRepo, protocolHandler, stateManager, eventBroker, ::log)
    }
    private val logbookOrchestrator by lazy {
        LogbookOrchestrator(logger, protocolHandler, { watchFaceOrchestrator.lastWatchFaceLayout }, ::log)
    }
    private val calendarOrchestrator by lazy {
        CalendarOrchestrator(logger, haApi, protocolHandler, { watchFaceOrchestrator.lastWatchFaceLayout }, ::log)
    }
    private val homeControlOrchestrator by lazy {
        HomeControlOrchestrator(logger, protocolHandler, { watchFaceOrchestrator.lastWatchFaceLayout }, ::log)
    }
    private val dashboardStateOrchestrator by lazy {
        DashboardStateOrchestrator(logger, haApi, protocolHandler, { watchFaceOrchestrator.lastWatchFaceLayout }, ::log)
    }

    // Job tracking
    private var job: Job? = null
    @Volatile private var activeVoiceSessionJob: Job? = null
    // Set from a voice trigger until its session has cleaned up: a second trigger meanwhile is ignored.
    private val voiceBusy = AtomicBoolean(false)

    // REQ-033 / REQ-066: reference to the active BleTransport so the Debug screen toggle
    // can flip diagnosticWriteType at runtime without routing through the command queue.
    @Volatile private var activeTransport: BleTransport? = null

    // PONG signal for liveness monitoring — carries the watch-side PONG msgId
    // (REQ-036). Matching PING.msgId against PONG.msgId rules out a stale PONG
    // from a previous round satisfying the current round's wait.
    // replay = 1 closes a subscribe-after-send race: the PONG wait below only
    // starts collecting after the PING has been written, and with replay = 0 a
    // reply that arrived in that window was emitted to no subscriber and
    // dropped, timing out a healthy link. Staleness is handled by matching the
    // echoed request id rather than by the absence of a replay buffer.
    private val pongSignal = MutableSharedFlow<Int>(replay = 1, extraBufferCapacity = 1)

    // Protocol log for debugging
    private val _protocolLog = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val protocolLog: StateFlow<List<ProtocolLogEntry>> = _protocolLog.asStateFlow()

    // Expose connection status from ConnectionManager
    val status: StateFlow<BridgeStatus> get() = connectionManager.status

    /**
     * REQ-067 — emits the new safeFaceTrips count when the watch HELLO shows
     * the counter has increased since the previous HELLO. The foreground service
     * observes this flow and posts a system notification linking to preflight.
     */
    private val _safeFaceTripEvent = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val safeFaceTripEvent: kotlinx.coroutines.flow.SharedFlow<Int> = _safeFaceTripEvent

    /**
     * A phone notification the user dismissed on the watch. The listener service collects it and
     * cancels that notification; the engine holds no reference to the service. A request waits in
     * the channel until the listener is there to take it.
     */
    private val phoneDismissChannel = Channel<PhoneNotifySync.DismissRequest>(capacity = 16)
    val phoneDismissRequests: Flow<PhoneNotifySync.DismissRequest> = phoneDismissChannel.receiveAsFlow()

    // The watch as the link gate for phone notifications sees it: bonded (read from Bluetooth) and
    // the watch's own report of the link's encryption (ProtocolHandler.peerEnc).
    @Volatile private var watchAddress: String? = null
    // The bond as last read with Bluetooth on: with the adapter off Android reports every device unbonded.
    @Volatile private var lastBonded = false
    private val linkTracker = WatchLinkTracker()
    private val _watchLink = MutableStateFlow(WatchLinkState.NOT_PAIRED)
    val watchLink: StateFlow<WatchLinkState> = _watchLink.asStateFlow()
    private var bondReceiver: BroadcastReceiver? = null
    private var mismatchJob: Job? = null

    // Commands from UI
    private sealed interface BridgeCommand {
        data class SendNotify(val payload: NotifyPayload) : BridgeCommand
        /** Send the phone forwards PhoneNotifySync has queued and not yet sent. */
        data object FlushPhoneForwards : BridgeCommand
        /** Send the NOTIFY_REMOVEs PhoneNotifySync has queued. */
        data object FlushPhoneRemoves : BridgeCommand
        data class SendHapticFeedback(val pattern: String) : BridgeCommand
        data object ForceResync : BridgeCommand
        /** HA is back with the watch link kept: only the states that changed while HA was away. */
        data object ResyncChanged : BridgeCommand
        data object ForceConfigPush : BridgeCommand
        data object PingWatch : BridgeCommand
        data class SendWatchFaceLayout(
            val payload: WatchFaceLayoutPayload,
            val result: CompletableDeferred<WatchFaceLayoutResult>,
            val onProgress: ((current: Int, total: Int) -> Unit)? = null
        ) : BridgeCommand
        data object SyncTime : BridgeCommand
        // REQ-072 — find-device commands.
        data class FindWatch(val durationSeconds: Int = 30) : BridgeCommand
        // REQ-066 — send a HELLO to the watch to refresh diagnostics; watch responds with its own HELLO.
        data object RefreshDiagnostics : BridgeCommand
    }

    private val commands = MutableSharedFlow<BridgeCommand>(extraBufferCapacity = 16)

    // WatchFaceOrchestrator and LogbookOrchestrator are lazy to avoid circular init with ::log

    fun start() {
        if (job != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // The watch takes a double-click for voice only while its last CONFIG_PUSH says voice is on, so
        // the switch is pushed when it changes. The first value is the session's own config push.
        scope.launch {
            settingsRepo.settings
                .map { it.voiceAssist.enabled }
                .distinctUntilChanged()
                .drop(1)
                .collect { commands.tryEmit(BridgeCommand.ForceConfigPush) }
        }
        // The watch's address and the forwarding switch: the link gate reads the bond of the first,
        // and turning the second off forgets what was forwarded.
        scope.launch {
            settingsRepo.settings
                .map { it.primaryDeviceAddress to it.notificationPrefs.forwardSystemNotifications }
                .distinctUntilChanged()
                .collect { (address, forwarding) ->
                    if (address != watchAddress) lastBonded = false
                    watchAddress = address
                    refreshWatchLink()
                    if (!forwarding) phoneNotifySync.clear()
                }
        }
        registerBondReceiver()
        job = scope.launch {
            // collectLatest cancels the running session on every emission, so only a change
            // the session depends on may get through (see sessionKey).
            settingsRepo.settings
                .distinctUntilChanged { old, new -> sessionKey(old) == sessionKey(new) }
                .collectLatest { settings ->
                    connectionManager.updateStatus { copy(connectedModeEnabled = settings.connectedModeEnabled) }
                    if (settings.connectedModeEnabled) {
                        runConnectedMode(settings)
                    } else {
                        log("engine", "connected mode off")
                    }
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        activeVoiceSessionJob?.cancel()
        activeVoiceSessionJob = null
        voiceAssistantManager.cancelSession()
        connectionManager.unregisterTimezoneReceiver()
        connectionManager.unregisterBluetoothStateReceiver()
        unregisterBondReceiver()
        connectionManager.updateStatus {
            copy(haState = HaConnectionState.Disconnected, bleState = BleConnectionState.Disconnected)
        }
        scope.cancel()
    }

    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                refreshWatchLink()
                // A bond just made: ask for a PONG, which carries the watch's own word on the
                // link's encryption, instead of waiting for the next liveness round.
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (state == BluetoothDevice.BOND_BONDED) commands.tryEmit(BridgeCommand.PingWatch)
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        bondReceiver = receiver
    }

    private fun unregisterBondReceiver() {
        bondReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Not registered, ignore
            }
        }
        bondReceiver = null
    }

    /**
     * Whether Android holds a bond with the watch. With Bluetooth off Android reports every device
     * unbonded, which says nothing of the pairing: the last bond read with it on stands.
     */
    @SuppressLint("MissingPermission")  // Checked at service start, as in ConnectionManager
    private fun bondedNow(): Boolean {
        val address = watchAddress ?: return false
        return try {
            val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null || !adapter.isEnabled) {
                lastBonded
            } else {
                (adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED).also { lastBonded = it }
            }
        } catch (_: Throwable) {
            lastBonded
        }
    }

    /**
     * Reads the bond and what the watch reports again into [watchLink]. A change is logged (the
     * state only, no content). A link that opens sends what waited for it; one that is reported
     * unencrypted is looked at again once its grace period has passed.
     */
    private fun refreshWatchLink() {
        val state = linkTracker.update(bondedNow(), protocolHandler.helloSeen, protocolHandler.peerEnc)
        val before = _watchLink.value
        _watchLink.value = state
        if (state == before) return
        log("link", "watch link: $state", null, "was $before")
        if (state.allowsPhoneNotifications) {
            emitPhoneCommand(BridgeCommand.FlushPhoneForwards)
            emitPhoneCommand(BridgeCommand.FlushPhoneRemoves)
        }
        if (state == WatchLinkState.PAIRED_NOT_ENCRYPTED) {
            mismatchJob?.cancel()
            mismatchJob = scope.launch {
                delay(WatchLinkTracker.GRACE_MS + 1_000)
                refreshWatchLink()
            }
        }
    }

    /**
     * Asks Android to bond with the watch, which shows a 6-digit code on the watch for the phone to
     * ask for. False when there is no watch address or Android refuses to start.
     */
    @SuppressLint("MissingPermission")
    suspend fun pairWithWatch(): Boolean {
        val address = settingsRepo.settings.first().primaryDeviceAddress ?: return false
        return try {
            val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            adapter?.getRemoteDevice(address)?.createBond() ?: false
        } catch (e: Throwable) {
            logger.w(tag, "Pairing with the watch could not start: ${e::class.java.simpleName}")
            false
        }
    }

    // --- Public API for sending commands ---

    fun sendTestNotification() {
        val payload = NotifyPayload(
            title = "Test",
            body = "This is a test notification from the phone.",
            severity = Severity.INFO,
            tag = "test",
            ts = System.currentTimeMillis(),
        )
        commands.tryEmit(BridgeCommand.SendNotify(payload))
    }

    fun sendNotification(payload: NotifyPayload) {
        commands.tryEmit(BridgeCommand.SendNotify(payload))
    }

    /**
     * A phone notification was posted or updated. If it passes the filter it is queued as a pending
     * forward, which reaches the watch now or, with no link, on the next connection. Unlike
     * [sendNotification] the queueing happens here and not in the command handler, which exists
     * only while connected: the pending set is what a reconnect resends.
     */
    fun onPhoneNotificationPosted(snapshot: PhoneNotificationSnapshot, switchOn: Boolean): PhoneNotifySync.Posted {
        val posted = phoneNotifySync.onPosted(snapshot, switchOn, _watchLink.value)
        when (posted) {
            is PhoneNotifySync.Posted.Forwarded -> {
                forwardDiagnostics.fromPhone(snapshot.packageName)
                emitPhoneCommand(BridgeCommand.FlushPhoneForwards)
            }
            is PhoneNotifySync.Posted.Skipped -> if (switchOn) forwardDiagnostics.skipped(posted.reason)
        }
        return posted
    }

    /** The commands are idempotent flushes of queues, so one lost to a full buffer is sent with the next. */
    private fun emitPhoneCommand(cmd: BridgeCommand) {
        if (!commands.tryEmit(cmd)) logger.w(tag, "Command buffer full: ${cmd::class.java.simpleName} not queued")
    }

    /**
     * A phone notification left the phone. Its pending forward is dropped and, unless the watch
     * dismissed it, a NOTIFY_REMOVE is queued: sent now, or on the next connection.
     */
    fun onPhoneNotificationRemoved(key: String): PhoneNotifySync.Removed {
        val removed = phoneNotifySync.onRemoved(key)
        if (removed is PhoneNotifySync.Removed.Queued) emitPhoneCommand(BridgeCommand.FlushPhoneRemoves)
        return removed
    }

    /**
     * The listener (re)connected and the phone's notifications are [activeKeys]: forwarded ones
     * that are gone are removed from the watch too. Returns how many.
     */
    fun reconcilePhoneNotifications(activeKeys: Set<String>): Int {
        val removed = phoneNotifySync.reconcile(activeKeys)
        if (removed.isNotEmpty()) emitPhoneCommand(BridgeCommand.FlushPhoneRemoves)
        return removed.size
    }

    /** The listener is about to cancel the phone notification for a watch dismissal. */
    fun onPhoneCancelStarting(request: PhoneNotifySync.DismissRequest) = phoneNotifySync.onCancelStarting(request)

    fun forceResyncStates() {
        commands.tryEmit(BridgeCommand.ForceResync)
    }

    fun pingWatch() {
        commands.tryEmit(BridgeCommand.PingWatch)
    }

    fun forceConfigPush() {
        commands.tryEmit(BridgeCommand.ForceConfigPush)
    }

    suspend fun sendWatchFaceLayout(
        payload: WatchFaceLayoutPayload,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): WatchFaceLayoutResult {
        val result = CompletableDeferred<WatchFaceLayoutResult>()
        commands.tryEmit(BridgeCommand.SendWatchFaceLayout(payload, result, onProgress))
        return withTimeoutOrNull(BridgeConfig.WATCHFACE_PUSH_TIMEOUT_MS) { result.await() }
            ?: WatchFaceOrchestrator.WatchFaceLayoutResult.Timeout
    }

    fun syncTime() {
        commands.tryEmit(BridgeCommand.SyncTime)
    }

    /** REQ-072 — ask the watch to vibrate+alert for [durationSeconds] seconds (1–60). */
    fun findWatch(durationSeconds: Int = 30) {
        commands.tryEmit(BridgeCommand.FindWatch(durationSeconds))
    }

    /**
     * REQ-033 / REQ-066 — toggle acknowledged-write diagnostic mode on the active BLE
     * transport. When enabled, WATCHFACE_LAYOUT chunks use WRITE_TYPE_DEFAULT so
     * per-chunk errors surface on the Android side. The toggle is a no-op when no
     * session is active (setting takes effect on the next connect).
     */
    fun setDiagnosticWriteType(enabled: Boolean) {
        activeTransport?.diagnosticWriteType = enabled
        connectionManager.updateStatus { copy(diagnosticWriteTypeEnabled = enabled) }
    }

    /**
     * Record the outcome of an infinassistant.get_watchface call so it reaches
     * the Debug screen and the debug bundle.
     *
     * The error was previously only rendered on the import screen, which is
     * transient — so a user who could not fetch their layout (and fell back to
     * applying a cached copy) had nothing to hand over but a recollection of
     * the wording. Pass null on success to clear a stale failure.
     */
    fun recordWatchFaceFetchError(error: String?) {
        connectionManager.updateStatus { copy(lastWatchFaceFetchError = error) }
    }

    /**
     * REQ-066 — send a phone HELLO so the watch replies with its diagnostic HELLO,
     * refreshing the fields shown on the Debug screen. No-op when BLE is disconnected.
     */
    fun refreshDiagnostics() {
        commands.tryEmit(BridgeCommand.RefreshDiagnostics)
    }

    // --- Main connection loop ---

    private suspend fun runConnectedMode(settings: BridgeSettings) {
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ha = HaWebSocketClient(logger, sessionScope)
        val ble = BleGattClient(appContext, logger, sessionScope)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bleWakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PineTimeBridge:BleTransfer")
        val transport = BleTransport(ble, logger, sessionScope, bleWakeLock)
        // REQ-033 / REQ-066: restore any persisted diagnostic mode from the last status snapshot
        // so the toggle survives a session restart within the same app process.
        transport.diagnosticWriteType = connectionManager.status.value.diagnosticWriteTypeEnabled
        activeTransport = transport

        // Restore lastWatchFaceLayout from DataStore (allows logbook requests to work after app restart)
        watchFaceOrchestrator.restoreLayout(settings.lastResolvedWatchFaceLayoutJson)

        val deviceAddr = settings.primaryDeviceAddress
        val validationError = connectionManager.validateSettings(settings)
        if (validationError != null) {
            connectionManager.updateStatus { copy(lastError = validationError) }
            return
        }

        var configVersion = 1

        // Reset reconnect bookkeeping (backoffs + HA circuit breaker) on each fresh
        // session so a restarted session — a settings change or the "Retry connection"
        // button — never begins already throttled.
        connectionManager.resetReconnectState()
        session.start()
        connectionManager.updateStatus { copy(sessionStartedAtMs = System.currentTimeMillis()) }
        // Bluetooth turned off and on shows in the bundle, and coming on retries the watch at once.
        connectionManager.registerBluetoothStateReceiver { on -> log("engine", if (on) "Bluetooth on" else "Bluetooth off") }

        // Listener jobs from setupListeners() are tracked across the loop so they are
        // cancelled on EVERY exit path. Previously the exception path — which every real
        // disconnect takes — leaked them, stacking a duplicate PING/PONG liveness monitor,
        // command handler, and collectors on the same link each reconnect until BLE
        // saturated and only a full engine restart (the button) recovered.
        var listenerJobs: List<Job> = emptyList()

        // The session's HA client serves HaApi's requests whenever it is connected.
        haApi.setSessionClient(ha)
        // The entities whose states HA sends: the tiles', and the layout's.
        haStateCache.wanted = { watchedEntityIds() }
        // The watch connected while HA was away: its apps get their data once HA is back.
        var appsPending = false

        try {
            while (sessionScope.isActive) {
                try {
                    // --- BLE connect, first: the watch link does not wait on HA. Until 2.1.109 HA
                    // came first, and an HA connect stalled with the phone asleep held the watch
                    // back with it (bundle -170345: 5 h). ---
                    if (ble.state.value !is BleConnectionState.Connected) {
                        var bleResult = connectionManager.connectBle(ble, deviceAddr!!)
                        if (bleResult is AppResult.Err) {
                            logConnectFailure("BLE", bleResult.error)
                            // Out of range or busy: Android's Bluetooth stack takes over, connects when
                            // the watch is back and wakes the app. A retry loop of the app's own stops
                            // with its timers while the phone sleeps.
                            session.bleWaitStarted(bleResult.error)
                            bleResult = connectionManager.connectBle(ble, deviceAddr, background = true)
                            session.bleWaitEnded((bleResult as? AppResult.Err)?.error, ble.state.value, ha.state.value)
                        }
                        if (bleResult is AppResult.Err) {
                            logConnectFailure("BLE", bleResult.error)
                            connectionManager.bleReconnectDelay()
                            session.checkSleep("ble-retry-wait", ble.state.value, ha.state.value)
                            continue
                        }
                        session.bleConnected()
                        // What the last connection's HELLO said does not hold for this one.
                        protocolHandler.resetSession()
                        refreshWatchLink()

                        // --- HA connect. A failure does not hold the watch back: the loop below
                        // retries HA with the watch link up, as it does when HA drops. ---
                        if (!ha.isConnected()) {
                            val haResult = connectionManager.connectHa(ha, settings)
                            if (haResult is AppResult.Err) {
                                logConnectFailure("HA", haResult.error)
                            } else if (connectionManager.subscribeHaEvents(ha, settings) is AppResult.Err) {
                                ha.disconnect()
                            }
                        }

                        // Perform handshake and initial sync
                        performInitialSync(ha, transport, settings, configVersion++)
                        // The watch's calendar, home-control and logbook apps show what the phone
                        // last sent, kept in RAM: every connection starts them with today's events,
                        // the devices' states and the latest logbook entries (once HA is there).
                        if (ha.isConnected()) sendAppsData(ha, transport, sessionScope)
                        appsPending = !ha.isConnected()
                        // The dashboard's live state is in RAM too: every connection starts it afresh.
                        dashboardStateOrchestrator.refresh("connect", ha, transport, sessionScope)
                    }

                    // --- Wire up listeners (cancel any prior set first so reconnects
                    // don't accumulate duplicate monitors/handlers on the same link) ---
                    listenerJobs.forEach { it.cancel() }
                    listenerJobs = setupListeners(sessionScope, ha, ble, transport, settings, configVersion)
                    connectionManager.registerTimezoneReceiver { syncTime() }

                    // Stay alive until the watch link fails. Losing HA does not end the
                    // session: the websocket is reconnected here, and the watch link, its
                    // handshake and the listeners stay up (they reach HA through the same
                    // client object). Until 2.1.74 a failed HA socket threw like a lost
                    // watch, so every HA blip dropped the watch and redid the whole
                    // handshake, and a push or a tile tap landing in it failed. This loop
                    // also checked for Failed only; a socket HA closes cleanly, as on an
                    // HA restart, reads Disconnected.
                    var haLostAtMs = 0L   // start of the current HA outage; 0 while connected
                    var haRetryAtMs = 0L
                    var haOfflineSent = false   // the watch was told HA is offline (sendStateFlags)
                    var seenBle = ble.state.value   // what the last pass acted on: a change since wakes the wait
                    var seenHa = ha.state.value
                    while (sessionScope.isActive && settings.connectedModeEnabled) {
                        // With HA up, only a change of either link (or the periodic look) needs this
                        // loop, so it waits for one: a tick a second kept the phone from ever sleeping.
                        // While HA is down it ticks every second, for the retry timer and the offline grace.
                        if (ha.isConnected()) {
                            awaitChange(ble.state, ha.state, seenBle, seenHa, BridgeConfig.SESSION_CHECK_MS)
                        } else {
                            delay(1_000)
                        }
                        seenBle = ble.state.value
                        seenHa = ha.state.value
                        connectionManager.syncStatus(ha, ble)
                        session.checkSleep("connected", ble.state.value, ha.state.value)
                        if (ble.state.value is BleConnectionState.Disconnected) {
                            session.bleLost(ha.state.value)
                            throw IllegalStateException("BLE disconnected")
                        }
                        if (ha.isConnected()) continue
                        val nowMs = System.currentTimeMillis()
                        if (haLostAtMs == 0L) {
                            haLostAtMs = nowMs
                            stateManager.setHaOffline(true)
                            val cause = when (val s = ha.state.value) {
                                is HaConnectionState.Failed -> "failed: ${s.error.message}"
                                else -> s.toString()
                            }
                            log("ha", "HA lost", null, "$cause; reconnecting, watch link kept")
                        }
                        // Past the grace period the watch is told, since no state update will go out
                        // to carry the flag while HA is away.
                        if (!haOfflineSent && stateManager.isHaOffline()) {
                            protocolHandler.sendStateFlags(transport)
                            haOfflineSent = true
                            log("ha", "HA offline sent", null, "after ${(nowMs - haLostAtMs) / 1000} s")
                        }
                        if (nowMs < haRetryAtMs) continue
                        if (reconnectHa(ha, settings)) {
                            log("ha", "HA reconnected", null, "after ${(System.currentTimeMillis() - haLostAtMs) / 1000} s; resyncing states")
                            stateManager.setHaOffline(false)
                            if (haOfflineSent) protocolHandler.sendStateFlags(transport)
                            // The changes made while HA was away came as no events: read the dashboard again.
                            dashboardStateOrchestrator.onHaReconnected(ha, transport, sessionScope)
                            if (appsPending) {
                                sendAppsData(ha, transport, sessionScope)
                                appsPending = false
                            }
                            haLostAtMs = 0L
                            haRetryAtMs = 0L
                            haOfflineSent = false
                        } else {
                            haRetryAtMs = System.currentTimeMillis() + connectionManager.haBackoff.nextDelayMs()
                        }
                    }

                    listenerJobs.forEach { it.cancel() }
                    listenerJobs = emptyList()
                    connectionManager.unregisterTimezoneReceiver()
                    if (!settings.connectedModeEnabled) break

                } catch (t: Throwable) {
                    // The session's own cancellation ends it. Any other CancellationException (a
                    // lower layer cancelling something this loop awaited, as a GATT closed under a
                    // connect did in app 2.1.118) is a failure like the rest: the loop retries,
                    // instead of the bridge ending without a word (4.5 h with no watch link).
                    if (t is CancellationException && (!currentCoroutineContext().isActive || !sessionScope.isActive)) throw t
                    logger.w(tag, "Connected mode loop error: ${t.message}", t)
                    connectionManager.updateStatus { copy(lastError = t.message) }
                    // Cancel this cycle's listener jobs so a duplicate set is not left
                    // running on the link across the reconnect — the core leak fix.
                    listenerJobs.forEach { it.cancel() }
                    listenerJobs = emptyList()
                    connectionManager.unregisterTimezoneReceiver()
                    try { ble.disconnect() } catch (_: Throwable) {}
                    delay(1_000)
                }
            }
        } finally {
            listenerJobs.forEach { it.cancel() }
            connectionManager.updateStatus { copy(sessionStartedAtMs = null) }
            activeTransport = null
            haApi.clearSessionClient()
            connectionManager.unregisterTimezoneReceiver()
            connectionManager.unregisterBluetoothStateReceiver()
            connectionManager.disconnectAll(ha, ble)
            sessionScope.coroutineContext[Job]?.cancel()
        }
    }

    /** Today's events, the devices' states and the logbook entries, for the watch's apps. */
    private fun sendAppsData(ha: HaWebSocketClient, transport: BleTransport, sessionScope: CoroutineScope) {
        calendarOrchestrator.sendToday(transport, sessionScope)
        homeControlOrchestrator.sendHomeControlUpdate(ha, transport, sessionScope)
        logbookOrchestrator.sendLogbookApp(ha, transport, sessionScope)
    }

    /** The entities [haStateCache] watches: the tiles' and the layout's. */
    private fun watchedEntityIds(): Set<String> = HaStateCache.watchedEntities(
        tiles = eventBroker.getTileEntityConfigs().map { it.entityId },
        layout = watchFaceOrchestrator.lastWatchFaceLayout,
    )

    /**
     * Reconnect the HA websocket without touching the watch link. The listeners collect
     * [HaWebSocketClient.events], which outlives a reconnect of the same client, so only the
     * subscriptions are renewed; then the states that changed while HA was away are sent
     * (ResyncChanged): the watch kept the rest, and until app 2.1.113 every reconnect resent all
     * of them, which a socket dropped every half minute made a steady stream to the watch. False
     * while HA is still unreachable, for the caller to back off.
     */
    private suspend fun reconnectHa(ha: HaWebSocketClient, settings: BridgeSettings): Boolean {
        if (connectionManager.connectHa(ha, settings) is AppResult.Err) return false
        if (connectionManager.subscribeHaEvents(ha, settings) is AppResult.Err) {
            ha.disconnect()
            return false
        }
        commands.tryEmit(BridgeCommand.ResyncChanged)
        return true
    }

    /**
     * Sends the phone's HELLO and waits for the watch's HELLO; null if none arrives in time.
     */
    private suspend fun exchangeHello(transport: BleTransport): FrameCodec.CompleteMessage? {
        // Register the reply waiter BEFORE writing. The watch can answer HELLO
        // faster than a post-send subscribe can attach, and the broadcast flow
        // has no replay buffer, so subscribing afterwards can miss the reply
        // entirely and stall the whole sync for the full timeout.
        val pendingHello = transport.expect { it.type == Protocol.Type.HELLO.wire }

        // try/finally because sendToWatch throws on a failed write, and the
        // whole sync can be cancelled — either would otherwise leak the waiter.
        // cancelExpect is a no-op once the waiter has been completed.
        return try {
            // Send HELLO (include semantic version for FR-BLE-6 negotiation)
            protocolHandler.sendToWatch(
                transport,
                Protocol.Type.HELLO,
                protocolHandler.nextMsgId(),
                HelloPayload(device = "phone", protoVersionString = Protocol.PROTOCOL_VERSION),
            )
            // Wait for watch HELLO (enriched with firmware diagnostics)
            withTimeoutOrNull(5_000) { pendingHello.await() }
        } finally {
            transport.cancelExpect(pendingHello)
        }
    }

    private suspend fun performInitialSync(
        ha: HaWebSocketClient,
        transport: BleTransport,
        settings: BridgeSettings,
        configVersion: Int,
    ) {
        var watchHello = exchangeHello(transport)
        if (watchHello == null) {
            // A watch from before the renamed config key would go silent on a CONFIG it cannot read,
            // with no prompt, if its HELLO was only dropped: ask once more before deciding.
            logger.w(tag, "no HELLO from the watch in 5 s; sending the phone HELLO once more")
            log("phone->watch", "HELLO", null, "no reply in 5 s; retrying once before CONFIG")
            watchHello = exchangeHello(transport)
            if (watchHello == null) {
                // Deliberate: CONFIG still goes out, so a 2.4.103 watch whose HELLO was lost keeps its settings.
                logger.w(tag, "still no HELLO from the watch; sending CONFIG_PUSH without it")
                log("config", "HELLO", null, "no reply after retry; CONFIG_PUSH sent without the watch's features")
            } else {
                log("watch->phone", "HELLO", null, "reply to the retry")
            }
        }
        if (watchHello != null) {
            val hello = protocolHandler.handleHello(watchHello)
            refreshWatchLink()
            if (hello != null) {
                connectionManager.updateStatus {
                    copy(
                        watchFirmwareVersion = hello.fwVersion,
                        lastSyncTime = System.currentTimeMillis(),
                        // REQ-066: capture HELLO diagnostics for the Debug screen.
                        lastResetReason = hello.resetReason ?: lastResetReason,
                        lastFreeHeap = hello.freeHeap ?: lastFreeHeap,
                        lastMinFreeHeap = hello.minFreeHeap ?: lastMinFreeHeap,
                        lastCrashBreadcrumb = hello.crashContext?.breadcrumb ?: lastCrashBreadcrumb,
                        lastCrashFault = hello.crashContext?.faultSummary() ?: lastCrashFault,
                        lastHelloDiagRev = hello.helloDiagRev ?: lastHelloDiagRev,
                        lastWakeDiag = hello.wakeDiag(),
                        lastLayoutRefused = hello.layoutRefusedCount(),
                        lastLayoutWriteFailed = hello.layoutWriteFailedCount(),
                    )
                }
                // The reset reason and the previous boot's crash context: the compact
                // HELLO the watch actually sends has no room for them (firmware 2.4.65+).
                protocolHandler.requestCrashReport(transport)?.let { report ->
                    val ctx = report.crashContext
                    // A planned restart (an update, a reboot) leaves a context too; only a
                    // crash goes to lastCrashContext (ProtocolHandler.crashSummary).
                    val crash = protocolHandler.crashSummary(report)
                    connectionManager.updateStatus {
                        if (crash != null) {
                            copy(
                                lastResetReason = report.resetReason ?: lastResetReason,
                                lastCrashBreadcrumb = ctx?.breadcrumb ?: lastCrashBreadcrumb,
                                lastCrashContext = crash,
                            )
                        } else {
                            copy(
                                lastResetReason = report.resetReason ?: lastResetReason,
                                lastRestartActivity = ctx?.let(protocolHandler::crashContextSummary) ?: lastRestartActivity,
                            )
                        }
                    }
                }
            }
        }

        // The session's settings leave voice assist out of their key (sessionKey), so a switch
        // flipped since the session began is read here, for a reconnect.
        val cfgPayload = configPushPayload(
            configVersion,
            settings.copy(voiceAssist = settingsRepo.settings.first().voiceAssist),
        )
        val updateNeeded = protocolHandler.watchPredatesConfigKey
        connectionManager.updateStatus { copy(watchUpdateNeeded = updateNeeded) }
        if (updateNeeded) {
            // The watch would ignore the renamed key and stop its sensor reports without a word, so
            // no CONFIG_PUSH goes out; the app shows "Update the watch" (BridgeStatus.watchUpdateNeeded).
            logger.w(tag, WATCH_PREDATES_CONFIG_KEY_LOG)
            log("config", "CONFIG_PUSH not sent", null, WATCH_PREDATES_CONFIG_KEY_LOG)
            connectionManager.updateStatus { copy(lastConfigAckOk = null) }
        } else {
            // Registered before the write — see the HELLO waiter above.
            val pendingConfigAck = transport.expect { it.type == Protocol.Type.CONFIG_ACK.wire }

            // Wait for CONFIG_ACK — REQ-035: capture the result so a timeout is observable
            // rather than silently advancing. The caller surfaces CONFIG_ACK_TIMEOUT via the
            // connection status; advancing the snapshot phase on a missing ACK previously
            // masked watch-side decode failures.
            val configAck = try {
                protocolHandler.sendConfigPush(transport, protocolHandler.nextMsgId(), cfgPayload)
                withTimeoutOrNull(5_000) { pendingConfigAck.await() }
            } finally {
                transport.cancelExpect(pendingConfigAck)
            }
            if (configAck == null) {
                logger.w(tag, "CONFIG_ACK timeout — watch did not acknowledge config push")
                log("config", "CONFIG_ACK timeout", null, "snapshot continues without ack")
                connectionManager.updateStatus { copy(lastConfigAckOk = false) }
            } else {
                connectionManager.updateStatus { copy(lastConfigAckOk = true) }
            }
        }

        // Initial state snapshot — the tile entities from the persisted watch face layout.
        // Without tile entities here, tiles show "---" on every
        // reconnect until the user taps them (which triggers a HA state_changed → STATE_UPDATE).
        val tileEntityConfigs: List<EntityConfig> = watchFaceOrchestrator.lastWatchFaceLayout
            ?.screens
            ?.flatMap { it.tiles }
            ?.map { it.entityId }
            ?.filter { it.isNotBlank() && !it.startsWith("device:") }
            ?.distinct()
            ?.map { EntityConfig(entityId = it) }
            ?: emptyList()

        // Register tile entities with EventBroker so ongoing state changes are forwarded
        if (tileEntityConfigs.isNotEmpty()) {
            eventBroker.updateTileEntityIds(tileEntityConfigs.map { it.entityId }.toSet())
            logger.d(tag, "Registered ${tileEntityConfigs.size} tile entities for state tracking on reconnect")
        }
        // The state cache follows the tiles just registered (a push that failed may have left others).
        haStateCache.sync(ha)

        val snapshotResult = protocolHandler.fetchSelectedStates(ha, tileEntityConfigs)
        if (snapshotResult is AppResult.Ok) {
            stateManager.recordHaFetch()
            stateManager.setHaOffline(false)
            stateManager.updateEntityStates(snapshotResult.value)
            // Batch into groups of 8 to avoid large BLE messages
            for (batch in snapshotResult.value.chunked(BridgeConfig.STATE_BATCH_SIZE)) {
                protocolHandler.sendStateUpdate(transport, protocolHandler.nextMsgId(), batch)
                if (snapshotResult.value.size > BridgeConfig.STATE_BATCH_SIZE) kotlinx.coroutines.delay(BridgeConfig.STATE_BATCH_DELAY_MS)
            }
        } else {
            logger.w(tag, "Initial state snapshot failed: ${(snapshotResult as AppResult.Err).error.message}")
        }

        // Resend any unacked notifications (the phone's only while still on the phone), then the
        // removals that waited for the link.
        for ((mid, p) in phoneNotifySync.pendingForResend(_watchLink.value.allowsPhoneNotifications)) {
            protocolHandler.sendToWatch(transport, Protocol.Type.NOTIFY, mid, p)
        }
        sendPhoneRemoves(transport)

        // Sync time immediately on every connection
        sendTimeSyncToWatch(transport)
    }

    private fun setupListeners(
        sessionScope: CoroutineScope,
        ha: HaWebSocketClient,
        ble: BleGattClient,
        transport: BleTransport,
        settings: BridgeSettings,
        configVersion: Int,
    ): List<Job> {
        var cfgVersion = configVersion
        val jobs = mutableListOf<Job>()

        // HA events
        jobs += sessionScope.launch {
            ha.events.collect { msg ->
                eventBroker.handleHaEvent(
                    msg = msg,
                    settings = settings,
                    ha = ha,
                    transport = transport,
                    scope = sessionScope,
                    onWatchFaceLayout = { payload ->
                        val result = CompletableDeferred<WatchFaceLayoutResult>()
                        commands.tryEmit(BridgeCommand.SendWatchFaceLayout(payload, result))
                    },
                    onNotify = { payload ->
                        forwardDiagnostics.fromHa()
                        val mid = protocolHandler.nextMsgId()
                        stateManager.addPendingNotification(mid, payload)
                        protocolHandler.sendToWatch(transport, Protocol.Type.NOTIFY, mid, payload)
                    },
                    onHapticFeedback = { pattern ->
                        commands.tryEmit(BridgeCommand.SendHapticFeedback(pattern))
                    },
                    onBridgeCommand = { command ->
                        when (command) {
                            "ping" -> commands.tryEmit(BridgeCommand.PingWatch)
                            "resync" -> commands.tryEmit(BridgeCommand.ForceResync)
                            "config_push" -> commands.tryEmit(BridgeCommand.ForceConfigPush)
                            "find_watch" -> commands.tryEmit(BridgeCommand.FindWatch())
                        }
                    },
                )
            }
        }

        // The watched entities' state changes, from the state cache (HA's subscription feeds it
        // directly, not through the events above).
        jobs += sessionScope.launch {
            haStateCache.changes.collect { change ->
                try {
                    // A tile entity's: the watch's states.
                    eventBroker.onStateChange(change, transport, sessionScope)
                    // A logbook entity's: the logbook app's entries go out again.
                    logbookOrchestrator.onStateChange(change, ha, transport, sessionScope)
                    // A home-control device's: the devices go out again.
                    homeControlOrchestrator.onStateChange(change, ha, transport, sessionScope)
                    // A dashboard entity's: the dashboard's state goes out again.
                    dashboardStateOrchestrator.onStateChange(change, ha, transport, sessionScope)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.w(tag, "State change of ${change.entityId} not handled: ${e.message}", e)
                }
            }
        }

        // The dashboard's full refresh, every 15 min, which keeps it from reading as stale.
        jobs += sessionScope.launch {
            dashboardStateOrchestrator.runHeartbeat(ha, transport, sessionScope)
        }

        // Every frame from the watch is a sign of life: the liveness PING waits for silence.
        val liveness = LivenessMonitor(android.os.SystemClock::elapsedRealtime)

        // Watch messages
        jobs += sessionScope.launch {
            transport.incomingMessages.collect { m ->
                if (liveness.onInbound()) {
                    logger.i(tag, "Frame received, watch responsive again")
                    connectionManager.updateStatus { copy(watchResponsive = true) }
                }
                logger.d(tag, "Received message: type=0x${m.type.toString(16)}, msgId=${m.msgId}, payload=${m.payload.size} bytes")
                // The watch's messages (a sensor report every minute) also time the dashboard's
                // periodic refresh, which a timer alone cannot while the phone sleeps.
                dashboardStateOrchestrator.onWatchActivity(ha, transport, sessionScope)
                protocolHandler.handleWatchMessage(
                    m = m,
                    ha = ha,
                    transport = transport,
                    sessionScope = sessionScope,
                    onVoiceAssistInvoke = { gesture -> handleVoiceAssistGesture(gesture, transport, sessionScope) },
                    onSensorReport = { payload -> handleSensorReport(payload, settings, sessionScope) },
                    onCalendarNav = { payload -> calendarOrchestrator.handleCalendarNav(payload, transport, sessionScope) },
                    onHomeControlCommand = { entityId -> homeControlOrchestrator.handleHomeControlCommand(entityId, ha, transport, sessionScope) },
                    onNotifyDismiss = { nid ->
                        phoneNotifySync.onWatchDismissed(nid)?.let {
                            if (!phoneDismissChannel.trySend(it).isSuccess) logger.w(tag, "Dismiss queue full: nid=$nid not queued")
                        }
                    },
                    onPong = { msgId ->
                        pongSignal.tryEmit(msgId)
                        refreshWatchLink()
                    },
                    onHelloReceived = { hello ->
                        refreshWatchLink()
                        // Unencrypted: a PONG soon says whether the watch has secured the link since.
                        if (hello.enc == false) commands.tryEmit(BridgeCommand.PingWatch)
                        // Removals waited for a HELLO to say whether the watch takes them.
                        emitPhoneCommand(BridgeCommand.FlushPhoneRemoves)
                        // REQ-067 — surface only a genuine *new* safe-face trip. Seeding the
                        // baseline silently on a null/backwards count avoids re-firing "layout
                        // too heavy" on the first HELLO after every reconnect for a historical
                        // trip (the dashboard is healthy). See shouldNotifySafeFaceTrip.
                        val newTrips = hello.safeFaceTrips
                        if (newTrips != null) {
                            val previousTrips = connectionManager.status.value.lastSafeFaceTrips
                            if (shouldNotifySafeFaceTrip(previousTrips, newTrips)) {
                                logger.w(tag, "Watch reported safe-face trip: trips=$newTrips (was $previousTrips). Layout too heavy; notify user to open preflight.")
                                _safeFaceTripEvent.tryEmit(newTrips)
                            }
                        }
                        connectionManager.updateStatus {
                            copy(
                                watchFirmwareVersion = hello.fwVersion,
                                lastSyncTime = System.currentTimeMillis(),
                                // REQ-066: refresh Debug screen diagnostics on every HELLO (pull-to-refresh).
                                lastResetReason = hello.resetReason ?: lastResetReason,
                                lastFreeHeap = hello.freeHeap ?: lastFreeHeap,
                                lastMinFreeHeap = hello.minFreeHeap ?: lastMinFreeHeap,
                                lastCrashBreadcrumb = hello.crashContext?.breadcrumb ?: lastCrashBreadcrumb,
                                lastCrashFault = hello.crashContext?.faultSummary() ?: lastCrashFault,
                                lastHelloDiagRev = hello.helloDiagRev ?: lastHelloDiagRev,
                                lastWakeDiag = hello.wakeDiag(),
                                watchSupportsVoice = (hello.features and Protocol.Features.VOICE_ASSIST) != 0,
                                watchUpdateNeeded = protocolHandler.watchPredatesConfigKey,
                                lastLayoutRefused = hello.layoutRefusedCount(),
                                lastLayoutWriteFailed = hello.layoutWriteFailedCount(),
                                // REQ-067: track last-seen trip count for future increment detection.
                                lastSafeFaceTrips = hello.safeFaceTrips ?: lastSafeFaceTrips,
                            )
                        }
                    },
                    onVersionMismatch = { required, current ->
                        val msg = "Protocol version mismatch: watch requires $required, phone is $current"
                        logger.w(tag, msg)
                        connectionManager.updateStatus { copy(lastError = msg) }
                    },
                )
            }
        }

        // Time sync
        jobs += sessionScope.launch {
            commands.emit(BridgeCommand.SyncTime)
            while (isActive) {
                delay(24 * 3600 * 1000L)
                commands.emit(BridgeCommand.SyncTime)
            }
        }

        // PING/PONG liveness monitor with latency + RSSI tracking. A PING goes out only after
        // BridgeConfig.LIVENESS_SILENCE_MS in which the watch sent nothing at all (see LivenessMonitor).
        jobs += sessionScope.launch {
            var sentMsgId = 0
            val verdict = liveness.run(
                sendPing = {
                    // REQ-036/037: capture sentMsgId so the PONG wait can match the current round's
                    // id and ignore stale PONGs left over from a previous round. If the PING write
                    // itself fails, it is a missed liveness round immediately rather than a wait of
                    // the full PONG timeout.
                    sentMsgId = protocolHandler.nextMsgId()
                    when (val r = protocolHandler.sendToWatchSafe(transport, Protocol.Type.PING, sentMsgId, EmptyPayload())) {
                        is AppResult.Ok -> LivenessMonitor.Send.Sent
                        is AppResult.Err -> (r.error as? BleLinkLostException)?.let { LivenessMonitor.Send.LinkLost(it.status) }
                            ?: LivenessMonitor.Send.Failed(r.error.message ?: r.error::class.java.simpleName)
                    }
                },
                awaitPong = { timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        // PONG_NO_ECHO is firmware older than 2.4.35, which echoed
                        // no request id; accept it rather than report the watch
                        // unresponsive on every round.
                        pongSignal.firstOrNull { it == sentMsgId || it == Protocol.PONG_NO_ECHO }
                    } != null
                },
                onEvent = { event ->
                    when (event) {
                        LivenessMonitor.Event.PingSent -> log("phone->watch", "PING", null, "liveness check")
                        is LivenessMonitor.Event.PingSendFailed -> {
                            logger.w(tag, "PING send failed (${event.misses}/${BridgeConfig.LIVENESS_MAX_MISSES}): ${event.message}")
                            log("phone->watch", "PING send failed", null, "${event.misses}/${BridgeConfig.LIVENESS_MAX_MISSES}: ${event.message}")
                            connectionManager.updateStatus { copy(watchResponsive = false, pingLatencyMs = null) }
                        }
                        is LivenessMonitor.Event.PongMissed -> {
                            logger.w(tag, "PONG timeout (${event.misses}/${BridgeConfig.LIVENESS_MAX_MISSES})")
                            log("liveness", "PONG miss", null, "${event.misses}/${BridgeConfig.LIVENESS_MAX_MISSES}")
                            connectionManager.updateStatus { copy(watchResponsive = false, pingLatencyMs = null) }
                        }
                        is LivenessMonitor.Event.Alive -> {
                            if (event.latencyMs != null) {
                                // Read RSSI alongside PONG
                                val rssi = ble.readRssi()
                                connectionManager.updateStatus { copy(watchResponsive = true, pingLatencyMs = event.latencyMs, rssi = rssi) }
                            } else {
                                // Another frame showed the link while the PONG did not come.
                                connectionManager.updateStatus { copy(watchResponsive = true) }
                            }
                        }
                    }
                },
            )
            when (verdict) {
                is LivenessMonitor.Verdict.LinkLost -> {
                    // The client closed the link already; the session loop reconnects on its next
                    // check. Three more PINGs would only delay that (up to 11 min, phone asleep).
                    log("liveness", "RECONNECT", null, "link lost (write status ${verdict.status})")
                    connectionManager.updateStatus { copy(watchResponsive = false, pingLatencyMs = null) }
                }
                is LivenessMonitor.Verdict.Unresponsive -> {
                    val why = if (verdict.writeFailed) "PING write failed" else "watch unresponsive"
                    val times = if (verdict.writeFailed) "${BridgeConfig.LIVENESS_MAX_MISSES}x" else "${BridgeConfig.LIVENESS_MAX_MISSES} missed PONGs"
                    logger.e(tag, "$why, forcing reconnect")
                    log("liveness", "RECONNECT", null, "$why ($times)")
                    // Disconnect BLE to trigger normal reconnect path in main loop.
                    // Do NOT throw — this coroutine runs under SupervisorJob, so uncaught
                    // exceptions bypass the outer catch block and crash the app.
                    try { ble.disconnect() } catch (_: Throwable) {}
                }
            }
        }

        // Command handler
        jobs += sessionScope.launch {
            // Phone forwards and removals queued while nobody was collecting (between the initial
            // sync's resend and this subscription) go out as soon as this one is subscribed.
            commands.onSubscription {
                try {
                    // The HELLO of the initial sync said the link is unencrypted: ask for a PONG now.
                    if (protocolHandler.peerEnc == false) {
                        protocolHandler.sendToWatch(transport, Protocol.Type.PING, protocolHandler.nextMsgId(), EmptyPayload())
                    }
                    sendPhoneForwards(transport)
                    sendPhoneRemoves(transport)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.e(tag, "Phone notification flush error: ${e::class.java.simpleName}")
                }
            }.collect { cmd ->
                try {
                    // REQ-038: only bump cfgVersion when pushing config or a watch face
                    // layout — those are the commands the watch matches against its
                    // stored version. Bumping on every command (PING, SyncTime, etc.)
                    // caused unnecessary reconfigure triggers on the watch.
                    val cv = if (cmd is BridgeCommand.ForceConfigPush ||
                                 cmd is BridgeCommand.SendWatchFaceLayout) {
                        cfgVersion++
                    } else {
                        cfgVersion
                    }
                    handleCommand(cmd, ha, ble, transport, settings, cv)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.e(tag, "Command handler error: ${e.message}", e)
                }
            }
        }

        return jobs
    }

    private suspend fun handleCommand(
        cmd: BridgeCommand,
        ha: HaWebSocketClient,
        ble: BleGattClient,
        transport: BleTransport,
        settings: BridgeSettings,
        configVersion: Int,
    ) {
        when (cmd) {
            is BridgeCommand.SendNotify -> {
                val mid = protocolHandler.nextMsgId()
                stateManager.addPendingNotification(mid, cmd.payload)
                if (ble.state.value is BleConnectionState.Connected) {
                    protocolHandler.sendToWatch(transport, Protocol.Type.NOTIFY, mid, cmd.payload)
                }
            }
            BridgeCommand.FlushPhoneForwards -> {
                if (ble.state.value is BleConnectionState.Connected) sendPhoneForwards(transport)
            }
            BridgeCommand.FlushPhoneRemoves -> {
                if (ble.state.value is BleConnectionState.Connected) sendPhoneRemoves(transport)
            }
            BridgeCommand.ForceResync -> {
                if (ble.state.value is BleConnectionState.Connected && ha.isConnected()) {
                    val tileConfigs = eventBroker.getTileEntityConfigs()
                    val snapshotResult = protocolHandler.fetchSelectedStates(ha, tileConfigs)
                    if (snapshotResult is AppResult.Ok) {
                        stateManager.updateEntityStates(snapshotResult.value)
                        // Batch STATE_UPDATEs into groups of 8 to avoid large multi-chunk
                        // BLE messages that exhaust the watch's heap during reassembly.
                        for (batch in snapshotResult.value.chunked(BridgeConfig.STATE_BATCH_SIZE)) {
                            protocolHandler.sendStateUpdate(transport, protocolHandler.nextMsgId(), batch)
                            if (snapshotResult.value.size > BridgeConfig.STATE_BATCH_SIZE) {
                                kotlinx.coroutines.delay(BridgeConfig.STATE_BATCH_DELAY_MS)
                            }
                        }
                    } else {
                        logger.w(tag, "Force resync failed: ${(snapshotResult as AppResult.Err).error.message}")
                    }
                }
            }
            BridgeCommand.ResyncChanged -> {
                if (ble.state.value is BleConnectionState.Connected && ha.isConnected()) {
                    val tileConfigs = eventBroker.getTileEntityConfigs()
                    val snapshotResult = protocolHandler.fetchSelectedStates(ha, tileConfigs)
                    if (snapshotResult is AppResult.Ok) {
                        // Compared with what the watch was sent last.
                        val changed = stateManager.filterAndUpdateChangedStates(snapshotResult.value)
                        log("ha", "Resync after reconnect", null, "${changed.size} of ${snapshotResult.value.size} states changed")
                        for (batch in changed.chunked(BridgeConfig.STATE_BATCH_SIZE)) {
                            protocolHandler.sendStateUpdate(transport, protocolHandler.nextMsgId(), batch)
                            if (changed.size > BridgeConfig.STATE_BATCH_SIZE) {
                                kotlinx.coroutines.delay(BridgeConfig.STATE_BATCH_DELAY_MS)
                            }
                        }
                    } else {
                        logger.w(tag, "Resync after reconnect failed: ${(snapshotResult as AppResult.Err).error.message}")
                    }
                }
            }
            BridgeCommand.ForceConfigPush -> {
                if (ble.state.value is BleConnectionState.Connected) {
                    val latestSettings = settingsRepo.settings.first()
                    val cfgPayload = configPushPayload(configVersion, latestSettings)
                    if (protocolHandler.sendConfigPush(transport, protocolHandler.nextMsgId(), cfgPayload)) {
                        log("config", "Forced config push")
                    } else {
                        log("config", "CONFIG_PUSH not sent", null, WATCH_PREDATES_CONFIG_KEY_LOG)
                    }
                }
            }
            BridgeCommand.PingWatch -> {
                if (ble.state.value is BleConnectionState.Connected) {
                    protocolHandler.sendToWatch(transport, Protocol.Type.PING, protocolHandler.nextMsgId(), EmptyPayload())
                } else {
                    log("error", "PING", summary = "BLE not connected")
                }
            }
            is BridgeCommand.SendHapticFeedback -> {
                if (ble.state.value is BleConnectionState.Connected) {
                    protocolHandler.sendToWatch(transport, Protocol.Type.HAPTIC_FEEDBACK, protocolHandler.nextMsgId(), HapticFeedbackPayload(pattern = cmd.pattern))
                }
            }
            is BridgeCommand.SendWatchFaceLayout -> handleWatchFaceLayoutCommand(cmd, ha, ble, transport, scope)
            BridgeCommand.SyncTime -> handleTimeSyncCommand(ble, transport)
            is BridgeCommand.FindWatch -> {
                if (ble.state.value is BleConnectionState.Connected) {
                    protocolHandler.sendFindWatch(transport, cmd.durationSeconds)
                    log("phone->watch", "FIND_WATCH", null, "duration=${cmd.durationSeconds}s")
                }
            }
            // REQ-066: send phone HELLO so watch responds with its diagnostic HELLO,
            // refreshing firmware version, reset reason, heap, and crash context.
            BridgeCommand.RefreshDiagnostics -> {
                if (ble.state.value is BleConnectionState.Connected) {
                    protocolHandler.sendToWatch(
                        transport,
                        Protocol.Type.HELLO,
                        protocolHandler.nextMsgId(),
                        HelloPayload(device = "phone", protoVersionString = Protocol.PROTOCOL_VERSION),
                    )
                    log("phone->watch", "HELLO", null, "diagnostic refresh")
                } else {
                    log("error", "HELLO", summary = "BLE not connected — diagnostic refresh skipped")
                }
            }
        }
    }

    /** NOTIFY for each phone forward not yet sent, if the link still allows it (else they are dropped). */
    private suspend fun sendPhoneForwards(transport: BleTransport) {
        phoneNotifySync.flushForwards(_watchLink.value.allowsPhoneNotifications) { mid, payload ->
            protocolHandler.sendToWatch(transport, Protocol.Type.NOTIFY, mid, payload)
        }
    }

    /** NOTIFY_REMOVE for each removal that is waiting, if the watch's HELLO has PHONE_NOTIFY_SYNC. */
    private suspend fun sendPhoneRemoves(transport: BleTransport) {
        val nids = phoneNotifySync.takeRemoves(
            helloSeen = protocolHandler.helloSeen,
            watchSupportsSync = protocolHandler.peerSupports(Protocol.Features.PHONE_NOTIFY_SYNC),
        )
        for (nid in nids) {
            protocolHandler.sendToWatch(transport, Protocol.Type.NOTIFY_REMOVE, protocolHandler.nextMsgId(), NotifyRemovePayload(nid))
        }
    }

    private suspend fun handleWatchFaceLayoutCommand(
        cmd: BridgeCommand.SendWatchFaceLayout,
        ha: HaWebSocketClient,
        ble: BleGattClient,
        transport: BleTransport,
        sessionScope: CoroutineScope,
    ) {
        if (ble.state.value !is BleConnectionState.Connected) {
            cmd.result.complete(WatchFaceOrchestrator.WatchFaceLayoutResult.NotConnected)
            return
        }
        val result = watchFaceOrchestrator.handleWatchFaceLayoutCommand(
            cmd.payload, ha, transport, cmd.onProgress,
            protocolHandler.peerFeatures, protocolHandler.peerHelloDiagRev,
        )
        cmd.result.complete(result)
        // After a successful layout push, which can change the calendars, the devices and the
        // logbook entities, send today's events, the device states and the logbook entries.
        if (result is WatchFaceOrchestrator.WatchFaceLayoutResult.Success ||
            result is WatchFaceOrchestrator.WatchFaceLayoutResult.SuccessTrimmed
        ) {
            calendarOrchestrator.sendToday(transport, sessionScope)
            homeControlOrchestrator.sendHomeControlUpdate(ha, transport, sessionScope)
            logbookOrchestrator.sendLogbookApp(ha, transport, sessionScope)
            // A new layout can change the glyphs, so the state the watch holds no longer applies.
            dashboardStateOrchestrator.refresh("layout", ha, transport, sessionScope)
        }
    }

    private suspend fun handleTimeSyncCommand(ble: BleGattClient, transport: BleTransport) {
        if (ble.state.value is BleConnectionState.Connected) {
            sendTimeSyncToWatch(transport)
        }
    }

    private suspend fun sendTimeSyncToWatch(transport: BleTransport) {
        protocolHandler.sendTimeSync(transport)
    }

    private suspend fun handleVoiceAssistGesture(gesture: String, transport: BleTransport, sessionScope: CoroutineScope) {
        when (gesture) {
            "tap" -> handleVoiceAssistInvoke(transport, sessionScope)
            "double" -> {
                val running = voiceBusy.get() || activeVoiceSessionJob?.isActive == true
                log("voice", "cancel", null, if (running) "double-click during a session" else "double-click, no session")
                activeVoiceSessionJob?.cancel()
                activeVoiceSessionJob = null
                voiceAssistantManager.cancelSession()
                // The result clears the watch's session state, which a lost final result can leave set.
                // With no session there is nothing to confirm by touch, so no haptic.
                sendVoiceResult(transport, ok = true, message = "cancelled")
                if (running) sendVoiceHaptic(transport, "short")
            }
        }
    }

    /**
     * A voice trigger from the watch: checks first, and only a trigger that can work is answered
     * with "listening" (the manager sends it when the microphone is ready). One that cannot is
     * answered with its reason and a failure buzz. A trigger during a session is ignored.
     */
    private suspend fun handleVoiceAssistInvoke(transport: BleTransport, sessionScope: CoroutineScope) {
        if (!voiceBusy.compareAndSet(false, true)) {
            log("voice", "trigger ignored", null, "a session is running")
            return
        }
        var launched = false
        try {
            val settings = settingsRepo.settings.first()
            val token = tokenStore.getToken().orEmpty()
            val refusal = VoicePreflight.failure(
                enabled = settings.voiceAssist.enabled,
                microphonePermission = appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
                serviceMicrophone = connectionManager.status.value.serviceMicrophone,
                hasToken = token.isNotBlank(),
                tokenRejected = token.isNotBlank() && voiceAssistantManager.authGuard.isRejected(token),
            )
            if (refusal != null) {
                log("voice", "trigger refused", null, refusal)
                sendVoiceFailure(transport, refusal)
                return
            }

            // The URL that URL selection picks now, local or remote: the voice session uses it as it is.
            val urlResult = connectionManager.selectUrl(settings)
            if (urlResult is AppResult.Err) {
                log("voice", "trigger refused", null, "no HA URL: ${urlResult.error.message}")
                sendVoiceFailure(transport, "HA offline")
                return
            }
            val haUrl = (urlResult as AppResult.Ok).value

            activeVoiceSessionJob = sessionScope.launch {
                try {
                    val listener = VoiceSessionListener { state ->
                        when (state) {
                            VoiceState.Listening -> {
                                sendVoiceResult(transport, ok = true, message = "listening")
                                sendVoiceHaptic(transport, "recording_start")
                            }
                            VoiceState.Processing -> sendVoiceResult(transport, ok = true, message = "processing")
                            VoiceState.Speaking -> sendVoiceResult(transport, ok = true, message = "speaking")
                            else -> Unit
                        }
                    }
                    val request = VoiceSessionRequest(haUrl, token, settings.voiceAssist.pipelineId)
                    when (val result = voiceAssistantManager.startSession(request, listener)) {
                        is AppResult.Ok -> {
                            sendVoiceResult(transport, ok = true, message = "done")
                            sendVoiceHaptic(transport, "short")
                        }
                        is AppResult.Err -> sendVoiceFailure(transport, result.error.message ?: "Voice failed")
                    }
                } finally {
                    voiceBusy.set(false)
                }
            }
            launched = true
        } finally {
            if (!launched) voiceBusy.set(false)
        }
    }

    /** Tells the watch how the voice session stands; a watch that cannot be reached is not an error here. */
    private suspend fun sendVoiceResult(transport: BleTransport, ok: Boolean, message: String) {
        protocolHandler.sendToWatchSafe(transport, Protocol.Type.ACTION_RESULT, protocolHandler.nextMsgId(),
            ActionResultPayload(actionId = Protocol.VOICE_ASSIST_ACTION_ID, ok = ok, message = message))
    }

    private suspend fun sendVoiceHaptic(transport: BleTransport, pattern: String) {
        protocolHandler.sendToWatchSafe(transport, Protocol.Type.HAPTIC_FEEDBACK, protocolHandler.nextMsgId(),
            HapticFeedbackPayload(pattern = pattern))
    }

    /** The session's failure: the reason as the final result, and the double buzz. */
    private suspend fun sendVoiceFailure(transport: BleTransport, reason: String) {
        sendVoiceResult(transport, ok = false, message = reason)
        sendVoiceHaptic(transport, "double")
    }

    /**
     * Records whether the bridge's foreground service runs with the microphone type. Voice needs
     * it to record while the app is in the background; a trigger without it is refused.
     */
    fun setServiceMicrophone(active: Boolean) {
        connectionManager.updateStatus { copy(serviceMicrophone = active) }
    }

    private fun handleSensorReport(payload: SensorReportPayload, settings: BridgeSettings, sessionScope: CoroutineScope) {
        connectionManager.updateStatus {
            copy(
                watchBatteryPercent = payload.batteryPercent,
                watchBatteryCharging = payload.batteryCharging,
                lastSyncTime = System.currentTimeMillis(),
                lastSensorReport = payload,
            )
        }
        if (settings.infinassistant.enabled && settings.infinassistant.webhookId.isNotEmpty()) {
            val deviceAddr = settings.primaryDeviceAddress
            if (!deviceAddr.isNullOrBlank()) {
                sessionScope.launch {
                    webhookClient.postSensorReport(
                        webhookId = settings.infinassistant.webhookId,
                        deviceId = deviceAddr,
                        payload = payload
                    ).onFailure { error ->
                        logger.w(tag, "Failed to push sensor report: ${error.message}")
                    }
                }
            }
        }
    }


    /** The last connection failure logged per link ("HA", "BLE"): its reason and when. */
    private val lastConnectFailure = mutableMapOf<String, Pair<String, Long>>()

    /**
     * Connection failures go to the diagnostic log, once per new reason and otherwise at most
     * every 10 minutes per link. The watch is only tried once HA is connected, and after one
     * firmware update the phone stayed off the watch for 3 hours with nothing in a bundle to
     * say which link was failing or why.
     */
    private fun logConnectFailure(link: String, error: Throwable) {
        val reason = error.message ?: error.javaClass.simpleName
        val now = System.currentTimeMillis()
        val last = lastConnectFailure[link]
        if (last == null || last.first != reason || now - last.second >= CONNECT_FAILURE_LOG_INTERVAL_MS) {
            lastConnectFailure[link] = reason to now
            log("engine", "$link connect failed", null, reason)
        }
    }

    private fun log(direction: String, type: String, msgId: Int? = null, summary: String = "") {
        val entry = ProtocolLogEntry(ts = System.currentTimeMillis(), direction = direction, type = type, msgId = msgId, summary = summary)
        _protocolLog.update { (it + entry).takeLast(50) }
        diagnosticLogger.log(
            level = "DEBUG",
            tag = direction,
            msg = type,
            msgId = msgId,
            extra = summary.takeIf { it.isNotEmpty() },
        )
    }

    /** Export diagnostic logs to a shareable file. Returns null if no logs exist. */
    fun exportLogs(): java.io.File? {
        flushDiagnostics()
        val fwVersion = status.value.watchFirmwareVersion
        return diagnosticLogger.exportForSharing(
            appVersion = try { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" },
            firmwareVersion = fwVersion,
        )
    }

    /** Writes the counts still held for the diagnostic log (the notification skips), before it is read. */
    fun flushDiagnostics() = forwardDiagnostics.flush()

    /** Clear all diagnostic logs. */
    fun clearLogs() {
        diagnosticLogger.clear()
    }

    /** The last resolved watchface layout, for the debug bundle. Null if none pushed yet. */
    fun lastWatchFaceLayout() = watchFaceOrchestrator.lastWatchFaceLayout

    /**
     * True when the connected watch accepts short layout keys, by the same rule a push uses
     * ([ProtocolHandler.peerAcceptsShortKeys]), so the editor measures the spelling a push
     * would send ([ProtocolHandler.encodeWatchFaceLayout]).
     */
    fun watchAcceptsShortKeys(): Boolean = protocolHandler.peerAcceptsShortKeys()

    /** The screens the connected watch holds: 6 with the six-screen HELLO bit, else 4 (also with no watch connected). */
    fun watchScreenCap(): Int = WatchFaceOrchestrator.screenCap(protocolHandler.peerFeatures)
}

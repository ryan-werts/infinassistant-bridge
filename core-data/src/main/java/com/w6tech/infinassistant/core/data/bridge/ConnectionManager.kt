package com.w6tech.infinassistant.core.data.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.ble.BleGattClient
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.CircuitBreaker
import com.w6tech.infinassistant.core.common.ExponentialBackoff
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.data.BridgeStatus
import com.w6tech.infinassistant.core.data.HaTokenStore
import com.w6tech.infinassistant.core.data.HealthSummary
import com.w6tech.infinassistant.core.data.UrlSelector
import com.w6tech.infinassistant.core.data.UrlSelectionResult
import com.w6tech.infinassistant.core.domain.BridgeSettings
import com.w6tech.infinassistant.core.ha.HaConnectionState
import com.w6tech.infinassistant.core.ha.HaWebSocketClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages Home Assistant and BLE connection lifecycle.
 *
 * Responsibilities:
 * - URL selection
 * - HA WebSocket connection establishment
 * - BLE GATT connection establishment
 * - Reconnection with exponential backoff
 * - Connection state tracking
 * - Timezone change monitoring
 */
class ConnectionManager(
    private val appContext: Context,
    private val logger: Logger,
    private val tokenStore: HaTokenStore,
    private val urlSelector: UrlSelector,
    /** The watched entities' states, which every HA connection subscribes ([subscribeHaEvents]). */
    private val stateCache: HaStateCache,
) {
    private val tag = "ConnectionManager"

    private val _status = MutableStateFlow(
        BridgeStatus(
            connectedModeEnabled = false,
            haState = HaConnectionState.Disconnected,
            bleState = BleConnectionState.Disconnected,
        )
    )
    val status: StateFlow<BridgeStatus> = _status.asStateFlow()

    // A background bridge must never permanently give up reconnecting — otherwise the
    // watch stays disconnected until the user manually toggles Connected Mode. Retries are
    // unlimited; ExponentialBackoff caps the interval (maxMs = 120 s) so an out-of-range
    // watch costs roughly one reconnect attempt every two minutes.
    val haBackoff = ExponentialBackoff()
    val bleBackoff = ExponentialBackoff()

    // Bluetooth coming back on ends a BLE retry's wait (bleReconnectDelay), so the watch is
    // reached at once instead of up to 2 min later. Conflated: an "on" while no retry waits
    // ends the next wait, one prompt extra attempt at most.
    private val bluetoothCameOn = Channel<Unit>(Channel.CONFLATED)
    // Bluetooth going off ends a background connection's wait (connectBle): the stack drops the
    // request with the adapter, and the wait would otherwise outlast it.
    private val bluetoothWentOff = Channel<Unit>(Channel.CONFLATED)
    private var bluetoothReceiver: BroadcastReceiver? = null

    /** Circuit breaker protecting HA API calls. Opens after 5 failures in 60 s. */
    val haCircuitBreaker = CircuitBreaker(failureThreshold = 5, failureWindowMs = 60_000L)

    // Timezone change receiver for automatic time sync
    private var timezoneReceiver: BroadcastReceiver? = null
    private var lastKnownTimezoneId: String = java.util.TimeZone.getDefault().id
    private var onTimezoneChanged: (() -> Unit)? = null

    /**
     * Update connection state status.
     */
    fun updateStatus(update: BridgeStatus.() -> BridgeStatus) {
        _status.update { it.update() }
    }

    /**
     * Select the appropriate URL.
     */
    suspend fun selectUrl(settings: BridgeSettings): AppResult<String> {
        val urlResult = urlSelector.selectUrl(settings.ha)

        return when (urlResult) {
            is UrlSelectionResult.Selected -> {
                // Which URL, and why: a debug bundle keeps this line (DiagnosticTeeLogger), so the
                // remote URL taken when the local one's test failed shows with the failure.
                val which = if (urlResult.url == settings.ha.localUrl) "local" else "remote"
                val why = urlResult.localTestError?.let { "; local test failed: ${it.take(120)}" }.orEmpty()
                logger.i(tag, "${HealthSummary.HA_URL} $which ${urlResult.url} (${urlResult.source}$why)")
                AppResult.Ok(urlResult.url)
            }
            is UrlSelectionResult.Error -> {
                AppResult.Err(IllegalStateException(urlResult.reason))
            }
        }
    }

    /**
     * Validate settings and return null if valid, or an error message if not.
     */
    suspend fun validateSettings(settings: BridgeSettings): String? {
        if (settings.primaryDeviceAddress.isNullOrBlank()) {
            return "No associated device"
        }
        if (tokenStore.getToken().isNullOrBlank()) {
            return "Home Assistant token not configured"
        }
        val urlValidation = selectUrl(settings)
        if (urlValidation is AppResult.Err) {
            return "Home Assistant URL not configured: ${urlValidation.error.message}"
        }
        return null
    }

    /**
     * Connect to Home Assistant WebSocket.
     */
    suspend fun connectHa(
        ha: HaWebSocketClient,
        settings: BridgeSettings,
    ): AppResult<String> {
        updateStatus { copy(haState = HaConnectionState.Connecting, lastError = null) }

        val urlResult = selectUrl(settings)
        if (urlResult is AppResult.Err) {
            // Before any socket: the HA socket's own "connect failed" lines do not cover these two.
            logger.w(tag, "HA connect failed: ${urlResult.error.message}")
            updateStatus { copy(haState = HaConnectionState.Failed(urlResult.error), lastError = urlResult.error.message) }
            return urlResult
        }
        val url = (urlResult as AppResult.Ok).value

        val token = tokenStore.getToken() ?: run {
            logger.w(tag, "HA connect failed: no token")
            return AppResult.Err(IllegalStateException("No token"))
        }

        // Guard HA connection attempt with the circuit breaker.
        if (!haCircuitBreaker.allowRequest()) {
            val msg = "HA circuit breaker OPEN — too many recent failures"
            logger.w(tag, msg)
            updateStatus { copy(haState = HaConnectionState.Failed(IllegalStateException(msg)), lastError = msg) }
            return AppResult.Err(IllegalStateException(msg))
        }

        val r = ha.connect(url, token)
        if (r is AppResult.Err) {
            haCircuitBreaker.recordFailure()
            updateStatus { copy(haState = HaConnectionState.Failed(r.error), lastError = r.error.message) }
            return AppResult.Err(r.error)
        }

        haCircuitBreaker.recordSuccess()
        haBackoff.reset()
        updateStatus { copy(haState = HaConnectionState.Connected) }
        return AppResult.Ok(url)
    }

    /**
     * Subscribe to Home Assistant events: the states of the entities the watch needs, which
     * [HaStateCache] keeps (state_changed, all of HA's, on an HA older than 2022.4), and the
     * notification, layout and command events.
     */
    suspend fun subscribeHaEvents(ha: HaWebSocketClient, settings: BridgeSettings): AppResult<Unit> {
        stateCache.subscribe(ha).also { if (it is AppResult.Err) return it }
        ha.subscribeEvents(settings.notificationEventType).also { if (it is AppResult.Err) return it }
        ha.subscribeEvents("infinassistant_watchface_layout_changed").also { if (it is AppResult.Err) return it }
        ha.subscribeEvents("infinassistant_command").also { if (it is AppResult.Err) return it }
        return AppResult.Ok(Unit)
    }

    /**
     * Connects to the watch over BLE. Direct by default. [background] leaves it to Android's
     * Bluetooth stack ([BleGattClient.connect]), which connects when the watch is in range and
     * wakes the app: the wait for a watch that went out of range. Bluetooth going off ends that
     * wait with "Bluetooth is off".
     */
    suspend fun connectBle(
        ble: BleGattClient,
        deviceAddress: String,
        background: Boolean = false,
    ): AppResult<Unit> {
        updateStatus { copy(bleState = BleConnectionState.Connecting, lastError = null) }

        val (bluetoothOn, device) = withContext(Dispatchers.Default) {
            val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
                ?: throw IllegalStateException("Bluetooth not available")
            adapter.isEnabled to adapter.getRemoteDevice(deviceAddress)
        }

        // With Bluetooth off, or its service restarting, Android reports every device as not
        // bonded: until 2.1.106 that read "Device not bonded" for as long as Bluetooth was off
        // (47 min in one bundle), which looked like a lost pairing.
        if (!bluetoothOn) {
            val error = IllegalStateException("Bluetooth is off")
            updateStatus { copy(bleState = BleConnectionState.Failed(error), lastError = error.message) }
            return AppResult.Err(error)
        }

        @Suppress("MissingPermission")  // Permission checked at service start
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            val error = IllegalStateException("Device not bonded; bonding required")
            updateStatus { copy(bleState = BleConnectionState.Failed(error), lastError = error.message) }
            return AppResult.Err(error)
        }

        val r = if (background) untilBluetoothOff { ble.connect(device, background = true) } else ble.connect(device)
        if (r is AppResult.Err) {
            updateStatus { copy(bleState = BleConnectionState.Failed(r.error), lastError = r.error.message) }
            return r
        }

        bleBackoff.reset()
        updateStatus { copy(bleState = ble.state.value) }
        return AppResult.Ok(Unit)
    }

    /**
     * Update status from current connection states.
     */
    fun syncStatus(ha: HaWebSocketClient, ble: BleGattClient) {
        updateStatus { copy(haState = ha.state.value, bleState = ble.state.value) }
    }

    /**
     * Registers a BroadcastReceiver to detect timezone changes and trigger time sync.
     */
    fun registerTimezoneReceiver(onChanged: () -> Unit) {
        if (timezoneReceiver != null) return // Already registered
        onTimezoneChanged = onChanged

        timezoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> {
                        val newTimezoneId = java.util.TimeZone.getDefault().id
                        logger.d(tag, "Timezone/time changed: $lastKnownTimezoneId -> $newTimezoneId")
                        lastKnownTimezoneId = newTimezoneId
                        onTimezoneChanged?.invoke()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(timezoneReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(timezoneReceiver, filter)
        }
        logger.d(tag, "Timezone change receiver registered")
    }

    /**
     * Unregisters the timezone change receiver.
     */
    fun unregisterTimezoneReceiver() {
        timezoneReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
                logger.d(tag, "Timezone change receiver unregistered")
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
        }
        timezoneReceiver = null
        onTimezoneChanged = null
    }

    /**
     * Handle backoff delay for BLE reconnection. Never gives up — the interval is capped by
     * [ExponentialBackoff] (maxMs) so retries continue indefinitely at a low duty cycle.
     * Bluetooth coming back on ([onBluetoothState]) ends the wait, and the retries start over
     * from the shortest interval.
     */
    suspend fun bleReconnectDelay() {
        val delayMs = bleBackoff.nextDelayMs()
        logger.d(tag, "BLE reconnect delay: ${delayMs}ms (attempt ${bleBackoff.retryCount})")
        if (withTimeoutOrNull(delayMs) { bluetoothCameOn.receive() } != null) {
            bleBackoff.reset()
        }
    }

    /**
     * Follows the Bluetooth adapter for a session: [onChanged] gets true when it comes on and
     * false when it goes off (the diagnostic log records both), and coming on ends a BLE
     * retry's wait.
     */
    fun registerBluetoothStateReceiver(onChanged: (on: Boolean) -> Unit) {
        if (bluetoothReceiver != null) return
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> onBluetoothState(true, onChanged)
                    BluetoothAdapter.STATE_OFF -> onBluetoothState(false, onChanged)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(bluetoothReceiver, filter)
        }
    }

    fun unregisterBluetoothStateReceiver() {
        bluetoothReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver was not registered, ignore
            }
        }
        bluetoothReceiver = null
    }

    /**
     * The adapter went on or off: on ends a BLE retry's wait, off a background connection's.
     * The receiver's entry, and the tests'.
     */
    internal fun onBluetoothState(on: Boolean, onChanged: (on: Boolean) -> Unit = {}) {
        if (on) bluetoothCameOn.trySend(Unit) else bluetoothWentOff.trySend(Unit)
        onChanged(on)
    }

    /**
     * [attempt]'s result, or "Bluetooth is off" once the adapter goes off, which cancels the
     * attempt. An "off" from before the call does not count.
     */
    internal suspend fun untilBluetoothOff(attempt: suspend () -> AppResult<Unit>): AppResult<Unit> {
        while (bluetoothWentOff.tryReceive().isSuccess) Unit
        return coroutineScope {
            val run = async { attempt() }
            val off = async { bluetoothWentOff.receive() }
            select {
                run.onAwait { result ->
                    off.cancel()
                    result
                }
                off.onAwait {
                    run.cancel()
                    AppResult.Err(IllegalStateException("Bluetooth is off"))
                }
            }
        }
    }

    /**
     * Reset all reconnection bookkeeping — both backoffs and the HA circuit breaker.
     * Called at the start of a fresh connect session so a restarted session never begins
     * already throttled (e.g. after the "Retry connection" button or a settings change).
     */
    fun resetReconnectState() {
        haBackoff.reset()
        bleBackoff.reset()
        haCircuitBreaker.reset()
    }

    /**
     * Disconnect from both HA and BLE.
     */
    suspend fun disconnectAll(ha: HaWebSocketClient, ble: BleGattClient) {
        try {
            ble.disconnect()
        } catch (_: Throwable) {}
        ha.disconnect()
        updateStatus {
            copy(
                haState = HaConnectionState.Disconnected,
                bleState = BleConnectionState.Disconnected,
            )
        }
    }
}

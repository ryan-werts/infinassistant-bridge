package com.w6tech.infinassistant.core.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.common.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

sealed interface BleConnectionState {
    data object Disconnected : BleConnectionState
    data object Connecting : BleConnectionState
    data object Discovering : BleConnectionState
    data class Connected(val mtu: Int) : BleConnectionState
    data class Failed(val error: Throwable) : BleConnectionState
}

class BleGattClient(
    private val appContext: Context,
    private val logger: Logger,
    private val scope: CoroutineScope,
) {
    private val tag = "BleGattClient"

    companion object {
        /** Incoming BLE frame channel capacity. */
        const val INCOMING_CHANNEL_CAPACITY = 256
        /** Timeout for GATT connect (ms). */
        const val CONNECT_TIMEOUT_MS = 20_000L
        /** Timeout for MTU negotiation (ms). */
        const val MTU_TIMEOUT_MS = 5_000L
        /** Default MTU when negotiation fails — BLE ATT spec minimum. */
        const val DEFAULT_MTU = 23
        /** Timeout for service discovery (ms). */
        const val DISCOVERY_TIMEOUT_MS = 20_000L
        /** Timeout for characteristic write with response (ms). */
        const val WRITE_RESPONSE_TIMEOUT_MS = 10_000L

        /**
         * writeCharacteristic results (BluetoothStatusCodes) that mean the link is already gone:
         * ERROR_BLUETOOTH_NOT_ENABLED (1) and ERROR_PROFILE_SERVICE_NOT_BOUND (9). Android reports
         * the second with no disconnect callback, overnight with the phone asleep; retrying the
         * write cannot succeed.
         */
        val LINK_LOST_WRITE_STATUSES = setOf(1, 9)

        /**
         * A connection-state change with a failure status on a link that was up ([established]: the
         * connect finished, notifications on; not merely the radio link made) is the link dropping
         * (147, the connection timing out; 8, supervision timeout; 19, the watch ending it), not a
         * failed connect: the GATT is closed, as a clean disconnect is, so the bridge sees the link
         * lost and connects and syncs the watch again. During a connect it fails the connect. Until app 2.1.118 it was only logged; Android's
         * background connection then brought the same GATT back a minute later, pings went through,
         * and the watch, which had reset its side at the disconnect, never got the phone's setup
         * again (2026-10-21: 1 h 47 min with no CONFIG_PUSH, so the watch's voice gate stayed off).
         */
        fun isLinkLost(established: Boolean, status: Int, newState: Int): Boolean =
            established && status != 0 && newState == 0  // BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED

        /**
         * REQ-032 — flip to `true` in instrumented tests / a debug build flavor
         * to fail fast on concurrent GATT ops instead of just logging.
         */
        const val DEBUG_ASSERT_CONCURRENT: Boolean = false
    }

    private val _state = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val state: StateFlow<BleConnectionState> = _state.asStateFlow()

    private val incoming = Channel<ByteArray>(capacity = INCOMING_CHANNEL_CAPACITY)
    val incomingFrames: Flow<ByteArray> = incoming.receiveAsFlow()

    private var gatt: BluetoothGatt? = null
    private var txNotify: BluetoothGattCharacteristic? = null
    private var rxWrite: BluetoothGattCharacteristic? = null

    private val writeMutex = Mutex()
    @Volatile
    private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    @Volatile
    private var pendingRssi: CompletableDeferred<Int>? = null

    /**
     * REQ-032 — single-state-machine guard. The Android GATT stack is single-
     * threaded but does not enforce sequencing between requestMtu /
     * discoverServices / write / readRssi; concurrent ops manifest as silent
     * timeouts or out-of-order callbacks. Track the in-flight op name here so
     * any overlapping op surfaces immediately in logs (and throws in debug
     * builds via DEBUG_ASSERT_CONCURRENT). Release builds log a warning and
     * let the BLE stack sort it out — losing telemetry beats throwing in
     * production from a callback path the user cannot recover.
     */
    @Volatile
    private var pendingGattOp: String? = null

    private fun beginGattOp(name: String) {
        val prior = pendingGattOp
        if (prior != null) {
            val msg = "Concurrent GATT op: '$name' started while '$prior' pending"
            if (DEBUG_ASSERT_CONCURRENT) throw IllegalStateException(msg)
            logger.w(tag, msg)
        }
        pendingGattOp = name
    }

    private fun endGattOp() {
        pendingGattOp = null
    }

    /**
     * Connects to [device] and readies the bridge service: MTU, discovery, notifications.
     *
     * Direct ([background] false): the Bluetooth stack tries for some 30 s with the watch out of
     * range, and this call gives up after [CONNECT_TIMEOUT_MS].
     *
     * Background ([background] true): Android's background connection (autoConnect). The request
     * stays with the stack until the watch is in range, however long that is, and the stack
     * reconnects and wakes the app; no timer of the app's runs meanwhile. The app's timers stop
     * while the phone sleeps, so with the watch away a loop of direct attempts stalled for hours
     * (bundle -170345: 5 h). Cancelling the call withdraws the request.
     */
    suspend fun connect(device: BluetoothDevice, mtuRequest: Int = 247, background: Boolean = false): AppResult<Unit> {
        return try {
            _state.value = BleConnectionState.Connecting
            val connected = CompletableDeferred<Unit>()
            val discovered = CompletableDeferred<Unit>()
            val mtuResult = CompletableDeferred<Int>()

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        logger.w(tag, "onConnectionStateChange status=$status")
                        if (isLinkLost(_state.value is BleConnectionState.Connected, status, newState)) {
                            if (g === gatt) scope.launch { closeGatt("link lost: status=$status") }
                            return
                        }
                        // A connect still in progress failed: every step it is waiting on fails with
                        // it, so connect() returns an error the engine retries. (App 2.1.118 closed
                        // the GATT here instead, under the connect, and the session ended.)
                        val failure = RuntimeException("GATT connect status=$status")
                        connected.completeExceptionally(failure)
                        mtuResult.completeExceptionally(failure)
                        discovered.completeExceptionally(failure)
                        pendingDescriptorWrite?.completeExceptionally(failure)
                        return
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        logger.i(tag, "GATT connected")
                        endGattOp() // "connect" finished
                        connected.complete(Unit)
                        // REQ-032: do NOT call g.discoverServices() here — discovery
                        // is sequenced explicitly after MTU negotiation in connect()
                        // to keep the state machine single-threaded.
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        logger.i(tag, "GATT disconnected")
                        scope.launch { closeGatt("disconnect") }
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    endGattOp() // "mtu" finished (success or fail — both complete the wait)
                    // REQ-062 — name the captured value `negotiatedMtu` to mirror
                    // the firmware HELLO field of the same name; future diagnostic
                    // surfaces can correlate the two without a second grep.
                    val negotiatedMtu = mtu
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        logger.i(tag, "MTU negotiated: negotiatedMtu=$negotiatedMtu")
                    } else {
                        logger.w(tag, "MTU request failed status=$status negotiatedMtu=$negotiatedMtu")
                    }
                    mtuResult.complete(negotiatedMtu)
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    endGattOp() // "discover" finished
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        discovered.completeExceptionally(RuntimeException("discoverServices status=$status"))
                        return
                    }
                    val svc = g.getService(BleUuids.SERVICE)
                    if (svc == null) {
                        discovered.completeExceptionally(IllegalStateException("HA Bridge service not found"))
                        return
                    }
                    txNotify = svc.getCharacteristic(BleUuids.CHAR_TX_NOTIFY)
                    rxWrite = svc.getCharacteristic(BleUuids.CHAR_RX_WRITE)
                    if (txNotify == null || rxWrite == null) {
                        discovered.completeExceptionally(IllegalStateException("Missing required characteristics"))
                        return
                    }
                    // REQ-032: do NOT launch enableNotify here — it runs explicitly
                    // after discovered.await() in connect() so the sequence stays
                    // observable from a single coroutine.
                    discovered.complete(Unit)
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    if (characteristic.uuid == BleUuids.CHAR_TX_NOTIFY) {
                        if (!incoming.trySend(value).isSuccess) {
                            logger.w(tag, "BLE frame dropped: channel full (capacity=$INCOMING_CHANNEL_CAPACITY)")
                        }
                    }
                }

                override fun onCharacteristicWrite(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    // HA-restore debugging: acknowledged writes (WRITE_TYPE_DEFAULT) are
                    // used for WATCHFACE_LAYOUT — a SUCCESS here means the watch's ATT
                    // layer acked receipt, i.e. the watch physically received the frame.
                    // That cleanly separates "watch got the layout but never replied"
                    // (reply-path bug / crash) from "link was dead" (write failed). Log
                    // those completions and all failures; stay quiet on routine
                    // no-response writes (PING/STATE_UPDATE) to avoid flooding the ring.
                    val acked = characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (acked) logger.i(tag, "GATT acked-write completed — watch received frame (status=0)")
                        pendingWrite?.complete(Unit)
                    } else {
                        logger.w(tag, "GATT write FAILED status=$status (acked=$acked)")
                        pendingWrite?.completeExceptionally(RuntimeException("write status=$status"))
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    val p = pendingDescriptorWrite ?: return
                    if (status == BluetoothGatt.GATT_SUCCESS) p.complete(Unit)
                    else p.completeExceptionally(RuntimeException("descriptor write status=$status"))
                }

                override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                    val p = pendingRssi ?: return
                    if (status == BluetoothGatt.GATT_SUCCESS) p.complete(rssi)
                    else p.completeExceptionally(RuntimeException("readRemoteRssi status=$status"))
                }
            }

            // REQ-032: single-state-machine sequence.
            //   1. connectGatt    → await STATE_CONNECTED
            //   2. requestMtu     → await onMtuChanged
            //   3. discoverServices → await onServicesDiscovered
            //   4. enableNotify   → await onDescriptorWrite
            // Each step claims pendingGattOp via beginGattOp(); the callback
            // releases via endGattOp(). Any overlap (next step starting while
            // prior pending) trips the assertion / warning.
            beginGattOp("connect")
            val g = device.connectGatt(appContext, background, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = g
            if (background) {
                connected.await()
                logger.i(tag, "GATT connected in the background")
            } else {
                withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
            }

            _state.value = BleConnectionState.Discovering

            // Step 2: MTU negotiation.
            beginGattOp("mtu")
            val mtuRequested = g.requestMtu(mtuRequest)
            val mtu = if (mtuRequested) {
                withTimeoutOrNull(MTU_TIMEOUT_MS) { mtuResult.await() } ?: run {
                    endGattOp()
                    logger.w(tag, "MTU negotiation timed out, using default MTU=$DEFAULT_MTU")
                    DEFAULT_MTU
                }
            } else {
                endGattOp()
                logger.w(tag, "requestMtu() returned false, using default MTU=$DEFAULT_MTU")
                DEFAULT_MTU
            }

            // Honor the actually negotiated MTU — no floor. BleTransport handles
            // the ATT minimum (23-byte MTU → 20-byte payload) downstream.
            logger.i(tag, "effective MTU = $mtu")

            // Step 3: service discovery (now sequenced after MTU, not concurrent).
            beginGattOp("discover")
            if (!g.discoverServices()) {
                endGattOp()
                throw RuntimeException("discoverServices() returned false")
            }
            withTimeout(DISCOVERY_TIMEOUT_MS) { discovered.await() }

            // Step 4: enable notifications on TX characteristic.
            beginGattOp("enableNotify")
            try {
                enableNotify(g, txNotify!!)
            } finally {
                endGattOp()
            }

            _state.value = BleConnectionState.Connected(mtu)
            AppResult.Ok(Unit)
        } catch (e: TimeoutCancellationException) {
            // A step that timed out failed; it is no cancellation. Rethrown as one (until 2.1.109)
            // it ended the engine's whole session without a word.
            endGattOp()
            logger.e(tag, "connect timed out: ${e.message}")
            _state.value = BleConnectionState.Failed(e)
            closeGatt("connect timed out")
            AppResult.Err(RuntimeException("GATT connect timed out", e))
        } catch (e: CancellationException) {
            // Cancelled (the session ending, Bluetooth going off): no request is left with the stack.
            endGattOp()
            withContext(NonCancellable) { closeGatt("connect cancelled") }
            throw e
        } catch (e: Exception) {
            logger.e(tag, "connect failed: ${e.message}", e)
            _state.value = BleConnectionState.Failed(e)
            closeGatt("connect failed")
            AppResult.Err(e)
        }
    }

    suspend fun disconnect() {
        closeGatt("user")
    }

    suspend fun writeFrame(bytes: ByteArray, withResponse: Boolean = false): AppResult<Unit> {
        return writeMutex.withLock {
            val g = gatt ?: return@withLock AppResult.Err(IllegalStateException("No GATT"))
            val ch = rxWrite ?: return@withLock AppResult.Err(IllegalStateException("No RX characteristic"))

            try {
                pendingWrite?.cancel()  // Cancel any stale deferred
                val pending = CompletableDeferred<Unit>()
                pendingWrite = pending

                ch.writeType = if (withResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }

                val writeResult = g.writeCharacteristic(ch, bytes, ch.writeType)
                // BluetoothStatusCodes.SUCCESS = 0
                if (writeResult != 0) {
                    logger.w(tag, "writeCharacteristic failed with status=$writeResult")
                    if (writeResult in LINK_LOST_WRITE_STATUSES) {
                        // Close now: the state goes to Disconnected and the engine reconnects on its
                        // next check (1 s), instead of after three failed liveness PINGs, which
                        // with the phone asleep took up to 11 min.
                        closeGatt("link lost: write status $writeResult")
                        return@withLock AppResult.Err(BleLinkLostException(writeResult))
                    }
                    return@withLock AppResult.Err(RuntimeException("BLE write failed: status=$writeResult"))
                }

                // Only wait for callback if expecting response
                if (withResponse) {
                    withTimeout(WRITE_RESPONSE_TIMEOUT_MS) { pending.await() }
                } else {
                    // No response expected, give BLE stack time to queue write
                    delay(5)
                }
                AppResult.Ok(Unit)
            } catch (t: Throwable) {
                AppResult.Err(t)
            } finally {
                pendingWrite = null
            }
        }
    }

    suspend fun readRssi(): Int? {
        val g = gatt ?: return null
        val pending = CompletableDeferred<Int>()
        pendingRssi = pending
        return try {
            if (!g.readRemoteRssi()) {
                pendingRssi = null
                return null
            }
            withTimeoutOrNull(MTU_TIMEOUT_MS) { pending.await() }
        } catch (_: Throwable) {
            null
        } finally {
            pendingRssi = null
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val ok = g.setCharacteristicNotification(ch, true)
        if (!ok) throw RuntimeException("setCharacteristicNotification failed")
        val desc = ch.getDescriptor(BleUuids.CCCD) ?: throw RuntimeException("CCCD not found")
        val wrote = CompletableDeferred<Unit>()
        pendingDescriptorWrite = wrote

        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        withTimeout(WRITE_RESPONSE_TIMEOUT_MS) { wrote.await() }
        pendingDescriptorWrite = null
    }

    private suspend fun closeGatt(reason: String) {
        logger.i(tag, "Closing GATT ($reason)")
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        txNotify = null
        rxWrite = null
        // Failed, not cancelled: a cancelled deferred throws CancellationException into whoever
        // awaits it, which reads as that caller's own cancellation. A GATT closed under a connect
        // (link lost during discovery) did that to the engine's session in app 2.1.118: it ended
        // without a word, 4.5 h with no watch link (bundle 20261025-201816).
        val closed = GattClosedException(reason)
        pendingWrite?.completeExceptionally(closed)
        pendingWrite = null
        pendingDescriptorWrite?.completeExceptionally(closed)
        pendingDescriptorWrite = null
        pendingRssi?.completeExceptionally(closed)
        pendingRssi = null
        _state.value = BleConnectionState.Disconnected
    }
}

/** What a GATT operation still waiting gets when the GATT is closed under it. */
class GattClosedException(reason: String) : java.io.IOException("GATT closed ($reason)")

/**
 * A write refused because the link to the watch is already gone ([BleGattClient.LINK_LOST_WRITE_STATUSES]).
 * The client has closed the GATT by the time this is returned, so the connection state is Disconnected.
 */
class BleLinkLostException(val status: Int) : RuntimeException("BLE write failed: status=$status (link lost)")

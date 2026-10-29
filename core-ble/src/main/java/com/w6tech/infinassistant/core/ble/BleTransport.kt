package com.w6tech.infinassistant.core.ble

import android.os.PowerManager
import com.w6tech.infinassistant.core.common.AppResult
import com.w6tech.infinassistant.core.common.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

class BleTransport(
    private val gatt: BleGattClient,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val wakeLock: PowerManager.WakeLock? = null,
) {
    private val tag = "BleTransport"

    companion object {
        /**
         * Wire type for WATCHFACE_LAYOUT (Protocol.Type.WATCHFACE_LAYOUT.wire).
         * Duplicated here to avoid a core-ble → core-domain dependency. Pinned
         * by ProtocolHandlerTest.kt:355 assertion against the canonical value.
         */
        const val WATCHFACE_LAYOUT_WIRE_TYPE = 0x0E

        /** Buffer for the broadcast message flow; matches the GATT frame channel. */
        const val INCOMING_BUFFER_CAPACITY = 256

        /** Timeout per BLE chunk write (ms). */
        const val CHUNK_WRITE_TIMEOUT_MS = 5_000L
        /** Inter-chunk delay for small-ish multi-chunk messages (ms). */
        const val INTER_CHUNK_DELAY_MS = 15L
        /** Inter-chunk delay for large (>10 chunk) messages (ms). */
        const val INTER_CHUNK_DELAY_LARGE_MS = 25L
        /** Minimum chunk count before inter-chunk delays kick in. */
        const val INTER_CHUNK_DELAY_THRESHOLD = 3
        /** Maximum WakeLock hold time (ms) — kept for callers that don't supply a payload size. */
        const val WAKELOCK_TIMEOUT_MS = 60_000L
        /** WakeLock renewal interval for long transfers (ms). */
        const val WAKELOCK_RENEWAL_INTERVAL_MS = 20_000L

        /**
         * Proportional WakeLock duration: 10 s base + 1 s per KB of payload, capped at 60 s.
         */
        fun calculateWakeLockDurationMs(payloadSizeBytes: Int): Long {
            val kbPayload = payloadSizeBytes / 1024.0
            val durationMs = 10_000L + (kbPayload * 1_000L).toLong()
            return durationMs.coerceAtMost(WAKELOCK_TIMEOUT_MS)
        }
    }

    private val maxFrameBytes = AtomicInteger(20 + FrameCodec.HEADER_LEN)

    /**
     * REQ-033 — when true, WATCHFACE_LAYOUT chunks use WRITE_TYPE_DEFAULT
     * (acknowledged writes) instead of WRITE_TYPE_NO_RESPONSE. Slower but
     * surfaces per-chunk errors on the producer side — useful for diagnosing
     * a watch that silently drops chunks. Off by default; flipped from the
     * Debug screen (REQ-066) at runtime. WATCHFACE_LAYOUT only because (a)
     * it's the largest typical payload (most chunks → most failure surface),
     * and (b) acknowledged writes serialize the host's BLE pipeline, which
     * would cripple throughput if applied to PING/SENSOR_REPORT.
     */
    @Volatile
    var diagnosticWriteType: Boolean = false

    private val reassembler = FrameCodec.Reassembler()

    /**
     * Completed watch messages, broadcast to every collector.
     *
     * This was previously `gatt.incomingFrames.mapNotNull { reassembler.ingest(it) }`
     * over a `Channel.receiveAsFlow()`. `receiveAsFlow` *distributes* elements —
     * each frame reaches exactly one collector — so whenever a temporary
     * `first { ... }` waiter overlapped the long-lived dispatch loop, roughly
     * half the inbound frames were consumed by the waiter, failed its predicate,
     * and were discarded. A SharedFlow broadcasts instead, so every collector
     * sees every message published here.
     *
     * A reply claimed by a [expect] waiter is deliberately *not* published — the
     * waiter consumes it, exactly as the old `first { }` did — so the dispatch
     * loop is not handed ACK types it has never had to handle.
     *
     * Reassembly stays in the single pump coroutine below: the Reassembler is
     * stateful and must ingest each frame exactly once.
     */
    private val _incomingMessages = MutableSharedFlow<FrameCodec.CompleteMessage>(
        replay = 0,
        extraBufferCapacity = INCOMING_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingMessages: SharedFlow<FrameCodec.CompleteMessage> = _incomingMessages.asSharedFlow()

    private val correlator = MessageCorrelator<FrameCodec.CompleteMessage>()

    /**
     * Register a one-shot waiter for the next message matching [predicate].
     *
     * Registration is synchronous, so callers must register *before* writing the
     * request. A `SharedFlow` has no replay buffer, and collecting only after the
     * write leaves a window in which a fast reply is lost — the watch can answer
     * HELLO before the caller subscribes. Registering first closes that window.
     *
     * The returned deferred is completed by the pump. Callers should wrap
     * `await()` in `withTimeoutOrNull` inside a try/finally that always calls
     * [cancelExpect] — it is a no-op once the waiter has completed.
     */
    fun expect(predicate: (FrameCodec.CompleteMessage) -> Boolean): CompletableDeferred<FrameCodec.CompleteMessage> =
        correlator.register(predicate)

    /** Drop a waiter that timed out or is no longer wanted. */
    fun cancelExpect(deferred: CompletableDeferred<FrameCodec.CompleteMessage>) =
        correlator.cancel(deferred)

    /**
     * Messages received while nothing is collecting [incomingMessages].
     *
     * The session starts the dispatch loop only *after* the handshake
     * (`setupListeners` runs after `performInitialSync`), and a SharedFlow with
     * `replay = 0` drops emissions that have no subscriber. The Channel this
     * replaced buffered them, so without this queue every uncorrelated frame
     * arriving during the initial sync — NOTIFY_ACKs for resent notifications,
     * STATE_REFRESH_REQUEST, PING — would be lost.
     *
     * Bounded to the same depth as the GATT frame channel, oldest dropped
     * first. Guarded by [bufferLock].
     */
    private val preSubscriberBuffer = ArrayDeque<FrameCodec.CompleteMessage>()
    private val bufferLock = Mutex()

    init {
        // Single pump: reassemble once, then either satisfy a waiter or publish.
        //
        // A message that satisfies a waiter is NOT broadcast. That matches the
        // previous `first { }` behaviour, where the waiter consumed the frame
        // and the dispatch loop never saw it — keeping correlated replies off
        // the general path avoids handing it ACKs it has never had to handle.
        scope.launch {
            try {
                gatt.incomingFrames.collect { frame ->
                    // An exception here would kill RX for the rest of the
                    // session with nothing to restart it, so per-frame failures
                    // are contained rather than allowed to escape the collect.
                    val msg = try {
                        reassembler.ingest(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.w(tag, "Reassembly failed for a frame: ${e.message}")
                        null
                    } ?: return@collect
                    if (correlator.deliver(msg)) return@collect

                if (_incomingMessages.subscriptionCount.value == 0) {
                    bufferLock.withLock {
                        while (preSubscriberBuffer.size >= INCOMING_BUFFER_CAPACITY) {
                            val dropped = preSubscriberBuffer.removeFirst()
                            logger.w(tag, "Pre-subscriber buffer full; dropped type=0x${dropped.type.toString(16)}")
                        }
                        preSubscriberBuffer.addLast(msg)
                    }
                    return@collect
                }

                val backlog = bufferLock.withLock {
                    if (preSubscriberBuffer.isEmpty()) emptyList()
                    else preSubscriberBuffer.toList().also { preSubscriberBuffer.clear() }
                }
                    if (backlog.isNotEmpty()) {
                        logger.d(tag, "Flushing ${backlog.size} message(s) buffered before a collector attached")
                        backlog.forEach { _incomingMessages.emit(it) }
                    }
                    _incomingMessages.emit(msg)
                }
            } finally {
                // The frame flow ends when the session scope is cancelled or the
                // link drops. Fail outstanding waiters instead of leaving them to
                // run out their timeouts against a transport that is gone.
                correlator.cancelAll()
            }
        }
    }

    init {
        // Keep max frame size in sync with negotiated MTU.
        scope.launch {
            gatt.state.collect { st ->
                val mtu = (st as? BleConnectionState.Connected)?.mtu ?: return@collect
                // ATT payload is MTU - 3 (ATT header). BLE spec minimum MTU is 23
                // → minimum payload is 20. Coerce to 20 (not 23) so MTU=23 negotiations
                // don't oversize chunks and silently fail the BLE write.
                val attPayload = (mtu - 3).coerceAtLeast(20)
                maxFrameBytes.set(attPayload)
                logger.i(tag, "maxFrameBytes=$attPayload (mtu=$mtu)")
            }
        }
    }

    suspend fun send(
        type: Int,
        flags: Int,
        msgId: Int,
        payload: ByteArray,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): AppResult<Unit> {
        val max = maxFrameBytes.get()
        val frames = FrameCodec.chunk(type, flags, msgId, payload, maxFrameBytes = max)

        // WATCHFACE_LAYOUT chunks ALWAYS use acknowledged writes (WRITE_TYPE_DEFAULT).
        // Reliability > speed for a one-time multi-chunk push: WRITE_TYPE_NO_RESPONSE
        // can silently drop or reorder chunks, and the watch's reassembler requires
        // in-order chunks — a single lost/reordered chunk means reassembly never
        // completes, the watch sends no WATCHFACE_ACK, and the user sees "watch did
        // not respond" (the 15 s ACK timeout). diagnosticWriteType remains for other
        // diagnostics but no longer gates layout-push reliability. (Was:
        // diagnosticWriteType && type == WATCHFACE_LAYOUT_WIRE_TYPE.)
        val useResponseWrite = type == WATCHFACE_LAYOUT_WIRE_TYPE

        // Acquire WakeLock for multi-chunk sends using proportional duration.
        val useWakeLock = frames.size > INTER_CHUNK_DELAY_THRESHOLD && wakeLock != null
        val wakeLockDurationMs = calculateWakeLockDurationMs(payload.size)
        if (useWakeLock) {
            try {
                wakeLock?.acquire(wakeLockDurationMs)
            } catch (_: Exception) { }
        }

        try {
            var lastRenewalMs = System.currentTimeMillis()
            for ((index, f) in frames.withIndex()) {
                val r = withTimeoutOrNull(CHUNK_WRITE_TIMEOUT_MS) {
                    gatt.writeFrame(f, withResponse = useResponseWrite)
                } ?: return AppResult.Err(RuntimeException("Chunk ${index + 1}/${frames.size} timed out after ${CHUNK_WRITE_TIMEOUT_MS}ms"))
                if (r is AppResult.Err) return r

                onProgress?.invoke(index + 1, frames.size)

                // Progress-based WakeLock renewal every WAKELOCK_RENEWAL_INTERVAL_MS.
                if (useWakeLock && wakeLock?.isHeld == true) {
                    val now = System.currentTimeMillis()
                    if (now - lastRenewalMs >= WAKELOCK_RENEWAL_INTERVAL_MS) {
                        try {
                            wakeLock.release()
                            wakeLock.acquire(wakeLockDurationMs)
                        } catch (_: Exception) { }
                        lastRenewalMs = now
                    }
                }

                // Adaptive inter-chunk delay to avoid overwhelming BLE stack.
                // Skip delay for last chunk.
                if (index < frames.size - 1 && frames.size > INTER_CHUNK_DELAY_THRESHOLD) {
                    kotlinx.coroutines.delay(if (frames.size > 10) INTER_CHUNK_DELAY_LARGE_MS else INTER_CHUNK_DELAY_MS)
                }
            }
            return AppResult.Ok(Unit)
        } finally {
            if (useWakeLock && wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (_: Exception) { }
            }
        }
    }
}

package com.w6tech.infinassistant.core.data

/**
 * Centralizes tunable constants for the bridge engine.
 * BLE transport constants live in BleTransport.Companion and BleGattClient.Companion
 * (core-ble module) since core-ble cannot depend on core-data.
 */
object BridgeConfig {

    // --- Liveness (PING/PONG) ---

    /**
     * How long the watch may say nothing (no frame of any kind: PONG, NOTIFY_ACK, SENSOR_REPORT,
     * HELLO, ...) before the phone sends a liveness PING. Every frame from the watch proves the link,
     * so a PING goes out only after this much silence. It was a PING every 30 s whatever the watch
     * sent (the bundle of 2026-10-27: about 800 in 7.4 h, each waking the phone and the watch).
     */
    const val LIVENESS_SILENCE_MS = 90_000L

    /**
     * After a missed PONG the next PING follows this soon (and again after each miss), so a dead
     * link is found in minutes, not after three more silences.
     */
    const val LIVENESS_RETRY_MS = 15_000L

    /** Timeout for PONG response before counting a miss (ms). */
    const val LIVENESS_PONG_TIMEOUT_MS = 10_000L

    /** Consecutive PONG misses before forcing a BLE reconnect. */
    const val LIVENESS_MAX_MISSES = 3

    /**
     * The longest a link can be dead before the phone reconnects: the first PING after
     * [LIVENESS_SILENCE_MS], then each miss costs [LIVENESS_PONG_TIMEOUT_MS] and, but for the
     * last, [LIVENESS_RETRY_MS]: 90 + 10 + 15 + 10 + 15 + 10 = 150 s, inside the 3 minutes asked of it.
     * A write the stack refuses (link lost, status 9) does not wait for this: it reconnects at once.
     */
    const val LIVENESS_DETECTION_WORST_CASE_MS =
        LIVENESS_SILENCE_MS + LIVENESS_MAX_MISSES * LIVENESS_PONG_TIMEOUT_MS + (LIVENESS_MAX_MISSES - 1) * LIVENESS_RETRY_MS

    /** The session loop looks at the links at least this often when they have not changed (it is woken by a change). */
    const val SESSION_CHECK_MS = 60_000L

    // --- Watch Face Push ---

    /**
     * Top-level timeout for the sendWatchFaceLayout command (ms): two attempts and the wait between
     * them. A watch still committing the last layout refuses the next as pending, and the orchestrator
     * tries again after PENDING_PUSH_RETRY_DELAY_MS (35 s); with 30 s here the import screen said
     * "Watch did not respond" while that retry went on and often succeeded.
     */
    const val WATCHFACE_PUSH_TIMEOUT_MS = 90_000L

    // --- State Batching ---

    /** Maximum entities per batched STATE_UPDATE BLE message. */
    const val STATE_BATCH_SIZE = 8

    /** Delay between batched STATE_UPDATE messages when total exceeds BATCH_SIZE (ms). */
    const val STATE_BATCH_DELAY_MS = 100L
}

package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.ha.HaConnectionState

/**
 * The session loop's diagnostic lines about what the loop cannot see by itself: the phone's deep
 * sleep, the background waits for the watch, and the BLE connect attempts, counted instead of
 * logged one by one. [BridgeEngine.runConnectedMode] calls it; it logs through [log] (tag
 * [TAG]), and [HealthSummary] reads the lines back.
 *
 * Coroutine timers stop while the phone is in deep sleep, so the 1-s loop and the reconnect waits
 * only resume late: a phone that made no reconnect attempt for 5 hours left nothing in a bundle to
 * say it had been asleep. [elapsedRealtimeMs] counts deep sleep and [uptimeMs] does not, so between
 * two checks what the first gained on the second is the time asleep.
 *
 * Called from the session loop only, one call at a time.
 */
class SessionDiagnostics(
    private val log: (msg: String, extra: String) -> Unit,
    private val elapsedRealtimeMs: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val uptimeMs: () -> Long = android.os.SystemClock::uptimeMillis,
) {
    companion object {
        const val TAG = "session"

        /** A sleep this long or longer gets a line. */
        const val SLEEP_LINE_MS = 60_000L
        /** While the watch link is down, its failed attempts go out at most this often. */
        const val ATTEMPTS_LINE_MS = 10 * 60_000L
        /** A background wait this long is a wait, not just one more failed attempt. */
        const val LONG_WAIT_MS = 60_000L

        // The lines' messages.
        const val STARTED = "session started"
        const val SLEPT = "phone slept"             // "phone slept N s"
        const val BLE_LOST = "BLE link lost"
        const val BLE_CONNECTED = "BLE connected"   // extra "direct; ..." or "background; ..."
        const val BLE_WAIT_STARTED = "BLE background wait started"
        const val BLE_WAIT_ENDED = "BLE background wait ended"
        const val BLE_ATTEMPTS = "BLE connect attempts"

        private const val MIN_SLEEP_MS = 1_000L
        private const val REASONS_SHOWN = 3
    }

    private var markElapsedMs = 0L
    private var markUptimeMs = 0L
    private var sleptMs = 0L

    private var connectedAtMs: Long? = null   // elapsedRealtime when the link came up
    private var downSinceMs: Long? = null     // elapsedRealtime when it went down
    private var failed = 0
    private val reasons = LinkedHashMap<String, Int>()
    private var attemptsLineAtMs = 0L
    private var waitFromMs: Long? = null
    private var logNextWait = true   // the next background wait gets its lines
    private var waitLogged = false   // the current one has
    private var viaBackground = false

    /** A new session: everything starts over. */
    fun start() {
        val now = elapsedRealtimeMs()
        val up = uptimeMs()
        markElapsedMs = now
        markUptimeMs = up
        sleptMs = 0
        connectedAtMs = null
        startOutage(now)
        waitFromMs = null
        waitLogged = false
        viaBackground = false
        log(STARTED, "phone up ${now / 1000} s since boot, ${(now - up).coerceAtLeast(0) / 1000} s of it asleep")
    }

    /**
     * The time asleep since the last check: a line when it reaches [SLEEP_LINE_MS], with [where]
     * the session was and the links' states now.
     */
    fun checkSleep(where: String, ble: BleConnectionState, ha: HaConnectionState) {
        val now = elapsedRealtimeMs()
        val up = uptimeMs()
        val slept = (now - markElapsedMs) - (up - markUptimeMs)
        markElapsedMs = now
        markUptimeMs = up
        if (slept < MIN_SLEEP_MS) return
        sleptMs += slept
        if (slept < SLEEP_LINE_MS) return
        log("$SLEPT ${slept / 1000} s", "during $where; ble=${name(ble)} ha=${name(ha)}; ${sleptMs / 1000} s asleep this session")
    }

    /**
     * The direct connect failed with [error]: Android's Bluetooth stack now waits for the watch.
     * A wait that fails at once (Bluetooth off, say) comes round with every retry, so only the
     * outage's first wait and the one after a long wait get lines; the attempt lines count the rest.
     */
    fun bleWaitStarted(error: Throwable) {
        attemptFailed(error)
        waitFromMs = elapsedRealtimeMs()
        waitLogged = logNextWait
        logNextWait = false
        if (waitLogged) log(BLE_WAIT_STARTED, "after: ${reason(error)}")
    }

    /** The background wait ended: connected when [error] is null. */
    fun bleWaitEnded(error: Throwable?, ble: BleConnectionState, ha: HaConnectionState) {
        checkSleep("ble-background-wait", ble, ha)
        val waitedMs = waitFromMs?.let { elapsedRealtimeMs() - it } ?: 0L
        waitFromMs = null
        val long = waitedMs >= LONG_WAIT_MS
        if (error == null) {
            viaBackground = true
            log(BLE_WAIT_ENDED, "connected after ${waitedMs / 1000} s")
        } else {
            attemptFailed(error)
            if (waitLogged || long) log(BLE_WAIT_ENDED, "failed after ${waitedMs / 1000} s: ${reason(error)}")
            if (long) logNextWait = true
        }
        waitLogged = false
    }

    /** The watch link is up: directly, or from the background wait that just ended. */
    fun bleConnected() {
        val now = elapsedRealtimeMs()
        val down = downSinceMs?.let { "down ${(now - it) / 1000} s, " } ?: ""
        val attempts = if (failed == 1) "1 failed attempt" else "$failed failed attempts"
        log(BLE_CONNECTED, "${if (viaBackground) "background" else "direct"}; $down$attempts${reasonList()}")
        connectedAtMs = now
        downSinceMs = null
        failed = 0
        reasons.clear()
        viaBackground = false
    }

    /** The session loop found the watch link down. */
    fun bleLost(ha: HaConnectionState) {
        val now = elapsedRealtimeMs()
        val up = connectedAtMs?.let { "up ${(now - it) / 1000} s; " } ?: ""
        log(BLE_LOST, "${up}ha=${name(ha)}")
        connectedAtMs = null
        startOutage(now)
    }

    private fun startOutage(now: Long) {
        downSinceMs = now
        failed = 0
        reasons.clear()
        attemptsLineAtMs = now
        logNextWait = true
    }

    private fun attemptFailed(error: Throwable) {
        failed++
        val r = reason(error)
        reasons[r] = (reasons[r] ?: 0) + 1
        val now = elapsedRealtimeMs()
        if (now - attemptsLineAtMs >= ATTEMPTS_LINE_MS) {
            attemptsLineAtMs = now
            val down = downSinceMs?.let { " in ${(now - it) / 1000} s" } ?: ""
            log(BLE_ATTEMPTS, "$failed failed$down${reasonList()}")
        }
    }

    private fun reasonList(): String {
        if (reasons.isEmpty()) return ""
        val top = reasons.entries.sortedByDescending { it.value }.take(REASONS_SHOWN)
            .joinToString(", ") { "${it.key} x${it.value}" }
        return " ($top${if (reasons.size > REASONS_SHOWN) ", ..." else ""})"
    }

    private fun reason(error: Throwable): String = (error.message ?: error.javaClass.simpleName).take(80)

    private fun name(state: BleConnectionState): String = when (state) {
        is BleConnectionState.Connected -> "Connected"
        is BleConnectionState.Failed -> "Failed"
        else -> state.toString()
    }

    private fun name(state: HaConnectionState): String = when (state) {
        is HaConnectionState.Failed -> "Failed"
        else -> state.toString()
    }
}

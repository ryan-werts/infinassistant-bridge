package com.w6tech.infinassistant.core.data

import kotlinx.coroutines.delay

/**
 * Finds a dead watch link without waking the phone while the watch is talking. A PING goes out only
 * after [silenceMs] in which nothing at all came from the watch ([onInbound] is called for every
 * frame: PONG, NOTIFY_ACK, SENSOR_REPORT, ...). Until app 2.1.124 a PING went out every 30 s
 * whatever the watch sent, which kept the phone from ever sleeping and woke the watch for nothing.
 *
 * A PING that gets no PONG within [pongTimeoutMs] is a miss unless some other frame arrived since it
 * was sent. After a miss the next PING follows in [retryMs]; [maxMisses] in a row end the run, and
 * the caller reconnects. The worst case is [BridgeConfig.LIVENESS_DETECTION_WORST_CASE_MS].
 *
 * One [run] per watch link, on one coroutine; [onInbound] may be called from any thread.
 */
class LivenessMonitor(
    private val clockMs: () -> Long,
    private val silenceMs: Long = BridgeConfig.LIVENESS_SILENCE_MS,
    private val retryMs: Long = BridgeConfig.LIVENESS_RETRY_MS,
    private val pongTimeoutMs: Long = BridgeConfig.LIVENESS_PONG_TIMEOUT_MS,
    private val maxMisses: Int = BridgeConfig.LIVENESS_MAX_MISSES,
) {
    /** What writing the PING came to. */
    sealed interface Send {
        data object Sent : Send
        /** The write failed; the link may still be up. */
        data class Failed(val message: String) : Send
        /** The stack refused the write because the link is gone (the GATT is closed already). */
        data class LinkLost(val status: Int) : Send
    }

    /** What happened in a round, for the caller's log and status. */
    sealed interface Event {
        data object PingSent : Event
        data class PingSendFailed(val misses: Int, val message: String) : Event
        data class PongMissed(val misses: Int) : Event
        /** The link answered: [latencyMs] is the PONG's round trip, null when other traffic showed it. */
        data class Alive(val latencyMs: Long?) : Event
    }

    /** Why [run] ended. */
    sealed interface Verdict {
        data class LinkLost(val status: Int) : Verdict
        /** [writeFailed]: the misses were failed writes, not missing PONGs. */
        data class Unresponsive(val writeFailed: Boolean) : Verdict
    }

    private val lock = Any()
    private var lastRxMs = clockMs()
    private var lastCheckMs = lastRxMs
    private var misses = 0

    /**
     * A frame came from the watch. Returns true when it ended a run of misses (the watch is
     * responsive again), so the caller can say so.
     */
    fun onInbound(): Boolean = synchronized(lock) {
        lastRxMs = clockMs()
        val hadMisses = misses > 0
        misses = 0
        hadMisses
    }

    /**
     * Checks the link until it is found dead or lost. [sendPing] writes one PING; [awaitPong]
     * waits up to the given time for the PONG to that PING and says whether it came.
     */
    suspend fun run(
        sendPing: suspend () -> Send,
        awaitPong: suspend (timeoutMs: Long) -> Boolean,
        onEvent: suspend (Event) -> Unit,
    ): Verdict {
        while (true) {
            awaitDue()
            val sentAtMs = clockMs()
            when (val sent = sendPing()) {
                is Send.LinkLost -> return Verdict.LinkLost(sent.status)
                is Send.Failed -> {
                    val n = miss()
                    onEvent(Event.PingSendFailed(n, sent.message))
                    if (n >= maxMisses) return Verdict.Unresponsive(writeFailed = true)
                    continue
                }
                Send.Sent -> onEvent(Event.PingSent)
            }
            if (awaitPong(pongTimeoutMs)) {
                // The PONG is a frame like any other (the caller sees it too); counting it here keeps
                // the next PING a whole silence away whatever the caller does.
                onInbound()
                onEvent(Event.Alive(clockMs() - sentAtMs))
            } else if (heardSince(sentAtMs)) {
                onEvent(Event.Alive(null))
            } else {
                val n = miss()
                onEvent(Event.PongMissed(n))
                if (n >= maxMisses) return Verdict.Unresponsive(writeFailed = false)
            }
        }
    }

    /** Suspends until a PING is due: [silenceMs] after the last frame, or [retryMs] after the last miss. */
    private suspend fun awaitDue() {
        while (true) {
            val wait = synchronized(lock) {
                maxOf(lastRxMs, lastCheckMs) + (if (misses > 0) retryMs else silenceMs)
            } - clockMs()
            if (wait <= 0) return
            delay(wait)
        }
    }

    private fun heardSince(sentAtMs: Long): Boolean = synchronized(lock) { lastRxMs >= sentAtMs }

    private fun miss(): Int = synchronized(lock) {
        misses++
        lastCheckMs = clockMs()
        misses
    }
}

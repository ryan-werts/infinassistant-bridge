package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.common.Logger
import com.w6tech.infinassistant.core.common.Redactor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The app's [Logger]. Everything still goes to [delegate] (logcat); warnings and errors, and the
 * few events in [infoEvents], also go to the diagnostic log through [sink], so a debug bundle has
 * them. Until this the core modules logged to logcat only: a bundle showed the HA socket dropping
 * every 30 s as "failed: null", and the close codes and failures that said why never reached it.
 *
 * A flood must not evict the history a bundle is for (the log keeps about 1 MB). A message is keyed
 * by its level, tag and text with the numbers blanked out. In a window of [windowMs] a key is
 * written [burst] times and then only counted; when the window closes the count goes out as one
 * line ("repeated N more times ..."), and a key that was still repeating gets a window twice as long,
 * up to [maxWindowMs], so a flood that lasts settles at a few lines an hour. Each tag is also held to
 * [tagBudget] lines per [tagWindowMs] whatever its messages, with the lines it lost counted.
 * Windows close when a later message comes, or on [flush], which an export runs.
 */
class DiagnosticTeeLogger(
    private val delegate: Logger,
    private val sink: Sink,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 60_000L,
    private val maxWindowMs: Long = 60 * 60_000L,
    private val burst: Int = 3,
    private val tagBudget: Int = 120,
    private val tagWindowMs: Long = 60 * 60_000L,
    private val infoEvents: Map<String, List<String>> = INFO_EVENTS,
    timeZone: TimeZone = TimeZone.getDefault(),
) : Logger {

    /** Where the teed lines go: [DiagnosticLogger.log] in the app. */
    fun interface Sink {
        fun write(level: String, tag: String, msg: String, extra: String?)
    }

    companion object {
        /**
         * Below WARN only these reach the file, by tag and message prefix: the HA socket's open,
         * auth and close, the URL each HA connect chose, and the GATT client's connects (direct and
         * background), closes and MTU, a voice session's start, turns and end, and the notification
         * listener's decisions (package name, nid and a fixed reason word; never a title or text).
         * The listener's lines were debug-level, so none ever reached a bundle.
         */
        val INFO_EVENTS: Map<String, List<String>> = mapOf(
            "HaWebSocket" to listOf("WebSocket opened", "auth", "onClosing", "onFailure"),
            HealthSummary.CONNECTION_TAG to listOf(HealthSummary.HA_URL),
            "BleGattClient" to listOf("GATT connected", "GATT disconnected", "Closing GATT", "MTU negotiated", "effective MTU"),
            "VoiceAssist" to listOf("session ", "turn "),
            "NotificationForwarder" to listOf("pkg=", "nid=", "Notification listener", "reconcile"),
        )

        /** The start of a repeat count's extra; [occurrences] reads the count back. */
        const val REPEATED = "repeated "
        private val REPEAT_COUNT = Regex("""^repeated (\d+) more time""")

        /** How many occurrences a line of the log stands for: N for a repeat count's line, else 1. */
        fun occurrences(extra: String?): Int =
            extra?.let { REPEAT_COUNT.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 1

        private const val MAX_KEYS = 512
        private const val MAX_TEXT = 500
        private const val SWEEP_MS = 1_000L
        private val NUMBER = Regex("""\d+""")
    }

    private class KeyWindow(val level: String, val tag: String, var startMs: Long, var lengthMs: Long) {
        var written = 0
        var repeats = 0
        var lastText = ""
        var lastExtra: String? = null
        var lastMs = 0L
    }

    private class TagWindow(val startMs: Long) {
        var written = 0
        var dropped = 0
    }

    private val lock = Any()
    private val keys = LinkedHashMap<String, KeyWindow>()
    private val tags = HashMap<String, TagWindow>()
    private var sweptAtMs = Long.MIN_VALUE
    private val timeOfDay = SimpleDateFormat("HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }

    override fun d(tag: String, msg: String, tr: Throwable?) {
        delegate.d(tag, msg, tr)
        if (isEvent(tag, msg)) tee("DEBUG", tag, msg, tr)
    }

    override fun i(tag: String, msg: String, tr: Throwable?) {
        delegate.i(tag, msg, tr)
        if (isEvent(tag, msg)) tee("INFO", tag, msg, tr)
    }

    override fun w(tag: String, msg: String, tr: Throwable?) {
        delegate.w(tag, msg, tr)
        tee("WARN", tag, msg, tr)
    }

    override fun e(tag: String, msg: String, tr: Throwable?) {
        delegate.e(tag, msg, tr)
        tee("ERROR", tag, msg, tr)
    }

    /** Writes the counts of every window still open, and starts the windows afresh. */
    fun flush() {
        try {
            synchronized(lock) {
                val now = clockMs()
                for (w in keys.values) if (w.repeats > 0) writeRepeats(w)
                keys.clear()
                for ((tag, t) in tags) if (t.dropped > 0) writeDropped(tag, t, now)
                tags.clear()
            }
        } catch (_: Exception) {
            // Best effort, as every logging path here.
        }
    }

    private fun isEvent(tag: String, msg: String): Boolean =
        infoEvents[tag]?.any { msg.startsWith(it) } == true

    private fun tee(level: String, tag: String, msg: String, tr: Throwable?) {
        try {
            val text = Redactor.redact(msg).take(MAX_TEXT)
            val extra = tr?.let(::describe)
            synchronized(lock) {
                val now = clockMs()
                closeExpired(now)
                val key = "$level|$tag|" + NUMBER.replace(text, "#")
                val w = keys[key]
                when {
                    w == null -> {
                        if (keys.size >= MAX_KEYS) {
                            for (old in keys.values) if (old.repeats > 0) writeRepeats(old)
                            keys.clear()
                        }
                        keys[key] = KeyWindow(level, tag, now, windowMs).also { it.written = 1 }
                        admit(level, tag, text, extra, now)
                    }
                    w.written < burst -> {
                        w.written++
                        admit(level, tag, text, extra, now)
                    }
                    else -> {
                        w.repeats++
                        w.lastText = text
                        w.lastExtra = extra
                        w.lastMs = now
                    }
                }
            }
        } catch (_: Exception) {
            // Logging must never fail its caller.
        }
    }

    /** Writes a line within its tag's budget, or counts it as dropped. */
    private fun admit(level: String, tag: String, text: String, extra: String?, now: Long) {
        val t = tags.getOrPut(tag) { TagWindow(now) }
        if (t.written < tagBudget) {
            t.written++
            sink.write(level, tag, text, extra)
        } else {
            t.dropped++
        }
    }

    /**
     * Closes the windows that have run their length, looked at no more than once a second. A
     * clock set back closes them too, rather than holding them open until it catches up.
     */
    private fun closeExpired(now: Long) {
        if (now >= sweptAtMs && now < sweptAtMs + SWEEP_MS) return
        sweptAtMs = now
        val it = keys.values.iterator()
        while (it.hasNext()) {
            val w = it.next()
            if (now - w.startMs in 0 until w.lengthMs) continue
            if (w.repeats == 0) {
                it.remove()
                continue
            }
            writeRepeats(w)
            // Still repeating: a longer window, so a flood that lasts costs fewer lines.
            w.startMs = now
            w.lengthMs = minOf(w.lengthMs * 2, maxWindowMs)
            w.written = 0
            w.repeats = 0
        }
        val tagIt = tags.entries.iterator()
        while (tagIt.hasNext()) {
            val (tag, t) = tagIt.next()
            if (now - t.startMs in 0 until tagWindowMs) continue
            if (t.dropped > 0) writeDropped(tag, t, now)
            tagIt.remove()
        }
    }

    private fun writeRepeats(w: KeyWindow) {
        val times = if (w.repeats == 1) "time" else "times"
        val span = (w.lastMs - w.startMs).coerceAtLeast(0) / 1000
        sink.write(
            w.level,
            w.tag,
            w.lastText,
            "$REPEATED${w.repeats} more $times in $span s, the last at ${timeOfDay.format(Date(w.lastMs))}" +
                (w.lastExtra?.let { "; $it" } ?: ""),
        )
    }

    private fun writeDropped(tag: String, t: TagWindow, now: Long) {
        sink.write("WARN", tag, "rate limit: ${t.dropped} more lines not written", "in ${(now - t.startMs) / 1000} s")
    }

    /** The throwable and up to two causes, class and message, never the stack (it can hold tokens). */
    private fun describe(tr: Throwable): String =
        Redactor.redact(
            generateSequence(tr) { t -> t.cause?.takeIf { it !== t } }
                .take(3)
                .joinToString(" <- ") { t -> t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "") },
        ).take(MAX_TEXT)
}

package com.w6tech.infinassistant.core.data.bridge

import com.w6tech.infinassistant.core.data.HealthSummary

/**
 * What a debug bundle says about notifications on the watch: where each NOTIFY came from (Home
 * Assistant, or forwarded from the phone and by which app), and why the phone's notifications were
 * not forwarded. [HealthSummary] reads these lines back.
 *
 * It takes package names and fixed reason words only, never a title or text: the log goes into
 * debug bundles. Anything else is cut to a safe alphabet. HA and phone origins are one line each
 * (a few dozen an hour); skips can come in thousands (a media player updating its notification), so
 * they are counted and written as one line per [FLUSH_MS], or at [flush].
 */
class ForwardDiagnostics(
    private val write: (msg: String, extra: String) -> Unit,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val skips = LinkedHashMap<String, Int>()
    private var lastFlushMs = clockMs()

    /** A NOTIFY made from a Home Assistant event. */
    @Synchronized
    fun fromHa() = write(HealthSummary.NOTIFY_HA, "")

    /** A NOTIFY queued for a notification of the phone's app [packageName]. */
    @Synchronized
    fun fromPhone(packageName: String) = write(HealthSummary.NOTIFY_PHONE, "pkg=${safe(packageName)}")

    /** A phone notification not forwarded, for the fixed word [reason], with the forwarding switch on. */
    @Synchronized
    fun skipped(reason: String) {
        skips.merge(safe(reason), 1, Int::plus)
        if (clockMs() - lastFlushMs >= FLUSH_MS) flush()
    }

    /** Writes the skips counted since the last line, if any. */
    @Synchronized
    fun flush() {
        lastFlushMs = clockMs()
        if (skips.isEmpty()) return
        write(HealthSummary.NOTIFY_SKIPS, skips.entries.joinToString(" ") { (reason, n) -> "$reason=$n" })
        skips.clear()
    }

    private fun safe(text: String): String =
        text.take(MAX_NAME).replace(UNSAFE, "?")

    companion object {
        const val FLUSH_MS = 10 * 60_000L
        private const val MAX_NAME = 100
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
    }
}

package com.w6tech.infinassistant.core.domain

/**
 * The watch's screen-wake telemetry: the HELLO's optional `wk` key (firmware 2.4.104, `helloDiagRev`
 * 5). Five unsigned integers, the first four counted since boot and capped at 65535 by the watch,
 * the fifth a bit field of the settings that drive the battery. The layout is the firmware's
 * (`WakeDiag.h`, `PackWakeSettings` in `WakeTelemetry.h`; docs/reference/ble-protocol-spec.md):
 *
 *   settings bits 0-4    wake-up modes: bit 0 single tap, 1 double tap, 2 raise wrist, 3 shake, 4 lower wrist
 *   bit 5                Always On display (the setting)
 *   bits 6-7             notification status: 0 On, 1 Off, 2 Sleep
 *   bits 8-10            brightness: 0 Off, 1 AlwaysOn, 2 Low, 3 Medium, 4 High, 5 B (button: High after a
 *                        side-button wake, Low after any other wake; firmware 2.4.105)
 *   bits 11-15           screen timeout in whole seconds, 31 at most
 */
data class WakeDiag(
    val wakes: Int,
    val notifyWakes: Int,
    val notifyStored: Int,
    val screenOnSec: Int,
    val settings: Int,
) {
    val wakeModes: Int get() = settings and 0x1F
    val alwaysOn: Boolean get() = (settings shr 5) and 1 == 1
    val notificationStatus: Int get() = (settings shr 6) and 0x3
    val brightness: Int get() = (settings shr 8) and 0x7
    val timeoutSec: Int get() = (settings shr 11) and 0x1F

    /** The wake-up modes in words, "none" when no mode is set. */
    fun wakeModeWords(): String =
        WAKE_MODES.filterIndexed { bit, _ -> wakeModes and (1 shl bit) != 0 }.joinToString(" + ").ifEmpty { "none" }

    fun notificationStatusWords(): String = NOTIFICATION_STATUS.getOrNull(notificationStatus) ?: "status $notificationStatus"

    fun brightnessWords(): String = BRIGHTNESS.getOrNull(brightness) ?: "level $brightness"

    /** The values as the watch sent them: `[300, 60, 20, 1234, 0x7814]`. */
    fun raw(): String = "[$wakes, $notifyWakes, $notifyStored, $screenOnSec, 0x${settings.toString(16).padStart(4, '0')}]"

    /** The values in words, without the uptime: `wakes 300 (by notification 60, stored quietly 20), screen on 1234 s, ...`. */
    fun decoded(): String =
        "wakes ${count(wakes)} (by notification ${count(notifyWakes)}, stored quietly ${count(notifyStored)}), " +
            "screen on ${count(screenOnSec)} s, timeout $timeoutSec s, brightness ${brightnessWords()}, " +
            "Always On ${if (alwaysOn) "on" else "off"}, wake modes ${wakeModeWords()}, notifications ${notificationStatusWords()}"

    /**
     * The one line of a HEALTH SUMMARY. [uptimeSec] is the HELLO's uptime, for the share of it the
     * screen was on; null leaves the share out.
     */
    fun summaryLine(uptimeSec: Long?): String {
        val onWords = if (screenOnSec < 120) "$screenOnSec s" else "${screenOnSec / 60}${if (screenOnSec >= CAP) "+" else ""} min"
        val share = uptimeSec?.takeIf { it > 0 }?.let { " (${minOf(100L, screenOnSec * 100L / it)}% of uptime)" }.orEmpty()
        return "screen on $onWords since boot$share, wakes ${count(wakes)} (by notification ${count(notifyWakes)}, " +
            "stored quietly ${count(notifyStored)}), timeout $timeoutSec s, brightness ${brightnessWords()}, " +
            "Always On ${if (alwaysOn) "on" else "off"}, wake modes ${wakeModeWords()}, notifications ${notificationStatusWords()}"
    }

    /** A counter the watch capped reads "65535+": it is at least that. */
    private fun count(n: Int): String = if (n >= CAP) "$CAP+" else n.toString()

    companion object {
        /** The watch caps the four counters, and the settings word is below it, at 16 bits. */
        const val CAP = 65535

        private val WAKE_MODES = listOf("single tap", "double tap", "raise wrist", "shake", "lower wrist")
        private val NOTIFICATION_STATUS = listOf("On", "Off", "Sleep")
        private val BRIGHTNESS = listOf("Off", "Always On", "Low", "Medium", "High", "B (button)")

        /** The `wk` array, or null when it is absent or not five unsigned 16-bit integers. */
        fun from(wk: List<Long>?): WakeDiag? {
            if (wk == null || wk.size != 5 || wk.any { it < 0 || it > CAP }) return null
            return WakeDiag(wk[0].toInt(), wk[1].toInt(), wk[2].toInt(), wk[3].toInt(), wk[4].toInt())
        }
    }
}

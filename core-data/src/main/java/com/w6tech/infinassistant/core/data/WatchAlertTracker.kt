package com.w6tech.infinassistant.core.data

import com.w6tech.infinassistant.core.ble.BleConnectionState
import com.w6tech.infinassistant.core.domain.NotificationPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * FR-AF-1/2: when the phone alerts about the watch. Fed each bridge status, and a clock tick so the
 * disconnected alert can fire while nothing else changes, it says which alert to show or take away.
 * Each alert shows once per episode: battery low until the watch charges or climbs
 * [BATTERY_REARM_MARGIN] points above the threshold; disconnected until the watch is back or
 * Connected Mode is turned off.
 */
class WatchAlertTracker {

    sealed interface Action {
        data class ShowBatteryLow(val percent: Int) : Action
        data object ClearBatteryLow : Action
        /** The watch has been away since [sinceMs] (epoch milliseconds). */
        data class ShowDisconnected(val sinceMs: Long) : Action
        data object ClearDisconnected : Action
    }

    private var batteryShown = false
    private var disconnectedSince: Long? = null
    private var disconnectShown = false

    fun update(status: BridgeStatus, prefs: NotificationPrefs, nowMs: Long): List<Action> {
        val actions = mutableListOf<Action>()
        battery(status, prefs, actions)
        connection(status, prefs, nowMs, actions)
        return actions
    }

    private fun battery(status: BridgeStatus, prefs: NotificationPrefs, out: MutableList<Action>) {
        val percent = status.watchBatteryPercent
        val charging = status.watchBatteryCharging == true
        if (!batteryShown) {
            if (prefs.watchBatteryAlert && percent != null && !charging && percent <= prefs.watchBatteryAlertPercent) {
                batteryShown = true
                out += Action.ShowBatteryLow(percent)
            }
        } else if (!prefs.watchBatteryAlert || charging ||
            (percent != null && percent >= prefs.watchBatteryAlertPercent + BATTERY_REARM_MARGIN)
        ) {
            batteryShown = false
            out += Action.ClearBatteryLow
        }
    }

    private fun connection(status: BridgeStatus, prefs: NotificationPrefs, nowMs: Long, out: MutableList<Action>) {
        val watching = prefs.watchDisconnectAlert && status.connectedModeEnabled
        if (!watching || status.bleState is BleConnectionState.Connected) {
            disconnectedSince = null
            if (disconnectShown) {
                disconnectShown = false
                out += Action.ClearDisconnected
            }
            return
        }
        val since = disconnectedSince ?: nowMs.also { disconnectedSince = it }
        if (!disconnectShown && nowMs - since >= prefs.watchDisconnectAlertMinutes * 60_000L) {
            disconnectShown = true
            out += Action.ShowDisconnected(since)
        }
    }

    companion object {
        /** Points above the threshold the battery must reach before a new low alert can show. */
        const val BATTERY_REARM_MARGIN = 5

        /**
         * The clock tick [update] needs so the disconnected alert can fire while nothing else changes:
         * one when [status] changes between away and not away, and one every [tickMs] only while
         * Connected Mode is on and the watch is away. With the watch connected there is nothing to
         * time, so the phone is not woken for it (it was, every 30 s, whatever the state).
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun ticksWhileAway(status: Flow<BridgeStatus>, tickMs: Long): Flow<Unit> =
            status
                .map { it.connectedModeEnabled && it.bleState !is BleConnectionState.Connected }
                .distinctUntilChanged()
                .transformLatest { away ->
                    emit(Unit)
                    if (away) {
                        while (true) {
                            delay(tickMs)
                            emit(Unit)
                        }
                    }
                }
    }
}
